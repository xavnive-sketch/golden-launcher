package com.goldenlauncher.app;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

/** Funciones que la página llama como window.GLNative.* */
class NativeBridge {
    private final MainActivity act;
    private final WebView web;
    private final ObdManager obd;
    private final DeviceScanner scanner;
    private final Media media;

    NativeBridge(MainActivity act, WebView web, ObdManager obd) {
        this.act = act;
        this.web = web;
        this.obd = obd;
        this.scanner = new DeviceScanner(act);
        this.media = new Media(act);
    }

    // ---- Cajón de apps ----
    @JavascriptInterface
    public void listApps() {
        new Thread(() -> {
            String json = media.listApps().toString();
            web.post(() -> web.evaluateJavascript("window.onNativeApps && window.onNativeApps(" + json + ")", null));
        }, "list-apps").start();
    }

    @JavascriptInterface
    public boolean launchApp(String pkg) {
        try {
            return media.launch(pkg);
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Música ----
    @JavascriptInterface
    public String mediaInfo() {
        return media.info().toString();
    }

    @JavascriptInterface
    public void mediaControl(String action) {
        media.control(action == null ? "toggle" : action);
    }

    @JavascriptInterface
    public void mediaVolume(int dir) {
        media.volume(dir);
    }

    @JavascriptInterface
    public void openNotificationAccess() {
        media.openNotificationAccess();
    }

    @JavascriptInterface
    public String obdSnapshot() {
        return obd.snapshot();
    }

    @JavascriptInterface
    public void obdStart(String address, String kind) {
        if (address == null || address.isEmpty()) return;
        act.ensureBluetoothPermissions();
        obd.start(address, kind == null ? "auto" : kind);
    }

    @JavascriptInterface
    public void obdStop() {
        obd.stop();
    }

    @JavascriptInterface
    public void scanDevices() {
        act.runOnUiThread(() -> {
            if (!act.ensureBluetoothPermissions()) {
                Toast.makeText(act, "Permite el acceso a Bluetooth y vuelve a buscar", Toast.LENGTH_LONG).show();
                push("[]", true);
                return;
            }
            if (!scanner.bluetoothReady()) {
                try {
                    act.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                } catch (Exception ignored) {
                }
                Toast.makeText(act, "Enciende el Bluetooth y vuelve a buscar", Toast.LENGTH_LONG).show();
                push("[]", true);
                return;
            }
            scanner.scan((list, done) -> push(list.toString(), done));
        });
    }

    @JavascriptInterface
    public String version() {
        try {
            PackageInfo p = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return p.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void push(String json, boolean done) {
        String js = "window.onNativeDevices && window.onNativeDevices(" + json + "," + done + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    void shutdown() {
        scanner.stop();
        obd.stop();
    }
}
