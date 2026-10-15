package com.dsh.batteryalarm;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private WebView webView;
    private static final int REQ_NOTIF = 1001;

    private static class AppWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    view.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
                return true;
            }
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // تنظیم نوار وضعیت شیک تیره
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.parseColor("#080c16"));
        window.setNavigationBarColor(Color.parseColor("#080c16"));

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setBackgroundColor(Color.parseColor("#080c16"));
        webView.setKeepScreenOn(true);

        webView.addJavascriptInterface(new WebBridge(this), "Android");
        webView.addJavascriptInterface(new SysInfoBridge(this), "SysInfo");

        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/index.html");

        requestPermissions();
        startWatchService();
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                int check = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS);
                if (check != PackageManager.PERMISSION_GRANTED) {
                    String[] perms = {Manifest.permission.POST_NOTIFICATIONS};
                    requestPermissions(perms, REQ_NOTIF);
                }
            } catch (Exception e) {
                Log.w(TAG, "notification permission request failed", e);
            }
        }
        requestIgnoreBatteryOptimizations();
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            String pkg = getPackageName();
            if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                Intent intent = new Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATION_SETTINGS");
                intent.setData(Uri.parse("package:" + pkg));
                startActivity(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "battery opt-out request failed", e);
        }
    }

    private void startWatchService() {
        try {
            Intent svc = new Intent(this, BatteryWatchService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot start service", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "notification permission: " + (granted ? "granted" : "denied"));
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
