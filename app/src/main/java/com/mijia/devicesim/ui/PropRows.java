/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 09:38:48 +08:00
 */
package com.mijia.devicesim.ui;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import com.mijia.devicesim.core.MiotLabels;
import com.mijia.devicesim.core.MiotSpec;
import com.mijia.devicesim.databinding.RowPropBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一行属性编辑器：按 spec 的 format / range / value-list 决定长什么样。
 *
 * <p>为什么不用一个通用输入框了事：spec 已经把「这个属性能不能写、取值范围、步进、枚举」都写清楚了，
 * 界面照它给控件，用户就写不出非法值；就算写出来了，{@link MiotSpec#coerce} 还会再校正一次。
 *
 * <p>编辑设备初值和实时调试面板共用这一份，两边只是 commit 之后写到哪里不同。
 */
final class PropRows {

    private PropRows() {
    }

    interface Commit {
        /** value 已经是按 spec 校正过的合法值。*/
        void onCommit(MiotSpec.Prop prop, Object value);
    }

    /** SeekBar 跨度上限：再大就拖着没意义，只留数字输入框。*/
    private static final int MAX_SEEK_STEPS = 2000;

    static RowPropBinding add(android.widget.LinearLayout container, Context ctx,
                              MiotSpec spec, MiotSpec.Prop prop, Object current, Commit commit) {
        RowPropBinding b = RowPropBinding.inflate(android.view.LayoutInflater.from(ctx), container, false);
        // 属性标题带中文说明：开关状态（switch-status） 2:1 [bool][rw-]
        b.propLabel.setText(MiotLabels.propLabel(prop));
        bind(b, spec, prop, current, commit);
        container.addView(b.getRoot());
        return b;
    }

    /** 刷新已有的一行（写值回来后同步显示，不重建 View）。*/
    static void bind(RowPropBinding b, MiotSpec spec, MiotSpec.Prop prop, Object current, Commit commit) {
        final boolean hasRange = prop.range != null && prop.range.length >= 2;
        b.propSwitch.setVisibility(prop.isBool() ? View.VISIBLE : View.GONE);
        b.propSpinner.setVisibility(prop.hasValueList() ? View.VISIBLE : View.GONE);
        boolean useSeek = !prop.hasValueList() && !prop.isBool() && !prop.isString() && hasRange && seekSteps(prop) <= MAX_SEEK_STEPS;
        b.propSeek.setVisibility(useSeek ? View.VISIBLE : View.GONE);
        boolean useInput = !prop.isBool() && !prop.hasValueList();
        b.propInput.setVisibility(useInput ? View.VISIBLE : View.GONE);
        b.propInput.setInputType(prop.isString()
                ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                : (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL));

        // 回填控件时不要触发监听器，否则会立刻「写回」一次
        b.propSeek.setOnSeekBarChangeListener(null);
        b.propSwitch.setOnCheckedChangeListener(null);
        b.propSpinner.setOnItemSelectedListener(null);

        b.propValue.setText(display(prop, current));

        if (prop.isBool()) {
            b.propSwitch.setChecked(truthy(current));
            b.propSwitch.setOnCheckedChangeListener((v, checked) ->
                    commit.onCommit(prop, spec == null ? checked : spec.coerce(prop, checked)));
        }

        if (prop.hasValueList()) {
            List<String> titles = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            for (MiotSpec.ValueUnit v : prop.valueList) {
                values.add(v.value);
                titles.add(MiotLabels.valueTitle(v.value, v.description));
            }
            b.propSpinner.setAdapter(new ArrayAdapter<>(b.getRoot().getContext(),
                    android.R.layout.simple_spinner_dropdown_item, titles));
            int sel = indexOf(values, current);
            b.propSpinner.setSelection(sel < 0 ? 0 : sel, false);
            b.propSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    Object picked = values.get(position);
                    commit.onCommit(prop, spec == null ? picked : spec.coerce(prop, picked));
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }

        if (useInput) {
            b.propInput.setText(plain(current));
            b.propInput.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) return;
                String text = b.propInput.getText().toString().trim();
                if (text.isEmpty()) return;
                commit.onCommit(prop, spec == null ? text : spec.coerce(prop, text));
            });
        }

        if (useSeek) {
            final Object[] live = {current};
            int steps = seekSteps(prop);
            b.propSeek.setMax(steps);
            b.propSeek.setProgress(progressOf(prop, current));
            b.propSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    double v = prop.range[0] + (double) progress * step(prop);
                    Object coerced = spec == null ? v : spec.coerce(prop, v);
                    live[0] = coerced;
                    b.propValue.setText(display(prop, coerced));
                    commit.onCommit(prop, coerced);
                }

                @Override
                public void onStartTrackingTouch(android.widget.SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(android.widget.SeekBar bar) {
                    // 输入框跟着同步一次，免得拖完两处显示不一致
                    b.propInput.setText(plain(live[0]));
                }
            });
        }
    }

    /* ---------- 小工具 ---------- */

    private static double step(MiotSpec.Prop p) {
        if (p.range != null && p.range.length > 2 && p.range[2] > 0) return p.range[2];
        return p.isFloat() ? 0.1 : 1.0;
    }

    private static int seekSteps(MiotSpec.Prop p) {
        if (p.range == null || p.range.length < 2) return 0;
        double st = step(p);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE - 1, Math.round((p.range[1] - p.range[0]) / st)));
    }

    private static int progressOf(MiotSpec.Prop p, Object v) {
        double d = asDouble(v, p.range[0]);
        return (int) Math.max(0, Math.min(seekSteps(p), Math.round((d - p.range[0]) / step(p))));
    }

    private static double asDouble(Object v, double fallback) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        return asDouble(v, 0) != 0;
    }

    private static int indexOf(List<Object> values, Object current) {
        for (int i = 0; i < values.size(); i++) {
            Object v = values.get(i);
            if (v == null || current == null) continue;
            if (v instanceof Number && current instanceof Number) {
                if (Math.abs(((Number) v).doubleValue() - ((Number) current).doubleValue()) < 1e-9) return i;
            } else if (String.valueOf(v).equals(String.valueOf(current))) {
                return i;
            }
        }
        return -1;
    }

    /** 一行右边显示的值：枚举值带上中文含义，整数不带小数点，bool 显示 开/关。*/
    static String display(MiotSpec.Prop p, Object v) {
        if (v == null) return "—";
        if (v instanceof Boolean) return ((Boolean) v) ? "开" : "关";
        // 枚举属性显示「值 中文」，比如 1 空闲、4 清扫中
        String labeled = MiotLabels.valueText(p, v);
        if (!labeled.isEmpty()) return labeled;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && (p == null || !p.isFloat())) return String.valueOf((long) d);
            return String.format(Locale.ROOT, "%.2f", d);
        }
        String s = String.valueOf(v);
        return s.length() > 40 ? s.substring(0, 37) + "..." : s;
    }

    private static String plain(Object v) {
        return v == null ? "" : display(null, v);
    }
}
