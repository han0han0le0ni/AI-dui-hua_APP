# ---------- Room ----------
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public <init>();
}
# Room 生成的 DAO 实现类通过反射调用
-keep @androidx.room.Entity class * { *; }
-dontwarn kotlinx.coroutines.**

# ---------- kotlinx.serialization ----------
-keepclassmembers class com.personal.roleplay.** {
    *** Companion;
}
-keepclasseswithmembers class com.personal.roleplay.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.personal.roleplay.**$* {
    *** Companion;
}
-dontwarn kotlinx.serialization.**

# ---------- Tink（androidx.security:security-crypto 依赖）----------
# Tink 的字节码引用了 errorprone 的注解，这些注解只在编译期存在，
# 运行时不缺任何类。R8 会把它们报成 missing class，直接放行。
-dontwarn com.google.errorprone.annotations.**

# ---------- OkHttp / Okio ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepattributes Signature
-keepattributes *Annotation*

# ---------- 调试：保留行号便于排查线上崩溃 ----------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
