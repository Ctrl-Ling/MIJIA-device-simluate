/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-26 15:16:34 +08:00
 */
package com.mijia.devicesim.hook;

import android.content.Context;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 标准插件路由兜底：让「没有专属 RN 插件的模拟型号」也能落到标准插件上。
 *
 * <p>大白话把链路说清楚（11.10.623 反汇编坐实，{@code PluginApi.sendMessage} 私有实现）：
 * <ol>
 *   <li>{@code stdModel = nyr.OooO0o0(model, DeviceStat.specUrn)}（内部走
 *       {@code StdPluginConfig.OooOO0}）；</li>
 *   <li>总开关 {@code hyr.OooO0OO()}（远程配置 {@code std_plugin_enable}，实测本机为 true）；</li>
 *   <li>真正卡人的是 01b9 那道白名单：
 *       {@code StdPluginConfig.OooOOO(model, specUrn)} 返回 false →
 *       直接打日志「is not in std-plugin-whitelist!」并掉进 {@code sendMessageInternal}
 *       自定义插件下载；自定义包里没有这个型号 → 回调 {@code -666}
 *       「未获取到插件下载地址，请检查是否有新版本米家」。</li>
 * </ol>
 *
 * <p>白名单/候选都来自 {@code StdPluginConfig.OooO0oO(model, specUrn)} 给的「候选标准型号」，
 * 它来自 {@code _m_j.mxr} 的 category 映射（服务端下发 + 内置兜底）。映射没命中 → 候选空 →
 * 白名单 false。本机其实已经把标准插件的元配置下载好了
 * （{@code files/std_plugin_config_cache/<uid>_<stdModel>_std_plugin_meta_config_<ver>_CN.json}，
 * 里面 {@code model_list} 就包含我们的型号），所以这里扫本地元配置补候选，
 * 并对模拟型号把「候选 / 型号 / 白名单」三处空值兜住。
 *
 * <p>只对启用中的模拟设备的型号生效；真设备原样放行。
 */
public final class StdPluginRouteHook {

    private static final String GROUP = "std-plugin-route";
    private static final String CL_CONFIG =
            "com.xiaomi.smarthome.framework.plugin.standard.StdPluginConfig";
    private static final String CL_NYR = "_m_j.nyr";
    private static final String CL_LAUNCHER = "com.xiaomi.smarthome.frame.plugin.StdPluginLauncher";
    private static final String CL_PLUGIN_API = "com.xiaomi.smarthome.frame.plugin.PluginApi";
    private static final String NAME_RE = "[oO0]{6,9}";
    private static final String M_CANDIDATES = "OooO0oO";
    private static final String M_MODEL = "OooOO0";
    private static final String M_WHITELIST = "OooOOO";
    private static final String M_BY_CATEGORY = "OooOOOO";
    private static final String M_NYR_MODEL = "OooO0o0";
    private static final String CACHE_DIR = "std_plugin_config_cache";
    private static final String META_SUFFIX = "_std_plugin_meta_config_";

    private static volatile boolean sInstalled;

    /** model -> 命中的标准型号（null 表示扫过但没有）。避免每次点击都扫目录。*/
    private static final Map<String, String> sResolved = new ConcurrentHashMap<>();

    /** model@挂点 -> 上次打印的值，只在「第一次 / 值变了」时打日志，别刷屏。*/
    private static final Map<String, String> sSeen = new ConcurrentHashMap<>();

