package com.legion.clockhider;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 锁屏时钟隐藏引擎（框架无关：现代/传统两套 API 都通过 {@link Hooker} 适配进来）。
 *
 * 目标：ZUXOS（联想拯救者 Y700 五代）锁屏界面的「大时钟 + 日期 + 星期」。
 *
 * 三层识别，层层兜底（换固件/换版本也不至于完全失效）：
 *  1. 已知类名直挂 —— ZUI 锁屏时钟由
 *     {@code com.android.systemui.shared.clocks.AnimatableClockViewWithDate} 渲染
 *     （该结论来自开源 ZUXOS 模块 ZTool 对 ZUXOS 1.5 的逆向注释），另附 AOSP 原生
 *     KeyguardClockSwitch / KeyguardStatusView 等候选；
 *  2. 本机 dex 自动挖掘 —— 在 SystemUI 进程内扫 SystemUI.apk 的 classes*.dex，
 *     找出本机真实存在的"锁屏/时钟"类名并自动补挂（应对改名的固件）；
 *  3. 激进模式（默认关）—— 全局扫 View.onAttachedToWindow，凡是类名含 clock/withdate
 *     的视图都按规则处理，作为最后的保险。
 *
 * 命中后的隐藏动作由 {@link ViewRules} 完成：默认"整体隐藏时钟容器"，
 * 也可切换成"时钟/日期/星期分别隐藏"（同一行文本里能只删星期保留日期）。
 *
 * 所有 Hook 都用 PROTECTIVE 异常模式，且默认关闭总开关；关闭时卸载 Hook 并还原视图。
 */
final class ClockHider implements ConfigStore.Listener {

    /** 已知的锁屏时钟候选类（第一个是 ZUI/ZUXOS 实测类名与主目标）。 */
    private static final String[] PRIMARY_CLASSES = {
            "com.android.systemui.shared.clocks.AnimatableClockViewWithDate", // ZUI 实测
            "com.android.systemui.shared.clocks.AnimatableClockView",
            "com.android.systemui.clocks.AnimatableClockView",
            "com.android.systemui.keyguard.ui.view.AnimatableClockView",
            "com.android.keyguard.KeyguardClockSwitch",
            "com.android.systemui.statusbar.phone.KeyguardClockSwitch",
            "com.android.systemui.statusbar.phone.KeyguardStatusView",
    };

    /** 状态栏时钟（可选顺带隐藏；ZUI 保留 AOSP 类名）。 */
    private static final String[] STATUS_BAR_CLOCK_CLASSES = {
            "com.android.systemui.statusbar.policy.Clock",
    };

    /** 同一次事件风暴里的重复处理节流（ms）。 */
    private static final long EVENT_THROTTLE_MS = 40L;
    /** 周期复检间隔（ms）：防止系统把可见性/文本改回去。 */
    private static final long PERIODIC_MS = 500L;
    /** 方法名包含这些片段的，认为是"会刷新时钟/日期"的方法。 */
    private static final String[] UPDATE_HINTS = {
            "update", "refresh", "settext", "settime", "setdate", "setformat", "apply",
            "ontime", "ondate", "onconfig", "format", "settint", "setcolor", "init",
    };
    /** 明确跳过的高频方法（每帧都会调用）。 */
    private static final String[] HEAVY_HINTS = {
            "ondraw", "draw", "onmeasure", "measure", "onlayout", "layout",
            "ontouchevent", "dispatch", "oncreate", "onresume", "onpause",
    };

    private final Hooker mHooker;
    private final String mPackage;
    private final ClassLoader mClassLoader;

    private final Set<String> mHandled = ConcurrentHashMap.newKeySet();
    private final Set<String> mDetected = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> mWholesale = new ConcurrentHashMap<>();
    private final Set<String> mTargetClassNames = ConcurrentHashMap.newKeySet();
    private final List<Class<?>> mTargetClasses = new CopyOnWriteArrayList<>();
    private final List<Object> mHandles = new CopyOnWriteArrayList<>();

    private final Set<View> mTracked = ViewRules.newViewSet();
    private final Map<View, String> mTrackedClass =
            Collections.synchronizedMap(new WeakHashMap<View, String>());
    private final Set<View> mStatusBarViews = ViewRules.newViewSet();
    private final Set<Object> mStatusBarHandles = ConcurrentHashMap.newKeySet();

