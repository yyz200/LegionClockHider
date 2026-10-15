package com.legion.clockhider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/**
 * 配置跨进程通道：被 Hook 的 SystemUI 进程通过 ContentResolver + ContentObserver 只读读取配置。
 * 只暴露几个开关数值（不含任何用户数据）；写入仅允许模块自身 uid，防第三方篡改。
 */
public class PrefsProvider extends ContentProvider {

    private static final String[] COLUMNS = {"_id"};
    private static final Object[] ROW = {1};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            Context ctx = getContext();
            if (ctx == null) return null;
            SharedPreferences sp = ctx.getSharedPreferences(
                    ConfigStore.PREFS_NAME, Context.MODE_PRIVATE);
            MatrixCursor mc = new MatrixCursor(COLUMNS);
            Bundle b = new Bundle();
            b.putBoolean(ConfigStore.KEY_ENABLED, sp.getBoolean(ConfigStore.KEY_ENABLED, false));
            b.putInt(ConfigStore.KEY_MODE, sp.getInt(ConfigStore.KEY_MODE, ConfigStore.MODE_WHOLE));
            b.putBoolean(ConfigStore.KEY_CLOCK, sp.getBoolean(ConfigStore.KEY_CLOCK, true));
            b.putBoolean(ConfigStore.KEY_DATE, sp.getBoolean(ConfigStore.KEY_DATE, true));
            b.putBoolean(ConfigStore.KEY_WEEKDAY, sp.getBoolean(ConfigStore.KEY_WEEKDAY, true));
            b.putBoolean(ConfigStore.KEY_LUNAR, sp.getBoolean(ConfigStore.KEY_LUNAR, false));
            b.putInt(ConfigStore.KEY_VIS, sp.getInt(ConfigStore.KEY_VIS, ConfigStore.VIS_GONE));
            b.putBoolean(ConfigStore.KEY_AUTO, sp.getBoolean(ConfigStore.KEY_AUTO, true));
            b.putBoolean(ConfigStore.KEY_AGGRESSIVE,
                    sp.getBoolean(ConfigStore.KEY_AGGRESSIVE, false));
            b.putBoolean(ConfigStore.KEY_STATUSBAR,
                    sp.getBoolean(ConfigStore.KEY_STATUSBAR, false));
            b.putString(ConfigStore.KEY_EXTRA, sp.getString(ConfigStore.KEY_EXTRA, ""));
            b.putLong(ConfigStore.KEY_DUMP_TOKEN, sp.getLong(ConfigStore.KEY_DUMP_TOKEN, 0L));
            mc.setExtras(b);
            mc.addRow(ROW);
            return mc;
        } catch (Throwable t) {
            HookLog.e("PrefsProvider: query 失败", t);
            return null;
        }
    }

    private static boolean mutateOnlySelf() {
        return Binder.getCallingUid() == Process.myUid();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return mutateOnlySelf() ? uri : null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd." + ConfigStore.MODULE_PACKAGE + ".prefs";
    }
}
