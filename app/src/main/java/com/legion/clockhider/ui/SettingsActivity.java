package com.legion.clockhider.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.legion.clockhider.ConfigStore;
import com.legion.clockhider.ConfigWriter;
import com.legion.clockhider.Diag;
import com.legion.clockhider.LogFileProvider;
import com.legion.clockhider.R;
import com.legion.clockhider.RemotePrefsBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 设置界面（完全程序化构建，只用系统控件，不依赖 AndroidX）。
 *
 * 界面结构：
 *   头部（模块名 + 框架激活状态）
 *   一、隐藏开关（总开关 / 隐藏方式 / 时钟 / 日期 / 星期 / 农历 / 隐藏手段）
 *   二、兼容与高级（自动发现 / 激进模式 / 状态栏时钟 / 手动补充类名）
 *   三、操作（抓取锁屏视图树 / 重启 SystemUI / 查看日志 / 导出日志）
 *   四、说明与版本
 *
 * 配置改动即时生效：Agent 侧写 libxposed 远程偏好 + root 兜底文件，
 * SystemUI 进程内的 Hook 收到变更后立刻重算（无需重启手机）。
 */
public class SettingsActivity extends Activity {

    private SharedPreferences mPrefs;
    private LinearLayout mContainer;
    private TextView mStatusPill;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        mPrefs = getSharedPreferences(ConfigStore.PREFS_NAME, MODE_PRIVATE);
        mContainer = (LinearLayout) findViewById(R.id.container);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ==================== 界面构建 ====================

    private void buildUi() {
        mContainer.removeAllViews();
        header();

        sectionTitle("一、隐藏开关");
        mStatusPill = null;
        switchRow("总开关", "关闭时所有 Hook 会被卸载，锁屏时钟恢复原样", ConfigStore.KEY_ENABLED, false);

        card("隐藏方式", "推荐「整体隐藏」：直接把锁屏时钟容器隐藏，最可靠；"
                + "「逐项隐藏」可分别处理时钟/日期/星期（同一行的日期+星期能只删星期保留日期）");
        segment(new String[]{"整体隐藏（推荐）", "逐项隐藏"}, new int[]{
                ConfigStore.MODE_WHOLE, ConfigStore.MODE_ITEM
        }, ConfigStore.KEY_MODE, ConfigStore.MODE_WHOLE, null);

        switchRow("隐藏时钟", "锁屏大时钟（时:分）", ConfigStore.KEY_CLOCK, true);
        switchRow("隐藏日期", "如「9月12日」", ConfigStore.KEY_DATE, true);
        switchRow("隐藏星期", "如「星期五 / 周五」；与日期同一行时会只删星期", ConfigStore.KEY_WEEKDAY, true);
        switchRow("隐藏农历 / 节气", "如果本机锁屏显示农历，一并隐藏（默认关）", ConfigStore.KEY_LUNAR, false);

        card("隐藏手段", "GONE＝不占位置（推荐，时钟位置直接消失）；"
                + "INVISIBLE＝保留原位置，锁屏布局不跳动");
        segment(new String[]{"GONE（推荐）", "INVISIBLE"}, new int[]{
                ConfigStore.VIS_GONE, ConfigStore.VIS_INVISIBLE
        }, ConfigStore.KEY_VIS, ConfigStore.VIS_GONE, null);

        sectionTitle("二、兼容与高级");
        switchRow("自动发现本机类名", "扫 SystemUI 的 dex 找出本机真实的锁屏时钟类并自动挂载；"
                + "换固件/Ota 后也能自适应，建议保持开启", ConfigStore.KEY_AUTO, true);
        switchRow("激进模式（兜底）", "全局扫描新出现的视图，凡类名含 clock/withdate 的都按规则处理。"
                + "默认方案无效时再开", ConfigStore.KEY_AGGRESSIVE, false);
        switchRow("顺带隐藏状态栏时钟", "注意：这是全局的，解锁后状态栏时钟也会消失", ConfigStore.KEY_STATUSBAR, false);

        card("手动补充类名（诊断后使用）", "一行一个完整的类名，例如：\n"
                + "com.android.systemui.shared.clocks.AnimatableClockViewWithDate\n"
                + "留空表示不用。多个类名可用换行或逗号分隔。");
        extraClassesEditor();

        sectionTitle("三、操作");
        button("抓取锁屏视图树", "把当前锁屏时钟的视图结构写进日志（排查用）", new Runnable() {
            @Override
            public void run() {
                requestDump();
            }
        });
        button("重启 SystemUI（生效/排错用）", "重启后锁屏界面会闪一下，属正常现象", new Runnable() {
            @Override
            public void run() {
                confirmRestartSystemUi();
            }
        });
        button("查看模块日志（root）", "读取 logcat 中本模块的日志 + 诊断信息", new Runnable() {
            @Override
            public void run() {
                showLogs(false);
            }
        });
        button("精简日志", "只取最关键几段，适合截图反馈", new Runnable() {
            @Override
            public void run() {
                showLogs(true);
            }
        });
        button("导出日志到 txt", "写入 App 私有目录，可分享到微信/电脑", new Runnable() {
            @Override
            public void run() {
                exportLogs();
            }
        });

        sectionTitle("四、说明");
        card("使用步骤", "1) 在 LSPosed 管理器里启用本模块，作用域勾选「系统界面 / System UI」（com.android.systemui）；\n"
                + "2) 回到本界面打开「总开关」；\n"
                + "3) 息屏再亮屏（或点上面的「重启 SystemUI」）即可看到锁屏时钟消失。\n"
                + "配置改动无需重启手机，实时生效。");
        card("如果没生效", "先点「抓取锁屏视图树」，回到锁屏停 2 秒，再点「查看模块日志」，"
                + "把 [D] 段（本机挖掘到的候选类名）和 [H] 段（SystemUI 里真实存在的时钟类）发回给开发者即可定位。");

        footer();
    }

