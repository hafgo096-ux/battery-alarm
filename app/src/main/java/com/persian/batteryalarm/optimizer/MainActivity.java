package com.persian.batteryalarm.optimizer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

/**
 * اکتیویتی اصلی (نسخه ۱.۰.۱).
 *
 * اصلاحات مهم:
 *  - بارگذاری صفحه از دامنه امن https://appassets.androidplatform.net با WebViewAssetLoader
 *    تا localStorage/DOM Storage (که روی file:// در اندروید مسدود است) کار کند.
 *  - حذف setKeepScreenOn(true) (مصرف شدید باتری/نمایشگر).
 *  - توقف کامل تایمرهای جاوااسکریپت و رندر هنگام رفتن اپ به پس‌زمینه (onPause/onResume).
 *  - آزادسازی کامل WebView در onDestroy (رفع نشتی حافظه).
 *  - درخواست معافیت بهینه‌سازی باتری فقط یک‌بار (نه در هر اجرا).
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQ_NOTIF = 1001;
    private static final String ASSET_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final String FALLBACK_URL = "file:///android_asset/index.html";

    private WebView webView;
    private boolean usedFallback = false;

    private static class AppWebViewClient extends WebViewClient {
        private final WebViewAssetLoader assetLoader;
        private final MainActivity activity;

        AppWebViewClient(MainActivity activity, WebViewAssetLoader assetLoader) {
            this.activity = activity;
            this.assetLoader = assetLoader;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                if (request != null && request.getUrl() != null) {
                    WebResourceResponse r = assetLoader.shouldInterceptRequest(request.getUrl());
                    if (r != null) return r;
                }
            } catch (Exception ignored) {}
            return null;
        }

        @SuppressWarnings("deprecation")
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            try {
                if (url != null) {
                    WebResourceResponse r = assetLoader.shouldInterceptRequest(Uri.parse(url));
                    if (r != null) return r;
                }
            } catch (Exception ignored) {}
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(view, request != null && request.getUrl() != null ? request.getUrl().toString() : null);
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(view, url);
        }

        private boolean handleUrl(WebView view, String url) {
            if (url == null) return false;
            if (url.contains("appassets.androidplatform.net") || url.startsWith("file://")) {
                return false; // بارگذاری داخل خود وب‌ویو
            }
            try {
                view.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Log.w(TAG, "cannot open external url: " + url, e);
            }
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
                MainActivity act = activity;
                if (act != null && !act.usedFallback) {
                    act.usedFallback = true;
                    Log.w(TAG, "asset loader failed, falling back to file://");
                    view.loadUrl(FALLBACK_URL);
                }
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.parseColor("#070a13"));
        window.setNavigationBarColor(Color.parseColor("#070a13"));

        webView = new WebView(this);
        setContentView(webView);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);          // بدون این، تنظیمات وب ذخیره نمی‌شود
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);            // فقط برای مسیر پشتیبان (fallback)
        settings.setAllowContentAccess(true);           // برای نمایش thumbnail تصاویر/ویدیوها (content://)
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setSaveFormData(false);

        webView.setBackgroundColor(Color.parseColor("#070a13"));
        // نکته مهم مصرف باتری: دیگر setKeepScreenOn(true) نداریم تا نمایشگر آزادانه خاموش شود.
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);

        webView.addJavascriptInterface(new WebBridge(this), "Android");
        webView.addJavascriptInterface(new SysInfoBridge(this), "SysInfo");

        webView.setWebViewClient(new AppWebViewClient(this, assetLoader));
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                if (cm != null) Log.i("WebConsole", cm.message() + " @" + cm.lineNumber());
                return true;
            }
        });

        try {
            boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            WebView.setWebContentsDebuggingEnabled(debuggable);
        } catch (Exception ignored) {}

        webView.loadUrl(ASSET_URL);

        requestAppPermissions();
        startMonitoringAccordingToSettings();
    }

    private void requestAppPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                }
            } catch (Exception e) {
                Log.w(TAG, "notification permission request failed", e);
            }
        }
        // درخواست معافیت از بهینه‌سازی باتری فقط یک‌بار (اصلاح باگ درخواست مکرر)
        if (!MonitorPrefs.getBool(this, MonitorPrefs.S_BATTERY_PROMPT_DONE, false)) {
            requestIgnoreBatteryOptimizations();
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            String pkg = getPackageName();
            if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + pkg));
                startActivity(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "battery optimization opt-out request failed", e);
        } finally {
            MonitorPrefs.putBool(this, MonitorPrefs.S_BATTERY_PROMPT_DONE, true);
        }
    }

    private void startMonitoringAccordingToSettings() {
        try {
            MonitorPrefs.putBool(this, MonitorPrefs.S_FIRST_RUN_DONE, true);
            if (!MonitorPrefs.monitoringEnabled(this)) return;
            if (MonitorPrefs.lowPowerMode(this)) {
                BatteryMonitorJobService.schedule(this);
                return;
            }
            Intent svc = new Intent(this, BatteryWatchService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
        } catch (Exception e) {
            Log.w(TAG, "cannot start watch service", e);
        }
    }

    private void notifyWebVisibility(boolean visible) {
        if (webView == null) return;
        try {
            webView.evaluateJavascript(
                    "(function(){try{if(window.__onAppVisibility)window.__onAppVisibility(" + visible + ");}catch(e){}})();",
                    null);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            notifyWebVisibility(false);
            webView.onPause();
            webView.pauseTimers();     // توقف تایمرهای JS در پس‌زمینه ⇒ مصرف باتری کمتر
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
            notifyWebVisibility(true);
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

    @Override
    protected void onDestroy() {
        try {
            if (webView != null) {
                webView.removeJavascriptInterface("Android");
                webView.removeJavascriptInterface("SysInfo");
                webView.loadUrl("about:blank");
                webView.destroy();
                webView = null;
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
