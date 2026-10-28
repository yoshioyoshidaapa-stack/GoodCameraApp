# Camera app proguard rules
-keepattributes *Annotation*
-keep class com.goodcamera.app.** { *; }

# Claude API SDK (JSONの変換に名前を使うため、縮小しない)
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.anthropic.**
-dontwarn com.fasterxml.jackson.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
