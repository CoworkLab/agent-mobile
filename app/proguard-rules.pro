# Keep Gson model classes
-keep class com.mobileagent.app.data.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep JavascriptInterfaces for WebViews (Cyberpunk Orb & Settings Activity)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Vosk and JNA native libraries & models
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class net.java.dev.jna.** { *; }
# JNA ships optional desktop AWT bridges that are not present on Android; app code does not use them.
-dontwarn java.awt.**

# Keep Room database models
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase


# ── Mobile Agent Security Module — keep rules ───────────────────────────────────
-keep class com.mobileagent.app.security.SecurityManager { *; }
-keep class com.mobileagent.app.security.DeveloperInfo { *; }
-keep class com.mobileagent.app.security.AppIdentityVerifier { *; }

# Obfuscate everything else aggressively
-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'tgptx'

# Remove Log calls in release builds (security: don't leak internals)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
