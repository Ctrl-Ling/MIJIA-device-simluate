/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 09:25:23 +08:00
 */
package com.mijia.devicesim.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MIoT Spec 的只读模型（官方 JSON 结构：services -> properties / actions / events）。
 *
 * 为什么要在模块里自己解析一遍：假设备的属性初值编辑器、状态机的取值校验、
 * 以及喂给米家 spec 面板的 propInfo，全部要以 spec 为唯一依据（plan Phase 2 状态机的根据）。
 * 米家自己的 spec 解析类是混淆+版本相关的，不能依赖。
 */
public class MiotSpec {

    public static final String FORMAT_BOOL = "bool";
    public static final String FORMAT_INT8 = "int8";
    public static final String FORMAT_INT16 = "int16";
    public static final String FORMAT_INT32 = "int32";
    public static final String FORMAT_INT64 = "int64";
    public static final String FORMAT_UINT8 = "uint8";
    public static final String FORMAT_UINT16 = "uint16";
    public static final String FORMAT_UINT32 = "uint32";
    public static final String FORMAT_FLOAT = "float";
    public static final String FORMAT_STRING = "string";

    public final String urn;
    public final String model;
    public final String description;
    public final String nameCn;
    public final List<Service> services = new ArrayList<>();

    private final JSONObject raw;

    public MiotSpec(JSONObject raw) {
        this.raw = raw;
        this.urn = raw.optString("type");
        this.model = raw.optString("model");
        this.description = raw.optString("description");
        this.nameCn = raw.optString("nameCn", raw.optString("title", ""));
        JSONArray svcs = raw.optJSONArray("services");
        if (svcs != null) {
            for (int i = 0; i < svcs.length(); i++) {
                JSONObject s = svcs.optJSONObject(i);
                if (s != null) services.add(new Service(s));
            }
        }
    }

    public JSONObject raw() {
        return raw;
    }

    public boolean isValid() {
        return !services.isEmpty();
    }

    /** 从 URN 里取人类可读的类型名，例如 urn:miot-spec-v2:device:light:0000A001:xxx:1 -> light。*/
    public String deviceType() {
        return typeSegment(urn);
    }

    public String category() {
        String t = deviceType();
        return t.isEmpty() ? "设备" : t;
    }

    public static String typeSegment(String urn) {
        if (urn == null) return "";
        String[] parts = urn.split(":");
        // urn:miot-spec-v2:device:light:0000A001:model:1
        // [0]=urn [1]=miot-spec-v2 [2]=device/service/property [3]=light ← 这一段才是人类可读的类型
        return parts.length >= 4 ? parts[3] : "";
    }

    /* ---------- 查询 ---------- */

    public Prop findProp(int siid, int piid) {
        for (Service s : services) {
            if (s.iid != siid) continue;
            for (Prop p : s.properties) if (p.iid == piid) return p;
        }
        return null;
    }

    public Action findAction(int siid, int aiid) {
        for (Service s : services) {
            if (s.iid != siid) continue;
            for (Action a : s.actions) if (a.iid == aiid) return a;
        }
        return null;
    }

    public List<Prop> allProps() {
        List<Prop> out = new ArrayList<>();
        for (Service s : services) out.addAll(s.properties);
        return out;
    }

    /**
     * 面板上值得露出来的属性：可写 + 可通知的只读量。
     * 纯只读且没通知能力的（manufacturer/model/serial-number 这类）过滤掉，
     * 不然属性编辑器第一屏全是无意义的字符串。
     */
    public List<Prop> editableProps() {
        List<Prop> out = new ArrayList<>();
        for (Service s : services) {
            for (Prop p : s.properties) {
                if (p.writable() || p.notifiable()) out.add(p);
            }
        }
        return out;
    }

    /* ---------- 取值处理 ---------- */

