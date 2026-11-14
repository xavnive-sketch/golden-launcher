package com.goldenlauncher.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bluetooth de baja energía (BLE), como el Vgate iCar Pro 2S. No hay un estándar para el
 * ELM327 por BLE, así que se busca un servicio que tenga una característica de notificación
 * (para recibir) y una de escritura (para enviar): FFF0/FFF1/FFF2, FFE0/FFE1, 18F0, etc.
 */
@SuppressLint("MissingPermission")
@SuppressWarnings("deprecation")
class BleLink extends Link {
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context ctx;
    private final BluetoothDevice device;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx, tx;
    private final CountDownLatch servicesReady = new CountDownLatch(1);
    private final CountDownLatch notifyReady = new CountDownLatch(1);
    private final Semaphore writeDone = new Semaphore(0);
    private volatile boolean servicesOk = false;
    private int chunk = 20;

    BleLink(Context ctx, BluetoothDevice device) {
        this.ctx = ctx;
        this.device = device;
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Una operación GATT a la vez: primero el MTU, y al terminar, los servicios
                if (!g.requestMtu(185)) g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                markClosed();
                servicesReady.countDown();
                notifyReady.countDown();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) chunk = Math.min(mtu - 3, 180);
            g.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            servicesOk = status == BluetoothGatt.GATT_SUCCESS;
            servicesReady.countDown();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            notifyReady.countDown();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            writeDone.release();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v != null) onBytes(v, v.length);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (value != null) onBytes(value, value.length);
        }
    };

    @Override
    void open() throws IOException {
        if (Build.VERSION.SDK_INT >= 23) gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        else gatt = device.connectGatt(ctx, false, cb);
        if (gatt == null) throw new IOException("No se pudo abrir la conexión BLE");
        try {
            if (!servicesReady.await(20, TimeUnit.SECONDS) || closed || !servicesOk)
                throw new IOException("El adaptador BLE no respondió");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrumpido");
        }
        pickCharacteristics();
        if (rx == null || tx == null) throw new IOException("El dispositivo BLE no parece un adaptador OBD");
        gatt.setCharacteristicNotification(rx, true);
        BluetoothGattDescriptor d = rx.getDescriptor(CCCD);
        if (d != null) {
            byte[] val = (rx.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(d, val);
            else {
                d.setValue(val);
                gatt.writeDescriptor(d);
            }
            try {
                notifyReady.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (closed) throw new IOException("Se perdió la conexión BLE");
    }

    private static boolean isStandard(UUID u) {
        String s = u.toString().toLowerCase();
        return s.startsWith("00001800-") || s.startsWith("00001801-") || s.startsWith("0000180a-")
                || s.startsWith("0000180f-");
    }

    private void pickCharacteristics() {
        for (BluetoothGattService svc : gatt.getServices()) {
            if (isStandard(svc.getUuid())) continue;
            BluetoothGattCharacteristic n = null, w = null;
            for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                int p = c.getProperties();
                if (n == null && (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0)
                    n = c;
                if (w == null && (p & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0)
                    w = c;
            }
            if (n != null && w != null) {
                rx = n;
                tx = w;
                return;
            }
        }
    }

    @Override
    void write(byte[] data) throws IOException {
        if (closed || gatt == null) throw new IOException("Sin conexión BLE");
        boolean noResp = (tx.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        int type = noResp ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        for (int off = 0; off < data.length; off += chunk) {
            int len = Math.min(chunk, data.length - off);
            byte[] part = new byte[len];
            System.arraycopy(data, off, part, 0, len);
            writeDone.drainPermits();
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33) {
                ok = gatt.writeCharacteristic(tx, part, type) == BluetoothGatt.GATT_SUCCESS;
            } else {
                tx.setWriteType(type);
                tx.setValue(part);
                ok = gatt.writeCharacteristic(tx);
            }
            if (!ok) throw new IOException("No se pudo enviar por BLE");
            try {
                writeDone.tryAcquire(1500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrumpido");
            }
        }
    }

    @Override
    void close() {
        markClosed();
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
    }

    @Override
    String describe() {
        return "Bluetooth BLE";
    }
}
