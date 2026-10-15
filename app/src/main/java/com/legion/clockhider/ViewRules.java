package com.legion.clockhider;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视图规则引擎：判断一个 View 是"时钟 / 日期 / 星期 / 农历"中的哪些，并按配置隐藏它。
 *
 * 为什么要按"文本内容"判断，而不是写死控件 id：
 *  - 各机型/各 ZUXOS 版本里锁屏时钟的控件 id 与类名都可能不一样（本模块已按 ZUI 的
 *    AnimatableClockViewWithDate 为第一目标，但仍需要兜底）；
 *  - "9:41"、"9月12日"、"星期五" 这些文本形态在任何固件上都稳定出现，识别它比猜 id 可靠。
 *
 * 隐藏策略：
 *  - 一个 TextView 同时包含日期+星期时（"9月12日 星期五"），可以只去掉星期保留日期，
 *    做法是把文本里对应片段正则删除，而不是整块隐藏；原文本会被记下来，关掉开关即还原。
 */
public final class ViewRules {

    public static final int K_CLOCK = 1;
    public static final int K_DATE = 2;
    public static final int K_WEEKDAY = 4;
    public static final int K_LUNAR = 8;

    private static final Pattern TIME_RE =
            Pattern.compile("\\d{1,2}\\s*[:：]\\s*\\d{2}(\\s*[:：]\\s*\\d{2})?");
    private static final Pattern WEEKDAY_RE =
            Pattern.compile("(星期|周|礼拜)\\s*[一二三四五六日天]");
    private static final Pattern DATE_RE = Pattern.compile(
            "(\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日)"
                    + "|(\\d{4}\\s*[-/年]\\s*\\d{1,2}\\s*[-/月]\\s*\\d{1,2})"
                    + "|(\\d{1,2}\\s*[-/]\\s*\\d{1,2})");
    private static final Pattern LUNAR_RE = Pattern.compile(
            "(农历|阴历|闰[一二三四五六七八九十]?月|腊月|冬月|正月"
                    + "|廿[一二三四五六七八九十]|初[一二三四五六七八九十])");
    /** 去掉替换后残留的首尾空白与连接符。 */
    private static final Pattern EDGE_JUNK = Pattern.compile(
            "^[\\s\\p{Punct}，。、·|/\\\\-]+|[\\s\\p{Punct}，。、·|/\\\\-]+$");

    /** 我们主动改过的控件 → 原始文本（关闭开关时还原）。 */
    private static final Map<TextView, String> ORIGINALS =
            Collections.synchronizedMap(new WeakHashMap<TextView, String>());
    /** 我们写上去的文本（用于判断"系统是否又写了新文本"）。 */
    private static final Map<TextView, String> APPLIED =
            Collections.synchronizedMap(new WeakHashMap<TextView, String>());
    /** 我们主动隐藏的控件 → 我们设置的可见性值。 */
    private static final Map<View, Integer> HIDDEN =
            Collections.synchronizedMap(new WeakHashMap<View, Integer>());

    private ViewRules() {
    }

    // ==================== 识别 ====================

    /** 判断视图内容类型（位掩码，0 = 与时钟/日期无关）。 */
    public static int classify(View v) {
        int kind = 0;
        String cls = v.getClass().getName().toLowerCase(Locale.ROOT);
        String id = idName(v);

        if (cls.contains("clock") || contains(id, "clock") || contains(id, "time")) kind |= K_CLOCK;
        if (cls.contains("date") || contains(id, "date")) kind |= K_DATE;
        if (cls.contains("week") || contains(id, "week")) kind |= K_WEEKDAY;
        if (cls.contains("lunar") || contains(id, "lunar") || contains(id, "nongli")) kind |= K_LUNAR;

        if (v instanceof TextView) {
            String t = text(v);
            if (t != null && !t.isEmpty() && t.length() < 200) {
                if (TIME_RE.matcher(t).find()) kind |= K_CLOCK;
                if (WEEKDAY_RE.matcher(t).find()) kind |= K_WEEKDAY;
                if (DATE_RE.matcher(t).find()) kind |= K_DATE;
                if (LUNAR_RE.matcher(t).find()) kind |= K_LUNAR;
            }
        }
        return kind;
    }

