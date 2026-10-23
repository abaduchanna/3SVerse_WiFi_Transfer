# 3SVerse WiFi Transfer - R8/proguard rules (t197: anti-AI-reverse)
# R8 shrinks + obfuscates app logic; the manifest-driven service and the
# SAF document-tree helper keep their names (system reflection surface).

-keep class com.threesverse.wifitransfer.TransferService { *; }
-keep class com.threesverse.wifitransfer.SafTree { *; }

# WebView JS bridges (if any) - reflective @JavascriptInterface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
