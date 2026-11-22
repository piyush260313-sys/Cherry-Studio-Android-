# Keep youtubedl-android & FFmpeg models/JNI classes
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }

# Keep Jackson annotations & deserialization
-keepattributes *Annotation*,EnclosingMethod,Signature
-keepnames class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
