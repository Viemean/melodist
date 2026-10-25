# Melodist TV ProGuard / R8 优化规则

# 包名重排与符号池压缩
-repackageclasses ''

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# Core Model（仅保留参与序列化映射的字段，允许其他模型与工具类被彻底内联优化）
-keep @kotlinx.serialization.Serializable class org.melodist.model.** {
    <fields>;
}

# Media3 & ExoPlayer（保留跨进程 Session 与 AIDL，移除对 Extractor/Decoder 的全量通配）
-keep class androidx.media3.session.MediaSession* { *; }
-keep interface androidx.media3.session.MediaSession* { *; }
-keep class androidx.media3.session.IMedia* { *; }
-keep interface androidx.media3.session.IMedia* { *; }
-dontwarn androidx.media3.**

# OkHttp & Coil
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn coil3.**

# Release 优化与日志剥离
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

-assumenosideeffects class org.melodist.api.ApiLogger {
    public static void d(...);
    public static void i(...);
}

-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

