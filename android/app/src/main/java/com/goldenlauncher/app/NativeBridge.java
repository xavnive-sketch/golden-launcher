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
    private final Voice voice;

    NativeBridge(MainActivity act, WebView web, ObdManager obd) {
        this.act = act;
        this.web = web;
        this.obd = obd;
        this.scanner = new DeviceScanner(act);
        this.media = new Media(act);
        this.voice = new Voice(act, (type, text) -> push("window.onNativeVoice && window.onNativeVoice("
                + org.json.JSONObject.quote(type) + "," + org.json.JSONObject.quote(text) + ")"));
    }

    // ---- HTTP nativo (Hermes): sin restricciones CORS del navegador ----
    @JavascriptInterface
    public void http(String id, String method, String url, String headersJson, String body, int timeoutMs) {
        // Solo direcciones web: nunca file:, content: ni otros esquemas internos de Android
        String u = url == null ? "" : url.trim().toLowerCase(java.util.Locale.ROOT);
        String m = method == null ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
        if (!(u.startsWith("http://") || u.startsWith("https://")) || !java.util.Arrays.asList("GET", "POST", "PUT", "DELETE").contains(m)) {
            push("window.onNativeHttp && window.onNativeHttp(" + org.json.JSONObject.quote(id == null ? "" : id) + ",0,'URL no permitida')");
            return;
        }
        final int tmo = Math.max(1000, Math.min(timeoutMs, 180000));
        new Thread(() -> {
            int status = 0;
            String text;
            java.net.HttpURLConnection c = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setRequestMethod(m);
                c.setConnectTimeout(Math.min(tmo, 10000));
                c.setReadTimeout(tmo);
                if (headersJson != null && !headersJson.isEmpty()) {
                    org.json.JSONObject h = new org.json.JSONObject(headersJson);
                    java.util.Iterator<String> it = h.keys();
                    while (it.hasNext()) { String k = it.next(); c.setRequestProperty(k, h.getString(k)); }
                }
                if (body != null && !body.isEmpty()) {
                    c.setDoOutput(true);
                    byte[] b = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    c.getOutputStream().write(b);
                }
                status = c.getResponseCode();
                java.io.InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
                if (in != null) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0 && os.size() < 2_000_000) os.write(buf, 0, n);
                }
                text = os.toString("UTF-8");
            } catch (Exception e) {
                status = 0;
                text = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                if (c != null) c.disconnect();
            }
            push("window.onNativeHttp && window.onNativeHttp(" + org.json.JSONObject.quote(id) + "," + status + "," + org.json.JSONObject.quote(text) + ")");
        }, "http").start();
    }

    // ---- Voz ----
    @JavascriptInterface
    public boolean voiceAvailable() {
        return voice.canListen();
    }

    @JavascriptInterface
    public void listen() {
        if (!act.ensureMicPermission()) {
            push("window.onNativeVoice && window.onNativeVoice('error','Permite el micrófono y vuelve a tocar el botón.')");
            return;
        }
        voice.listen();
    }

    @JavascriptInterface
    public void stopListening() {
        voice.stopListening();
    }

    @JavascriptInterface
    public void speak(String text) {
        voice.speak(text);
    }

    @JavascriptInterface
    public void stopSpeaking() {
        voice.stopSpeaking();
    }

    private void push(String js) {
        web.post(() -> web.evaluateJavascript(js, null));
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
        if (pkg == null || !pkg.matches("[A-Za-z0-9_.]{1,200}")) return false;
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
    public String mediaPlayApp(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_.]{1,200}")) return "error";
        try {
            return media.playApp(pkg);
        } catch (Exception e) {
            return "error";
        }
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
        voice.shutdown();
        scanner.stop();
        obd.stop();
    }
}
