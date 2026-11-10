/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 10:40:36 +08:00
 */
package com.mijia.devicesim.ui;

import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.mijia.devicesim.R;
import com.mijia.devicesim.core.ConfigChannel;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.MiotSpec;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;
import com.mijia.devicesim.core.SpecRepository;
import com.mijia.devicesim.databinding.ActivityDebugBinding;
import com.mijia.devicesim.databinding.RowPropBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 实时调试面板：钩子命中报告 + 每台设备的属性状态 + 最近控制调用流水。
 *
 * <p>为什么值得单独一页：plan Phase 6 要求「失败时降级但不影响真实设备」，前提是失败看得见。
 * 米家一升级把类名换了，这一页会直接告诉我是哪一组钩子黄了、哪一组红了，
 * 而不是让我去猜为什么列表空了。
 *
 * <p>数据全部来自 Hook 侧写进 Provider 的 report.json / calllog.json，界面只做展示；
 * 这里的刷新全部由按钮驱动，不做定时轮询 —— 页面上留一份快照，比后台一直转圈更省电量也更可信。
 */
public class DebugActivity extends AppCompatActivity {

    private ActivityDebugBinding b;
    private SimConfig cfg;

    private final ActivityResultLauncher<String> dumpLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::doDump);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityDebugBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        b.btnRefresh.setOnClickListener(v -> refresh());
        b.btnClearLog.setOnClickListener(v -> clearCalls());
        b.btnDump.setOnClickListener(v -> dumpLauncher.launch("mijia-devicesim-debug-" + UiBackend.stamp() + ".json"));
        b.reportList.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        b.callLogList.setMovementMethod(new android.text.method.ScrollingMovementMethod());

        refresh();
    }

    private void refresh() {
        UiBackend.ensure(this);
        cfg = UiBackend.config();
        renderStatus();
        renderReport();
        renderState();
        renderCalls();
    }

    /* ---------- 状态 ---------- */

    private void renderStatus() {
        boolean self = UiBackend.moduleActive();
        boolean active = UiBackend.hostInjected(this) || self;
        boolean provider = UiBackend.providerAlive(this);
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.lbl_active)).append("：").append(Labels.yesNo(this, active))
                .append("　").append(getString(R.string.lbl_probe_self)).append("：").append(Labels.yesNo(this, self)).append("　")
                .append(getString(R.string.lbl_provider)).append("：").append(Labels.okFail(this, provider)).append('\n');
        sb.append("注入：").append(cfg.injectEnabled ? "开" : "关")
                .append("　云隔离：").append(cfg.cloudGuard ? "开" : "关")
                .append("　扫描注入：").append(cfg.scanInjection ? "开" : "关")
                .append("　详细日志：").append(cfg.verboseLog ? "开" : "关").append('\n');
        int valid = 0;
        for (com.mijia.devicesim.core.SimDevice d : cfg.devices) if (d.enabled && d.isValid()) valid++;
        sb.append("启用中的假设备：").append(cfg.enabledDevices().size()).append(" / ").append(cfg.size())
                .append(" 台　参与注入：").append(valid).append(" 台");
        if (!active) sb.append('\n').append(getString(R.string.msg_module_inactive));
        else if (!provider) sb.append('\n').append(getString(R.string.msg_provider_dead));
        if (StateBridge.rootConfigShadowed() || StateBridge.rootStateShadowed()) {
            sb.append('\n').append(getString(R.string.lbl_root_hint));
        }
        // 两侧各报一次：界面进程读到什么、米家进程读到什么，差在哪一眼就看出来
        sb.append('\n').append(UiBackend.localStatus(this));
        sb.append('\n').append(UiBackend.hostStatus(this));
        b.debugStatus.setText(sb.toString());
    }

    /* ---------- 钩子命中 ---------- */

    private void renderReport() {
        String raw = UiBackend.readReport(this);
        JSONObject o;
        try {
            o = new JSONObject(raw);
        } catch (Throwable t) {
            b.reportSummary.setText("还没有钩子报告：说明米家进程压根没载入模块（未生效 / 未重启米家）");
            b.reportList.setText("");
            return;
        }
        JSONArray rows = o.optJSONArray("rows");
        int ok = 0, skip = 0, fail = 0;
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                String s = rows.optJSONObject(i).optString("status");
                if (HookReport.OK.equals(s)) ok++;
                else if (HookReport.SKIP.equals(s)) skip++;
                else if (HookReport.FAIL.equals(s)) fail++;
            }
        }
        b.reportSummary.setText(getString(R.string.lbl_hit_ok) + " " + ok + "　"
                + getString(R.string.lbl_hit_skip) + " " + skip + "　"
                + getString(R.string.lbl_hit_fail) + " " + fail + "　"
                + getString(R.string.lbl_process) + "：" + o.optString("process") + "　"
                + getString(R.string.lbl_version) + "：" + o.optString("moduleVersion"));

        StringBuilder sb = new StringBuilder();
        if (rows != null) {
            // 失败和跳过先看，绿色的往后排
            appendRows(sb, rows, HookReport.FAIL);
            appendRows(sb, rows, HookReport.SKIP);
            appendRows(sb, rows, HookReport.OK);
        }
        b.reportList.setText(sb.length() == 0 ? "报告是空的" : sb.toString());
    }

    private void appendRows(StringBuilder sb, JSONArray rows, String status) {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (!status.equals(r.optString("status"))) continue;
            sb.append(status.charAt(0)).append(' ')
                    .append(r.optString("group")).append(" :: ")
                    .append(r.optString("target"));
            String detail = r.optString("detail");
            if (!detail.isEmpty()) sb.append(" :: ").append(cut(detail, 120));
            sb.append('\n');
        }
    }

    /* ---------- 属性状态 ---------- */

    private void renderState() {
        b.stateContainer.removeAllViews();
        List<SimDevice> devices = cfg.enabledDevices();
        if (devices.isEmpty()) {
            b.stateContainer.addView(textRow("没有启用的模拟设备", null));
            return;
        }
        for (SimDevice d : devices) {
            MiotSpec spec = SpecRepository.forModel(this, d.model);
            b.stateContainer.addView(textRow(d.name + "  " + d.did + "  " + d.model,
                    ContextCompat.getColor(this, R.color.accent)));
            if (spec == null) {
                b.stateContainer.addView(textRow("    无 spec：面板读数只能用配置里的初值", null));
                // spec 缺失时至少把配置初值列出来，不然这页会一片空白
                for (Map.Entry<String, Object> e : d.initialProps.entrySet()) {
                    b.stateContainer.addView(stateRow(d, null, e.getKey(), e.getValue()));
                }
                continue;
            }
            Map<String, Object> live = StateBridge.readDevice(this, d.did);
            for (MiotSpec.Prop p : spec.allProps()) {
                if (!p.readable()) continue;
                Object v = live.get(p.key());
                if (v == null) v = d.initialProps.get(p.key());
                if (v == null) v = spec.defaultValue(p);
                b.stateContainer.addView(stateRow(d, p, p.key(), v));
            }
        }
    }

    /** 一行状态；点开后能直接改值，改完写 Provider，米家进程收到通知重载 → 面板立刻跟着变。*/
    private View stateRow(SimDevice d, MiotSpec.Prop p, String key, Object value) {
        TextView tv = new TextView(this);
        tv.setTextSize(13f);
        tv.setPadding(12, 8, 12, 8);
        String label = p == null ? key : (p.name() + "  " + key);
        tv.setText(label + "  =  " + PropRows.display(p, value));
        tv.setOnClickListener(v -> editValue(d, p, key, value));
        return tv;
    }

    private void editValue(SimDevice d, MiotSpec.Prop p, String key, Object current) {
        MiotSpec spec = p == null ? null : SpecRepository.forModel(this, d.model);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 20, 40, 0);

        final Object[] picked = {current};
        final android.widget.EditText freeInput;
        if (p != null && spec != null) {
            // 有 spec：直接用列表页同一套控件，范围/枚举/步进都按 spec 走
            RowPropBinding row = RowPropBinding.inflate(LayoutInflater.from(this), box, true);
            PropRows.bind(row, spec, p, current, (prop, value) -> picked[0] = value);
            freeInput = null;
        } else {
            // 没 spec：只能原样收发，数字串认成数字，其余按字符串
            freeInput = new android.widget.EditText(this);
            freeInput.setInputType(InputType.TYPE_CLASS_TEXT);
            freeInput.setText(String.valueOf(current));
            box.addView(freeInput);
        }

        new AlertDialog.Builder(this)
                .setTitle(d.name + "\n" + key)
                .setView(box)
                .setPositiveButton(R.string.action_save, (dialog, w) -> {
                    Object value = picked[0];
                    if (freeInput != null) value = parseLoose(freeInput.getText().toString().trim());
                    boolean ok = StateBridge.writeProp(this, d.did, key, value);
                    Toast.makeText(this, ok ? "已写入：" + value : getString(R.string.msg_save_failed),
                            Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .setNeutralButton(R.string.action_clear_override, (dialog, w) -> {
                    StateBridge.writeProp(this, d.did, key, null);
                    refresh();
                })
                .show();
    }

    /** 无 spec 的属性：能认成数字就当数字，否则当字符串。*/
    private static Object parseLoose(String text) {
        if (text.isEmpty()) return "";
        if (text.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (text.equalsIgnoreCase("false")) return Boolean.FALSE;
        try {
            if (text.contains(".")) return Double.parseDouble(text);
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return text;
        }
    }

    /* ---------- 调用流水 ---------- */

    private void renderCalls() {
        String raw = UiBackend.readCallLog(this);
        JSONArray a;
        try {
            a = new JSONArray(raw);
        } catch (Throwable t) {
            b.callLogList.setText("还没有调用记录：先在米家里点开一台假设备的控制面板");
            return;
        }
        if (a.length() == 0) {
            b.callLogList.setText("还没有调用记录：先在米家里点开一台假设备的控制面板");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(a.length(), 60); i++) {
            JSONObject e = a.optJSONObject(i);
            sb.append(time(e.optLong("ts"), "HH:mm:ss")).append(' ')
                    .append(cut(e.optString("op"), 8)).append(' ')
                    .append(e.optString("did")).append(' ')
                    .append(e.optString("key"));
            String in = e.optString("in");
            String out = e.optString("out");
            if (!in.isEmpty()) sb.append("  ← ").append(cut(in, 40));
            if (!out.isEmpty()) sb.append("  → ").append(cut(out, 40));
            long ms = e.optLong("ms");
            if (ms >= 0) sb.append(String.format(Locale.ROOT, "  %dms", ms));
            sb.append('\n');
        }
        b.callLogList.setText(sb.toString());
    }

    private void clearCalls() {
        boolean ok = ConfigChannel.writeCallLog(this, "[]");
        Toast.makeText(this, ok ? R.string.msg_calls_cleared : R.string.msg_save_failed, Toast.LENGTH_SHORT).show();
        renderCalls();
    }

    /* ---------- 导出调试包 ---------- */

    /** 一份文件带上：配置 + 状态 + 报告 + 流水，出问题时直接发这个就够定位。*/
    private void doDump(Uri uri) {
        if (uri == null) return;
        try {
            JSONObject root = new JSONObject();
            root.put("exportedAt", System.currentTimeMillis());
            root.put("moduleActive", UiBackend.moduleActive());
            root.put("providerAlive", UiBackend.providerAlive(this));
            root.put("config", new JSONObject(cfg.toJsonString()));
            root.put("state", new JSONObject(textOr(ConfigChannel.readState(this), "{}")));
            root.put("report", new JSONObject(UiBackend.readReport(this)));
            root.put("callLog", new JSONArray(UiBackend.readCallLog(this)));
            boolean ok = UiBackend.writeText(this, uri, root.toString(2));
            Toast.makeText(this, ok ? R.string.msg_exported : R.string.msg_export_failed, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "导出失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    /* ---------- 小工具 ---------- */

    private TextView textRow(String text, Integer color) {
        TextView tv = new TextView(this);
        tv.setTextSize(14f);
        tv.setPadding(12, 12, 12, 2);
        tv.setText(text);
        if (color != null) tv.setTextColor(color);
        return tv;
    }

    private static String textOr(String s, String fallback) {
        return s == null || s.trim().isEmpty() ? fallback : s;
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String time(long ts, String pattern) {
        if (ts <= 0) return "--:--:--";
        return new java.text.SimpleDateFormat(pattern, Locale.ROOT).format(new java.util.Date(ts));
    }
}
