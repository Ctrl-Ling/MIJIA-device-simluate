/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 20:02:02 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 自动化编辑页校验码清零（v133）。
 *
 * <p>大白话：编辑页顶部那条「部分功能已失效，请修改后保存此自动化」，和「创建」按钮点不动，
 * 源头是一串整数校验码。链路：
 * <pre>
 * a38$OooO00o#OooO00o(item, repo) -> int       每条触发/条件/动作算一个码
 *      -> w28 四个谓词（-1 无效 / -2 不在家 / -7 蓝牙离线 / -15 蓝牙不可用）
 * who#OooO0Oo(repo, triggers, conditions, actions, elseActions, pair) -> ArrayList
 *      外层 4 个 ArrayList&lt;Integer&gt; 分四类，第 5 项是时间窗 Pair
 *      -> SceneValidatorViewModel$updateValidateCode$1 -> Oooo0(List,List) -> 黄条 + 创建闸门
 * </pre>
 *
 * <p>两道闸，互为兜底：
 * <ol>
 *   <li>{@code a38$OooO00o#OooO00o}：条目里含「启用中的模拟 did」就直接返回 0（合法）；</li>
 *   <li>{@code who#OooO0Oo}：结果出来后再对一遍，和入参四条列表按下标对齐，
 *       模拟设备那几格的非 0 码就地改 0；长度对不上时（米家换了分组口径）该类别整列清零，
 *       并在日志里标 mismatch，方便下次照日志修。</li>
 * </ol>
 *
 * <p>真设备的码一个都不动；模拟设备没启用的也不动。
 */
public final class SceneValidationHook {

    private static final String GROUP = "scene-validation";

    /** 单条校验器（每个 item 算一个码）。*/
    private static final String CL_ITEM_VALIDATOR = "_m_j.a38$OooO00o";
    private static final String M_ITEM_VALIDATE = "OooO00o";

    /** 汇总校验器（外层 ArrayList，4 类校验码 + 时间窗）。*/
    private static final String CL_WHO = "_m_j.who";
    private static final String M_WHO = "OooO0Oo";

    private static final int CATEGORY_COUNT = 4;

    /** 日志限流。*/
    private static final AtomicInteger sItemLogs = new AtomicInteger();
    private static final AtomicInteger sWhoLogs = new AtomicInteger();

    /** 逐类缓存反射字段，避免每次递归都 getDeclaredFields。*/
    private static final Map<Class<?>, Field[]> sFields = new ConcurrentHashMap<>();
    private static final Field[] NO_FIELDS = new Field[0];

    private SceneValidationHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        int item = HookSniper.hook(lpparam.classLoader, GROUP, CL_ITEM_VALIDATOR,
                new String[]{M_ITEM_VALIDATE},
                HookSniper.Shape.of("int", "Oooo000", "BleRssiRepository"),
                "[oO0]{6,9}", ITEM_ZERO);
        int who = HookSniper.hook(lpparam.classLoader, GROUP, CL_WHO,
                new String[]{M_WHO},
                HookSniper.Shape.of("ArrayList", "BleRssiRepository",
                        "List", "List", "List", "List", "Pair"),
                "[oO0]{6,9}", WHO_ZERO);
        if (item > 0 || who > 0) {
            HookReport.ok(GROUP, "install", "单条校验=" + item + "，汇总校验=" + who);
        } else {
            HookReport.fail(GROUP, "install", "两个校验挂点都没命中（类/方法可能又改名）");
        }
    }

    /* ---------- 1. 单条：含模拟设备直接判 0 ---------- */

    private static final XC_MethodHook ITEM_ZERO = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length == 0) return;
                String did = enabledSimDid(param.args[0], 0);
                if (did == null) return;
                param.setResult(0);
                int n = sItemLogs.incrementAndGet();
                if (n <= 8 || n % 200 == 0) {
                    Log.d("场景单条校验已放行 #" + n + " did=" + did);
                }
            } catch (Throwable t) {
                Log.e("场景单条校验放行异常", t);
            }
        }
    };

    /* ---------- 2. 汇总：模拟设备那几格的校验码改 0 ---------- */

    private static final XC_MethodHook WHO_ZERO = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object res = param.getResult();
                if (!(res instanceof List)) return;
                List<?> out = (List<?>) res;
                if (param.args == null || param.args.length < 1 + CATEGORY_COUNT) return;

                int cleared = 0;
                StringBuilder detail = new StringBuilder();
                boolean mismatch = false;
                for (int c = 0; c < CATEGORY_COUNT; c++) {
                    Object inObj = param.args[c + 1];
                    if (!(inObj instanceof List)) continue;
                    List<?> in = (List<?>) inObj;
                    if (c >= out.size()) continue;
                    Object subObj = out.get(c);
                    if (!(subObj instanceof List)) continue;
                    @SuppressWarnings("unchecked")
                    List<Object> sub = (List<Object>) subObj;

                    if (sub.size() != in.size()) {
                        // 分组口径变了：只在本类里确实有模拟设备时才整列清零，避免误伤
                        if (firstSimDid(in, 1) != null) {
                            int n = forceZero(sub);
                            if (n > 0) {
                                cleared += n;
                                mismatch = true;
                                detail.append(" 类").append(c).append("长度")
                                        .append(in.size()).append('/').append(sub.size())
                                        .append("清").append(n);
                            }
                        }
                        continue;
                    }

                    for (int i = 0; i < in.size(); i++) {
                        String did = enabledSimDid(in.get(i), 1);
                        if (did == null) continue;
                        Object codeObj = sub.get(i);
                        int code = codeObj instanceof Number ? ((Number) codeObj).intValue() : 0;
                        detail.append(" 类").append(c).append('#').append(i).append('=').append(code);
                        if (code != 0) {
                            sub.set(i, 0);
                            cleared++;
                        }
                    }
                }

                if (detail.length() == 0) return;
                int n = sWhoLogs.incrementAndGet();
                if (cleared > 0) {
                    Log.d("场景校验码已清零 " + cleared + " 格" + (mismatch ? "（含分组不一致兜底）" : "")
                            + ":" + detail);
                } else if (n <= 5) {
                    Log.d("场景校验码原本就是 0:" + detail);
                }
            } catch (Throwable t) {
                Log.e("场景校验码清零异常", t);
            }
        }
    };

    private static int forceZero(List<Object> sub) {
        int n = 0;
        for (int i = 0; i < sub.size(); i++) {
            Object o = sub.get(i);
            int code = o instanceof Number ? ((Number) o).intValue() : 0;
            if (code != 0) {
                sub.set(i, 0);
                n++;
            }
        }
        return n;
    }

    /* ---------- 条目里找模拟 did ---------- */

    /**
     * 在条目对象里找「启用中的模拟 did」。
     *
     * @param depth 递归深度；item 用 0，列表元素用 1（元素→状态→字段）。
     */
    private static String enabledSimDid(Object obj, int depth) {
        String did = simDid(obj, depth);
        if (did == null) return null;
        SimConfig cfg = ConfigRepository.current();
        if (cfg == null || !cfg.injectEnabled) return null;
        return cfg.findEnabledByDid(did) != null ? did : null;
    }

    private static String firstSimDid(List<?> list, int depth) {
        for (Object o : list) {
            String did = enabledSimDid(o, depth);
            if (did != null) return did;
        }
        return null;
    }

    /** 深度受限地找一个模拟 did：先看自己的 did 字段，再往非 JDK 对象里走两层。*/
    private static String simDid(Object obj, int depth) {
        if (obj == null || depth > 2) return null;
        Class<?> c = obj.getClass();
        if (isLeaf(c)) return null;

        Object direct = HookUtil.readFieldQuiet(obj, "did");
        if (direct instanceof String && Consts.isSimulatedDid((String) direct)) {
            return (String) direct;
        }
        for (Object v : fieldValues(obj)) {
            if (v == null) continue;
            if (v instanceof String) {
                if (Consts.isSimulatedDid((String) v)) return (String) v;
                continue;
            }
            Class<?> vc = v.getClass();
            if (isLeaf(vc)) continue;
            String did = simDid(v, depth + 1);
            if (did != null) return did;
        }
        return null;
    }

    private static Object[] fieldValues(Object obj) {
        Field[] fields = fieldsOf(obj.getClass());
        if (fields.length == 0) return new Object[0];
        Object[] values = new Object[fields.length];
        int n = 0;
        for (Field f : fields) {
            try {
                values[n++] = f.get(obj);
            } catch (Throwable ignored) {
                // 读不到就当没有
            }
        }
        if (n == values.length) return values;
        Object[] trimmed = new Object[n];
        System.arraycopy(values, 0, trimmed, 0, n);
        return trimmed;
    }

    private static Field[] fieldsOf(Class<?> cls) {
        Field[] cached = sFields.get(cls);
        if (cached != null) return cached;
        Field[] out;
        try {
            out = cls.getDeclaredFields();
            for (Field f : out) {
                try {
                    f.setAccessible(true);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            out = NO_FIELDS;
        }
        sFields.put(cls, out);
        return out;
    }

    /** JDK / Android / JSON / 集合这些类型不用往里钻。*/
    private static boolean isLeaf(Class<?> c) {
        if (c.isPrimitive() || c.isEnum() || c.isArray()) return true;
        String name = c.getName();
        if (name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("android.") || name.startsWith("kotlin.")
                || name.startsWith("dalvik.") || name.startsWith("org.json.")
                || name.startsWith("org.jetbrains.")) {
            return true;
        }
        return Iterable.class.isAssignableFrom(c) || Map.class.isAssignableFrom(c);
    }
}