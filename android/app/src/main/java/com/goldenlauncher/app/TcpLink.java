package com.goldenlauncher.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** ELM327 Wi-Fi (por ejemplo 192.168.0.10:35000). */
class TcpLink extends Link {
    private final String host;
    private final int port;
    private Socket socket;
    private OutputStream out;

    TcpLink(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    void open() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
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
        }, "obd-tcp-reader");
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
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    String describe() {
        return "Wi-Fi " + host + ":" + port;
    }
}
