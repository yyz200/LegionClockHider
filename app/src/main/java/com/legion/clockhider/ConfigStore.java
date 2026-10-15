package com.legion.clockhider;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 配置读取（被 Hook 的 SystemUI 进程与模块 App 共用）。
 *
 * 读取优先级：ContentProvider（App 前台时最快）→ 框架远程偏好（libxposed RemotePreferences）
 *            → 兜底文件 → 传统 XSharedPreferences。
 * 兜底文件（任一存在且含 enabled 即生效）：
 *   1) /data/adb/lspd/modules/0/&lt;pkg&gt;/config.properties  （App 用 root 写入，SystemUI 直读 + FileObserver）
 *   2) /data/adb/legionclockhider.conf
 *   3) /sdcard/Download/legionclockhider.conf              （无需 root，文件管理器即可改）
 *
 * 本模块全部配置项都是"隐藏锁屏时钟/日期/星期"的开关，没有任何用户数据外泄风险。
 */
public final class ConfigStore {

    public static final String MODULE_PACKAGE = "com.legion.clockhider";
    public static final String PREFS_NAME = "com.legion.clockhider_preferences";
    public static final Uri PREFS_URI =
            Uri.parse("content://" + MODULE_PACKAGE + ".prefs/prefs");
    public static final String CONFIG_FILE =
            "/data/adb/lspd/modules/0/" + MODULE_PACKAGE + "/config.properties";

    /** 隐藏方式：整体隐藏时钟容器（最可靠，推荐）。 */
    public static final int MODE_WHOLE = 0;
    /** 隐藏方式：逐项隐藏（时钟 / 日期 / 星期 分别开关）。 */
    public static final int MODE_ITEM = 1;

    /** 隐藏手段：View.GONE（不占位）。 */
    public static final int VIS_GONE = 0;
    /** 隐藏手段：View.INVISIBLE（占位不变，布局不跳动）。 */
    public static final int VIS_INVISIBLE = 1;

    // ---- 配置键 ----
    public static final String KEY_ENABLED = "enabled";             // 总开关
    public static final String KEY_MODE = "hide_mode";              // 0=整体 1=分项
    public static final String KEY_CLOCK = "hide_clock";            // 分项：时钟
    public static final String KEY_DATE = "hide_date";              // 分项：日期
    public static final String KEY_WEEKDAY = "hide_weekday";        // 分项：星期
    public static final String KEY_LUNAR = "hide_lunar";            // 分项：农历/节气
    public static final String KEY_VIS = "hide_visibility";         // 0=GONE 1=INVISIBLE
    public static final String KEY_AUTO = "auto_discover";          // 自动挖掘本机真实类名
    public static final String KEY_AGGRESSIVE = "aggressive";       // 激进模式（全局视图扫描）
    public static final String KEY_STATUSBAR = "hide_statusbar_clock"; // 顺带隐藏状态栏时钟
    public static final String KEY_EXTRA = "extra_classes";         // 手动补充类名（换行/逗号分隔）
    public static final String KEY_DUMP_TOKEN = "dump_token";       // 抓取视图树的触发令牌
    public static final String KEY_PREFS_VERSION = "prefs_version";

    private static final long DISPATCH_DEBOUNCE_MS = 300L;

    public interface Listener {
        void onConfigChanged();
    }

    private static final List<Listener> sListeners = new CopyOnWriteArrayList<>();

    private static volatile boolean sInited = false;
    private static volatile boolean sEnabled = false;
    private static volatile int sMode = MODE_WHOLE;
    private static volatile boolean sClock = true;
    private static volatile boolean sDate = true;
    private static volatile boolean sWeekday = true;
    private static volatile boolean sLunar = false;
    private static volatile int sVis = VIS_GONE;
    private static volatile boolean sAuto = true;
    private static volatile boolean sAggressive = false;
    private static volatile boolean sStatusBar = false;
    private static volatile String sExtra = "";
    private static volatile long sDumpToken = 0L;