    private void header() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_card);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        box.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText(R.string.header_subtitle);
        sub.setTextColor(getColor(R.color.text_secondary));
        sub.setTextSize(13);
        sub.setPadding(0, dp(6), 0, dp(10));
        box.addView(sub);

        mStatusPill = new TextView(this);
        mStatusPill.setTextSize(12);
        mStatusPill.setPadding(dp(12), dp(6), dp(12), dp(6));
        box.addView(mStatusPill);

        TextView desc = new TextView(this);
        desc.setText(R.string.module_description);
        desc.setTextColor(getColor(R.color.text_tertiary));
        desc.setTextSize(12);
        desc.setPadding(0, dp(10), 0, 0);
        box.addView(desc);

        mContainer.addView(box);
        refreshStatus();
    }

    private void refreshStatus() {
        if (mStatusPill == null) return;
        boolean ready = RemotePrefsBridge.isReady();
        mStatusPill.setText(ready
                ? "框架已连接 · " + RemotePrefsBridge.frameworkInfo()
                : "未检测到框架：请在 LSPosed 中启用本模块并勾选作用域（系统界面）");
        mStatusPill.setTextColor(getColor(ready ? R.color.pill_on_fg : R.color.pill_off_fg));
        mStatusPill.setBackgroundResource(ready ? R.drawable.bg_pill_on : R.drawable.bg_pill_off);
    }

    private void sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.text_secondary));
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), dp(18), dp(4), dp(8));
        mContainer.addView(t);
    }

    private void card(String title, String body) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_card);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        box.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getColor(R.color.text_primary));
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);

        TextView b = new TextView(this);
        b.setText(body);
        b.setTextColor(getColor(R.color.text_secondary));
        b.setTextSize(12);
        b.setPadding(0, dp(6), 0, 0);
        b.setLineSpacing(dp(3), 1f);
        box.addView(b);

        mContainer.addView(box);
    }

    private void switchRow(final String title, String sub, final String key, final boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_row);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getColor(R.color.text_primary));
        t.setTextSize(15);
        texts.addView(t);

        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(getColor(R.color.text_tertiary));
            s.setTextSize(12);
            s.setPadding(0, dp(4), 0, 0);
            texts.addView(s);
        }
        row.addView(texts);

        final TextView pill = new TextView(this);
        pill.setTextSize(12);
        pill.setPadding(dp(10), dp(5), dp(10), dp(5));
        applyPill(pill, mPrefs.getBoolean(key, def));
        row.addView(pill);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean nv = !mPrefs.getBoolean(key, def);
                mPrefs.edit().putBoolean(key, nv).apply();
                applyPill(pill, nv);
                commit();
            }
        });
        mContainer.addView(row);
    }

    private void segment(final String[] labels, final int[] values, final String key,
                         final int def, String unused) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setBackgroundResource(R.drawable.bg_segment);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        box.setLayoutParams(lp);

        final TextView[] items = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int value = values[i];
            TextView item = new TextView(this);
            item.setText(labels[i]);
            item.setTextSize(13);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(8), dp(10), dp(8), dp(10));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            item.setLayoutParams(ilp);
            item.setClickable(true);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mPrefs.edit().putInt(key, value).apply();
                    for (int j = 0; j < items.length; j++) {
                        styleSegment(items[j], values[j] == mPrefs.getInt(key, def));
                    }
                    commit();
                }
            });
            items[i] = item;
            box.addView(item);
        }
        for (int j = 0; j < items.length; j++) {
            styleSegment(items[j], values[j] == mPrefs.getInt(key, def));
        }
        mContainer.addView(box);
    }

    private void styleSegment(TextView t, boolean selected) {
        t.setTextColor(getColor(selected ? R.color.text_primary : R.color.text_tertiary));
        t.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setBackgroundResource(selected ? R.drawable.bg_pill_on : 0);
    }

    private void extraClassesEditor() {
        final EditText et = new EditText(this);
        et.setText(mPrefs.getString(ConfigStore.KEY_EXTRA, ""));
        et.setHint("com.example.ClassName");
        et.setTextColor(getColor(R.color.text_primary));
        et.setHintTextColor(getColor(R.color.text_tertiary));
        et.setTextSize(12);
        et.setBackgroundResource(R.drawable.bg_row);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        et.setMinLines(2);
        et.setGravity(Gravity.TOP | Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        et.setLayoutParams(lp);
        mContainer.addView(et);

        button("保存补充类名", null, new Runnable() {
            @Override
            public void run() {
                mPrefs.edit().putString(ConfigStore.KEY_EXTRA, et.getText().toString()).apply();
                commit();
                toast("已保存（重新挂载需重启 SystemUI）");
            }
        });
    }

    private void button(String text, String sub, final Runnable action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_btn_secondary);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        box.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.text_primary));
        t.setTextSize(14);
        box.addView(t);

        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(getColor(R.color.text_tertiary));
            s.setTextSize(12);
            s.setPadding(0, dp(4), 0, 0);
            box.addView(s);
        }
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        mContainer.addView(box);
    }

    private void footer() {
        TextView t = new TextView(this);
        t.setText(R.string.version_footer);
        t.setTextColor(getColor(R.color.text_tertiary));
        t.setTextSize(11);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(18), 0, 0);
        mContainer.addView(t);
    }

    // ==================== 配置写入 ====================

    /** 保存后立刻下发：远程偏好（现代框架）+ root 兜底文件（所有框架）。 */
    private void commit() {
        try {
            RemotePrefsBridge.mirror();
        } catch (Throwable ignored) {
        }
        final SharedPreferences snapshot = mPrefs;
        new Thread(new Runnable() {
            @Override
            public void run() {
                ConfigWriter.writeFromPrefs(SettingsActivity.this, snapshot);
            }
        }, "lch-config-save").start();
        toast("已保存，实时生效（若锁屏正显示，息屏再亮屏即可看到）");
    }

    // ==================== 操作 ====================

    private void requestDump() {
        mPrefs.edit().putLong(ConfigStore.KEY_DUMP_TOKEN, System.currentTimeMillis()).apply();
        commit();
        toast("已发送抓取请求：请息屏 → 亮屏回到锁屏停 2 秒，再点「查看模块日志」");
    }

    private void confirmRestartSystemUi() {
        new AlertDialog.Builder(this)
                .setTitle("重启 SystemUI？")
                .setMessage("屏幕会短暂黑一下并回到锁屏，属正常现象。用于让 Hook 重新挂载。")
                .setPositiveButton("重启", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        restartSystemUi();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void restartSystemUi() {
        toast("正在重启 SystemUI…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out = ConfigWriter.runRoot("pkill -f com.android.systemui; echo LCH-RESTART-DONE", 20);
                final boolean ok = out != null && out.contains("LCH-RESTART-DONE");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(ok ? "已发送重启指令，稍候即生效" : "重启失败：请确认已授予 Root 权限");
                    }
                });
            }
        }, "lch-restart").start();
    }

    private void showLogs(final boolean brief) {
        toast(brief ? "正在采集精简日志…" : "正在采集完整日志（可能需要十几秒）…");
        collectLogs(brief, new LogCallback() {
            @Override
            public void done(final String text) {
                ScrollView sv = new ScrollView(SettingsActivity.this);
                final TextView tv = new TextView(SettingsActivity.this);
                tv.setText(text);
                tv.setTextSize(10);
                tv.setTextColor(getColor(R.color.text_primary));
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextIsSelectable(true);
                int pad = dp(12);
                tv.setPadding(pad, pad, pad, pad);
                sv.addView(tv);
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(brief ? "精简日志" : "模块日志")
                        .setView(sv)
                        .setPositiveButton("关闭", null)
                        .setNeutralButton("导出到 txt", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                exportText(text);
                            }
                        })
                        .show();
            }
        });
    }

    private interface LogCallback {
        void done(String text);
    }

    private void collectLogs(final boolean brief, final LogCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder sb = new StringBuilder();
                sb.append("LegionClockHider 诊断报告\n");
                sb.append("时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date())).append('\n');
                sb.append("配置：").append(ConfigStore.describe()).append('\n');
                Diag.Section[] sections = brief ? Diag.briefSections() : Diag.sections();
                for (Diag.Section s : sections) {
                    sb.append("\n================ ").append(s.label).append(" ================\n");
                    sb.append("$ ").append(s.command).append('\n');
                    String out = null;
                    try {
                        out = ConfigWriter.runRoot(s.command, s.timeoutSec);
                    } catch (Throwable t) {
                        out = "(执行异常: " + t + ")";
                    }
                    sb.append(out == null || out.trim().isEmpty() ? "(无输出)" : out.trim()).append('\n');
                }
                final String text = sb.toString();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(text);
                    }
                });
            }
        }, "lch-log-collect").start();
    }

    private void exportLogs() {
        toast("正在采集并导出…");
        collectLogs(false, new LogCallback() {
            @Override
            public void done(String text) {
                exportText(text);
            }
        });
    }

    private void exportText(String text) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getCacheDir();
            String name = "legionclockhider-log-"
                    + new SimpleDateFormat("MMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
            File out = new File(dir, name);
            OutputStreamWriter w = null;
            try {
                w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
                w.write(text);
            } finally {
                if (w != null) {
                    try {
                        w.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            Uri uri = LogFileProvider.uriFor(this, out);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "LegionClockHider 日志");
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            toast("已导出：" + out.getAbsolutePath());
            startActivity(Intent.createChooser(share, "分享日志"));
        } catch (Throwable t) {
            toast("导出失败：" + t);
        }
    }

    // ==================== 小工具 ====================

    private void applyPill(TextView pill, boolean on) {
        pill.setText(on ? "已开启" : "已关闭");
        pill.setTextColor(getColor(on ? R.color.pill_on_fg : R.color.pill_off_fg));
        pill.setBackgroundResource(on ? R.drawable.bg_pill_on : R.drawable.bg_pill_off);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 供 Activity 之外（如以后加广播）复用。 */
    public static Context appContext(Context ctx) {
        return ctx.getApplicationContext();
    }
}