    private volatile boolean mInstalled;
    private volatile boolean mFrameworkHooked;
    private volatile boolean mStatusBarHooked;
    private volatile long mLastEvent;
    private volatile long mLastDumpToken = -1L;
    private volatile long mDumpPendingLoggedToken = -1L;
    private Handler mHandler;
    private Runnable mPeriodic;

    ClockHider(Hooker hooker, String packageName, ClassLoader classLoader) {
        mHooker = hooker;
        mPackage = packageName;
        mClassLoader = classLoader;
    }

    // ==================== 生命周期 ====================

    void start() {
        try {
            mHandler = new Handler(Looper.getMainLooper());
        } catch (Throwable t) {
            mHandler = null;
            HookLog.e("ClockHider: 主线程 Handler 不可用", t);
        }
        mLastDumpToken = ConfigStore.getDumpToken();   // 旧令牌不触发抓取
        ConfigStore.addListener(this);
        HookLog.i("ClockHider: 引擎启动 pkg=" + mPackage + ", 框架=" + mHooker.describe());
        onConfigChanged();
    }

    @Override
    public void onConfigChanged() {
        try {
            ConfigStore.Snapshot cfg = ConfigStore.snapshot();
            if (!cfg.enabled) {
                if (mInstalled) {
                    restoreAll();
                    uninstall();
                    HookLog.i("ClockHider: 总开关已关 → Hook 卸载、视图已还原");
                }
                return;
            }
            if (!mInstalled) {
                install(cfg);
                mInstalled = true;
            } else {
                reapply(cfg);
                ensureStatusBar(cfg);
            }
        } catch (Throwable t) {
            HookLog.e("ClockHider: onConfigChanged 异常", t);
        }
    }

    private void install(ConfigStore.Snapshot cfg) {
        HookLog.i("ClockHider: 安装 Hook（" + ConfigStore.describe() + "）");
        for (String name : PRIMARY_CLASSES) {
            hookClass(name);
        }
        for (String name : splitExtra(cfg.extraClasses)) {
            HookLog.i("ClockHider: 使用手动补充类名 " + name);
            hookClass(name);
        }
        if (cfg.autoDiscover) {
            TargetScanner.scan(mClassLoader, new TargetScanner.Consumer() {
                @Override
                public void accept(List<String> names) {
                    for (String n : names) {
                        hookClass(n);
                    }
                    HookLog.i("ClockHider: 自动发现结束，候选类累计 " + mHandled.size() + " 个");
                }
            });
        } else {
            HookLog.i("ClockHider: 自动发现已关闭（只用已知类名）");
        }
        if (cfg.aggressive) {
            hookAggressive();
        }
        ensureStatusBar(cfg);
        startPeriodic();
    }

    private void uninstall() {
        mInstalled = false;
        if (mPeriodic != null && mHandler != null) {
            mHandler.removeCallbacks(mPeriodic);
        }
        mPeriodic = null;
        for (Object h : new ArrayList<>(mHandles)) {
            mHooker.unhook(h);
        }
        mHandles.clear();
        for (Object h : new ArrayList<>(mStatusBarHandles)) {
            mHooker.unhook(h);
        }
        mStatusBarHandles.clear();
        mHandled.clear();
        mWholesale.clear();
        mTargetClassNames.clear();
        mTargetClasses.clear();
        mFrameworkHooked = false;
        mStatusBarHooked = false;
    }

    private void restoreAll() {
        for (View v : new ArrayList<>(mTracked)) {
            ViewRules.restore(v);
        }
        mTracked.clear();
        mTrackedClass.clear();
        mDetected.clear();
        for (View v : new ArrayList<>(mStatusBarViews)) {
            ViewRules.restore(v);
        }
        mStatusBarViews.clear();
    }

    private void reapply(ConfigStore.Snapshot cfg) {
        for (View v : new ArrayList<>(mTracked)) {
            String cls = mTrackedClass.get(v);
            boolean wholesale = cls != null && Boolean.TRUE.equals(mWholesale.get(cls));
            ViewRules.apply(v, cfg, wholesale);
        }
        HookLog.i("ClockHider: 配置变化已重新应用，跟踪视图 " + mTracked.size() + " 个");
        maybeDump();
    }

    // ==================== 挂 Hook ====================

