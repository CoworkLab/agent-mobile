# Keep the AccessibilityService entry point reachable by name from the system.
-keep class com.phoneagent.service.** { *; }

# Room generated implementations.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.phoneagent.**$$serializer { *; }
-keepclasseswithmembers class com.phoneagent.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
