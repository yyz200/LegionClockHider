# Xposed 入口类与配置通道必须保留（若未来开启 minify）
-keep class com.legion.clockhider.MainHook { *; }
-keep class com.legion.clockhider.ResourceHook { *; }
-keep class com.legion.clockhider.PrefsProvider { *; }
-keep class com.legion.clockhider.ConfigStore { *; }

# LSPosed 入口在 assets 里按全类名反射加载，禁止重命名
-keepnames class com.legion.clockhider.** { *; }
