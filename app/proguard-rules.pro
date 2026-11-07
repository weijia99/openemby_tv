# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# ---- OpenEmby 保留规则 ----
# 数据模型全部靠 Gson 反射解析 JSON，字段名不能混淆，否则服务器返回的数据读不出来
-keep class com.xxxx.emby_tv.data.model.** { *; }
-keepclassmembers class com.xxxx.emby_tv.data.model.** { <fields>; }

# Gson 自身的反射与泛型
-keep class com.google.gson.** { *; }
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# OkHttp / Retrofit 系（反射构造）
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Media3 / ExoPlayer 有 JNI 与反射
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# 协议与 JNI 层
-keep class com.xxxx.emby_tv.LocalServer { *; }
-keepclasseswithmembernames class * { native <methods>; }

# 应用入口
-keep class com.xxxx.emby_tv.App { *; }
-keep class com.xxxx.emby_tv.MainActivity { *; }

# Kotlin 协程
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# NanoHTTPD 反射
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# 保留行号，崩溃栈可读
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Gson TypeToken 关键修复 ----
# R8 会擦除匿名 TypeToken 子类的泛型签名，导致:
# IllegalStateException: TypeToken must be created with a type argument
# 必须保留所有 TypeToken 子类及其泛型签名
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers class * extends com.google.gson.reflect.TypeToken { *; }
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations
-keepattributes AnnotationDefault

# 所有含泛型的 API 返回类型（保持签名完整）
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# 网络与序列化相关的全部保留
-keep class com.xxxx.emby_tv.data.remote.** { *; }
-keep class com.xxxx.emby_tv.data.repository.** { *; }