    private static volatile SharedPreferences sModernPrefs;
    private static volatile Object sLegacyXsp;
    private static volatile boolean sObserverRegistered = false;
    private static volatile android.os.FileObserver sConfigObserver;
    private static Handler sMainHandler;

    private static final Runnable sDispatchRunnable = () -> dispatchListeners();

    private ConfigStore() {
    }

    /** 现代入口注入框架远程偏好（libxposed Remote Preferences）。 */
    public static void setRemotePreferences(SharedPreferences prefs) {
        sModernPrefs = prefs;
        try {
            prefs.registerOnSharedPreferenceChangeListener((sp, key) -> refreshAndNotify());
            HookLog.i("ConfigStore: 远程偏好已挂载（含变更监听）");
        } catch (Throwable t) {
            HookLog.i("ConfigStore: 远程偏好已挂载（无变更监听：" + t + "）");
        }
        refresh();
    }

    public static synchronized void ensureInitialized() {
        if (sInited) return;
        sInited = true;
        try {
            sMainHandler = new Handler(Looper.getMainLooper());
        } catch (Throwable ignored) {
        }

        // 传统只读兜底（类缺失时静默）
        try {
            Object xsp = Class.forName("de.robv.android.xposed.XSharedPreferences")
                    .getConstructor(String.class, String.class)
                    .newInstance(MODULE_PACKAGE, PREFS_NAME);
            xsp.getClass().getMethod("makeWorldReadable").invoke(xsp);
            sLegacyXsp = xsp;
        } catch (Throwable t) {
            sLegacyXsp = null;
        }

        ensureObserver();
        startConfigObserver();
        refresh();
        HookLog.i("ConfigStore: 初始化完成，" + describe());
    }

