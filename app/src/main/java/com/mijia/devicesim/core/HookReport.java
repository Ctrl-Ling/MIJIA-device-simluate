/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 12:28:06 +08:00
 */
package com.mijia.devicesim.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 钩子命中报告：哪个钩子点挂上了、跳过了还是失败了。
 * plan Phase 6 要求「失败时降级不影响真实设备」，前提就是失败要看得见 ——
 * 米家升级把类名改掉以后，界面这一页会直接告诉你是哪一行黄了。
 */
public final class HookReport {

    private HookReport() {
    }

    public static final String OK = "OK";
    public static final String SKIP = "SKIP";
    public static final String FAIL = "FAIL";

    private static final List<Row> sRows = new ArrayList<>();
    private static volatile String sProcess = "";

    public static class Row {
        public final String group;
        public final String target;
        public final String status;
        public final String detail;
        public final long ts = System.currentTimeMillis();

        Row(String group, String target, String status, String detail) {
            this.group = group;
            this.target = target;
            this.status = status;
            this.detail = detail;
        }
    }

    public static void ok(String group, String target, String detail) {
        add(group, target, OK, detail);
    }

    public static void skip(String group, String target, String detail) {
        add(group, target, SKIP, detail);
    }

    public static void fail(String group, String target, String detail) {
        add(group, target, FAIL, detail);
    }

    private static synchronized void add(String group, String target, String status, String detail) {
        sRows.add(new Row(group, target, status, detail));
        Log.v("钩子[" + status + "] " + group + " :: " + target + (detail == null ? "" : " :: " + detail));
    }

    public static void setProcess(String processName) {
        sProcess = processName == null ? "" : processName;
    }

    public static synchronized String snapshotJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("process", sProcess);
            root.put("moduleVersion", moduleVersion());
            root.put("capturedAt", System.currentTimeMillis());
            /* 米家侧「到底读到了什么」——界面拿这三行就能判断是不是配置通道没通，
               而不是让人去猜为什么首页空。 */
            SimConfig cfg = ConfigRepository.current();
            root.put("configSource", ConfigRepository.source());
            root.put("configDevices", cfg.size());
            root.put("injectDevices", cfg.enabledDevices().size());
            root.put("injectEnabled", cfg.injectEnabled);
            /* 他在米家向导里刚选的那个型号（米家进程里的现场状态）。
               空字符串 = 现在没有正在进行的添加流程。 */
            root.put("requestedModel", RequestedModel.describe());
            JSONArray rows = new JSONArray();
            for (Row r : sRows) {
                JSONObject o = new JSONObject();
                o.put("group", r.group);
                o.put("target", r.target);
                o.put("status", r.status);
                o.put("detail", r.detail == null ? "" : r.detail);
                o.put("ts", r.ts);
                rows.put(o);
            }
            root.put("rows", rows);
            return root.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    public static synchronized int count(String status) {
        int n = 0;
        for (Row r : sRows) if (r.status.equals(status)) n++;
        return n;
    }

    /** 报告落库，界面读。*/
    public static void publish(Context ctx) {
        ConfigChannel.writeReport(ctx, snapshotJson());
    }

    private static String moduleVersion() {
        Context ctx = ConfigRepository.context();
        if (ctx == null) return "?";
        try {
            return ctx.getPackageManager().getPackageInfo(Consts.MODULE_PACKAGE, 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
