/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
 */
package com.mijia.devicesim.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

import com.mijia.devicesim.core.ConfigChannel;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 配置的唯一落地点，跑在本模块进程里。所有数据都是 UTF-8 JSON 文件：
 * <pre>
 *   files/config.json          设备清单（唯一数据源）
 *   files/state.json           每台假设备的属性实时值
 *   files/specs/&lt;model&gt;.json   型号 MIoT spec 库
 *   files/report.json          钩子命中报告（Hook 层写，界面读）
 *   files/calllog.json         最近控制调用记录（Hook 层写，界面读）
 * </pre>
 * 写入后 notifyChange，米家进程里注册的 ContentObserver 立刻重载，不需要重编模块或杀进程。
 */
public class ConfigProvider extends ContentProvider {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String FILE_CONFIG = "config.json";
    private static final String FILE_STATE = "state.json";
    private static final String FILE_REPORT = "report.json";
    private static final String FILE_CALLLOG = "calllog.json";
    private static final String DIR_SPECS = "specs";
    /** 单个 Blob 上限：spec 最大也就几百 KB，1MB 足够，超出直接拒绝而不是截断。*/
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/json";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String path = uri.getPath();
        MatrixCursor cursor = new MatrixCursor(new String[]{"path", "payload"});
        if (Consts.PATH_SPEC.equals(path)) {
            for (String model : listSpecModels()) cursor.addRow(new Object[]{model, null});
            return cursor;
        }
        cursor.addRow(new Object[]{path, read(fileForSpecOrPlain(path, uri))});
        return cursor;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle reply = new Bundle();
        String payload = extras == null ? null : extras.getString(ConfigChannel.EXTRA_PAYLOAD);
        try {
            switch (method == null ? "" : method) {
                case Consts.CALL_PING:
                    reply.putString(ConfigChannel.EXTRA_PAYLOAD, "1");
                    break;
                case Consts.CALL_GET_CONFIG:
                    reply.putString(ConfigChannel.EXTRA_PAYLOAD, read(FILE_CONFIG));
                    break;
                case Consts.CALL_PUT_CONFIG:
                    reply.putBoolean(ConfigChannel.EXTRA_OK, write(FILE_CONFIG, payload));
                    notify(Consts.PATH_CONFIG);
                    break;
                case Consts.CALL_GET_STATE:
                    reply.putString(ConfigChannel.EXTRA_PAYLOAD, read(FILE_STATE));
                    break;
                case Consts.CALL_PUT_STATE:
                    reply.putBoolean(ConfigChannel.EXTRA_OK, write(FILE_STATE, payload));
                    notify(Consts.PATH_STATE);
                    break;
                case Consts.CALL_GET_SPEC:
                    reply.putString(ConfigChannel.EXTRA_PAYLOAD, readSpec(arg));
                    break;
                case Consts.CALL_PUT_SPEC:
                    reply.putBoolean(ConfigChannel.EXTRA_OK, writeSpec(arg, payload));
                    notify(Consts.PATH_SPEC);
                    break;
                case Consts.CALL_LIST_SPEC:
                    reply.putString(ConfigChannel.EXTRA_PAYLOAD, specManifest());
                    break;
                case Consts.CALL_DELETE_SPEC:
                    reply.putBoolean(ConfigChannel.EXTRA_OK, deleteSpec(arg));
                    notify(Consts.PATH_SPEC);
                    break;
                case Consts.CALL_GET_REPORT:
                    reply.putString(ConfigChannel.EXTRA_PAYLOAD, read(FILE_REPORT));
                    break;
                case Consts.CALL_PUT_REPORT:
                    reply.putBoolean(ConfigChannel.EXTRA_OK, write(FILE_REPORT, payload));
                    notify(Consts.PATH_REPORT);
                    break;
                case Consts.CALL_GET_CALLLOG:
                    reply.putString(ConfigChannel.EXTRA_PAYLOAD, read(FILE_CALLLOG));
                    break;
                case Consts.CALL_PUT_CALLLOG:
                    reply.putBoolean(ConfigChannel.EXTRA_OK, write(FILE_CALLLOG, payload));
                    notify(Consts.PATH_CALLLOG);
                    break;
                default:
                    Log.w("Provider 收到未知方法: " + method);
                    reply.putBoolean(ConfigChannel.EXTRA_OK, false);
            }
        } catch (Throwable t) {
            Log.e("Provider call " + method + " 异常", t);
            reply.putBoolean(ConfigChannel.EXTRA_OK, false);
        }
        return reply;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("use call()");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("use call()");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("use call()");
    }

    /* ---------- 文件操作 ---------- */

    private Context appContext() {
        Context c = getContext();
        return c != null ? c.getApplicationContext() : null;
    }

    private File root() {
        return appContext().getFilesDir();
    }

    private String fileForSpecOrPlain(String path, Uri uri) {
        if (Consts.PATH_CONFIG.equals(path)) return FILE_CONFIG;
        if (Consts.PATH_STATE.equals(path)) return FILE_STATE;
        if (Consts.PATH_REPORT.equals(path)) return FILE_REPORT;
        if (Consts.PATH_CALLLOG.equals(path)) return FILE_CALLLOG;
        List<String> seg = uri.getPathSegments();
        if (seg.size() >= 2 && Consts.PATH_SPEC.substring(1).equals(seg.get(0))) return specFile(seg.get(1));
        return FILE_CONFIG;
    }

    private String specFile(String model) {
        return DIR_SPECS + File.separator + ConfigChannel.safeFileName(model) + ".json";
    }

    private String read(String rel) {
        if (rel == null) return null;
        File f = new File(root(), rel);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(64, f.length()));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String s = new String(bos.toByteArray(), UTF8);
            return s.trim().isEmpty() ? null : s;
        } catch (Throwable t) {
            Log.e("Provider 读取失败 " + rel, t);
            return null;
        }
    }

    private boolean write(String rel, String payload) {
        if (payload == null) return false;
        if (payload.getBytes(UTF8).length > MAX_BYTES) {
            Log.w("Provider 拒绝写入超大内容 " + rel);
            return false;
        }
        File f = new File(root(), rel);
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        // 先写 .tmp 再 rename：避免米家进程正好在读时拿到半截 JSON
        File tmp = new File(parent, f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(payload.getBytes(UTF8));
            out.flush();
        } catch (Throwable t) {
            Log.e("Provider 写入失败 " + rel, t);
            return false;
        }
        if (!tmp.renameTo(f)) {
            if (!f.delete() || !tmp.renameTo(f)) {
                Log.w("Provider rename 失败 " + rel);
                return false;
            }
        }
        return true;
    }

    private String readSpec(String model) {
        if (model == null || model.isEmpty()) return null;
        return read(specFile(model));
    }

    private boolean writeSpec(String model, String payload) {
        if (model == null || model.isEmpty() || payload == null) return false;
        return write(specFile(model), payload);
    }

    private boolean deleteSpec(String model) {
        if (model == null || model.isEmpty()) return false;
        File f = new File(root(), specFile(model));
        return !f.exists() || f.delete();
    }

    private List<String> listSpecModels() {
        List<String> out = new ArrayList<>();
        File dir = new File(root(), DIR_SPECS);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File f : files) out.add(f.getName().substring(0, f.getName().length() - 5));
        }
        return out;
    }

    /** 型号库清单：model 名从 spec 内容里取，文件名只是索引。*/
    private String specManifest() {
        StringBuilder sb = new StringBuilder("[");
        File dir = new File(root(), DIR_SPECS);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            Arrays.sort(files);
            boolean first = true;
            for (File f : files) {
                String json = read(DIR_SPECS + File.separator + f.getName());
                if (json == null) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"model\":\"").append(escape(f.getName().substring(0, f.getName().length() - 5)))
                        .append("\",\"bytes\":").append(json.length()).append('}');
            }
        }
        return sb.append(']').toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void notify(String path) {
        try {
            Context c = appContext();
            if (c == null) return;
            c.getContentResolver().notifyChange(ConfigChannel.uri(path), null);
        } catch (Throwable t) {
            Log.v("notifyChange 失败 " + path + ": " + t);
        }
    }
}
