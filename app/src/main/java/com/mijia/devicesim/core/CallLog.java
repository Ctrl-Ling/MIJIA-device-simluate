/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
 */
package com.mijia.devicesim.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 控制调用流水（plan Phase 2B 的「最近的控制调用记录」）。
 * 记的是米家面板真的调了什么：入参、出参、耗时，界面调试面板直接读这份。
 */
public final class CallLog {

    private CallLog() {
    }

    public static final String OP_GET = "GET";
    public static final String OP_SET = "SET";
    public static final String OP_ACTION = "ACTION";
    public static final String OP_SUBSCRIBE = "SUBSCRIBE";
    public static final String OP_INJECT = "INJECT";
    public static final String OP_EVENT = "EVENT";
    /** 「完成添加」短路后本地落库，界面上单独一种颜色。*/
    public static final String OP_ACCEPT = "ACCEPT";

    private static final int CAP = 200;
    private static final Deque<Entry> sEntries = new ArrayDeque<>();
    private static volatile Handler sHandler;

    public static class Entry {
        public final long ts = System.currentTimeMillis();
        public final String op;
        public final String did;
        public final String model;
        public final String key;
        public final String input;
        public final String output;
        public final long costMs;
        public final String note;

        Entry(String op, String did, String model, String key, String input, String output, long costMs, String note) {
            this.op = op;
            this.did = did;
            this.model = model;
            this.key = key;
            this.input = input;
            this.output = output;
            this.costMs = costMs;
            this.note = note;
        }

        JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("ts", ts);
                o.put("op", op);
                o.put("did", did == null ? "" : did);
                o.put("model", model == null ? "" : model);
                o.put("key", key == null ? "" : key);
                o.put("in", input == null ? "" : input);
                o.put("out", output == null ? "" : output);
                o.put("ms", costMs);
                o.put("note", note == null ? "" : note);
                return o;
            } catch (Throwable t) {
                return new JSONObject();
            }
        }
    }

    public static void record(Context ctx, String op, String did, String model, String key,
                              Object input, Object output, long costMs, String note) {
        Entry e = new Entry(op, did, model, key, brief(input), brief(output), costMs, note);
        synchronized (sEntries) {
            sEntries.addFirst(e);
            while (sEntries.size() > CAP) sEntries.removeLast();
        }
        Log.v(op + " " + did + " " + key + " in=" + e.input + " out=" + e.output + " " + costMs + "ms");
        scheduleFlush(ctx);
    }

    /** 最近 n 条，新的在前。*/
    public static List<Entry> recent(int n) {
        List<Entry> out = new ArrayList<>();
        synchronized (sEntries) {
            for (Entry e : sEntries) {
                out.add(e);
                if (out.size() >= n) break;
            }
        }
        return out;
    }

    public static String snapshotJson() {
        JSONArray a = new JSONArray();
        synchronized (sEntries) {
            for (Entry e : sEntries) a.put(e.toJson());
        }
        return a.toString();
    }

    public static void clear() {
        synchronized (sEntries) {
            sEntries.clear();
        }
    }

    /** 面板上显示一行就够，长数组截断。*/
    private static String brief(Object o) {
        if (o == null) return "";
        String s = o instanceof JSONObject || o instanceof JSONArray ? String.valueOf(o) : String.valueOf(o);
        if (s.length() > 160) return s.substring(0, 157) + "...";
        return s;
    }

    private static void scheduleFlush(Context ctx) {
        if (ctx == null) return;
        Looper looper = Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper();
        Handler h = sHandler;
        if (h == null || h.getLooper() != looper) {
            h = new Handler(looper);
            sHandler = h;
        }
        final Handler fh = h;
        fh.removeCallbacksAndMessages(null);
        fh.postDelayed(() -> {
            try {
                ConfigChannel.writeCallLog(ctx, snapshotJson());
            } catch (Throwable t) {
                Log.v("调用流水写回失败: " + t);
            }
        }, 800L);
    }
}