    public static String kindName(int kind) {
        if (kind == 0) return "无关";
        StringBuilder sb = new StringBuilder();
        if ((kind & K_CLOCK) != 0) sb.append("时钟");
        if ((kind & K_DATE) != 0) sb.append(sb.length() == 0 ? "日期" : "+日期");
        if ((kind & K_WEEKDAY) != 0) sb.append(sb.length() == 0 ? "星期" : "+星期");
        if ((kind & K_LUNAR) != 0) sb.append(sb.length() == 0 ? "农历" : "+农历");
        return sb.toString();
    }

    /** 这个类名是否允许"整体隐藏"（即它本身就是时钟/日期容器，隐藏不会波及别的元素）。 */
    public static boolean wholesaleSafe(String className) {
        String n = className.toLowerCase(Locale.ROOT);
        if (n.contains("statusview") || n.contains("status_view")) return false;
        if (n.contains("slice") || n.contains("weather") || n.contains("notification")) return false;
        return n.contains("withdate")
                || n.endsWith("animatableclockview")
                || n.contains("lockscreenclock")
                || n.contains("keyguardclockview");
    }

    // ==================== 应用 ====================

    /**
     * 对一个"疑似锁屏时钟"的视图执行隐藏策略。
     *
     * @param root           目标视图（时钟容器）
     * @param cfg            配置快照
     * @param wholesaleAllow 是否允许用"整体隐藏"方式处理它
     * @return 本次实际隐藏/改写的视图数量
     */
    public static int apply(View root, ConfigStore.Snapshot cfg, boolean wholesaleAllow) {
        if (root == null) return 0;
        int vis = (cfg.visibility == ConfigStore.VIS_INVISIBLE)
                ? View.INVISIBLE : View.GONE;

        if (cfg.mode == ConfigStore.MODE_WHOLE && wholesaleAllow) {
            boolean changed = setVis(root, vis);
            return changed ? 1 : 0;
        }

        List<View> views = collect(root, 80);
        boolean includeRoot = !(root instanceof ViewGroup)
                || ((ViewGroup) root).getChildCount() == 0;
        int touched = 0;
        for (View v : views) {
            if (v == root && !includeRoot) continue;
            int kind = classify(v);
            if (kind == 0) continue;
            if (applyOne(v, kind, cfg, vis)) touched++;
        }
        return touched;
    }

    /** 单项处理：内容全部要隐藏 → 隐藏视图；只隐藏一部分 → 删掉对应文本片段。 */
    private static boolean applyOne(View v, int kind, ConfigStore.Snapshot cfg, int vis) {
        int hideBits = 0;
        if (cfg.clock) hideBits |= K_CLOCK;
        if (cfg.date) hideBits |= K_DATE;
        if (cfg.weekday) hideBits |= K_WEEKDAY;
        if (cfg.lunar) hideBits |= K_LUNAR;

        // 该视图承载的内容全部要求隐藏 → 直接隐藏视图
        if ((kind & ~hideBits) == 0) {
            return setVis(v, vis);
        }
        if (!(v instanceof TextView)) {
            return false;
        }

        TextView tv = (TextView) v;
        String current = text(tv);
        String original = ORIGINALS.get(tv);
        String applied = APPLIED.get(tv);
        // 系统又写入过新文本（或首次处理）→ 以当前文本为新基准
        if (original == null || (applied != null && !applied.equals(current))) {
            original = current;
            if (original != null) ORIGINALS.put(tv, original);
            APPLIED.remove(tv);
            applied = null;
        }
        if (original == null || original.isEmpty()) {
            return false;
        }

        String r = original;
        if (cfg.clock) r = TIME_RE.matcher(r).replaceAll("");
        if (cfg.weekday) r = WEEKDAY_RE.matcher(r).replaceAll("");
        if (cfg.date) r = DATE_RE.matcher(r).replaceAll("");
        if (cfg.lunar) r = LUNAR_RE.matcher(r).replaceAll("");
        r = tidy(r);

        boolean changed = false;
        if (r.isEmpty()) {
            changed = setVis(tv, vis);
            if (!changed) changed = unhide(tv);
        } else {
            if (unhide(tv)) changed = true;
            if (!r.equals(current)) {
                setText(tv, r);
                APPLIED.put(tv, r);
                changed = true;
            } else if (applied == null) {
                APPLIED.remove(tv);
            }
        }
        return changed;
    }

