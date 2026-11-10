/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
 */
package com.mijia.devicesim.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.mijia.devicesim.core.SpecRepository;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * miot-spec.org 的在线导入。
 *
 * <p>大白话：官方站点没有「按型号查 spec」的接口 —— {@code instances?model=} 这个参数它直接忽略，
 * 一次性给你全量清单（约 7MB）。所以流程只能是：下载全量清单 → 里面找到你要的型号 →
 * 拿到那条记录的 urn（版本号取最大、已发布优先）→ 用 {@code instance?type=<urn>} 取真正的 spec。
 *
 * <p>urn 是 {@code urn:miot-spec-v2:device:<类型>:<类型码>:<型号用连字符>:<版本>} 这个格式，
 * 里面既没有型号到类型码的映射、也没有版本号规律，光凭型号名拼不出来，必须先查清单。
 */
final class SpecFetcher {

    private SpecFetcher() {
    }

    private static final String BASE = "https://miot-spec.org/miot-spec-v2";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Listener {
        /** 成功：model = 真正入库的型号，payload = urn 或清单文本；失败：payload = 原因。*/
        void onDone(boolean ok, String model, String payload);
    }

    /** 清单里的一条索引记录。*/
    private static final class Hit {
        final String urn;
        final int version;
        final boolean released;

        Hit(String urn, int version, boolean released) {
            this.urn = urn;
            this.version = version;
            this.released = released;
        }
    }

    /* ---------- 对外三个动作 ---------- */

    /** 按型号查 spec 并直接写进型号库。*/
    static void importByModel(Context ctx, String model, Listener l) {
        String want = safe(model);
        if (want.isEmpty()) {
            post(l, false, "", "型号为空");
            return;
        }
        POOL.execute(() -> {
            try {
                List<Hit> hits = scanInstances(want);
                if (hits.isEmpty()) {
                    post(l, false, want, "官方库里没有这个型号");
                    return;
                }
                Hit best = pickBest(hits);
                String spec = get(BASE + "/instance?type=" + best.urn);
                JSONObject parsed = store(ctx, spec, want);
                post(l, true, parsed.optString("model", want), best.urn);
            } catch (Throwable t) {
                post(l, false, want, "出错：" + t.getClass().getSimpleName() + " " + msg(t));
            }
        });
    }

    /** 只查不入库：把匹配到的 urn 列表回显出来，方便挑版本。*/
    static void listByModel(Context ctx, String model, Listener l) {
        String want = safe(model);
        POOL.execute(() -> {
            try {
                List<Hit> hits = scanInstances(want);
                if (hits.isEmpty()) {
                    post(l, false, want, "官方库里没有这个型号");
                    return;
                }
                StringBuilder sb = new StringBuilder("命中 " + hits.size() + " 条：\n");
                for (int i = 0; i < Math.min(hits.size(), 10); i++) {
                    Hit h = hits.get(i);
                    sb.append(h.released ? "已发布 " : "开发中 ").append(h.urn).append('\n');
                }
                post(l, true, want, sb.toString());
            } catch (Throwable t) {
                post(l, false, want, "出错：" + msg(t));
            }
        });
    }

    /** 已经知道完整 urn（比如从别处抄来的）时直接下 spec。*/
    static void importByUrn(Context ctx, String urn, Listener l) {
        String want = safe(urn);
        if (!want.startsWith("urn:miot-spec")) {
            post(l, false, "", "urn 应以 urn:miot-spec 开头");
            return;
        }
        POOL.execute(() -> {
            try {
                String spec = get(BASE + "/instance?type=" + want);
                JSONObject parsed = store(ctx, spec, modelFromUrn(want));
                if (parsed == null) return;
                post(l, true, parsed.optString("model", modelFromUrn(want)), want);
            } catch (Throwable t) {
                post(l, false, want, "出错：" + msg(t));
            }
        });
    }

    /** 校验 + 入库；失败一律抛异常，由调用方统一回显原因。*/
    private static JSONObject store(Context ctx, String spec, String fallbackModel) throws Exception {
        if (spec == null || spec.length() < 32) throw new IllegalStateException("spec 太短，按下载失败处理");
        JSONObject o = new JSONObject(spec);
        String model = o.optString("model", fallbackModel);
        if (model.isEmpty()) throw new IllegalStateException("spec 里没有 model 字段");
        if (!SpecRepository.importSpec(ctx, model, spec)) throw new IllegalStateException("写入型号库失败（Provider 不通？）");
        return o;
    }

    /* ---------- 全量清单 ---------- */

    private static List<Hit> scanInstances(String model) throws Exception {
        byte[] body = getBytes(BASE + "/instances?status=all");
        String s = new String(body, UTF8);
        String needle = "\"model\":\"" + model + "\"";
        List<Hit> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int idx = s.indexOf(needle, from);
            if (idx < 0) break;
            from = idx + needle.length();
            Hit h = parseHit(s, idx);
            if (h != null && !containsUrn(out, h.urn)) out.add(h);
        }
        return out;
    }

    private static boolean containsUrn(List<Hit> hits, String urn) {
        for (Hit h : hits) if (h.urn.equals(urn)) return true;
        return false;
    }

    /** 从命中位置往前找最近的 '{'，往后按括号配对找 '}'，截出这一条索引记录。*/
    private static Hit parseHit(String s, int idx) {
        int start = s.lastIndexOf('{', idx);
        if (start < 0) return null;
        int depth = 0;
        int end = -1;
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                if (--depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        if (end < 0) return null;
        try {
            JSONObject o = new JSONObject(s.substring(start, end + 1));
            String type = o.optString("type");
            return type.startsWith("urn:")
                    ? new Hit(type, o.optInt("version", 1), "released".equals(o.optString("status")))
                    : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 已发布优先，其次版本号大者优先。*/
    private static Hit pickBest(List<Hit> hits) {
        Hit best = hits.get(0);
        for (Hit h : hits) {
            if (h.released != best.released) {
                if (h.released) best = h;
            } else if (h.version > best.version) {
                best = h;
            }
        }
        return best;
    }

    /** urn 第 6 段是型号（点被换成连字符），反解回来当文件名/入库键够用。*/
    static String modelFromUrn(String urn) {
        String[] parts = urn.split(":");
        return parts.length < 7 ? urn : parts[6].replace('-', '.');
    }

    /* ---------- HTTP ---------- */

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(45000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "MIJIA-device-simulate/1");
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
        return c;
    }

    private static String get(String url) throws Exception {
        return new String(getBytes(url), UTF8);
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream(128 * 1024)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /* ---------- 回调 ---------- */

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static void post(Listener l, boolean ok, String model, String payload) {
        if (l == null) return;
        MAIN.post(() -> l.onDone(ok, model, payload));
    }
}
