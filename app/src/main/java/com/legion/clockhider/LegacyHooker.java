package com.legion.clockhider;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 传统 de.robv API 适配器 —— 只给旧框架（LSPosed 1.x）用。
 *
 * 注意：本类只被 {@link LegacyEntry} 引用，而 LegacyEntry 只在框架读取 assets/xposed_init 时才会加载；
 * 现代框架（LSPosed 2.x）不提供 de.robv 类，因此那两个类在这类框架上永远不会被加载，不会引发
 * NoClassDefFoundError。编译期依赖 tools/downloads/api-82.jar（不打包进 APK）。
 */
final class LegacyHooker implements Hooker {

    private final ClassLoader mClassLoader;

    LegacyHooker(ClassLoader classLoader) {
        mClassLoader = classLoader;
    }

    @Override
    public Object hook(final Method method, final String id, final Callback callback) {
        try {
            method.setAccessible(true);
            return XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Hooker.HookParam p = wrap(param);
                    try {
                        callback.before(p);
                    } catch (Throwable t) {
                        HookLog.e("LegacyHooker: before 异常 " + id, t);
                    }
                    if (p.returnEarly) {
                        param.setResult(p.result);
                    } else if (p.argsChanged) {
                        param.args = p.args;
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Hooker.HookParam p = wrap(param);
                    p.result = param.getResult();
                    try {
                        callback.after(p);
                    } catch (Throwable t) {
                        HookLog.e("LegacyHooker: after 异常 " + id, t);
                    }
                    if (p.result != param.getResult()) {
                        param.setResult(p.result);
                    }
                }

                private Hooker.HookParam wrap(MethodHookParam param) {
                    return new Hooker.HookParam(param.thisObject, param.args, method);
                }
            });
        } catch (Throwable t) {
            HookLog.e("LegacyHooker: hook 失败 " + method, t);
            return null;
        }
    }

    @Override
    public void unhook(Object handle) {
        try {
            if (handle instanceof XC_MethodHook.Unhook) {
                ((XC_MethodHook.Unhook) handle).unhook();
            }
        } catch (Throwable t) {
            HookLog.i("LegacyHooker: unhook 失败（忽略）" + t);
        }
    }

    @Override
    public Class<?> findClass(String className) {
        try {
            return XposedHelpers.findClassIfExists(className, mClassLoader);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String describe() {
        return "de.robv(传统) api=" + XposedBridge.getXposedVersion();
    }
}
