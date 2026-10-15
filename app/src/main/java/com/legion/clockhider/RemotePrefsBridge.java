package com.legion.clockhider;

import android.content.SharedPreferences;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 现代框架（LSPosed 2.x）配置通道桥。
 *
 * 模块 App 注册服务监听 → 框架下发 XposedService → App 通过
 * {@code service.getRemotePreferences(group)} 把配置写进框架的远程偏好库；
 * 被 Hook 的 SystemUI 进程用 {@code XposedModule.getRemotePreferences(同一 group)} 读取，
 * 且支持变更监听 —— 这就是官方文档里的 Remote Preferences，配置改完立刻生效、无需重启。
 *
 * 为什么需要它：在 SystemUI 进程里拿 Application Context 依赖隐藏 API
 * （ActivityThread.currentApplication 在新系统上被限制），而远程偏好完全不需要 Context。
 */
public final class RemotePrefsBridge {

    private static volatile XposedService sService;
    private static volatile SharedPreferences sRemote;
    private static volatile SharedPreferences sLocal;

    private RemotePrefsBridge() {
    }

    /** 模块 App 启动时注册服务监听；框架可用时会回调 onServiceBind 并自动镜像一次。 */
    public static void init(SharedPreferences local) {
        sLocal = local;
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService service) {
                    sService = service;
                    try {
                        sRemote = service.getRemotePreferences(ConfigStore.PREFS_NAME);
                        HookLog.i("RemotePrefsBridge: 框架服务已连接 ("
                                + service.getFrameworkName() + " " + service.getFrameworkVersion()
                                + ", api=" + service.getApiVersion() + ")");
                    } catch (Throwable t) {
                        HookLog.e("RemotePrefsBridge: getRemotePreferences 失败", t);
                    }
                    mirror();
                }

                @Override
                public void onServiceDied(XposedService service) {
                    sService = null;
                    sRemote = null;
                    HookLog.i("RemotePrefsBridge: 框架服务断开");
                }
            });
            HookLog.i("RemotePrefsBridge: 服务监听已注册");
        } catch (Throwable t) {
            // 非现代框架（或类缺失）→ 静默，配置走 ContentProvider / 文件通道
            HookLog.i("RemotePrefsBridge: 不可用（" + t + "）");
        }
    }

    /** 把本地配置镜像进框架远程偏好（每次保存配置时调用；SystemUI 侧据此实时生效）。 */
    public static void mirror() {
        SharedPreferences remote = sRemote;
        SharedPreferences local = sLocal;
        if (remote == null || local == null) return;
        try {
            remote.edit()
                    .putBoolean(ConfigStore.KEY_ENABLED,
                            local.getBoolean(ConfigStore.KEY_ENABLED, false))
                    .putInt(ConfigStore.KEY_MODE,
                            local.getInt(ConfigStore.KEY_MODE, ConfigStore.MODE_WHOLE))
                    .putBoolean(ConfigStore.KEY_CLOCK,
                            local.getBoolean(ConfigStore.KEY_CLOCK, true))
                    .putBoolean(ConfigStore.KEY_DATE,
                            local.getBoolean(ConfigStore.KEY_DATE, true))
                    .putBoolean(ConfigStore.KEY_WEEKDAY,
                            local.getBoolean(ConfigStore.KEY_WEEKDAY, true))
                    .putBoolean(ConfigStore.KEY_LUNAR,
                            local.getBoolean(ConfigStore.KEY_LUNAR, false))
                    .putInt(ConfigStore.KEY_VIS,
                            local.getInt(ConfigStore.KEY_VIS, ConfigStore.VIS_GONE))
                    .putBoolean(ConfigStore.KEY_AUTO,
                            local.getBoolean(ConfigStore.KEY_AUTO, true))
                    .putBoolean(ConfigStore.KEY_AGGRESSIVE,
                            local.getBoolean(ConfigStore.KEY_AGGRESSIVE, false))
                    .putBoolean(ConfigStore.KEY_STATUSBAR,
                            local.getBoolean(ConfigStore.KEY_STATUSBAR, false))
                    .putString(ConfigStore.KEY_EXTRA,
                            local.getString(ConfigStore.KEY_EXTRA, ""))
                    .putLong(ConfigStore.KEY_DUMP_TOKEN,
                            local.getLong(ConfigStore.KEY_DUMP_TOKEN, 0L))
                    .apply();
            HookLog.i("RemotePrefsBridge: 已镜像配置 enabled="
                    + local.getBoolean(ConfigStore.KEY_ENABLED, false));
        } catch (Throwable t) {
            HookLog.e("RemotePrefsBridge: 镜像失败", t);
        }
    }

    /** 是否已连上框架（App 界面用来显示激活状态）。 */
    public static boolean isReady() {
        return sRemote != null;
    }

    public static String frameworkInfo() {
        XposedService s = sService;
        if (s == null) return "未连接";
        try {
            return s.getFrameworkName() + " " + s.getFrameworkVersion() + " (api=" + s.getApiVersion() + ")";
        } catch (Throwable t) {
            return "已连接";
        }
    }
}