    /** 把任意输入按 spec 归一到合法值；不合法时就近取值而不是抛错。*/
    public Object coerce(Prop p, Object input) {
        if (p == null || input == null) return p == null ? null : defaultValue(p);
        try {
            if (p.hasValueList()) {
                Double numeric = asDoubleOrNull(input);
                if (numeric != null) {
                    // 枚举：命中就用，没命中用第一个合法值
                    for (ValueUnit v : p.valueList) {
                        Double candidate = asDoubleOrNull(v.value);
                        if (candidate != null && Math.abs(candidate - numeric) < 1e-9) return v.value;
                    }
                }
                String text = String.valueOf(input);
                for (ValueUnit v : p.valueList) if (String.valueOf(v.value).equals(text)) return v.value;
                return p.valueList.get(0).value;
            }
            switch (p.format) {
                case FORMAT_BOOL: {
                    if (input instanceof Boolean) return input;
                    Double d = asDoubleOrNull(input);
                    if (d != null) return d != 0.0;
                    String s = String.valueOf(input).trim().toLowerCase(Locale.ROOT);
                    return s.equals("true") || s.equals("on") || s.equals("开") || s.equals("1");
                }
                case FORMAT_FLOAT: {
                    Double d = asDoubleOrNull(input);
                    if (d == null) return defaultValue(p);
                    return clampFloat(p, d);
                }
                case FORMAT_STRING:
                    return String.valueOf(input);
                default: {
                    Double d = asDoubleOrNull(input);
                    if (d == null) return defaultValue(p);
                    return clampInt(p, d);
                }
            }
        } catch (Throwable t) {
            Log.v("coerce 失败 " + p.key() + ": " + t);
            return defaultValue(p);
        }
    }

    public double clampFloat(Prop p, double v) {
        if (p.range != null) {
            if (p.range.length > 0) v = Math.max(p.range[0], v);
            if (p.range.length > 1) v = Math.min(p.range[1], v);
            if (p.range.length > 2 && p.range[2] > 0) {
                double min = p.range.length > 0 ? p.range[0] : 0;
                v = min + Math.round((v - min) / p.range[2]) * p.range[2];
            }
        }
        return roundDisplay(v);
    }

    public long clampInt(Prop p, double v) {
        return Math.round(clampFloat(p, v));
    }

    private static double roundDisplay(double v) {
        // 浮点步进容易出现 25.400000000000002，面板显示和回写都不好看
        return Math.round(v * 1e6) / 1e6;
    }

    /** spec 里第一个合法值：可写属性给中位/最小，只读属性给最小。*/
    public Object defaultValue(Prop p) {
        if (p == null) return null;
        if (p.hasValueList()) return p.valueList.get(0).value;
        switch (p.format) {
            case FORMAT_BOOL:
                return Boolean.FALSE;
            case FORMAT_STRING:
                return "";
            case FORMAT_FLOAT: {
                double base = p.range != null && p.range.length > 0 ? p.range[0] : 0;
                double cap = p.range != null && p.range.length > 1 ? p.range[1] : 100;
                return roundDisplay(base + (cap - base) / 2);
            }
            default: {
                double base = p.range != null && p.range.length > 0 ? p.range[0] : 0;
                double cap = p.range != null && p.range.length > 1 ? p.range[1] : 100;
                return Math.round(base + (cap - base) / 2);
            }
        }
    }

