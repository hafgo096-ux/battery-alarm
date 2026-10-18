package com.persian.batteryalarm.optimizer;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.content.res.AssetFileDescriptor;
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

import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * اکتیویتی اصلی (نسخه ۱.۰.۳).
 *
 * اصلاحات ۱.۰.۳:
 *  - پراکسی رسانه (appmedia.local): پخش ویدیوهای content:// داخل WebView با
 *    پشتیبانی HTTP Range (206) ⇒ seek واقعی؛ قبلاً <video src="content://">
 *    در موتور مدیای WebView کار نمی‌کرد و «ویدیو پخش نمی‌شد».
 *
 * اصلاحات قدیمی:
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

    /**
     * دامنه مجازی پراکسی رسانه (نسخه ۱.۰.۳).
     * موتور مدیای WebView در اندروید نمی‌تواند مستقیم از URI‌های content://
     * ویدیو پخش کند (به‌همین دلیل «ویدیو پخش نمی‌شد»). به‌جای آن، JS آدرس
     * ویدیو را در قالب https://appmedia.local/m?u=<content-uri> می‌فرستد و
     * shouldInterceptRequest این درخواست را می‌گیرد و باپایپ Range (206)
     * بایت‌های واقعی را از ContentResolver استریم می‌کند ⇒ پخش + seek واقعی.
     */
    private static final String MEDIA_PROXY_HOST = "appmedia.local";

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
                    Uri u = request.getUrl();
                    if (MEDIA_PROXY_HOST.equals(u.getHost())) {
                        return activity.handleMediaProxy(u, request.getRequestHeaders());
                    }
                    WebResourceResponse r = assetLoader.shouldInterceptRequest(u);
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
                    Uri u = Uri.parse(url);
                    if (MEDIA_PROXY_HOST.equals(u.getHost())) {
                        return activity.handleMediaProxy(u, null);
                    }
                    WebResourceResponse r = assetLoader.shouldInterceptRequest(u);
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

    /**
     * سرویس‌دهی محتوای رسانه‌ای (content://) به ویدیو/تصویرهای WebView
     * با پشتیبانی کامل از HTTP Range ⇒ seek در ویدیو کار می‌کند.
     */
    private WebResourceResponse handleMediaProxy(Uri proxyUri, Map<String, String> headers) {
        try {
            String encoded = proxyUri.getQueryParameter("u");
            if (encoded == null || encoded.isEmpty()) return mediaError(404, "missing media uri");
            Uri mediaUri = Uri.parse(encoded);
            ContentResolver cr = getContentResolver();
            if (cr == null) return mediaError(500, "no resolver");

            String mime = cr.getType(mediaUri);
            if (mime == null || mime.isEmpty()) mime = guessMime(mediaUri);
            boolean isMedia = mime.startsWith("video/") || mime.startsWith("audio/");

            // طول کل فایله (برای Content-Length و محاسبه Range)
            long total = -1;
            try (AssetFileDescriptor probe = cr.openAssetFileDescriptor(mediaUri, "r")) {
                if (probe != null) total = probe.getLength();
            } catch (Exception ignored) {}

            long rangeStart = 0;
            long rangeEnd = -1;
            boolean partial = false;
            if (headers != null) {
                String range = headers.get("Range");
                if (range == null) range = headers.get("range");
                if (range != null && range.startsWith("bytes=")) {
                    try {
                        String spec = range.substring(6).split(",")[0].trim();
                        String[] parts = spec.split("-");
                        if (parts[0].isEmpty()) {
                            // فرم suffix: bytes=-500 (آخرین ۵۰۰ بایت)
                            long suffix = Long.parseLong(parts[1]);
                            if (total > 0) {
                                rangeStart = Math.max(0, total - suffix);
                                rangeEnd = total - 1;
                            } else {
                                rangeStart = 0;
                            }
                        } else {
                            rangeStart = Long.parseLong(parts[0]);
                            if (parts.length > 1 && !parts[1].isEmpty()) rangeEnd = Long.parseLong(parts[1]);
                        }
                        if (total > 0) {
                            if (rangeEnd > total - 1) rangeEnd = total - 1;
                            if (rangeStart >= total) return mediaError(416, "range not satisfiable");
                        }
                        partial = true;
                    } catch (Exception ignored) {
                        partial = false;
                    }
                }
            }
            // بدون دانستن طول کل، پاسخ 206 با Content-Range درست ساخته نمی‌شود → کامل بفرست
            if (total <= 0) partial = false;

            final long fStart = rangeStart;
            final long fLimit = (partial && rangeEnd > 0)
                    ? (rangeEnd - rangeStart + 1)
                    : (partial && total > 0 ? (total - rangeStart) : -1);

            InputStream stream = null;
            try (AssetFileDescriptor afd = cr.openAssetFileDescriptor(mediaUri, "r")) {
                if (afd != null) {
                    // کپی مستقل فایل‌دیسکریپتور تا پس از بسته‌شدن afd استریم زنده بماند
                    // (ParcelFileDescriptor با finalizer خودش آزاد می‌شود)
                    FileInputStream fis = new FileInputStream(
                            android.os.ParcelFileDescriptor.dup(afd.getFileDescriptor()).getFileDescriptor());
                    stream = new SkippedLimitedInputStream(fis, fStart, fLimit);
                }
            } catch (Exception e) {
                Log.w(TAG, "openAssetFileDescriptor failed", e);
            }
            if (stream == null) {
                try { stream = cr.openInputStream(mediaUri); } catch (Exception ignored) {}
                if (stream == null) return mediaError(404, "cannot open media");
                if (fStart > 0) stream = new SkippedLimitedInputStream(stream, fStart, fLimit);
            }

            WebResourceResponse r = new WebResourceResponse(mime, isMedia ? "" : "utf-8", stream);
            Map<String, String> respHeaders = new java.util.HashMap<>();
            if (partial) {
                r.setStatusCodeAndReasonPhrase(206, "Partial Content");
                long end = (rangeEnd > 0) ? rangeEnd : (total > 0 ? total - 1 : -1);
                respHeaders.put("Content-Range", "bytes " + fStart + "-" + end + (total > 0 ? "/" + total : "/-"));
            }
            if (total > 0) respHeaders.put("Content-Length", String.valueOf(total));
            respHeaders.put("Accept-Ranges", "bytes");
            r.setResponseHeaders(respHeaders);
            return r;
        } catch (Exception e) {
            Log.w(TAG, "media proxy failed", e);
            return mediaError(500, "proxy error");
        }
    }

    private static String guessMime(Uri uri) {
        String path = uri.getLastPathSegment() != null ? uri.getLastPathSegment().toLowerCase(java.util.Locale.US) : "";
        if (path.endsWith(".mp4")) return "video/mp4";
        if (path.endsWith(".3gp")) return "video/3gpp";
        if (path.endsWith(".mkv")) return "video/x-matroska";
        if (path.endsWith(".webm")) return "video/webm";
        if (path.endsWith(".mov")) return "video/quicktime";
        if (path.endsWith(".mp3")) return "audio/mpeg";
        if (path.endsWith(".m4a")) return "audio/mp4";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }

    private static WebResourceResponse mediaError(int code, String message) {
        try {
            WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8",
                    new java.io.ByteArrayInputStream(message.getBytes()));
            r.setStatusCodeAndReasonPhrase(code, message);
            return r;
        } catch (Exception e) {
            WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", null);
            r.setStatusCodeAndReasonPhrase(404, "error");
            return r;
        }
    }

    /**
     * استریم با دو محدودیت: پرش (skip) به offset درخواستی در Range و
     * توقف در طول حداکثری (برای پاسخ 206).
     */
    private static class SkippedLimitedInputStream extends FilterInputStream {
        private long toSkip;
        private long remaining;
        private boolean skipping = true;

        SkippedLimitedInputStream(InputStream in, long skip, long limit) {
            super(in);
            this.toSkip = Math.max(0, skip);
            this.remaining = limit > 0 ? limit : Long.MAX_VALUE;
        }

        private long doSkip(long n) throws IOException {
            long skipped = 0;
            byte[] buf = new byte[65536];
            while (skipped < n) {
                int want = (int) Math.min(buf.length, n - skipped);
                int r = in.read(buf, 0, want);
                if (r < 0) break;
                skipped += r;
            }
            return skipped;
        }

        @Override
        public int read() throws IOException {
            if (skipping) {
                // اگر قبل از پایان skip به EOF برسیم، خوانده‌های بعدی هم -1 می‌دهند
                doSkip(toSkip);
                skipping = false;
            }
            if (remaining <= 0) return -1;
            int r = in.read();
            if (r >= 0) remaining--;
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (skipping) {
                doSkip(toSkip);
                skipping = false;
            }
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(len, remaining);
            if (toRead <= 0) return -1;
            int r = in.read(b, off, toRead);
            if (r > 0) remaining -= r;
            return r;
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