    /** ContentObserver 懒注册（Context 就绪后补上）。 */
    private static void ensureObserver() {
        if (sObserverRegistered) return;
        Context ctx = Compat.appContext();
        if (ctx == null) {
            HookLog.i("ConfigStore: App Context 尚未就绪（Provider 通道延后）");
            return;
        }
        try {
            ctx.getContentResolver().registerContentObserver(
                    PREFS_URI, false,
                    new ContentObserver(sMainHandler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            refreshAndNotify();
                        }
                    });
            sObserverRegistered = true;
            HookLog.i("ConfigStore: ContentObserver 已注册 " + PREFS_URI);
        } catch (Throwable t) {
            HookLog.e("ConfigStore: ContentObserver 注册失败", t);
        }
    }

    /** 监听兜底配置文件变化（App 写入后实时生效）。 */
    private static void startConfigObserver() {
        if (sConfigObserver != null) return;
        try {
            java.io.File dir = new java.io.File(CONFIG_FILE).getParentFile();
            if (dir == null) return;
            dir.mkdirs();
            android.os.FileObserver fo = new android.os.FileObserver(dir.getAbsolutePath(),
                    android.os.FileObserver.CREATE
                            | android.os.FileObserver.MODIFY
                            | android.os.FileObserver.CLOSE_WRITE) {
                @Override
                public void onEvent(int event, String path) {
                    try {
                        if (path != null && path.contains("config.properties")) {
                            refreshAndNotify();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            fo.startWatching();
            sConfigObserver = fo;
            HookLog.i("ConfigStore: 配置文件监听已启动 " + dir.getAbsolutePath());
        } catch (Throwable t) {
            HookLog.i("ConfigStore: 配置文件监听不可用（" + t + "）");
        }
    }

    /** 读取最新值（Provider → 远程偏好 → 兜底文件 → XSP）。 */
    public static void refresh() {
        ensureObserver();
        Context ctx = Compat.appContext();
        if (ctx != null) {
            Cursor c = null;
            try {
                ContentResolver cr = ctx.getContentResolver();
                c = cr.query(PREFS_URI, null, null, null, null);
                if (c != null) {
                    Bundle b = c.getExtras();
                    if (b != null) {
                        readFrom(b);
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // Provider 不可达，走下一级
            } finally {
                if (c != null) {
                    try {
                        c.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        SharedPreferences modern = sModernPrefs;
        if (modern != null) {
            try {
                if (modern.contains(KEY_ENABLED) || modern.contains(KEY_MODE)) {
                    readFrom(modern);
                    return;
                }
            } catch (Throwable t) {
                HookLog.e("ConfigStore: 读取远程偏好失败", t);
            }
        }
        if (readFromFile()) return;
        Object xsp = sLegacyXsp;
        if (xsp != null) {
            try {
                xsp.getClass().getMethod("reload").invoke(xsp);
                readFrom((SharedPreferences) xsp);
            } catch (Throwable t) {
                HookLog.e("ConfigStore: 读取 XSharedPreferences 失败", t);
            }
        }
    }

    /** 直读配置文件（多个候选路径，按优先级）。 */
    private static boolean readFromFile() {
        String[] candidates = {
                CONFIG_FILE,
                "/data/adb/legionclockhider.conf",
                "/sdcard/Download/legionclockhider.conf",
                "/storage/emulated/0/Download/legionclockhider.conf",
        };
        for (String path : candidates) {
            try {
                java.io.File f = new java.io.File(path);
                if (!f.isFile() || !f.canRead()) continue;
                java.util.Properties p = new java.util.Properties();
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    p.load(in);
                }
                if (!p.containsKey(KEY_ENABLED)) continue;
                sEnabled = "1".equals(p.getProperty(KEY_ENABLED, "0"));
                sMode = clampMode(parseInt(p.getProperty(KEY_MODE, "0"), MODE_WHOLE));
                sClock = "1".equals(p.getProperty(KEY_CLOCK, "1"));
                sDate = "1".equals(p.getProperty(KEY_DATE, "1"));
                sWeekday = "1".equals(p.getProperty(KEY_WEEKDAY, "1"));
                sLunar = "1".equals(p.getProperty(KEY_LUNAR, "0"));
                sVis = clampVis(parseInt(p.getProperty(KEY_VIS, "0"), VIS_GONE));
                sAuto = "1".equals(p.getProperty(KEY_AUTO, "1"));
                sAggressive = "1".equals(p.getProperty(KEY_AGGRESSIVE, "0"));
                sStatusBar = "1".equals(p.getProperty(KEY_STATUSBAR, "0"));
                sExtra = p.getProperty(KEY_EXTRA, "");
                sDumpToken = parseLong(p.getProperty(KEY_DUMP_TOKEN, "0"), 0L);
                HookLog.i("ConfigStore: 配置来自 " + path + " -> " + describe());
                return true;
            } catch (Throwable ignored) {
                // 尝试下一个候选路径
            }
        }
        return false;
    }

    private static void readFrom(SharedPreferences sp) {
        sEnabled = sp.getBoolean(KEY_ENABLED, false);
        sMode = clampMode(sp.getInt(KEY_MODE, MODE_WHOLE));
        sClock = sp.getBoolean(KEY_CLOCK, true);
        sDate = sp.getBoolean(KEY_DATE, true);
        sWeekday = sp.getBoolean(KEY_WEEKDAY, true);
        sLunar = sp.getBoolean(KEY_LUNAR, false);
        sVis = clampVis(sp.getInt(KEY_VIS, VIS_GONE));
        sAuto = sp.getBoolean(KEY_AUTO, true);
        sAggressive = sp.getBoolean(KEY_AGGRESSIVE, false);
        sStatusBar = sp.getBoolean(KEY_STATUSBAR, false);
        sExtra = sp.getString(KEY_EXTRA, "");
        sDumpToken = sp.getLong(KEY_DUMP_TOKEN, 0L);
    }

    private static void readFrom(Bundle b) {
        sEnabled = b.getBoolean(KEY_ENABLED, false);
        sMode = clampMode(b.getInt(KEY_MODE, MODE_WHOLE));
        sClock = b.getBoolean(KEY_CLOCK, true);
        sDate = b.getBoolean(KEY_DATE, true);
        sWeekday = b.getBoolean(KEY_WEEKDAY, true);
        sLunar = b.getBoolean(KEY_LUNAR, false);
        sVis = clampVis(b.getInt(KEY_VIS, VIS_GONE));
        sAuto = b.getBoolean(KEY_AUTO, true);
        sAggressive = b.getBoolean(KEY_AGGRESSIVE, false);
        sStatusBar = b.getBoolean(KEY_STATUSBAR, false);
        sExtra = b.getString(KEY_EXTRA, "");
        sDumpToken = b.getLong(KEY_DUMP_TOKEN, 0L);
    }

    public static void refreshAndNotify() {
        refresh();
        HookLog.i("ConfigStore: 配置变更，" + describe());
        if (sMainHandler != null) {
            sMainHandler.removeCallbacks(sDispatchRunnable);
            sMainHandler.postDelayed(sDispatchRunnable, DISPATCH_DEBOUNCE_MS);
        } else {
            dispatchListeners();
        }
    }

    private static void dispatchListeners() {
        for (Listener l : sListeners) {
            try {
                l.onConfigChanged();
            } catch (Throwable t) {
                HookLog.e("ConfigStore: 监听器异常", t);
            }
        }
    }

    public static void addListener(Listener l) {
        if (l != null && !sListeners.contains(l)) {
            sListeners.add(l);
        }
    }

    public static void removeListener(Listener l) {
        sListeners.remove(l);
    }

    // ---- 取值 ----

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static long getDumpToken() {
        return sDumpToken;
    }

    /** 一次性配置快照：Hook 引擎按它执行，避免读到"半新半旧"的组合。 */
    public static final class Snapshot {
        public final boolean enabled;
        public final int mode;
        public final boolean clock;
        public final boolean date;
        public final boolean weekday;
        public final boolean lunar;
        public final int visibility;
        public final boolean autoDiscover;
        public final boolean aggressive;
        public final boolean statusBarClock;
        public final String extraClasses;

        Snapshot(boolean enabled, int mode, boolean clock, boolean date, boolean weekday,
                 boolean lunar, int visibility, boolean autoDiscover, boolean aggressive,
                 boolean statusBarClock, String extraClasses) {
            this.enabled = enabled;
            this.mode = mode;
            this.clock = clock;
            this.date = date;
            this.weekday = weekday;
            this.lunar = lunar;
            this.visibility = visibility;
            this.autoDiscover = autoDiscover;
            this.aggressive = aggressive;
            this.statusBarClock = statusBarClock;
            this.extraClasses = extraClasses;
        }

        /** 三项全隐藏（最常见需求）：此时"逐项"与"整体"效果等价。 */
        public boolean hideEverything() {
            return clock && date && weekday;
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(sEnabled, sMode, sClock, sDate, sWeekday, sLunar, sVis,
                sAuto, sAggressive, sStatusBar, sExtra);
    }

    public static String describe() {
        return "enabled=" + sEnabled
                + ", mode=" + (sMode == MODE_WHOLE ? "整体" : "分项")
                + ", clock=" + sClock + ", date=" + sDate + ", weekday=" + sWeekday
                + ", lunar=" + sLunar
                + ", vis=" + (sVis == VIS_GONE ? "GONE" : "INVISIBLE")
                + ", auto=" + sAuto + ", aggressive=" + sAggressive
                + ", statusbar=" + sStatusBar
                + ", extra=" + (sExtra == null || sExtra.isEmpty() ? "(无)" : sExtra.replace('\n', '|'));
    }

    private static int clampMode(int v) {
        return (v == MODE_ITEM) ? MODE_ITEM : MODE_WHOLE;
    }

    private static int clampVis(int v) {
        return (v == VIS_INVISIBLE) ? VIS_INVISIBLE : VIS_GONE;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
