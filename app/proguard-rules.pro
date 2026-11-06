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

# 保留行号信息，便于 release 崩溃堆栈调试
-keepattributes SourceFile,LineNumberTable

# 隐藏原始源文件名
-renamesourcefileattribute SourceFile

# 在 release 构建中剥离 debug 和 trace 日志调用，避免泄漏内部实现
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# predict4java and dependencies
-keep class com.github.amsacode.predict4java.** { *; }
-keep class org.apache.commons.lang.** { *; }
-keep class com.github.davidmoten.guavamini.** { *; }
-keep class org.apache.commons.logging.** { *; }
-keep class org.apache.commons.logging.impl.** { *; }
-dontwarn org.apache.commons.logging.**

# 自定义 commons-logging 工厂（通过 SPI 注册，类名必须保留）
-keep class com.example.hamkit.logging.** { *; }
-keep class com.example.hamkit.logging.AndroidLogFactory { *; }
-keep class com.example.hamkit.logging.AndroidLog { *; }

# 高德地图 SDK 3D地图（保留 native 接口和反射调用的类）
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.a.a.** { *; }
-keep class com.loc.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
-dontwarn com.a.a.**
-dontwarn com.loc.**

# 保留 BuildConfig 中的加密 key 字段
-keep class com.example.hamkit.BuildConfig { *; }

# ──────────────────────────────────────────────────────────────────────────
# WorkManager / Room：保留反射生成的数据库实现类
#
# 问题：WorkManager 内部使用 Room，Room 编译时生成 WorkDatabase_Impl。
# WorkManagerInitializer 在 App 启动时通过反射 getDeclaredConstructor()
# 查找该类的无参构造器。R8 full mode（AGP 8+ 默认）会因"无直接代码引用"
# 剥离该构造器，导致启动崩溃：
#   java.lang.NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
#   at androidx.work.WorkManagerInitializer.b(...)
#   at androidx.startup.InitializationProvider.onCreate(...)
#
# 修复：保留 WorkDatabase_Impl 的无参构造器与全部成员，避免 R8 剥离。
# 同时保留所有 Room 数据库实现类的构造器（通用保险）。
# ──────────────────────────────────────────────────────────────────────────
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }

# 通用 Room 保险：所有 RoomDatabase 子类的无参构造器
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * extends androidx.room.RoomDatabase { *; }

# WorkManager 内部通过反射实例化的其他类
-keep class androidx.work.impl.** { *; }
-dontwarn androidx.work.impl.**

# ──────────────────────────────────────────────────────────────────────────
# 小手环互联（xms-wearable-lib）
#
# AAR 自带 consumer proguard 规则会保留 com.xiaomi.xms.wearable.**，
# 但 SDK 会回调我们实现的 OnMessageReceivedListener / OnServiceConnectionListener，
# 这两个是我们自己的类、被 SDK 以接口方式调用，R8 不会感知到引用，
# 需要显式保留，否则 release 包上手环消息收不到（debug 分包下正常，极易漏测）。
# ──────────────────────────────────────────────────────────────────────────
-keep class com.example.hamkit.data.wearable.** { *; }
-dontwarn com.xiaomi.xms.wearable.**

