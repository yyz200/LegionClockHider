package com.legion.clockhider;

/**
 * 诊断命令集（App 侧执行，全部走 root shell）。
 *
 * 设计：**分段执行** —— 每段一条短命令，App 逐段调用 root 并各自超时，
 * 单段超时/失败不影响其它段。这样用户只要点一次"查看日志"就能拿到：
 * 模块日志、视图树抓取结果、Hook 安装情况、本机真实类名、配置真实值。
 *
 * 出问题时把这几个段的输出发回即可定位（尤其是 [D] 和 [H] 两段）。
 */
public final class Diag {

    private Diag() {
    }

    public static final class Section {
        public final String label;
        public final String command;
        public final int timeoutSec;

        Section(String label, String command, int timeoutSec) {
            this.label = label;
            this.command = command;
            this.timeoutSec = timeoutSec;
        }
    }

    private static final String PKG = ConfigStore.MODULE_PACKAGE;
    private static final String TAG = HookLog.TAG;
    private static final String LSPD_DIR = "/data/adb/lspd/modules/0/" + PKG;

    /** 完整诊断分段。 */
    public static Section[] sections() {
        return new Section[]{
                new Section("[A] 模块日志（最近 200 行）",
                        "logcat -d -s " + TAG + ":E | tail -n 200; "
                                + "echo \"(若为空 = 模块没有被注入到 SystemUI)\"", 25),
                new Section("[B] 视图树抓取结果（VIEWTREE）",
                        "logcat -d -s " + TAG + ":E | grep -F VIEWTREE | tail -n 200; "
                                + "echo \"(为空 = 还没抓取过，或锁屏时钟视图压根没被捕获)\"", 25),
                new Section("[C] Hook 安装与命中情况",
                        "logcat -d -s " + TAG + ":E "
                                + "| grep -E \"HOOK OK|类不存在|不是 View|首次捕获|已处理|自动发现|候选|engine|引擎|总开关\" "
                                + "| tail -n 80", 25),
                new Section("[D] 本机挖掘到的候选类名（自动发现结果）",
                        "F=" + LSPD_DIR + "/mined-classes.txt; "
                                + "if [ -f \"$F\" ]; then head -n 40 \"$F\"; "
                                + "echo \"...(共 $(wc -l < \"$F\") 行)\"; else echo \"(无 mined-classes.txt："
                                + "自动发现没跑到或没有写入权限)\"; fi", 25),
                new Section("[E] 配置真实值（App 本地 + lspd 兜底文件）",
                        "echo \"--- App SharedPreferences ---\"; "
                                + "cat /data/data/" + PKG + "/shared_prefs/" + PKG + "_preferences.xml 2>&1 | head -n 30; "
                                + "echo \"--- lspd config.properties ---\"; "
                                + "cat " + LSPD_DIR + "/config.properties 2>&1; "
                                + "echo \"--- /sdcard/Download/legionclockhider.conf ---\"; "
                                + "cat /sdcard/Download/legionclockhider.conf 2>&1 | head -n 20", 25),
                new Section("[F] SystemUI 进程与框架状态",
                        "echo \"--- systemui 进程 ---\"; ps -A 2>/dev/null | grep -i systemui | head -n 5; "
                                + "echo \"--- 本模块是否被框架加载 ---\"; "
                                + "logcat -d | grep -i \"legionclockhider\" | tail -n 20; "
                                + "echo \"--- lspd 目录 ---\"; ls -l " + LSPD_DIR + "/ 2>&1 | head -n 10", 30),
                new Section("[G] 已安装 APK 与模块入口文件",
                        "dumpsys package " + PKG + " 2>&1 | grep -E \"versionCode|versionName|codePath|lastUpdateTime\" | head -n 6; "
                                + "APK=$(pm path " + PKG + " | head -n 1 | cut -d: -f2); echo \"apk=$APK\"; "
                                + "if [ -n \"$APK\" ]; then unzip -l \"$APK\" 2>/dev/null "
                                + "| grep -iE \"xposed|module.prop|classes.dex\" | head -n 10; fi", 30),
                new Section("[H] 本机 SystemUI 里真实存在的「时钟」类（不依赖模块）",
                        "APK=$(pm path com.android.systemui | head -n 1 | cut -d: -f2); echo \"systemui=$APK\"; "
                                + "D=/data/local/tmp/lch-dex; rm -rf $D; mkdir -p $D; "
                                + "unzip -o -d $D \"$APK\" \"classes*.dex\" >/dev/null 2>&1; "
                                + "echo \"--- 名字含 Clock 的 SystemUI 类 ---\"; "
                                + "grep -a -o \"Lcom/android/systemui/[A-Za-z0-9_/$]*[Cc]lock[A-Za-z0-9_/$]*;\" $D/classes*.dex 2>/dev/null "
                                + "| sort -u | head -n 60; "
                                + "echo \"--- 名字含 Keyguard 的 SystemUI 类 ---\"; "
                                + "grep -a -o \"Lcom/android/systemui/[A-Za-z0-9_/$]*[Kk]eyguard[A-Za-z0-9_/$]*;\" $D/classes*.dex 2>/dev/null "
                                + "| sort -u | head -n 40", 180),
                new Section("[I] 锁屏 / AOD 相关系统键",
                        "settings list system 2>/dev/null | grep -iE \"clock|lockscreen|lock_screen|keyguard|aod\" | head -n 40", 40),
        };
    }

    /** 精简诊断（一屏内看完）。 */
    public static Section[] briefSections() {
        return new Section[]{
                new Section("1. 模块是否被注入",
                        "logcat -d -s " + TAG + ":E | head -n 12; echo \"---\"; "
                                + "logcat -d -s " + TAG + ":E | grep -cE \".\" | sed \"s/^/日志行数: /\"", 20),
                new Section("2. 是否抓到锁屏时钟视图",
                        "logcat -d -s " + TAG + ":E | grep -E \"首次捕获|HOOK OK|类不存在|已处理\" | tail -n 25", 20),
                new Section("3. 当前配置",
                        "cat " + LSPD_DIR + "/config.properties 2>&1 | head -n 15; echo \"---\"; "
                                + "cat /data/data/" + PKG + "/shared_prefs/" + PKG + "_preferences.xml 2>&1 | head -n 15", 20),
                new Section("4. 已知类是否存在",
                        "logcat -d -s " + TAG + ":E | grep -E \"AnimatableClockViewWithDate|自动发现结束\" | tail -n 10", 20),
        };
    }
}
