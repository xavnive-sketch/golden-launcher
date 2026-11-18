package com.goldenlauncher.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lista los adaptadores: emparejados + búsqueda BLE + búsqueda clásica (12 s). */
@SuppressLint("MissingPermission")
class DeviceScanner {
    interface Listener {
        void onDevices(JSONArray list, boolean done);
    }

    private static final long SCAN_MS = 12000;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, JSONObject> found = new LinkedHashMap<>();
    private Listener listener;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner le;
    private boolean scanning = false;
    private boolean receiverOn = false;

    DeviceScanner(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private final ScanCallback leCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            add(r.getDevice(), "ble");
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) add(r.getDevice(), "ble");
        }
    };

    private final BroadcastReceiver classicRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (BluetoothDevice.ACTION_FOUND.equals(i.getAction()) || BluetoothDevice.ACTION_NAME_CHANGED.equals(i.getAction())) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null) add(d, "classic");
            }
        }
    };

    private static String kindOf(BluetoothDevice d, String seenAs) {
        try {
            switch (d.getType()) {
                case BluetoothDevice.DEVICE_TYPE_LE:
                    return "ble";
                case BluetoothDevice.DEVICE_TYPE_CLASSIC:
                    return "classic";
                case BluetoothDevice.DEVICE_TYPE_DUAL:
                    return "dual";
                default:
                    return seenAs;
            }
        } catch (SecurityException e) {
            return seenAs;
        }
    }

    /** Los nombres típicos de adaptadores OBD van primero en la lista. */
    private static boolean looksObd(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("obd") || n.contains("elm") || n.contains("vlink") || n.contains("v-link")
                || n.contains("vgate") || n.contains("icar") || n.contains("veepeak") || n.contains("obdlink")
                || n.contains("konnwei") || n.contains("car");
    }

    private void add(BluetoothDevice d, String seenAs) {
        String name;
        try {
            name = d.getName();
        } catch (SecurityException e) {
            name = null;
        }
        String addr = d.getAddress();
        if (name == null || name.trim().isEmpty()) {
            if ("ble".equals(seenAs)) return;           // BLE sin nombre: hay muchos alrededor, casi nunca es el adaptador
            name = "Sin nombre (" + addr + ")";
        }
        boolean bonded;
        try {
            bonded = d.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (SecurityException e) {
            bonded = false;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("address", addr);
            o.put("kind", kindOf(d, seenAs));
            o.put("bonded", bonded);
            o.put("obd", looksObd(name));
            boolean isNew;
            synchronized (found) {
                JSONObject prev = found.get(addr);
                isNew = prev == null || !prev.optString("name").equals(name);
                found.put(addr, o);
            }
            if (isNew) publish(false);
        } catch (JSONException ignored) {
        }
    }

    private void publish(boolean done) {
        List<JSONObject> l;
        synchronized (found) {
            l = new ArrayList<>(found.values());
        }
        l.sort((a, b) -> Boolean.compare(b.optBoolean("obd"), a.optBoolean("obd")));
        JSONArray arr = new JSONArray();
        for (JSONObject o : l) arr.put(o);
        if (listener != null) listener.onDevices(arr, done);
    }

    void scan(Listener l) {
        listener = l;
        main.post(this::startOnMain);
    }

    private void startOnMain() {
        stopOnMain(false);
        synchronized (found) {
            found.clear();
        }
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            publish(true);
            return;
        }
        scanning = true;
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) add(d, "classic");
        } catch (SecurityException ignored) {
        }
        try {
            le = adapter.getBluetoothLeScanner();
            if (le != null) le.startScan(leCb);
        } catch (SecurityException ignored) {
        }
        try {
            IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            f.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(classicRx, f, Context.RECEIVER_EXPORTED);
            else ctx.registerReceiver(classicRx, f);
            receiverOn = true;
            adapter.startDiscovery();
        } catch (SecurityException ignored) {
        }
        publish(false);
        main.postDelayed(() -> stopOnMain(true), SCAN_MS);
    }

    void stop() {
        main.post(() -> stopOnMain(false));
    }

    private void stopOnMain(boolean notify) {
        if (!scanning) return;
        scanning = false;
        try {
            if (le != null) le.stopScan(leCb);
        } catch (Exception ignored) {
        }
        try {
            if (adapter != null) adapter.cancelDiscovery();
        } catch (Exception ignored) {
        }
        if (receiverOn) {
            try {
                ctx.unregisterReceiver(classicRx);
            } catch (Exception ignored) {
            }
            receiverOn = false;
        }
        main.removeCallbacksAndMessages(null);
        if (notify) publish(true);
    }

    boolean bluetoothReady() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
        return a != null && a.isEnabled();
    }
}
