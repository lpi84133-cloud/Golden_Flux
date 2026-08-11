# ───────────────────────────────────────────────────────────────────────────
#  Golden Flux — R8 rules.
#
#  Aggressive shrinking is deliberate. Two things must survive R8:
#
#   1. Everything AppsFlyer, Firebase or the WebView reaches by reflection or
#      by JNI. R8 has no idea any of these frameworks exist and will happily
#      strip them.
#   2. The one @JavascriptInterface bridge the shell installs into the
#      WebView. Renamed or stripped, its method disappears and the keyboard
#      never repositions the page.
#
#  Everything else — package structure, method names, class names — R8 is
#  free to rename. That churn is a net positive for our fingerprint story:
#  two releases from the same source tree do not have byte-identical
#  Kotlin/kotlinx metadata.
# ───────────────────────────────────────────────────────────────────────────

# Log stripping (release only).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-assumenosideeffects class com.goldenflux.goldenfluxgame.flux.util.Tracer {
    public static void i(...);
}

# WebView JS bridge — the method name is public API to the page.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# AppsFlyer SDK (reflection-heavy; unofficial but stable set of keeps).
-keep class com.appsflyer.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.android.installreferrer.** { *; }
-dontwarn com.appsflyer.**
-dontwarn com.android.installreferrer.**

# Firebase.
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# OkHttp / Okio.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Kotlin annotations that R8 warns about but never needs at runtime.
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.reflect.jvm.internal.**

# Keep the router — reachable only from the manifest.
-keep class com.goldenflux.goldenfluxgame.flux.entry.LaunchArbiter { *; }
-keep class com.goldenflux.goldenfluxgame.flux.signal.FluxPushService { *; }
-keep class com.goldenflux.goldenfluxgame.GoldenFluxApp { *; }
