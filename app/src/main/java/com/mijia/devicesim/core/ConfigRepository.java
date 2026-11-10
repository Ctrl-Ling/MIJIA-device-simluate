/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 10:35:40 +08:00
 */
package com.mijia.devicesim.core;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置的进程内缓存。米家进程和界面进程各自持一份，同一份数据源是 Provider 里的 config.json。
 *
 * 生效路径：界面保存 -> Provider 写文件 + notifyChange -> 米家进程里的 ContentObserver 回调
 * -> {@link #reload()} -> 监听者（HookRegistry / UI 列表）刷新。所以改配置不需要重编模块，
 * 也不需要杀米家进程；已经在屏上的首页列表要等它下一次读列表才会带上新的假设备。
 *
 * <p>「没读到」和「读到但是空的」是两件事，这里严格分开：
 * Provider 不可达时读回来的是 null，此时用本进程 files 目录里的 last-known-good 镜像顶上，
 * 并记住来源；两条路都空才叫未加载。未加载时不缓存空配置当权威 ——
 * 下一次有钩子真要读设备（{@link #current()}）就再试一次，直到读到为止，靠状态判断不靠定时器。
 */
public final class ConfigRepository {

    private ConfigRepository() {
    }

    private static volatile SimConfig sConfig = SimConfig.empty();
    private static volatile Context sAppContext;
    private static volatile boolean sObserving;
    /** 最近一次读取的来源，见 {@link Consts} 里的 SOURCE_*。*/
    private static volatile String sSource = Consts.SOURCE_NONE;
    /** 是否真读到过内容（镜像也算）。*/
    private static volatile boolean sLoaded;
    /** 自上次「数据可能变了」的事件以来，是否已经重试过：避免热路径反复敲 Provider。*/
    private static volatile boolean sRetriedSinceEvent;
    private static final Object LOCK = new Object();
    private static final List<Runnable> sListeners = new ArrayList<>();

    /** 第一次拿到 Context 时调用；重复调用只做一次注册。*/
    public static void init(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            sAppContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            sRetriedSinceEvent = false;
            reloadLocked();
            if (!sObserving) {
                sObserving = observeChanges(sAppContext);
                Log.d("ConfigRepository 初始化完成，观察通道=" + (sObserving ? "Provider" : "仅首屏加载"));
            }
        }
    }

    public static SimConfig current() {
        SimConfig cfg = sConfig;
        if (!sLoaded && !sRetriedSinceEvent) {
            // 上次没读到：现在有人要用设备了，再试一趟（读到以后 sLoaded=true，之后不再重读）
            synchronized (LOCK) {
                if (!sLoaded && !sRetriedSinceEvent) {
                    sRetriedSinceEvent = true;
                    reloadLocked();
                    cfg = sConfig;
                }
            }
        }
        return cfg;
    }

    /** 最近一次配置读到的来源；界面和钩子报告都拿它说话。*/
    public static String source() {
        return sSource;
    }

    /** 是否真读到过一份配置（Provider / adb 兜底 / 本地镜像任一）。*/
    public static boolean loaded() {
        return sLoaded;
    }

    public static Context context() {
        return sAppContext;
    }

    public static void reload() {
        synchronized (LOCK) {
            // 外部事件（保存/通知）意味着数据又可能变了，重试额度重新给满
            sRetriedSinceEvent = false;
            reloadLocked();
        }
        notifyListeners();
    }

    /** 界面保存入口：写盘 + 让本进程立刻看到。*/
    public static boolean save(Context ctx, SimConfig config) {
        if (ctx == null || config == null) return false;
        config.version = SimConfig.CURRENT_VERSION;
        config.updatedAt = System.currentTimeMillis();
        String json = config.toJsonString();
        boolean ok = ConfigChannel.writeConfig(ctx, json);
        if (ok) {
            synchronized (LOCK) {
                sConfig = config;
                sSource = Consts.SOURCE_PROVIDER;
                sLoaded = true;
                sRetriedSinceEvent = false;
            }
            Log.d("配置已保存，设备数=" + config.size());
        } else {
            Log.w("配置写入失败：Provider 不可达（模块未安装完成或被禁用？）");
        }
        return ok;
    }

    public static void addListener(Runnable r) {
        synchronized (sListeners) {
            if (r != null && !sListeners.contains(r)) sListeners.add(r);
        }
    }

    public static void removeListener(Runnable r) {
        synchronized (sListeners) {
            sListeners.remove(r);
        }
    }

    private static void notifyListeners() {
        List<Runnable> copy;
        synchronized (sListeners) {
            copy = new ArrayList<>(sListeners);
        }
        for (Runnable r : copy) {
            try {
                r.run();
            } catch (Throwable t) {
                Log.e("配置监听者回调异常", t);
            }
        }
    }


    private static void reloadLocked() {
        ConfigChannel.Payload p = ConfigChannel.readConfigWithSource(sAppContext);
        sSource = p.source;
        sLoaded = p.loaded();
        SimConfig parsed = SimConfig.parse(p.json);
        sConfig = parsed;
        Log.setVerbose(parsed.verboseLog);
        if (sLoaded && !Consts.SOURCE_MIRROR.equals(p.source)) {
            // 只有真从 Provider / adb 兜底读到的才配当镜像；拿镜像回灌自己没意义
            ConfigChannel.mirrorWrite(sAppContext, Consts.MIRROR_CONFIG, p.json);
        }
        if (sLoaded) {
            Log.v("读到配置：设备 " + parsed.size() + " 台，注入=" + parsed.injectEnabled + "，来源=" + p.source);
        } else {
            // 这条不是啰嗦，是「为什么首页没有假设备」的第一答案，所以不受 verboseLog 控制
            Log.w("没读到任何配置（Provider 不可达且本地无镜像），注入暂时空转；"
                    + "打开一次模块界面或到安全中心给本模块开自启动即可恢复");
        }
    }

    private static boolean observeChanges(Context ctx) {
        try {
            Handler handler = Looper.myLooper() != null ? new Handler(Looper.myLooper()) : new Handler(Looper.getMainLooper());
            ContentObserver observer = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    Log.d("收到配置变更通知，重载");
                    reload();
                }
            };
            ctx.getContentResolver().registerContentObserver(ConfigChannel.uri(Consts.PATH_CONFIG), false, observer);
            return true;
        } catch (Throwable t) {
            Log.w("注册配置观察失败: " + t);
            return false;
        }
    }
}
