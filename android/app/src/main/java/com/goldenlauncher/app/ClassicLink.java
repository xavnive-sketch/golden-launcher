package com.goldenlauncher.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

/** Bluetooth clásico (perfil serie SPP), el que usan la mayoría de ELM327 en Android. */
@SuppressLint("MissingPermission")
class ClassicLink extends Link {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final BluetoothAdapter adapter;
    private final BluetoothDevice device;
    private BluetoothSocket socket;
    private OutputStream out;

    ClassicLink(BluetoothAdapter adapter, BluetoothDevice device) {
        this.adapter = adapter;
        this.device = device;
    }

    @Override
    void open() throws IOException {
        try {
            adapter.cancelDiscovery();
        } catch (Exception ignored) {
        }
        IOException last = null;
        for (int attempt = 0; attempt < 3 && socket == null; attempt++) {
            BluetoothSocket s = null;
            try {
                if (attempt == 0) s = device.createRfcommSocketToServiceRecord(SPP);
                else if (attempt == 1) s = device.createInsecureRfcommSocketToServiceRecord(SPP);
                else {
                    Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                    s = (BluetoothSocket) m.invoke(device, 1);
                }
                s.connect();
                socket = s;
            } catch (IOException e) {
                last = e;
                closeQuietly(s);
            } catch (Exception e) {
                last = new IOException(e.getMessage());
                closeQuietly(s);
            }
        }
        if (socket == null) throw last != null ? last : new IOException("No se pudo conectar");
        out = socket.getOutputStream();
        final InputStream in = socket.getInputStream();
        Thread reader = new Thread(() -> {
            byte[] b = new byte[256];
            try {
                while (!closed) {
                    int n = in.read(b);
                    if (n < 0) break;
                    onBytes(b, n);
                }
            } catch (IOException ignored) {
            }
            markClosed();
        }, "obd-classic-reader");
        reader.setDaemon(true);
        reader.start();
    }

    @Override
    void write(byte[] data) throws IOException {
        if (out == null || closed) throw new IOException("Sin conexión");
        out.write(data);
        out.flush();
    }

    @Override
    void close() {
        markClosed();
        closeQuietly(socket);
        socket = null;
    }

    @Override
    String describe() {
        return "Bluetooth clásico";
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
