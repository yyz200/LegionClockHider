package com.legion.clockhider;

import android.app.Application;
import android.content.SharedPreferences;

/**
 * 模块 App 进程入口：一启动就
 *  1) 打一条可辨识日志（确认新版代码确实在跑）；
 *  2) 注册 libxposed 服务监听（现代框架配置通道，App 写的配置由此下发到 SystemUI）；
 *  3) 把当前配置写入兜底文件通道（root），供 SystemUI 直读。
 *
 * 放在 Application 而不是 Activity，是为了让配置不依赖用户是否打开设置界面。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            HookLog.i("App: 进程启动, version=" + versionName());
        } catch (Throwable ignored) {
        }
        try {
            SharedPreferences prefs = getSharedPreferences(ConfigStore.PREFS_NAME, MODE_PRIVATE);
            seedDefaults(prefs);
            RemotePrefsBridge.init(prefs);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ConfigWriter.writeFromPrefs(App.this,
                            getSharedPreferences(ConfigStore.PREFS_NAME, MODE_PRIVATE));
                }
            }, "lch-config-seed").start();
        } catch (Throwable t) {
            HookLog.e("App: 初始化失败", t);
        }
    }

    /** 首次安装时把推荐默认值写进本地偏好（总开关保持关闭，由用户自己打开）。 */
    private void seedDefaults(SharedPreferences prefs) {
        try {
            int v = prefs.getInt(ConfigStore.KEY_PREFS_VERSION, 0);
            if (v >= 1) return;
            prefs.edit()
                    .putBoolean(ConfigStore.KEY_ENABLED, false)
                    .putInt(ConfigStore.KEY_MODE, ConfigStore.MODE_ITEM)
                    .putBoolean(ConfigStore.KEY_CLOCK, true)
                    .putBoolean(ConfigStore.KEY_DATE, true)
                    .putBoolean(ConfigStore.KEY_WEEKDAY, true)
                    .putBoolean(ConfigStore.KEY_LUNAR, false)
                    .putInt(ConfigStore.KEY_VIS, ConfigStore.VIS_GONE)
                    .putBoolean(ConfigStore.KEY_AUTO, true)
                    .putBoolean(ConfigStore.KEY_AGGRESSIVE, false)
                    .putBoolean(ConfigStore.KEY_STATUSBAR, false)
                    .putInt(ConfigStore.KEY_PREFS_VERSION, 1)
                    .apply();
            HookLog.i("App: 已写入默认配置（总开关关闭）");
        } catch (Throwable t) {
            HookLog.e("App: 默认配置写入失败", t);
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
