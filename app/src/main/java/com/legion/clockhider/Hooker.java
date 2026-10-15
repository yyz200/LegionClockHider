package com.legion.clockhider;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

/**
 * 框架适配层：把"现代 libxposed API"和"传统 de.robv API"统一成同一套调用方式，
 * 使 {@link ClockHider} 的核心逻辑只写一遍就能在两种框架上运行。
 *
 * 为什么需要两套：
 *  - 新版 LSPosed（2.x，libxposed 时代）只认 META-INF/xposed/java_init.list 里的 XposedModule 子类；
 *  - 旧版 LSPosed（1.x）只认 assets/xposed_init 里的 IXposedHookLoadPackage，并提供 de.robv 类。
 *  两者都不引入额外依赖：传统 API 只在 {@link LegacyEntry} 被实例化时才会加载。
 */
interface Hooker {

    /** Hook 一个方法；失败返回 null（调用方继续挂别的）。 */
    Object hook(Method method, String id, Callback callback);

    /** 卸载一个 Hook。 */
    void unhook(Object handle);

    /** 查类（不存在返回 null，不抛异常）。 */
    Class<?> findClass(String className);

    /** 框架描述，写日志用。 */
    String describe();

    /** 回调参数（两种框架统一后的视图）。 */
    final class HookParam {
        public final Object thisObject;
        public final Object[] args;
        public final Member method;
        public Object result;
        /** before() 里置 true 表示不再执行原方法，直接返回 {@link #result}。 */
        public boolean returnEarly;
        /** 是否修改过 args（现代 API 需要显式把新参数传回链）。 */
        public boolean argsChanged;

        HookParam(Object thisObject, Object[] args, Member method) {
            this.thisObject = thisObject;
            this.args = args;
            this.method = method;
        }

        public void setArg(int index, Object value) {
            if (args != null && index >= 0 && index < args.length) {
                args[index] = value;
                argsChanged = true;
            }
        }
    }

    interface Callback {
        void before(HookParam param) throws Throwable;

        void after(HookParam param) throws Throwable;
    }
}
