# 保留可读类名与行号：开源应用无需混淆符号，既完整启用 R8 无用代码裁剪（BouncyCastle / ZXing / Material3）
# 与字节码内联优化，又保证车机导出的诊断日志和异常类名清晰可读。
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# 经车机本机网络 ADB（app_process）从命令行直接按类名反射启动的两个入口类，R8 静态可达性分析不到，必须保留。
-keep class io.github.gcbond.openhc.wifi.HotspotSwitchMain {
    public static void main(java.lang.String[]);
}
-keep class io.github.gcbond.openhc.wifi.ScanSwitchMain {
    public static void main(java.lang.String[]);
}

# BouncyCastle 仅用到轻量级 RFC7748/RFC8032 纯数学类，忽略其可选 JCA/LDAP/OSGi 引用告警。
-dontwarn org.bouncycastle.**
