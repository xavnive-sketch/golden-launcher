package com.goldenlauncher.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Golden Launcher: muestra el dashboard (assets/www/index.html) a pantalla completa y
 * le da acceso nativo al adaptador OBD-II por Bluetooth (window.GLNative).
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/www/index.html";
    private static final int REQ_PERMS = 7;

    private WebView web;
    private NativeBridge bridge;
    private boolean locationAtStart;
    private static int renderRestarts = 0;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        web = new WebView(this);
        web.setBackgroundColor(0xFF010610);
        setContentView(web);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setGeolocationEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        // Hermes puede estar en la red local por http://
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            // Si el motor del WebView se cae (p. ej. falta de memoria), reiniciar la pantalla en vez de cerrar la app
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.e("GoldenLauncher", "El WebView se detuvo (crash=" + (Build.VERSION.SDK_INT >= 26 && detail.didCrash()) + "); reiniciando");
                if (view == web) {
                    web = null;
                    try {
                        ((android.view.ViewGroup) view.getParent()).removeView(view);
                        view.destroy();
                    } catch (Exception ignored) {
                    }
                    if (renderRestarts++ < 3) recreate(); else finish();
                }
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d("GoldenLauncher", m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
        });

        ObdManager obd = new ObdManager(this);
        bridge = new NativeBridge(this, web, obd);
        web.addJavascriptInterface(bridge, "GLNative");

        locationAtStart = has(Manifest.permission.ACCESS_FINE_LOCATION);
        requestMissing(true);
        web.loadUrl(START_URL);
    }

    private boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private List<String> missing(boolean withLocation) {
        List<String> need = new ArrayList<>();
        if (withLocation && !has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (!has(Manifest.permission.BLUETOOTH_SCAN)) need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (!has(Manifest.permission.BLUETOOTH_CONNECT)) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !need.contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION); // en Android 11 o menor, buscar BLE requiere ubicación
        }
        return need;
    }

    private void requestMissing(boolean withLocation) {
        List<String> need = missing(withLocation);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    /** true si ya hay permisos de Bluetooth; si no, los pide y devuelve false. */
    boolean ensureBluetoothPermissions() {
        if (missing(false).isEmpty()) return true;
        runOnUiThread(() -> requestMissing(false));
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Si se acaba de dar el permiso de ubicación, recargar para que el GPS de la página arranque
        if (!locationAtStart && has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            locationAtStart = true;
            if (web != null) web.reload();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // Atrás = volver a Inicio (no cerrar la app)
        if (web != null) web.evaluateJavascript("typeof setPage==='function'&&setPage('inicio')", null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.evaluateJavascript("try{lsSet(KMF_KEY,kmSinceFill)}catch(e){}", null);
    }

    @Override
    protected void onDestroy() {
        bridge.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