    private void hookClass(String className) {
        if (className == null) return;
        className = className.trim();
        if (className.isEmpty()) return;
        if (!mHandled.add(className)) return;

        Class<?> clazz = mHooker.findClass(className);
        if (clazz == null) {
            HookLog.i("ClockHider: 类不存在（跳过）" + className);
            return;
        }
        if (!View.class.isAssignableFrom(clazz)) {
            HookLog.i("ClockHider: 不是 View（跳过）" + className);
            return;
        }
        boolean wholesale = ViewRules.wholesaleSafe(className);
        mWholesale.put(className, wholesale);
        mTargetClasses.add(clazz);
        mTargetClassNames.add(className);

        int hooked = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            try {
                if (m.isSynthetic() || Modifier.isAbstract(m.getModifiers())
                        || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                String mn = m.getName().toLowerCase(Locale.ROOT);
                if (heavy(mn)) continue;
                boolean lifecycle = mn.equals("onattachedtowindow")
                        || mn.equals("onfinishinflate")
                        || mn.equals("onconfigurationchanged")
                        || mn.equals("onwindowvisibilitychanged");
                if (!lifecycle && !hinted(mn)) continue;
                Object h = mHooker.hook(m, "lch-" + clazz.getSimpleName() + "-" + m.getName(),
                        viewEventCallback(className));
                if (h != null) {
                    mHandles.add(h);
                    hooked++;
                }
            } catch (Throwable t) {
                HookLog.e("ClockHider: 挂方法失败 " + className + "#" + m.getName(), t);
            }
        }
        hookFrameworkLifecycle();
        HookLog.i("ClockHider: HOOK OK " + className
                + "（自身方法 " + hooked + " 个，允许整体隐藏=" + wholesale + "）");
    }

    /**
     * 框架 View 生命周期兜底：只要目标类实例发生"附加到窗口/可见性变化"，
     * 就立刻执行隐藏策略 —— 这样锁屏一出现就生效，不必等下一次文本刷新。
     * 全局只挂一次，内部按目标类过滤，开销可忽略。
     */
    private void hookFrameworkLifecycle() {
        if (mFrameworkHooked) return;
        mFrameworkHooked = true;
        Class<?> view = mHooker.findClass("android.view.View");
        if (view == null) {
            HookLog.i("ClockHider: 找不到 android.view.View（框架生命周期兜底不可用）");
            return;
        }
        addFrameworkHook(view, "onAttachedToWindow", (Class<?>[]) null);
        addFrameworkHook(view, "onVisibilityChanged", new Class<?>[]{View.class, int.class});
        addFrameworkHook(view, "onWindowVisibilityChanged", new Class<?>[]{int.class});
    }

    private void addFrameworkHook(Class<?> view, String name, Class<?>[] params) {
        try {
            Method m = (params == null)
                    ? view.getDeclaredMethod(name)
                    : view.getDeclaredMethod(name, params);
            Object h = mHooker.hook(m, "lch-view-" + name, new Hooker.Callback() {
                @Override
                public void before(Hooker.HookParam param) {
                }

                @Override
                public void after(Hooker.HookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    View v = (View) param.thisObject;
                    if (!isTargetInstance(v)) return;
                    onEvent(v, v.getClass().getName());
                }
            });
            if (h != null) mHandles.add(h);
            HookLog.i("ClockHider: 框架生命周期 Hook 已挂 View#" + name);
        } catch (Throwable t) {
            HookLog.i("ClockHider: 框架生命周期 Hook 失败 View#" + name + " " + t);
        }
    }

    private Hooker.Callback viewEventCallback(final String className) {
        return new Hooker.Callback() {
            @Override
            public void before(Hooker.HookParam param) {
            }

            @Override
            public void after(Hooker.HookParam param) {
                if (param.thisObject instanceof View) {
                    onEvent((View) param.thisObject, className);
                }
            }
        };
    }

