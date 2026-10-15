package com.legion.clockhider;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 现代 libxposed API（101/102）适配器 —— 本模块的主力路径（LSPosed 2.x / 7790 走这里）。
 *
 * 关键点：
 *  - {@code setExceptionMode(PROTECTIVE)}：Hook 里抛异常时框架会保护性地恢复原方法，绝不弄崩 SystemUI；
 *  - 回调在目标方法所在线程同步执行，因此可以直接操作 View（View 的方法本来就在主线程调用）。
 */
final class ModernHooker implements Hooker {

    private final XposedModule mModule;
    private final ClassLoader mClassLoader;

    ModernHooker(XposedModule module, ClassLoader classLoader) {
        mModule = module;
        mClassLoader = classLoader;
    }

    @Override
    public Object hook(final Method method, final String id, final Callback callback) {
        try {
            method.setAccessible(true);
            return mModule.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId(id)
                    .intercept(chain -> {
                        Hooker.HookParam param = new Hooker.HookParam(
                                chain.getThisObject(),
                                chain.getArgs().toArray(),
                                chain.getExecutable());
                        callback.before(param);
                        if (param.returnEarly) {
                            return param.result;
                        }
                        Object result = param.argsChanged
                                ? chain.proceed(param.args)
                                : chain.proceed();
                        param.result = result;
                        callback.after(param);
                        return param.result;
                    });
        } catch (Throwable t) {
            HookLog.e("ModernHooker: hook 失败 " + method, t);
            return null;
        }
    }

    @Override
    public void unhook(Object handle) {
        try {
            if (handle instanceof XposedInterface.HookHandle) {
                ((XposedInterface.HookHandle) handle).unhook();
            }
        } catch (Throwable t) {
            HookLog.i("ModernHooker: unhook 失败（忽略）" + t);
        }
    }

    @Override
    public Class<?> findClass(String className) {
        try {
            if (mClassLoader != null) {
                return Class.forName(className, false, mClassLoader);
            }
            return Class.forName(className);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String describe() {
        return "libxposed(现代) " + Compat.describeFramework(mModule);
    }
}
