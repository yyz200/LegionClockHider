package com.legion.clockhider;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.concurrent.TimeUnit;

/**
 * 配置写入：把 App 的配置写成 properties 文本，用 root 落到三处：
 *  1. /data/adb/lspd/modules/0/&lt;pkg&gt;/config.properties  —— SystemUI 直读 + FileObserver（首选）
 *  2. /data/adb/legionclockhider.conf                     —— root 手动通道
 *  3. /sdcard/Download/legionclockhider.conf              —— 无需 root 的手动通道
 *
 * 现代框架下还有一条更快的通道：libxposed RemotePreferences（见 {@link RemotePrefsBridge}）。
 * 三条通道任一改动都会让 SystemUI 侧实时生效。
 */
public final class ConfigWriter {

    private ConfigWriter() {
    }

    private static final String LSPD_DIR = "/data/adb/lspd/modules/0/" + ConfigStore.MODULE_PACKAGE;
    private static final String LSPD_FILE = LSPD_DIR + "/config.properties";
    private static final String ADB_FILE = "/data/adb/legionclockhider.conf";
    private static final String SD_FILE = "/sdcard/Download/legionclockhider.conf";

    /** 从 App 的 SharedPreferences 读取当前配置并写入全部位置。 */
    public static void writeFromPrefs(Context ctx, SharedPreferences prefs) {
        if (prefs == null) return;
        String extra = prefs.getString(ConfigStore.KEY_EXTRA, "");
        if (extra == null) extra = "";
        extra = extra.replace('\n', ',').replace('\r', ' ').trim();

        String text = "enabled=" + b(prefs.getBoolean(ConfigStore.KEY_ENABLED, false))
                + "\nhide_mode=" + prefs.getInt(ConfigStore.KEY_MODE, ConfigStore.MODE_WHOLE)
                + "\nhide_clock=" + b(prefs.getBoolean(ConfigStore.KEY_CLOCK, true))
                + "\nhide_date=" + b(prefs.getBoolean(ConfigStore.KEY_DATE, true))
                + "\nhide_weekday=" + b(prefs.getBoolean(ConfigStore.KEY_WEEKDAY, true))
                + "\nhide_lunar=" + b(prefs.getBoolean(ConfigStore.KEY_LUNAR, false))
                + "\nhide_visibility=" + prefs.getInt(ConfigStore.KEY_VIS, ConfigStore.VIS_GONE)
                + "\nauto_discover=" + b(prefs.getBoolean(ConfigStore.KEY_AUTO, true))
                + "\naggressive=" + b(prefs.getBoolean(ConfigStore.KEY_AGGRESSIVE, false))
                + "\nhide_statusbar_clock=" + b(prefs.getBoolean(ConfigStore.KEY_STATUSBAR, false))
                + "\nextra_classes=" + extra
                + "\ndump_token=" + prefs.getLong(ConfigStore.KEY_DUMP_TOKEN, 0L)
                + "\n";
        write(ctx, text);
    }

    /** 写入配置文件（在后台线程调用）。 */
    public static void write(Context ctx, String text) {
        try {
            File tmp = new File(ctx.getCacheDir(), "config.properties");
            OutputStreamWriter w = null;
            try {
                w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
                w.write(text);
            } finally {
                if (w != null) {
                    try {
                        w.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            String src = tmp.getAbsolutePath();
            String cmd = "mkdir -p " + LSPD_DIR + "; chmod 777 " + LSPD_DIR + "; "
                    + "cp \"" + src + "\" " + LSPD_FILE + "; chmod 666 " + LSPD_FILE + "; "
                    + "cp \"" + src + "\" " + ADB_FILE + "; chmod 666 " + ADB_FILE + "; "
                    + "cp \"" + src + "\" " + SD_FILE + "; chmod 666 " + SD_FILE + "; "
                    + "echo LCHWRITTEN";
            String out = runRoot(cmd, 20);
            HookLog.i("ConfigWriter: 配置写入结果="
                    + (out != null && out.contains("LCHWRITTEN") ? "OK" : "失败（可能没有 Root）"));
        } catch (Throwable t) {
            HookLog.e("ConfigWriter: 写入失败", t);
        }
    }

    /** root 执行命令并返回输出（失败返回空串，最长等 timeoutSec 秒以等待授权）。 */
    public static String runRoot(String cmd, int timeoutSec) {
        String[] suCandidates = {"su", "/system/bin/su", "/system/xbin/su"};
        for (String su : suCandidates) {
            Process p = null;
            try {
                p = new ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start();
                final Process fp = p;
                final StringBuilder sb = new StringBuilder();
                Thread reader = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        java.io.InputStream in = null;
                        try {
                            in = fp.getInputStream();
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                String s = new String(buf, 0, n, "UTF-8");
                                synchronized (sb) {
                                    if (sb.length() < 400000) sb.append(s);
                                }
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            if (in != null) {
                                try {
                                    in.close();
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }, "lch-su-read");
                reader.start();
                if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                    p.destroy();
                    reader.join(500);
                }
                synchronized (sb) {
                    return sb.toString();
                }
            } catch (Throwable ignored) {
                // 尝试下一个 su 路径
            } finally {
                if (p != null) {
                    try {
                        p.destroy();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return "";
    }

    private static int b(boolean v) {
        return v ? 1 : 0;
    }
}