    /** 激进模式：全局 View 附加事件里找"类名含 clock 的锁屏视图"。 */
    private void hookAggressive() {
        Class<?> view = mHooker.findClass("android.view.View");
        if (view == null) {
            HookLog.i("ClockHider: 激进模式不可用（找不到 android.view.View）");
            return;
        }
        try {
            Method attach = view.getDeclaredMethod("onAttachedToWindow");
            Object h = mHooker.hook(attach, "lch-aggressive-attach", new Hooker.Callback() {
                @Override
                public void before(Hooker.HookParam param) {
                }

                @Override
                public void after(Hooker.HookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    View v = (View) param.thisObject;
                    if (!aggressiveInteresting(v)) return;
                    ConfigStore.Snapshot cfg = ConfigStore.snapshot();
                    if (!cfg.enabled) return;
                    String className = v.getClass().getName();
                    if (mDetected.add("aggressive:" + className)) {
                        HookLog.i("ClockHider: [激进模式] 命中疑似锁屏时钟视图 → "
                                + ViewRules.describe(v));
                        HookLog.i("ClockHider: [激进模式] 祖先链 "
                                + ViewRules.dump(v, 0, 14).replace('\n', '|'));
                    }
                    mTracked.add(v);
                    mTrackedClass.put(v, className);
                    ViewRules.apply(v, cfg, ViewRules.wholesaleSafe(className));
                }
            });
            if (h != null) {
                mHandles.add(h);
                HookLog.i("ClockHider: 激进模式已启用（全局 View.onAttachedToWindow）");
            }
        } catch (Throwable t) {
            HookLog.e("ClockHider: 激进模式挂载失败", t);
        }
    }