    private StdPluginRouteHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (sInstalled) return;
        sInstalled = true;
        int candidates = HookSniper.hook(lpparam.classLoader, GROUP, CL_CONFIG,
                new String[]{M_CANDIDATES},
                HookSniper.Shape.of("List", "String", "String"),
                NAME_RE, CANDIDATES);
        int model = HookSniper.hook(lpparam.classLoader, GROUP, CL_CONFIG,
                new String[]{M_MODEL},
                HookSniper.Shape.of("String", "String", "String"),
                NAME_RE, MODEL);
        int whitelist = HookSniper.hook(lpparam.classLoader, GROUP, CL_CONFIG,
                new String[]{M_WHITELIST, M_BY_CATEGORY},
                HookSniper.Shape.of("boolean", "String", "String"),
                NAME_RE, WHITELIST);
        int entry = HookSniper.hook(lpparam.classLoader, GROUP, CL_NYR,
                new String[]{M_NYR_MODEL},
                HookSniper.Shape.of("String", "String", "String"),
                NAME_RE, NYR_MODEL);
        int installed = HookSniper.hook(lpparam.classLoader, GROUP, CL_LAUNCHER,
                new String[]{"isInstalled"},
                HookSniper.Shape.of("boolean", "String"),
                NAME_RE, DIAG_INSTALLED);
        int available = HookSniper.hook(lpparam.classLoader, GROUP, CL_LAUNCHER,
                new String[]{"getAvailableStdPlugin"},
                HookSniper.Shape.of("StdPlugin", "DeviceStat", "boolean", "String"),
                NAME_RE, DIAG_AVAILABLE);
        int open = HookSniper.hook(lpparam.classLoader, GROUP, CL_PLUGIN_API,
                new String[]{"tryOpenStdPluginAfterPluginInstalled"},
                HookSniper.Shape.of("boolean", "String", "String", "StdPlugin", "Intent", "int", "int"),
                NAME_RE, DIAG_TRY_OPEN);
        int download = HookSniper.hook(lpparam.classLoader, GROUP, CL_NYR,
                new String[]{"OooOOO0"},
                HookSniper.Shape.of("void", "boolean", "String", null, null),
                NAME_RE, DIAG_DOWNLOAD);
        int custom = HookSniper.hook(lpparam.classLoader, GROUP, CL_PLUGIN_API,
                new String[]{"sendMessageInternal"},
                HookSniper.Shape.anyArgs("void"),
                NAME_RE, DIAG_CUSTOM);
        int resource = HookSniper.hook(lpparam.classLoader, GROUP, CL_LAUNCHER,
                new String[]{"isResourceValid"},
                HookSniper.Shape.of("boolean", "DeviceStat"),
                NAME_RE, RESOURCE_VALID);
        Log.d("标准插件路由安装: 候选=" + candidates + " 型号=" + model
                + " 白名单=" + whitelist + " 入口=" + entry
                + " 诊断=[" + installed + "/" + available + "/" + open
                + "/" + download + "/" + custom + "] 资源校验=" + resource);
    }

    /** 候选列表：已有的不动，只把本地解析出来的标准型号补进去（原来非空就静默退出，等于没挂）。*/
    private static final XC_MethodHook CANDIDATES = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 2) return;
                String model = (String) param.args[0];
                if (model == null || model.isEmpty()) return;
                if (!isSimulatedModel(model)) return;

                Object existing = param.getResult();
                List<String> out = new ArrayList<>();
                if (existing instanceof List) {
                    for (Object o : (List<?>) existing) {
                        if (o instanceof String) out.add((String) o);
                    }
                }
                String stdModel = resolveStdModel(model);
                if (stdModel != null && !out.contains(stdModel)) {
                    out.add(stdModel);
                    param.setResult(out);
                    logOnce(model, "候选",
                            "本地补候选 " + stdModel + "，原有=" + describe(existing));
                    return;
                }
                logOnce(model, "候选", "原有=" + describe(existing) + " 本地=" + stdModel);
            } catch (Throwable t) {
                Log.w("标准插件路由异常(候选): " + HookUtil.shortMsg(t));
            }
        }
    };

    /** stdModel 出口：空就填本地解析值。*/
    private static final XC_MethodHook MODEL = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 2) return;
                String model = (String) param.args[0];
                if (model == null || model.isEmpty()) return;
                if (!isSimulatedModel(model)) return;

                Object result = param.getResult();
                if (result instanceof String && !((String) result).isEmpty()) {
                    logOnce(model, "型号", "原有=" + result);
                    return;
                }
                String stdModel = resolveStdModel(model);
                if (stdModel == null) return;
                param.setResult(stdModel);
                logOnce(model, "型号", "空 → " + stdModel + "（原值=" + result + "）");
            } catch (Throwable t) {
                Log.w("标准插件路由异常(型号): " + HookUtil.shortMsg(t));
            }
        }
    };

    /** 白名单 / 分类判定：模拟型号返回 false 就强制 true，其余不动。*/
    private static final XC_MethodHook WHITELIST = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 2) return;
                String model = (String) param.args[0];
                if (model == null || model.isEmpty()) return;
                if (!isSimulatedModel(model)) return;

                Object result = param.getResult();
                if (!Boolean.FALSE.equals(result)) {
                    logOnce(model, "白名单", model + "=" + result + "（第二个参数="
                            + param.args[1] + "）");
                    return;
                }
                param.setResult(Boolean.TRUE);
                logOnce(model, "白名单", "false → true（第二个参数=" + param.args[1] + "）");
            } catch (Throwable t) {
                Log.w("标准插件路由异常(白名单): " + HookUtil.shortMsg(t));
            }
        }
    };

    /** {@code nyr.OooO0o0} 入口：空就填本地解析值（内部再走 OooOO0 也已经被兜住）。*/
    private static final XC_MethodHook NYR_MODEL = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 2) return;
                String model = (String) param.args[0];
                if (model == null || model.isEmpty()) return;
                if (!isSimulatedModel(model)) return;

                Object result = param.getResult();
                if (result instanceof String && !((String) result).isEmpty()) {
                    logOnce(model, "入口", "原有=" + result);
                    return;
                }
                String stdModel = resolveStdModel(model);
                if (stdModel == null) return;
                param.setResult(stdModel);
                logOnce(model, "入口", "空 → " + stdModel + "（原值=" + result + "）");
            } catch (Throwable t) {
                Log.w("标准插件路由异常(入口): " + HookUtil.shortMsg(t));
            }
        }
    };

    /**
     * 资源校验：{@code StdPluginLauncher.isResourceValid(DeviceStat)}。
     *
     * <p>这是 -666 的最后一公里——标准插件明明装了（{@code isInstalled=true}），但
     * {@code getAvailableStdPlugin} 里会再问一次 {@code isResourceValid}：
     * 它要拿 specUrn 去 {@code SpecCacheManager} 取 i18n/spec 资源，我们这种本地合成的
     * 假 specUrn 云端没有那条资源，取不到就返回 false → 可用插件 = null → 退回自定义下载 → -666。
     *
     * <p>模拟设备一律放行；真设备照旧。
     */
    private static final XC_MethodHook RESOURCE_VALID = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 1) return;
                String model = deviceModel(param.args[0]);
                if (model == null || !isSimulatedModel(model)) return;
                Object result = param.getResult();
                if (!Boolean.FALSE.equals(result)) {
                    logOnce(model, "资源校验", "=" + result);
                    return;
                }
                param.setResult(Boolean.TRUE);
                logOnce(model, "资源校验", "false → true（假 specUrn 在 SpecCacheManager 里没有资源）");
            } catch (Throwable t) {
                Log.w("标准插件路由异常(资源校验): " + HookUtil.shortMsg(t));
            }
        }
    };

    /** 诊断：标准插件是否已装（stdModel 维度）。*/
    private static final XC_MethodHook DIAG_INSTALLED = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object a = param.args != null && param.args.length > 0 ? param.args[0] : null;
                if (!(a instanceof String) || !isKnownStdModel((String) a)) return;
                logOnce((String) a, "isInstalled", "=" + param.getResult());
            } catch (Throwable ignored) {
            }
        }
    };

    /** 诊断：挑出来的可用标准插件是不是 null（null 就等于要退自定义 → -666）。*/
    private static final XC_MethodHook DIAG_AVAILABLE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 3) return;
                Object std = param.args[2];
                if (!(std instanceof String) || !isKnownStdModel((String) std)) return;
                String devModel = deviceModel(param.args[0]);
                Object plugin = param.getResult();
                logOnce(devModel == null ? (String) std : devModel, "可用插件",
                        "设备=" + devModel + " stdModel=" + std + " 插件="
                                + (plugin == null ? "null（会退自定义 -666）" : plugin.getClass().getName()));
            } catch (Throwable ignored) {
            }
        }
    };

    /** 诊断：真正去开标准插件页那一步成没成。*/
    private static final XC_MethodHook DIAG_TRY_OPEN = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 3) return;
                Object m = param.args[0];
                if (!(m instanceof String) || !isSimulatedModel((String) m)) return;
                logOnce((String) m, "开插件页", "model=" + m + " stdModel=" + param.args[1]
                        + " 结果=" + param.getResult());
            } catch (Throwable ignored) {
            }
        }
    };

    /** 诊断：标准插件下载被触发（说明 isInstalled=false 或强制更新）。*/
    private static final XC_MethodHook DIAG_DOWNLOAD = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 2) return;
                Object std = param.args[1];
                if (!(std instanceof String) || !isKnownStdModel((String) std)) return;
                logOnce((String) std, "标准下载", "force=" + param.args[0] + " stdModel=" + std);
            } catch (Throwable ignored) {
            }
        }
    };

    /** 诊断：自定义插件兜底被调用（就是 -666 那条老路）。*/
    private static final XC_MethodHook DIAG_CUSTOM = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 2) return;
                Object m = param.args[1];
                if (!(m instanceof String) || !isSimulatedModel((String) m)) return;
                logOnce((String) m, "自定义兜底", "sendMessageInternal 被调用");
            } catch (Throwable ignored) {
            }
        }
    };

    /** 是不是我们兜底时给出去的标准型号。*/
    private static boolean isKnownStdModel(String stdModel) {
        if (stdModel == null || stdModel.isEmpty()) return false;
        for (String v : sResolved.values()) {
            if (stdModel.equals(v)) return true;
        }
        return false;
    }

    /** 从 DeviceStat 里取 model 字段（字段是 public 的，换成反射只是防版本差异）。*/
    private static String deviceModel(Object stat) {
        if (stat == null) return null;
        try {
            java.lang.reflect.Field f = stat.getClass().getField("model");
            Object v = f.get(stat);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void logOnce(String model, String tag, String detail) {
        String key = model + "@" + tag;
        String value = String.valueOf(detail);
        String old = sSeen.put(key, value);
        if (value.equals(old)) return;
        Log.d("标准插件路由: " + model + " [" + tag + "] " + detail);
    }

    private static String describe(Object list) {
        if (list == null) return "null";
        if (list instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            int i = 0;
            for (Object o : (List<?>) list) {
                if (i++ > 0) sb.append(',');
                sb.append(o);
            }
            return sb.append(']').toString();
        }
        return list.getClass().getSimpleName();
    }

    /** 型号属于启用中的模拟设备才接管。*/
    private static boolean isSimulatedModel(String model) {
        SimConfig cfg = ConfigRepository.current();
        if (cfg == null) return false;
        for (SimDevice d : cfg.enabledDevices()) {
            if (d != null && model.equals(d.model)) return true;
        }
        return false;
    }

    /**
     * 扫 {@code files/std_plugin_config_cache}：文件名形如
     * {@code 37204929_miot.fridge.spec_std_plugin_meta_config_23_CN.json}，
     * 标准型号在第一个 '_' 之后、{@code _std_plugin_meta_config_} 之前；
     * 内容里的 {@code model_list} 含该型号即命中。
     */
    private static String resolveStdModel(String model) {
        if (sResolved.containsKey(model)) return sResolved.get(model);
        String found = null;
        try {
            Context ctx = DeviceListHook.context();
            if (ctx == null) return null;
            File dir = new File(ctx.getFilesDir(), CACHE_DIR);
            File[] files = dir.listFiles();
            if (files == null) return null;
            // 同一个标准型号会存在多个版本的元配置，逐个文件查（内容小，直接读）
            for (File f : files) {
                if (f == null || !f.isFile()) continue;
                String stdModel = stdModelOf(f.getName());
                if (stdModel == null) continue;
                String json = readText(f);
                if (json == null) continue;
                if (json.contains("\"" + model + "\"")) {
                    found = stdModel;
                    break;
                }
            }
        } catch (Throwable t) {
            Log.v("扫标准插件元配置失败: " + HookUtil.shortMsg(t));
        }
        if (found == null) found = "";
        sResolved.put(model, found);
        return found.isEmpty() ? null : found;
    }

    /** {@code 37204929_miot.fridge.spec_std_plugin_meta_config_23_CN.json} -> {@code miot.fridge.spec}。*/
    private static String stdModelOf(String name) {
        if (name == null || !name.endsWith(".json") || !name.contains(META_SUFFIX)) return null;
        int start = name.indexOf('_');
        int end = name.indexOf(META_SUFFIX);
        if (start <= 0 || end <= start + 1) return null;
        return name.substring(start + 1, end);
    }

    private static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(64, f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), Charset.forName("UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }
}