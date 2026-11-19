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
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
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

    // Gasolina y diésel: 04 carga, 0F temp. admisión, 10 MAF, 46 temp. ambiente, 49 pedal del acelerador,
    // 5E consumo de combustible (L/h) — los diésel suelen enviar estos aunque no envíen 11/06/07.
    private static final String[] PIDS = {"0C", "0D", "05", "5C", "0B", "33", "11", "06", "07", "2F", "42", "1F", "A6",
            "04", "0F", "10", "46", "49", "5E", "23", "2C", "31"};
    private static final String[] KEYS = {"rpm", "speed", "temp", "oil", "map", "baro", "throttle", "stft", "ltft", "fuel", "volts", "runtime", "odo",
            "load", "iat", "maf", "amb", "pedal", "fuel_rate", "rail", "egr", "dist_clr"};
    private static final int[] SIZES = {2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 4, 1, 1, 2, 1, 1, 2, 2, 1, 2};
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
            b -> Math.round((((long) b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]) / 10.0),
            b -> Math.round(b[0] * 100 / 255.0),
            b -> b[0] - 40,
            b -> Math.round((b[0] * 256 + b[1])) / 100.0,
            b -> b[0] - 40,
            b -> Math.round(b[0] * 1000 / 255.0) / 10.0,
            b -> Math.round((b[0] * 256 + b[1]) / 2.0) / 10.0,
            b -> Math.round((b[0] * 256 + b[1])) / 10.0,          // 23 presión del riel: x10 kPa -> bar
            b -> Math.round(b[0] * 1000 / 255.0) / 10.0,          // 2C EGR comandado (%)
            b -> b[0] * 256 + b[1]                                 // 31 km desde el borrado de códigos
    };
    private static final java.util.List<String> FAST = java.util.Arrays.asList("0C", "0D", "11", "49", "04", "5E");
    private static final long DTC_EVERY_MS = 30000;

    private final Context ctx;
    private final Object lock = new Object();
    private JSONObject state = new JSONObject();
    private Thread worker;
    private volatile boolean running = false;
    private volatile Link link;
    private String target = null;
    private double tripKm = 0, tripS = 0, vmax = 0;
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private static final String[] PROTOS = {"Automático", "SAE J1850 PWM", "SAE J1850 VPW", "ISO 9141-2", "ISO 14230 KWP (5 baud)",
            "ISO 14230 KWP (rápido)", "CAN 11 bit 500k", "CAN 29 bit 500k", "CAN 11 bit 250k", "CAN 29 bit 250k", "SAE J1939 CAN", "USER1 CAN", "USER2 CAN"};

    /** Registro de conexión visible en Diagnóstico (últimas 40 líneas). */
    void log(String s) {
        synchronized (log) {
            log.addLast(new SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new Date()) + "  " + s);
            while (log.size() > 40) log.removeFirst();
        }
    }

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
                synchronized (log) {
                    s.put("log", new JSONArray(log));
                }
                state = s;
            } catch (JSONException ignored) {
            }
        }
    }

    synchronized void start(String address, String kind) {
        String key = address + "|" + kind;
        if (running && key.equals(target)) return;
        stop();
        synchronized (log) {
            log.clear();
        }
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
        try {
            int t = dev.getType(), b = dev.getBondState();
            log("Dispositivo: " + dev.getName() + " · tipo " + (t == 1 ? "clásico" : t == 2 ? "BLE" : t == 3 ? "doble" : "desconocido")
                    + " · " + (b == BluetoothDevice.BOND_BONDED ? "emparejado" : "NO emparejado"));
        } catch (SecurityException e) {
            log("Sin permiso para leer el dispositivo Bluetooth");
        }
        if ("ble".equals(kind) || dev.getType() == BluetoothDevice.DEVICE_TYPE_LE) return new BleLink(ctx, dev);
        return new ClassicLink(ad, dev);
    }

    private void loop(String address, String kind) {
        boolean tryBleNext = false;
        int fails = 0;
        final long[] BACKOFF = {2000, 3000, 5000, 8000, 12000, 15000};
        while (running) {
            Link l = null;
            try {
                setState(false, false, "Conectando con el adaptador…", null, null);
                log("Conectando con " + address + " (modo " + kind + (tryBleNext ? ", probando BLE" : "") + ")");
                l = tryBleNext ? new BleLink(ctx, getDevice(address)) : makeLink(address, kind);
                link = l;
                log("Abriendo " + l.describe() + "…");
                l.open();
                log("✓ Conectado al adaptador. Iniciando ELM327…");
                Elm327 elm = new Elm327(l, this::log);
                elm.init();
                int pi = elm.protocol.isEmpty() ? -1 : Character.digit(elm.protocol.charAt(0), 16);
                fails = 0;
                log("✓ Adaptador listo · protocolo del vehículo: " + (pi >= 0 && pi < PROTOS.length ? PROTOS[pi] : elm.protocol));
                setState(true, false, null, null, null);
                poll(elm);
            } catch (Elm327.EngineOff e) {
                log("✗ El adaptador responde, pero el vehículo no (UNABLE TO CONNECT). ¿Contacto encendido / motor en marcha?");
                setState(true, false, "Adaptador OK, pero la ECU no responde (¿motor apagado?)", null, null);
                sleep(3000);
            } catch (SecurityException e) {
                log("✗ Falta el permiso de Bluetooth");
                setState(false, false, "Falta el permiso de Bluetooth (dalo en los ajustes de la app)", null, null);
                sleep(5000);
            } catch (Exception e) {
                // En modo automático: si el clásico falla, probar BLE en el siguiente intento
                if ("auto".equals(kind)) tryBleNext = !tryBleNext;
                String m = e.getMessage();
                m = m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
                long wait = BACKOFF[Math.min(fails, BACKOFF.length - 1)];
                fails++;
                ClassicLink.preferDirect();
                log("✗ " + m + " · reintento " + fails + " en " + (wait / 1000) + " s");
                setState(false, false, m, null, null);
                sleep(wait);
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
        // Preguntar al vehículo qué datos envía: así solo se leen esos (más rápido) y el resto se marca N/D
        java.util.Set<String> sup = elm.supportedPids();
        if (sup.isEmpty()) {
            sup = null;
            log("El vehículo no informó sus PIDs soportados: se probarán todos");
        } else {
            StringBuilder have = new StringBuilder();
            for (int i = 0; i < PIDS.length; i++) if (sup.contains(PIDS[i])) have.append(KEYS[i]).append(' ');
            log("PIDs soportados: " + sup.size() + " · útiles: " + have.toString().trim());
        }
        JSONArray nd = new JSONArray();
        if (sup != null) {
            for (int i = 0; i < PIDS.length; i++) if (!sup.contains(PIDS[i])) nd.put(KEYS[i]);
            if (!sup.contains("11") && sup.contains("49")) nd = remove(nd, "throttle"); // se usa el pedal
        }
        boolean adapterVolts = sup != null && !sup.contains("42");
        boolean noThrottle = sup == null ? true : !sup.contains("11");
        double pedalMin = 100, pedalMax = 0;
        if (adapterVolts) nd = remove(nd, "volts");
        while (running) {
            n++;
            for (int i = 0; i < PIDS.length; i++) {
                String pid = PIDS[i];
                if (!FAST.contains(pid) && n % 5 != 0) continue;
                if (sup != null && !sup.contains(pid)) continue;
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
            if (adapterVolts && n % 5 == 1) {
                try {
                    Double v = elm.batteryVoltage();
                    if (v != null) data.put("volts", v);
                } catch (IOException | JSONException ignored) {
                }
            }
            try {
                // Sin sensor de mariposa: usar el pedal (PID 49). Su valor absoluto no va de 0 a 100
                // (en reposo suele marcar 15-20 %), así que se escala entre el mínimo visto y ~55 puntos más.
                if (noThrottle && data.has("pedal") && (sup != null || fail.containsKey("11"))) {
                    double p = data.getDouble("pedal");
                    pedalMin = Math.min(pedalMin, p);
                    pedalMax = Math.max(pedalMax, Math.max(p, pedalMin + 55));
                    double rel = (p - pedalMin) / (pedalMax - pedalMin) * 100.0;
                    data.put("throttle", Math.round(Math.max(0, Math.min(100, rel)) * 10) / 10.0);
                }
            } catch (JSONException ignored) {
            }
            if (n == 1) {
                log("✓ Primera lectura del vehículo: " + (data.length() == 0 ? "ningún dato (el vehículo no respondió a los PID)" : data.toString()));
                elm.verbose = false; // no llenar el registro con cada lectura
            }
            long now = System.currentTimeMillis();
            double dt = (now - last) / 1000.0;
            last = now;
            try {
                if (data.has("speed") && dt < 30) {
                    double spd = data.getDouble("speed");
                    tripKm += spd * dt / 3600.0;
                    vmax = Math.max(vmax, spd);
                }
                if (data.optDouble("rpm", 0) > 0 && dt < 30) tripS += dt;
                if (data.has("map") && data.has("baro"))
                    data.put("boost", Math.round((data.getDouble("map") - data.getDouble("baro"))) / 100.0);
                JSONObject out = new JSONObject(data.toString());
                out.put("trip", Math.round(tripKm * 10) / 10.0);
                out.put("trip_time", (long) tripS);
                out.put("vmax", (long) vmax);
                out.put("_nd", nd);
                JSONArray dtc = null;
                if (now - lastDtc > DTC_EVERY_MS) {
                    lastDtc = now;
                    try {
                        List<String> codes = elm.readDtc();
                        if (n == 1) log("Códigos de avería: " + (codes.isEmpty() ? "ninguno" : codes.toString()));
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

    private static JSONArray remove(JSONArray a, String v) {
        JSONArray r = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!v.equals(a.optString(i))) r.put(a.opt(i));
        return r;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