    private void ensureStatusBar(ConfigStore.Snapshot cfg) {
        if (!cfg.statusBarClock) {
            if (mStatusBarHooked) {
                for (View v : new ArrayList<>(mStatusBarViews)) {
                    ViewRules.restore(v);
                }
                mStatusBarViews.clear();
                for (Object h : new ArrayList<>(mStatusBarHandles)) {
                    mHooker.unhook(h);
                }
                mStatusBarHandles.clear();
                mStatusBarHooked = false;
                HookLog.i("ClockHider: 状态栏时钟 Hook 已卸载并还原");
            }
            return;
        }
        if (mStatusBarHooked) return;
        for (String name : STATUS_BAR_CLOCK_CLASSES) {
            Class<?> clazz = mHooker.findClass(name);
            if (clazz == null) {
                HookLog.i("ClockHider: 状态栏时钟类不存在（跳过）" + name);
                continue;
            }
            Hooker.Callback cb = new Hooker.Callback() {
                @Override
                public void before(Hooker.HookParam param) {
                }

                @Override
                public void after(Hooker.HookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    View v = (View) param.thisObject;
                    mStatusBarViews.add(v);
                    int vis = (ConfigStore.snapshot().visibility == ConfigStore.VIS_INVISIBLE)
                            ? View.INVISIBLE : View.GONE;
                    try {
                        if (v.getVisibility() != vis) v.setVisibility(vis);
                    } catch (Throwable ignored) {
                    }
                }
            };
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.isSynthetic() || Modifier.isStatic(m.getModifiers())) continue;
                String mn = m.getName().toLowerCase(Locale.ROOT);
                if (heavy(mn)) continue;
                if (!mn.equals("onattachedtowindow") && !hinted(mn)) continue;
                Object h = mHooker.hook(m, "lch-statusbar-" + m.getName(), cb);
                if (h != null) mStatusBarHandles.add(h);
            }
            mStatusBarHooked = true;
            HookLog.i("ClockHider: 状态栏时钟隐藏已启用 " + name);
        }
    }

    // ==================== 事件 ====================

    private void onEvent(View v, String className) {
        if (v == null) return;
        ConfigStore.Snapshot cfg = ConfigStore.snapshot();
        if (!cfg.enabled) return;
        mTracked.add(v);
        mTrackedClass.put(v, className);

        if (mDetected.add(className)) {
            HookLog.i("ClockHider: 首次捕获锁屏时钟视图 " + className + " → " + ViewRules.describe(v));
        }

        long now = SystemClock.uptimeMillis();
        if (now - mLastEvent < EVENT_THROTTLE_MS) {
            return;
        }
        mLastEvent = now;
        boolean wholesale = Boolean.TRUE.equals(mWholesale.get(className));
        int touched = ViewRules.apply(v, cfg, wholesale);
        if (touched > 0) {
            HookLog.i("ClockHider: 已处理 " + className + " 内 " + touched + " 个视图（方式="
                    + (cfg.mode == ConfigStore.MODE_WHOLE && wholesale ? "整体隐藏" : "分项隐藏") + "）");
        }
        maybeDump();
    }

    private void startPeriodic() {
        if (mPeriodic != null || mHandler == null) return;
        mPeriodic = new Runnable() {
            @Override
            public void run() {
                try {
                    ConfigStore.Snapshot cfg = ConfigStore.snapshot();
                    if (cfg.enabled && !mTracked.isEmpty()) {
                        for (View v : new ArrayList<>(mTracked)) {
                            String cls = mTrackedClass.get(v);
                            boolean wholesale = cls != null
                                    && Boolean.TRUE.equals(mWholesale.get(cls));
                            ViewRules.apply(v, cfg, wholesale);
                        }
                    }
                } catch (Throwable t) {
                    HookLog.e("ClockHider: 周期复检异常", t);
                }
                if (mHandler != null && mInstalled) {
                    mHandler.postDelayed(this, PERIODIC_MS);
                }
            }
        };
        mHandler.postDelayed(mPeriodic, PERIODIC_MS);
    }

    /** App 点「抓取锁屏视图树」会写入新的 token，这里检测到变化就打印一次完整视图树。 */
    private void maybeDump() {
        final long token = ConfigStore.getDumpToken();
        if (token <= 0L || token == mLastDumpToken) return;
        if (mTracked.isEmpty()) {
            // 关键：此时还不能算"抓过了" —— 令牌要留到真的捕获到锁屏时钟视图再用，
            // 否则用户点了抓取、然后才回到锁屏，就会什么也抓不到。
            if (mDumpPendingLoggedToken != token) {
                mDumpPendingLoggedToken = token;
                HookLog.i("ClockHider: 已收到视图树抓取请求，但尚未捕获到锁屏时钟视图 —— "
                        + "请息屏再亮屏回到锁屏，模块捕获后会自动打印");
            }
            return;
        }
        mLastDumpToken = token;
        final List<View> snapshotViews = new ArrayList<>(mTracked);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    HookLog.i("ClockHider: ===== 视图树抓取开始 token=" + token + " =====");
                    HookLog.i("ClockHider: 已尝试挂载的类(" + mHandled.size() + "): " + mHandled);
                    HookLog.i("ClockHider: 已捕获视图 " + snapshotViews.size() + " 个");
                    if (snapshotViews.isEmpty()) {
                        HookLog.i("ClockHider: 尚未捕获到锁屏时钟视图 —— 请先息屏再亮屏（回到锁屏）后重试；"
                                + "若依旧为空，请开启「激进模式」再抓一次，并把日志发给开发者。");
                    }
                    java.util.Set<String> dumped = ConcurrentHashMap.newKeySet();
                    for (View v : snapshotViews) {
                        String key = v.getClass().getName();
                        if (!dumped.add(key)) continue;
                        logBlock("ClockHider: [VIEWTREE] ", ViewRules.dump(v, 12, 120));
                    }
                    HookLog.i("ClockHider: ===== 视图树抓取结束 =====");
                } catch (Throwable t) {
                    HookLog.e("ClockHider: 视图树抓取失败", t);
                }
            }
        };
        if (mHandler != null) {
            mHandler.post(r);
        } else {
            r.run();
        }
    }

    // ==================== 工具 ====================

    private boolean isTargetInstance(View v) {
        if (mTargetClasses.isEmpty()) return false;
        String name = v.getClass().getName();
        if (mTargetClassNames.contains(name)) return true;
        for (Class<?> c : mTargetClasses) {
            if (c.isInstance(v)) return true;
        }
        return false;
    }

    private static boolean aggressiveInteresting(View v) {
        String n = v.getClass().getName().toLowerCase(Locale.ROOT);
        if (n.startsWith("android.view.") || n.startsWith("android.widget.")) return false;
        if (n.contains("statusbar") || n.contains("status_bar")) return false;
        if (n.contains("notification") || n.contains("media") || n.contains("qs")) return false;
        return n.contains("clock") || n.contains("withdate");
    }

    private static boolean hinted(String lowerName) {
        for (String h : UPDATE_HINTS) {
            if (lowerName.contains(h)) return true;
        }
        return false;
    }

    private static boolean heavy(String lowerName) {
        for (String h : HEAVY_HINTS) {
            if (lowerName.contains(h)) return true;
        }
        return false;
    }

    /** 多行文本按行写日志（这样能用 grep -F VIEWTREE 精确抓取视图树）。 */
    private static void logBlock(String prefix, String block) {
        if (block == null) return;
        for (String line : block.split("\n")) {
            HookLog.i(prefix + line);
        }
    }

    static List<String> splitExtra(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String part : raw.split("[\\s,;，；]+")) {
            String s = part.trim();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }
}