    /** 还原一个视图（关闭模块 / 关闭开关时调用）。 */
    public static void restore(View v) {
        if (v == null) return;
        unhide(v);
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            String original = ORIGINALS.remove(tv);
            APPLIED.remove(tv);
            if (original != null && !original.equals(text(tv))) {
                setText(tv, original);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                restore(g.getChildAt(i));
            }
        }
    }

    // ==================== 诊断：打印视图树 ====================

    /** 打印目标视图的祖先链 + 子树（定位真实类名/id/文本用）。 */
    public static String dump(View v, int maxDepth, int maxLines) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("[VIEWTREE] === 锁屏时钟视图树（LegionClockHider 诊断）===\n");
        List<View> chain = new ArrayList<>();
        try {
            ViewParent p = v.getParent();
            int guard = 0;
            while (p instanceof View && guard++ < 8) {
                chain.add(0, (View) p);
                p = ((View) p).getParent();
            }
        } catch (Throwable ignored) {
        }
        sb.append("祖先链（由外到内）:\n");
        for (View a : chain) sb.append("  ").append(describe(a)).append('\n');
        sb.append("目标视图:\n");
        int[] budget = {maxLines};
        walk(v, 0, maxDepth, sb, budget);
        if (budget[0] <= 0) sb.append("  ...(已截断)\n");
        return sb.toString();
    }

    private static void walk(View v, int depth, int maxDepth, StringBuilder sb, int[] budget) {
        if (v == null || budget[0] <= 0) return;
        budget[0]--;
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) indent.append("  ");
        sb.append(indent).append('[').append(depth).append("] ").append(describe(v)).append('\n');
        if (depth >= maxDepth) return;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), depth + 1, maxDepth, sb, budget);
            }
        }
    }

    public static String describe(View v) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(simpleName(v));
        String id = idName(v);
        if (id != null) {
            sb.append(" id=").append(id);
        }
        try {
            sb.append(" (0x").append(Integer.toHexString(v.getId())).append(')');
        } catch (Throwable ignored) {
        }
        sb.append(" vis=").append(visName(v.getVisibility()));
        sb.append(" kind=").append(kindName(classify(v)));
        if (v instanceof TextView) {
            String t = text(v);
            if (t != null && !t.isEmpty()) {
                sb.append(" text=\"").append(t.length() > 40 ? t.substring(0, 40) + "…" : t).append('"');
            }
        }
        return sb.toString();
    }

    // ==================== 小工具 ====================

    private static List<View> collect(View root, int cap) {
        List<View> out = new ArrayList<>();
        ArrayList<View> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty() && out.size() < cap) {
            View v = stack.remove(stack.size() - 1);
            out.add(v);
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    stack.add(g.getChildAt(i));
                }
            }
        }
        return out;
    }

    private static boolean setVis(View v, int vis) {
        try {
            HIDDEN.put(v, vis);
            if (v.getVisibility() != vis) {
                v.setVisibility(vis);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean unhide(View v) {
        Integer mine = HIDDEN.remove(v);
        if (mine == null) return false;
        try {
            if (v.getVisibility() != View.VISIBLE) {
                v.setVisibility(View.VISIBLE);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void setText(TextView tv, String s) {
        try {
            tv.setText(s);
        } catch (Throwable ignored) {
        }
    }

    private static String text(View v) {
        if (!(v instanceof TextView)) return null;
        try {
            CharSequence cs = ((TextView) v).getText();
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String tidy(String s) {
        String r = s.replaceAll("\\s+", " ");
        Matcher m = EDGE_JUNK.matcher(r);
        return m.replaceAll("").trim();
    }

    private static String idName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return null;
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean contains(String s, String part) {
        return s != null && s.contains(part);
    }

    private static String simpleName(View v) {
        try {
            return v.getClass().getSimpleName();
        } catch (Throwable t) {
            return v.getClass().getName();
        }
    }

    private static String visName(int vis) {
        switch (vis) {
            case View.VISIBLE:
                return "VISIBLE";
            case View.INVISIBLE:
                return "INVISIBLE";
            case View.GONE:
                return "GONE";
            default:
                return String.valueOf(vis);
        }
    }

    /** 去重用的视图身份集合（弱引用）。 */
    public static java.util.Set<View> newViewSet() {
        return Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    }

    /** 快速身份表（用于需要"同一实例"语义的场景）。 */
    public static Map<View, Object> newIdentityMap() {
        return new IdentityHashMap<>();
    }
}
