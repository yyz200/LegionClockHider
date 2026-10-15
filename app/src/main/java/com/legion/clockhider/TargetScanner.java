package com.legion.clockhider;

import android.view.View;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 本机真实类名挖掘（纯 Java，不依赖手机上的 unzip / dexkit）。
 *
 * 为什么需要它：厂商固件里锁屏时钟的类名会随版本改名（本模块已知 ZUXOS 1.5 用的是
 * {@code com.android.systemui.shared.clocks.AnimatableClockViewWithDate}，但下一步 OTA
 * 就可能换掉）。这里的做法是在 SystemUI 进程内流式扫 SystemUI.apk 的 classes*.dex 字符串池，
 * 收集"锁屏 + 时钟"相关的类描述符：
 *  - 命中的候选类会回传给 {@link ClockHider} 自动补挂 Hook（换固件也能自适应）；
 *  - 全量结果写到 /data/adb/lspd/modules/0/&lt;pkg&gt;/mined-classes.txt，诊断里可直接查看；
 *  - 只读不写业务数据、单线程、每个进程只跑一次。
 */
final class TargetScanner {

    interface Consumer {
        void accept(List<String> classNames);
    }

    private static final String OUT_DIR =
            "/data/adb/lspd/modules/0/" + ConfigStore.MODULE_PACKAGE;
    private static final String OUT_FILE = OUT_DIR + "/mined-classes.txt";
    private static final int MAX_ALL = 4000;
    private static final int MAX_CANDIDATES = 40;

    private TargetScanner() {
    }

