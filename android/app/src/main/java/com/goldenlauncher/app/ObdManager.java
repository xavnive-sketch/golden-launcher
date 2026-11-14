package com.goldenlauncher.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mantiene la conexión con el adaptador y lee los PID en un hilo propio.
 * El dashboard consulta {@link #snapshot()} cada segundo (mismo formato que GET /obd de bridge.py).
 */
@SuppressLint("MissingPermission")
class ObdManager {
    private interface Fn {
        double apply(int[] b);
    }

    private static final String[] PIDS = {"0C", "0D", "05", "5C", "0B", "33", "11", "06", "07", "2F", "42", "1F", "A6"};
    private static final String[] KEYS = {"rpm", "speed", "temp", "oil", "map", "baro", "throttle", "stft", "ltft", "fuel", "volts", "runtime", "odo"};
    private static final int[] SIZES = {2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 4};
    private static final Fn[] FNS = {
            b -> Math.round((b[0] * 256 + b[1]) / 4.0),
            b -> b[0],
            b -> b[0] - 40,
            b -> b[0] - 40,
            b -> b[0],
            b -> b[0],
            b -> Math.round(b[0] * 1000 / 255.0) / 10.0,
            b -> Math.round((b[0] - 128) * 1000 / 128.0) / 10.0,
            b -> Math.round((b[0] - 128) * 1000 / 128.0) / 10.0,
            b -> Math.round(b[0] * 100 / 255.0),
            b -> Math.round((b[0] * 256 + b[1]) / 100.0) / 10.0,
            b -> b[0] * 256 + b[1],
            b -> Math.round((((long) b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]) / 10.0)
    };
    private static final String FAST = "0C0D11";
    private static final long DTC_EVERY_MS = 30000;

    private final Context ctx;
    private final Object lock = new Object();
    private JSONObject state = new JSONObject();
    private Thread worker;
    private volatile boolean running = false;
    private volatile Link link;
    private String target = null;
    private double tripKm = 0, tripS = 0, vmax = 0;

    ObdManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        setState(false, false, "Adaptador sin configurar", null, null);
    }

    String snapshot() {
        synchronized (lock) {
            return state.toString();
        }
    }

    private void setState(boolean connected, boolean ecu, String error, JSONObject data, JSONArray dtc) {
        synchronized (lock) {
            try {
                JSONObject s = new JSONObject();
                s.put("connected", connected);
                s.put("ecu", ecu);
                s.put("error", error == null ? JSONObject.NULL : error);
                s.put("data", data == null ? new JSONObject() : data);
                Object prevDtc = state.opt("dtc");
                s.put("dtc", dtc != null ? dtc : (ecu && prevDtc != null ? prevDtc : JSONObject.NULL));
                s.put("ts", System.currentTimeMillis() / 1000.0);
                state = s;
            } catch (JSONException ignored) {
            }
        }
    }

    synchronized void start(String address, String kind) {
        String key = address + "|" + kind;
        if (running && key.equals(target)) return;
        stop();
        target = key;
        running = true;
        worker = new Thread(() -> loop(address, kind), "obd-worker");
        worker.setDaemon(true);
        worker.start();
    }

    synchronized void stop() {
        running = false;
        target = null;
        Link l = link;
        if (l != null) l.close();
        if (worker != null) worker.interrupt();
        worker = null;
    }

    private Link makeLink(String address, String kind) throws IOException {
        if (address.contains(":") && address.indexOf(':') == address.lastIndexOf(':')) {
            // "host:puerto" -> Wi-Fi
            String[] p = address.split(":");
            return new TcpLink(p[0], Integer.parseInt(p[1]));
        }
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null) throw new IOException("Esta radio no tiene Bluetooth");
        if (!ad.isEnabled()) throw new IOException("El Bluetooth está apagado");
        BluetoothDevice dev = ad.getRemoteDevice(address);
        if ("ble".equals(kind) || dev.getType() == BluetoothDevice.DEVICE_TYPE_LE) return new BleLink(ctx, dev);
        return new ClassicLink(ad, dev);
    }

    private void loop(String address, String kind) {
        boolean tryBleNext = false;
        while (running) {
            Link l = null;
            try {
                setState(false, false, "Conectando con el adaptador…", null, null);
                l = tryBleNext ? new BleLink(ctx, getDevice(address)) : makeLink(address, kind);
                link = l;
                l.open();
                Elm327 elm = new Elm327(l);
                elm.init();
                setState(true, false, null, null, null);
                poll(elm);
            } catch (Elm327.EngineOff e) {
                setState(true, false, "Adaptador OK, pero la ECU no responde (¿motor apagado?)", null, null);
                sleep(3000);
            } catch (SecurityException e) {
                setState(false, false, "Falta el permiso de Bluetooth (dalo en los ajustes de la app)", null, null);
                sleep(5000);
            } catch (Exception e) {
                // En modo automático: si el clásico falla, probar BLE en el siguiente intento
                if ("auto".equals(kind)) tryBleNext = !tryBleNext;
                String m = e.getMessage();
                setState(false, false, m == null || m.isEmpty() ? e.getClass().getSimpleName() : m, null, null);
                sleep(5000);
            } finally {
                if (l != null) l.close();
                link = null;
            }
        }
    }

    private BluetoothDevice getDevice(String address) throws IOException {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null) throw new IOException("Esta radio no tiene Bluetooth");
        return ad.getRemoteDevice(address);
    }

    private void poll(Elm327 elm) throws IOException {
        Map<String, int[]> fail = new HashMap<>();   // pid -> {fallos, segundo del último fallo}
        JSONObject data = new JSONObject();
        long last = System.currentTimeMillis(), lastDtc = 0;
        int n = 0;
        while (running) {
            n++;
            for (int i = 0; i < PIDS.length; i++) {
                String pid = PIDS[i];
                if (!FAST.contains(pid) && n % 5 != 0) continue;
                int[] f = fail.get(pid);
                long nowS = System.currentTimeMillis() / 1000;
                if (f != null && f[0] >= 3 && nowS - f[1] < 60) continue; // no soportado: reintentar en 1 min
                int[] b = elm.query(pid);
                if (b == null || b.length < SIZES[i]) {
                    fail.put(pid, new int[]{f == null ? 1 : f[0] + 1, (int) nowS});
                    data.remove(KEYS[i]);
                } else {
                    fail.remove(pid);
                    try {
                        double v = FNS[i].apply(b);
                        if (v == Math.rint(v)) data.put(KEYS[i], (long) v);
                        else data.put(KEYS[i], v);
                    } catch (JSONException ignored) {
                    }
                }
            }
            long now = System.currentTimeMillis();
            double dt = (now - last) / 1000.0;
            last = now;
            try {
                if (data.has("speed") && dt < 5) {
                    double spd = data.getDouble("speed");
                    tripKm += spd * dt / 3600.0;
                    vmax = Math.max(vmax, spd);
                }
                if (data.optDouble("rpm", 0) > 0 && dt < 5) tripS += dt;
                if (data.has("map") && data.has("baro"))
                    data.put("boost", Math.round((data.getDouble("map") - data.getDouble("baro"))) / 100.0);
                JSONObject out = new JSONObject(data.toString());
                out.put("trip", Math.round(tripKm * 10) / 10.0);
                out.put("trip_time", (long) tripS);
                out.put("vmax", (long) vmax);
                JSONArray dtc = null;
                if (now - lastDtc > DTC_EVERY_MS) {
                    lastDtc = now;
                    try {
                        List<String> codes = elm.readDtc();
                        dtc = new JSONArray(codes);
                    } catch (IOException | RuntimeException ignored) {
                    }
                }
                setState(true, true, null, out, dtc);
            } catch (JSONException ignored) {
            }
            sleep(200);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
