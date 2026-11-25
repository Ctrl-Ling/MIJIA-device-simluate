/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 20:38:14 +08:00
 */
package com.mijia.devicesim.hook;

import android.content.Context;
import android.content.SharedPreferences;

import com.mijia.devicesim.core.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「本地才有」的自动化场景登记表。
 *
 * <p>大白话：假设备所在的家庭在云端根本不存在，米家把场景请求发上去必然失败，
 * 所以这类场景只能由模块在客户端就地判成功（见 {@link SceneCreateLocalHook}）。
 * 判成功以后米家自己只往内存里放一份，重启就没了，而且「智能」tab 的列表是整条云端
 * 链路下发的 —— 云端不知道这条场景，列表里就永远看不到。
 *
 * <p>所以这里把「我们替云端认下来的场景」落一份在宿主进程的 SharedPreferences 里：
 * 保存成功时写一条（id / 名字 / 家庭），列表解析完以后按这张表补行
 * （见 {@link SceneListLocalHook}）。存宿主自己的目录，米家进程和模块界面进程各写各的，
 * 不碰 SELinux，也不用 Provider。
 *
 * <p>线程约定：写和读都加同一把锁；persist 在调用线程直接写盘（数据量只有几条，
 * 保存动作本来也不在热路径上）。
 */
public final class LocalSceneStore {

    private static final String PREF_NAME = "mijia_devicesim_local_scenes";
    private static final String KEY_SCENES = "scenes";
    private static final int MAX_RECORDS = 200;

    private static final Object LOCK = new Object();
    private static final List<Record> sRecords = new ArrayList<>();
    private static volatile boolean sLoaded;

    private LocalSceneStore() {
    }

    /** 一条本地场景：列表注入只要 id / 名字 / 家庭，其余字段都是给日志看的。*/
    public static final class Record {
        public String id = "";
        public String name = "";
        public String homeId = "";
        public long createdAt = 0L;

        @Override
        public String toString() {
            return "Record{id=" + id + ", name=" + name + ", home=" + homeId + "}";
        }
    }

    /**
     * 新的本地场景 id：9 开头 9 位数字，稳定落在 int 范围内
     * （米家有些旧字段是 int，时间戳会溢出），并且和真实 scene_id 一眼分得开。
     */
    public static String newSceneId() {
        return String.format(Locale.ROOT, "9%08d", System.currentTimeMillis() % 100_000_000L);
    }

    /** 登记一条（同 id 覆盖）。返回是否真的写进去了。*/
    public static boolean add(String id, String name, String homeId) {
        if (id == null || id.isEmpty()) return false;
        synchronized (LOCK) {
            ensureLoadedLocked();
            Record r = findLocked(id);
            if (r == null) {
                r = new Record();
                r.id = id;
                r.createdAt = System.currentTimeMillis();
                sRecords.add(r);
            }
            if (name != null && !name.isEmpty()) r.name = name;
            if (homeId != null && !homeId.isEmpty()) r.homeId = homeId;
            while (sRecords.size() > MAX_RECORDS) sRecords.remove(0);
            persistLocked();
            Log.d("本地场景登记: " + r + " 共 " + sRecords.size() + " 条");
            return true;
        }
    }

    /** 当前登记表的快照（拷贝，调用方随便改）。*/
    public static List<Record> snapshot() {
        synchronized (LOCK) {
            ensureLoadedLocked();
            return new ArrayList<>(sRecords);
        }
    }

    /* ---------- 内部 ---------- */

    private static Record findLocked(String id) {
        for (Record r : sRecords) if (id.equals(r.id)) return r;
        return null;
    }

    private static void ensureLoadedLocked() {
        if (sLoaded) return;
        sLoaded = true;
        SharedPreferences sp = prefs();
        if (sp == null) return;
        String raw = sp.getString(KEY_SCENES, "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Record r = new Record();
                r.id = o.optString("id", "");
                r.name = o.optString("name", "");
                r.homeId = o.optString("home", "");
                r.createdAt = o.optLong("ts", 0L);
                if (!r.id.isEmpty()) sRecords.add(r);
            }
            Log.d("本地场景表恢复 " + sRecords.size() + " 条");
        } catch (Throwable t) {
            Log.w("本地场景表解析失败（按空表继续）: " + t);
        }
    }

    private static void persistLocked() {
        SharedPreferences sp = prefs();
        if (sp == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Record r : sRecords) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("name", r.name == null ? "" : r.name);
                o.put("home", r.homeId == null ? "" : r.homeId);
                o.put("ts", r.createdAt);
                arr.put(o);
            }
            sp.edit().putString(KEY_SCENES, arr.toString()).apply();
        } catch (Throwable t) {
            Log.w("本地场景表写入失败: " + t);
        }
    }

    private static SharedPreferences prefs() {
        Context ctx = SimContextHolder.get();
        if (ctx == null) return null;
        try {
            return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }
}