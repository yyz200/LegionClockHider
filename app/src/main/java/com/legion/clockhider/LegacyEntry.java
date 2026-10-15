package com.legion.clockhider;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 传统入口（de.robv API）—— 只在旧框架（LSPosed 1.x 等仍读 assets/xposed_init 的框架）上生效。
 *
 * 现代框架（LSPosed 2.x / 7790）不会读取 assets/xposed_init，也不会提供 de.robv 类，
 * 因此本类在那类框架上永远不会被加载，不会产生 NoClassDefFoundError。
 * 编译期依赖 tools/downloads/api-82.jar（不打包进 APK）。
 */
public class LegacyEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            String pkg = lpparam.packageName;
            if (!Compat.isTargetPackage(pkg)) return;

            HookLog.i("LegacyEntry: 进入目标进程 " + pkg);
            ConfigStore.ensureInitialized();

            if (!Compat.tryClaim("legacy:" + pkg)) {
                HookLog.i("LegacyEntry: 本进程已安装过 Hook，跳过");
                return;
            }
            new ClockHider(new LegacyHooker(lpparam.classLoader), pkg, lpparam.classLoader).start();
        } catch (Throwable t) {
            HookLog.e("LegacyEntry: 异常", t);
        }
    }
}