    static void scan(final ClassLoader cl, final Consumer onDone) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String> candidates = new ArrayList<>();
                try {
                    String apk = systemUiApkPath(cl);
                    if (apk == null) {
                        HookLog.i("TargetScanner: 未找到 SystemUI APK 路径，自动发现跳过");
                    } else {
                        TreeSet<String> all = new TreeSet<>();
                        ZipFile zip = null;
                        try {
                            zip = new ZipFile(apk);
                            Enumeration<? extends ZipEntry> entries = zip.entries();
                            while (entries.hasMoreElements()) {
                                ZipEntry entry = entries.nextElement();
                                String name = entry.getName();
                                if (!name.startsWith("classes") || !name.endsWith(".dex")) continue;
                                InputStream in = null;
                                try {
                                    in = zip.getInputStream(entry);
                                    scanDex(in, all);
                                } catch (Throwable t) {
                                    HookLog.e("TargetScanner: 扫描失败 " + name, t);
                                } finally {
                                    if (in != null) {
                                        try {
                                            in.close();
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }
                            }
                        } finally {
                            if (zip != null) {
                                try {
                                    zip.close();
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                        candidates = filterCandidates(all, cl);
                        report(apk, all, candidates);
                    }
                } catch (Throwable t) {
                    HookLog.e("TargetScanner: 挖掘失败", t);
                }
                if (onDone != null) {
                    try {
                        onDone.accept(candidates);
                    } catch (Throwable t) {
                        HookLog.e("TargetScanner: 回调失败", t);
                    }
                }
            }
        }, "lch-dex-scanner").start();
    }

    /** 取 SystemUI APK 路径：优先 /proc/self/maps 中含 SystemUI 的那条，再退到 CodeSource 与固定路径。 */
    private static String systemUiApkPath(ClassLoader cl) {
        BufferedReader reader = null;
        String firstApk = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream("/proc/self/maps")));
            String line;
            while ((line = reader.readLine()) != null) {
                int idx = line.indexOf(".apk");
                if (idx < 0) continue;
                int sp = line.lastIndexOf(' ', idx);
                if (sp < 0) continue;
                String path = line.substring(sp + 1, idx + 4).trim();
                if (!path.startsWith("/") || !new File(path).isFile()) continue;
                if (firstApk == null) firstApk = path;
                String lower = path.toLowerCase(Locale.ROOT);
                if (lower.contains("systemui") || lower.contains("system_ui")) {
                    HookLog.i("TargetScanner: SystemUI APK（来自 maps）= " + path);
                    return path;
                }
            }
        } catch (Throwable t) {
            HookLog.i("TargetScanner: maps 扫描失败（" + t + "）");
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
        String[] probes = {
                "com.android.systemui.shared.clocks.AnimatableClockViewWithDate",
                "com.android.systemui.SystemUIApplication",
                "com.android.systemui.R",
        };
        for (String probe : probes) {
            try {
                Class<?> c = Class.forName(probe, false, cl);
                java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
                if (cs != null && cs.getLocation() != null) {
                    String path = cs.getLocation().getPath();
                    if (path != null && path.endsWith(".apk") && new File(path).isFile()) {
                        HookLog.i("TargetScanner: SystemUI APK（来自 CodeSource）= " + path);
                        return path;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        String[] fixed = {
                "/system_ext/priv-app/SystemUI/SystemUI.apk",
                "/system/priv-app/SystemUI/SystemUI.apk",
                "/system_ext/priv-app/LenovoSystemUI/LenovoSystemUI.apk",
                "/system/priv-app/LenovoSystemUI/LenovoSystemUI.apk",
                "/my_product/priv-app/SystemUI/SystemUI.apk",
                "/my_product/priv-app/LenovoSystemUI/LenovoSystemUI.apk",
                "/product/priv-app/SystemUI/SystemUI.apk",
        };
        for (String p : fixed) {
            try {
                if (new File(p).isFile()) {
                    HookLog.i("TargetScanner: SystemUI APK（来自固定路径）= " + p);
                    return p;
                }
            } catch (Throwable ignored) {
            }
        }
        if (firstApk != null) {
            HookLog.i("TargetScanner: 回退到 maps 中第一个 APK = " + firstApk);
        }
        return firstApk;
    }

    /** 流式扫描：识别 Lcom/...; 形式的类描述符（dex 字符串池里就是这种编码）。 */
    private static void scanDex(InputStream in, TreeSet<String> hits) throws Exception {
        byte[] buf = new byte[1 << 16];
        StringBuilder cur = null;
        int n;
        while ((n = in.read(buf)) != -1) {
            for (int i = 0; i < n; i++) {
                char c = (char) (buf[i] & 0xFF);
                if (c == 'L') {
                    cur = new StringBuilder(64);
                    cur.append('L');
                } else if (cur != null) {
                    if (c == ';') {
                        String s = cur.toString();
                        if (s.length() > 14 && s.length() < 200 && interesting(s)
                                && hits.size() < MAX_ALL) {
                            hits.add(s);
                        }
                        cur = null;
                    } else if (isClassChar(c)) {
                        cur.append(c);
                    } else {
                        cur = null;
                    }
                }
            }
        }
    }

    private static boolean isClassChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '/' || c == '$' || c == '_';
    }

    /** 收集范围：SystemUI / keyguard / 厂商定制命名空间下，名字含 clock 或 keyguard/lockscreen 的类。 */
    private static boolean interesting(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        boolean ns = l.startsWith("lcom/android/systemui/")
                || l.startsWith("lcom/android/keyguard/")
                || l.startsWith("lcom/zui/")
                || l.startsWith("lcom/lenovo/")
                || l.startsWith("lcom/motorola/")
                || l.startsWith("lcom/android/internal/policy/");
        if (!ns) return false;
        return l.contains("clock") || l.contains("keyguard") || l.contains("lockscreen")
                || l.contains("withdate");
    }

    /** 从全量结果里挑出"值得挂 Hook 的锁屏时钟视图类"。 */
    private static List<String> filterCandidates(TreeSet<String> all, ClassLoader cl) {
        List<String> out = new ArrayList<>();
        int checked = 0;
        for (String desc : all) {
            if (out.size() >= MAX_CANDIDATES || checked >= 400) break;
            String name = toClassName(desc);
            if (name == null || !hookCandidate(name)) continue;
            checked++;
            try {
                Class<?> c = Class.forName(name, false, cl);
                if (!View.class.isAssignableFrom(c)) continue;
            } catch (Throwable t) {
                continue;
            }
            out.add(name);
        }
        return out;
    }

    /** 只接受"锁屏/时钟"语义的类，避免误伤状态栏时钟、控制中心、通知、天气等。 */
    private static boolean hookCandidate(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        if (l.contains("policy.clock")) return false;          // 状态栏时钟
        if (l.contains("widget") || l.contains("plugin") || l.contains("weather")) return false;
        if (l.contains("notification") || l.contains("media") || l.contains("screenshot")) return false;
        if (l.contains(".qs.") || l.contains("quickstep") || l.contains("dream")) return false;
        if (!l.contains("clock") && !l.contains("withdate")) return false;
        return l.contains("keyguard") || l.contains("lockscreen") || l.contains("lock")
                || l.contains("withdate") || l.contains("clocks.");
    }

    private static String toClassName(String desc) {
        if (desc == null || desc.length() < 3) return null;
        String s = desc;
        if (s.startsWith("L")) s = s.substring(1);
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
        if (s.contains("/")) s = s.replace('/', '.');
        return s;
    }

    /** 写出结果文件 + 日志摘要。 */
    private static void report(String apk, TreeSet<String> all, List<String> candidates) {
        HookLog.i("TargetScanner: APK=" + apk + ", 相关类 " + all.size()
                + " 个, 自动补挂候选 " + candidates.size() + " 个");
        boolean primaryFound = false;
        for (String d : all) {
            if (d.contains("AnimatableClockViewWithDate")) primaryFound = true;
        }
        HookLog.i("TargetScanner: ZUI 已知类 AnimatableClockViewWithDate 在本机 "
                + (primaryFound ? "存在 ✔" : "不存在 ✘（固件可能改名，请看候选列表）"));
        int logged = 0;
        for (String c : candidates) {
            HookLog.i("TargetScanner: 候选 " + c);
            if (++logged >= 30) break;
        }
        try {
            File dir = new File(OUT_DIR);
            dir.mkdirs();
            File out = new File(OUT_FILE);
            OutputStreamWriter w = null;
            try {
                w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
                w.write("# LegionClockHider 类名挖掘结果\n");
                w.write("# apk=" + apk + "\n");
                w.write("# AnimatableClockViewWithDate=" + (primaryFound ? "存在" : "不存在") + "\n");
                w.write("# --- 自动补挂候选（View 类型）---\n");
                for (String c : candidates) {
                    w.write(c);
                    w.write('\n');
                }
                w.write("# --- 其它相关类（诊断用）---\n");
                for (String d : all) {
                    w.write(d);
                    w.write('\n');
                }
            } finally {
                if (w != null) {
                    try {
                        w.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            HookLog.i("TargetScanner: 结果已写入 " + OUT_FILE);
        } catch (Throwable t) {
            HookLog.i("TargetScanner: 结果文件写入跳过（" + t + "）");
        }
    }
}
