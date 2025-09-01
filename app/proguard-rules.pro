# Keep readable stack traces
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# (From your case) ignore optional TLS providers pulled by OkHttp
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# If you use Markwon GIF/SVG decoders:
-keep class pl.droidsonroids.gif.** { *; }
-keep class com.caverock.androidsvg.** { *; }
