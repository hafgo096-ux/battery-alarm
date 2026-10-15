# Keep WebView JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.persian.batteryalarm.optimizer.** { *; }
-dontwarn com.persian.batteryalarm.optimizer.**
