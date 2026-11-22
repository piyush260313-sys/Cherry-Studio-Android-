# Keep NewPipeExtractor & Rhino JS engine for YouTube decryption
-keep class org.schabi.newpipe.** { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.schabi.newpipe.**
-dontwarn okhttp3.**
-dontwarn okio.**
