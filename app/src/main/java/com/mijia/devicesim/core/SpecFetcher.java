/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-26 16:37:14 +08:00
 */
package com.mijia.devicesim.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * miot-spec.org 的在线获取（界面导入 + 运行期补取共用）。
 *
 * <p>大白话：本地没有这个型号的 spec 时，唯一的正道是去官方站取真货。
 * 官方站没有「按型号查 spec」的接口 —— {@code instances?model=} 参数它直接忽略，
 * 一次性给你全量清单（约 7MB）。所以流程只能是：下载全量清单 → 找到型号 →
 * 拿到那条记录的 urn（版本号取最大、已发布优先）→ {@code instance?type=<urn>} 取真正的 spec。
 *
 * <p>urn 是 {@code urn:miot-spec-v2:device:<类型>:<类型码>:<型号连字符>:<版本>} 这个格式，
 * 里面既没有型号到类型码的映射、也没有版本号规律，光凭型号名拼不出来，必须先查清单。
 *
 * <p>铁律（用户 2026-11-26 明确）：<b>取不到就是取不到，绝不拿同族/别的型号的 spec 顶替</b>。
 * 错 spec 会渲染出一整套假控件，比空白更误导人；所以入库前还会校验 spec 的 urn 型号标记
 * 与请求的型号一致，对不上直接判失败。
 */
public final class SpecFetcher {

    private SpecFetcher() {
    }

    private static final String BASE = "https://miot-spec.org/miot-spec-v2";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 正在取的型号；同一型号并发只打一次网络。*/
    private static final Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();

    public interface Listener {
        /** 成功：model = 真正入库的型号，payload = urn；失败：payload = 原因。*/
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

    /* ---------- 对外动作 ---------- */

    /** 界面用：按型号查 spec 并直接写进型号库。*/
    public static void importByModel(Context ctx, String model, Listener l) {
        start(ctx, model, l, false);
    }

    /** 运行期用：本地确实没有该型号 spec 时才打网络，同型号只跑一次。*/
    public static void fetchForModel(Context ctx, String model, Listener l) {
        start(ctx, model, l, true);
    }

    /** 只查不入库：把匹配到的 urn 列表回显出来，方便挑版本。*/
    public static void listByModel(Context ctx, String model, Listener l) {
        final String want = safe(model);
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
    public static void importByUrn(Context ctx, String urn, Listener l) {
        final String want = safe(urn);
        if (!want.startsWith("urn:miot-spec")) {
            post(l, false, "", "urn 应以 urn:miot-spec 开头");
            return;
        }
        POOL.execute(() -> {
            try {
                String spec = get(BASE + "/instance?type=" + want);
                JSONObject parsed = store(ctx, spec, modelFromUrn(want), want);
                if (parsed == null) return;
                post(l, true, parsed.optString("model", modelFromUrn(want)), want);
            } catch (Throwable t) {
                post(l, false, want, "出错：" + msg(t));
            }
        });
    }

    /** 核心流程；{@code skipIfLocal} 为真时先看一眼本地，已有就什么都不做。*/
    private static void start(Context ctx, String model, Listener l, boolean skipIfLocal) {
        final String want = safe(model);
        if (want.isEmpty()) {
            post(l, false, "", "型号为空");
            return;
        }
        if (!INFLIGHT.add(want)) return;
        POOL.execute(() -> {
            try {
                if (skipIfLocal && SpecRepository.hasLocal(ctx, want)) {
                    post(l, true, want, "本地已有");
                    return;
                }
                List<Hit> hits = scanInstances(want);
                if (hits.isEmpty()) {
                    post(l, false, want, "官方库里没有这个型号");
                    return;
                }
                Hit best = pickBest(hits);
                String spec = get(BASE + "/instance?type=" + best.urn);
                JSONObject parsed = store(ctx, spec, want, best.urn);
                post(l, true, parsed.optString("model", want), best.urn);
            } catch (Throwable t) {
                post(l, false, want, "出错：" + t.getClass().getSimpleName() + " " + msg(t));
            } finally {
                INFLIGHT.remove(want);
            }
        });
    }

    /* ---------- 校验 + 入库 ---------- */

    /**
     * 校验 + 入库；失败一律抛异常，由调用方统一回显原因。
     *
     * <p>校验两条：必须是 miot-spec 设备实例；urn 里的「品牌-产品」标记必须和请求的型号一致。
     * 第二条是防「拿错型号的 spec」的最后一道闸 —— 官方站点确实会返回别的记录时这里直接拒绝。
     *
     * <p>落盘两路都试：Provider（模块自己的界面能看到、能管理）+ 本地镜像
     * {@code files/specs/<model>.json}（米家进程里 Provider 不通时，钩子只能读这份）。
     */
    private static JSONObject store(Context ctx, String spec, String wantedModel, String urn) throws Exception {
        if (spec == null || spec.length() < 32) throw new IllegalStateException("spec 太短，按下载失败处理");
        JSONObject o = new JSONObject(spec);
        String type = o.optString("type", "");
        if (!type.startsWith("urn:miot-spec-v2:device:")) {
            throw new IllegalStateException("不是 miot-spec 设备实例：" + type);
        }
        String model = o.optString("model", wantedModel);
        if (model == null || model.isEmpty()) throw new IllegalStateException("spec 里没有 model 字段");
        String want = safe(wantedModel);
        if (!want.isEmpty() && !tokenOf(model).equals(tokenOf(want))) {
            throw new IllegalStateException("返回的 spec 型号对不上（要 " + want + "，给 " + type + "）");
        }
        boolean provider = SpecRepository.importSpec(ctx, model, spec);
        boolean mirror = ConfigChannel.mirrorWrite(ctx,
                "specs/" + ConfigChannel.safeFileName(model) + ".json", spec);
        if (!provider && !mirror) throw new IllegalStateException("写入失败（Provider 与本地镜像都写不进去）");
        SpecRepository.invalidate(model);
        Log.d("在线取到 spec: " + model + " ← " + urn
                + "（" + spec.length() + " 字节，Provider=" + provider + " 镜像=" + mirror + "）");
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
    public static String modelFromUrn(String urn) {
        String[] parts = urn.split(":");
        return parts.length < 7 ? urn : parts[6].replace('-', '.');
    }

    /** 型号 → urn 里的「品牌-产品」标记；spec 的 urn 必须带这个标记。*/
    private static String tokenOf(String model) {
        String[] parts = model.split("\\.");
        return parts.length < 2 ? model : parts[0] + "-" + parts[parts.length - 1];
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