    private static Double asDoubleOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof Boolean) return ((Boolean) o) ? 1.0 : 0.0;
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* ---------- 结构体 ---------- */

    public static class Service {
        public final int iid;
        public final String type;
        public final String description;
        public final List<Prop> properties = new ArrayList<>();
        public final List<Action> actions = new ArrayList<>();
        public final List<Event> events = new ArrayList<>();

        Service(JSONObject o) {
            iid = o.optInt("iid");
            type = o.optString("type");
            description = o.optString("description");
            JSONArray ps = o.optJSONArray("properties");
            if (ps != null) for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p != null) properties.add(new Prop(iid, p));
            }
            JSONArray as = o.optJSONArray("actions");
            if (as != null) for (int i = 0; i < as.length(); i++) {
                JSONObject a = as.optJSONObject(i);
                if (a != null) actions.add(new Action(iid, a));
            }
            JSONArray es = o.optJSONArray("events");
            if (es != null) for (int i = 0; i < es.length(); i++) {
                JSONObject e = es.optJSONObject(i);
                if (e != null) events.add(new Event(iid, e));
            }
        }

        public String name() {
            String seg = typeSegment(type);
            return seg.isEmpty() ? "service" + iid : seg;
        }
    }

    public static class Prop {
        public final int siid;
        public final int iid;
        public final String type;
        public final String description;
        public final String format;
        public final List<String> access = new ArrayList<>();
        public final List<ValueUnit> valueList = new ArrayList<>();
        public final double[] range;
        public final String unit;

        Prop(int siid, JSONObject o) {
            this.siid = siid;
            iid = o.optInt("iid");
            type = o.optString("type");
            description = o.optString("description");
            format = o.optString("format", FORMAT_INT32);
            JSONArray acc = o.optJSONArray("access");
            if (acc != null) for (int i = 0; i < acc.length(); i++) access.add(acc.optString(i));
            unit = o.optString("unit", "");
            JSONArray vr = o.optJSONArray("range");
            if (vr != null && vr.length() >= 2) {
                range = new double[Math.min(vr.length(), 3)];
                for (int i = 0; i < range.length; i++) range[i] = vr.optDouble(i);
            } else {
                range = null;
            }
            JSONArray vl = o.optJSONArray("value-list");
            if (vl != null) for (int i = 0; i < vl.length(); i++) {
                JSONObject v = vl.optJSONObject(i);
                if (v != null) valueList.add(new ValueUnit(v.opt("value"), v.optString("description")));
            }
        }

        public String key() {
            return Consts.propKey(siid, iid);
        }

        public boolean readable() {
            return access.contains("read");
        }

        public boolean writable() {
            return access.contains("write");
        }

        public boolean notifiable() {
            return access.contains("notify");
        }

        public boolean hasValueList() {
            return !valueList.isEmpty();
        }

        public boolean isBool() {
            return FORMAT_BOOL.equals(format);
        }

        public boolean isFloat() {
            return FORMAT_FLOAT.equals(format);
        }

        public boolean isString() {
            return FORMAT_STRING.equals(format);
        }

        public String name() {
            String seg = typeSegment(type);
            if (!seg.isEmpty()) return seg;
            return description.isEmpty() ? "prop" + iid : description;
        }

        /** 面板上给人看的一行：开关 1:2 [bool][rw-]。*/
        public String label() {
            StringBuilder sb = new StringBuilder(name()).append(' ').append(key()).append(" [").append(format).append('[');
            sb.append(readable() ? "r" : "-").append(writable() ? "w" : "-").append(notifiable() ? "n" : "-").append(']');
            if (range != null) {
                sb.append(" ").append(trim(range[0])).append("~").append(trim(range[range.length - 1]));
                if (range.length > 2) sb.append("/").append(trim(range[2]));
            }
            if (!unit.isEmpty()) sb.append(' ').append(unit);
            return sb.toString();
        }

        private static String trim(double d) {
            return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
    }

    public static class ValueUnit {
        public final Object value;
        public final String description;

        ValueUnit(Object value, String description) {
            this.value = value;
            this.description = description;
        }
    }

    public static class Action {
        public final int siid;
        public final int iid;
        public final String type;
        public final String description;
        public final List<Prop> in = new ArrayList<>();
        public final List<Prop> out = new ArrayList<>();

        Action(int siid, JSONObject o) {
            this.siid = siid;
            iid = o.optInt("iid");
            type = o.optString("type");
            description = o.optString("description");
            JSONArray ins = o.optJSONArray("in");
            if (ins != null) for (int i = 0; i < ins.length(); i++) in.add(inlineProp(siid, ins.optJSONObject(i)));
            JSONArray outs = o.optJSONArray("out");
            if (outs != null) for (int i = 0; i < outs.length(); i++) out.add(inlineProp(siid, outs.optJSONObject(i)));
        }

        private static Prop inlineProp(int siid, JSONObject o) {
            return o == null ? new Prop(siid, new JSONObject()) : new Prop(siid, o);
        }

        public String key() {
            return Consts.actionKey(siid, iid);
        }

        public String name() {
            String seg = typeSegment(type);
            return seg.isEmpty() ? (description.isEmpty() ? "action" + iid : description) : seg;
        }
    }

    public static class Event {
        public final int siid;
        public final int iid;
        public final String type;
        public final String description;

        Event(int siid, JSONObject o) {
            this.siid = siid;
            iid = o.optInt("iid");
            type = o.optString("type");
            description = o.optString("description");
        }

        public String key() {
            return siid + Consts.KEY_SEPARATOR + iid;
        }

        public String name() {
            String seg = typeSegment(type);
            return seg.isEmpty() ? description : seg;
        }
    }
}
