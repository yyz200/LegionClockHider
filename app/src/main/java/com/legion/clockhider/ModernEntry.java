package com.legion.clockhider;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 现代入口（libxposed API 101/102），注册于 META-INF/xposed/java_init.list。
 *
 * 为什么以现代 API 为主：新版 LSPosed（2.x，版本号 7790 属于这一代）只实例化
 * java_init.list 里的 XposedModule 子类，且不再提供 de.robv.* 传统类。
 * 旧框架（LSPosed 1.x）由 {@link LegacyEntry}（assets/xposed_init）负责，
 * 两者用 {@link Compat#tryClaim} 保证同一进程只安装一次 Hook。
 *
 * 作用域：本模块只关心 SystemUI 进程 —— ZUXOS（联想拯救者 Y700 五代）的锁屏时钟、
 * 日期、星期全部由 com.android.systemui 渲染。
 */
public class ModernEntry extends XposedModule {

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        try {
            HookLog.attachModern(this);
            HookLog.i("ModernEntry: 模块已加载 [" + Compat.describeFramework(this)
                    + ", process=" + param.getProcessName()
                    + ", systemServer=" + param.isSystemServer() + "]");
            try {
                ConfigStore.setRemotePreferences(getRemotePreferences(ConfigStore.PREFS_NAME));
            } catch (Throwable t) {
                HookLog.i("ModernEntry: 远程偏好不可用（" + t + "）");
            }
        } catch (Throwable t) {
            HookLog.e("ModernEntry: onModuleLoaded 异常", t);
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        try {
            String pkg = param.getPackageName();
            if (!Compat.isTargetPackage(pkg)) return;

            HookLog.i("ModernEntry: 进入目标进程 " + pkg);
            ConfigStore.ensureInitialized();

            if (!Compat.tryClaim("modern:" + pkg)) {
                HookLog.i("ModernEntry: 本进程已安装过 Hook，跳过");
                return;
            }
            new ClockHider(new ModernHooker(this, param.getDefaultClassLoader()),
                    pkg, param.getDefaultClassLoader()).start();
        } catch (Throwable t) {
            // 兜底：绝不让异常冒泡进 SystemUI
            HookLog.e("ModernEntry: onPackageLoaded 异常", t);
        }
    }
}
