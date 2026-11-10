/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 12:28:06 +08:00
 */
package com.mijia.devicesim.ui;

import android.content.Context;
import android.net.Uri;

import com.mijia.devicesim.R;
import com.mijia.devicesim.core.ConfigChannel;
import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.hook.ActivationProbe;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 界面这一侧的公共底座：初始化配置缓存、保存并生效、读钩子侧回传的报告、
 * 以及和「文件」SAF 选择器打交道时需要的读写。
 *
 * <p>界面进程不 hook 任何东西，它只当 Provider 的客户端；
 * 所有「改完立刻生效」靠的是 {@link ConfigRepository#save} 之后的 notifyChange。
 */
final class UiBackend {

    private UiBackend() {
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static volatile boolean sInited;

    /** 每个 Activity 的 onCreate 先调这个；重复调用只会刷新一份缓存。*/
    static void ensure(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!sInited) {
            ConfigRepository.init(app);
            sInited = true;
        } else {
            ConfigRepository.reload();
        }
    }

    static SimConfig config() {
        return ConfigRepository.current();
    }

    /** 保存 = 写 Provider + 触发米家进程重载；返回 false 说明 Provider 不通。*/
    static boolean save(Context ctx, SimConfig cfg) {
        return ConfigRepository.save(ctx, cfg);
    }

    /** 放弃未保存的修改：直接从数据源重载，不做任何本地回滚逻辑。*/
    static void revert(Context ctx) {
        ConfigRepository.reload();
    }

    /** 框架是否真的在给本模块下钩子（自挂探针）。*/
    static boolean moduleActive() {
        return ActivationProbe.isActive();
    }

    /** Provider 是否可达；不可达时界面写不进配置，Hook 侧也读不到。*/
    static boolean providerAlive(Context ctx) {
        return ConfigChannel.providerAlive(ctx);
    }

    /**
     * 喊米家那几个进程重读一次配置。
     *
     * <p>为什么界面上要主动喊：MIUI 的自启动管控不让米家把本模块进程拉起来，
     * 冷启动时米家那边读配置会失败。而用户能打开这个界面，说明模块进程此刻是活的，
     * 这一嗓子 notifyChange 过去，米家侧的 ContentObserver 就会重新读一趟 ——
     * 读得到就注入，读不到也有本地镜像兜着，不需要任何定时器。
     */
    static boolean wakeHost(Context ctx) {
        return ConfigChannel.notifyConfigChanged(ctx);
    }

    /** 米家进程上报的那份钩子报告；还没上报过（返回 null）就说明模块没进米家进程。*/
    static org.json.JSONObject hostReport(Context ctx) {
        String raw = ConfigChannel.readReport(ctx);
        if (raw == null) return null;
        try {
            org.json.JSONObject o = new org.json.JSONObject(raw);
            return o.has("process") ? o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 本模块 apk 的版本名，用来判断米家侧那份报告是不是旧版留下的。*/
    static String moduleVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(Consts.MODULE_PACKAGE, 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 米家侧到底读到了什么，一行说清楚。
     * 数据全部来自 Hook 进程写进 Provider 的报告，界面只是转述，不猜。
     */
    static String hostStatus(Context ctx) {
        org.json.JSONObject r = hostReport(ctx);
        if (r == null) return ctx.getString(R.string.msg_host_missing_report);
        String source = r.optString("configSource", Consts.SOURCE_NONE);
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.getString(R.string.lbl_host_report)).append("：")
                .append(ctx.getString(R.string.lbl_host_source)).append('=')
                .append(Labels.sourceText(ctx, source)).append("　")
                .append(ctx.getString(R.string.lbl_host_devices)).append('=')
                .append(r.optInt("injectDevices")).append(" 台　")
                .append("注入开关=").append(r.optInt("injectEnabled", 1) == 1 ? "开" : "关").append("　")
                .append("正在请求的型号=").append(
                        r.optString("requestedModel", "").isEmpty() ? "无（没在添加流程里）"
                                : r.optString("requestedModel")).append("　")
                .append(ctx.getString(R.string.lbl_process)).append("：").append(r.optString("process")).append("　")
                .append(ctx.getString(R.string.lbl_version)).append("：").append(r.optString("moduleVersion"));
        String mine = moduleVersion(ctx);
        String reported = r.optString("moduleVersion", "?");
        if (!"?".equals(mine) && !mine.equals(reported)) {
            sb.append("\n报告来自旧版本 ").append(reported).append("：米家还挂着老模块，完全退出米家再进。");
        } else if (Consts.SOURCE_NONE.equals(source)) {
            sb.append('\n').append(ctx.getString(R.string.msg_host_none));
        } else if (Consts.SOURCE_MIRROR.equals(source)) {
            sb.append('\n').append(ctx.getString(R.string.msg_host_mirror));
        }
        return sb.toString();
    }

    /**
     * 米家进程是否真的被钩住了：以它自己上报的那份报告为准 ——
     * 报告里有 process 字段就说明模块代码在米家进程里跑过，版本号对得上就说明是当前这份 apk。
     *
     * <p>为什么不能只看 {@link #moduleActive()}：那个探针查的是「框架有没有给本模块自己的进程下钩子」，
     * 作用域里只勾米家、没勾模块自身时它永远是 false，可注入其实完全正常。两个信号各说各的，
     * 混成一个就会把「其实好用」的界面写成「未生效」。
     */
    static boolean hostInjected(Context ctx) {
        org.json.JSONObject r = hostReport(ctx);
        if (r == null) return false;
        String mine = moduleVersion(ctx);
        String reported = r.optString("moduleVersion", "?");
        return "?".equals(mine) || mine.equals(reported);
    }

    /** 界面进程自己这侧读到了什么：一行说清楚，用来和米家侧那份对照。
     * 两侧都写在同一个 Provider 后面，但进程不同、能不能拉起彼此也不同，
     * 对照着看才知道卡在哪一层。
     */
    static String localStatus(Context ctx) {
        SimConfig cfg = ConfigRepository.current();
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.getString(R.string.lbl_local_report)).append("：")
                .append(ctx.getString(R.string.lbl_host_source)).append('=')
                .append(Labels.sourceText(ctx, ConfigRepository.source())).append("　")
                .append(ctx.getString(R.string.lbl_devices)).append('=').append(cfg.size()).append(" 台");
        if (!ConfigRepository.loaded()) {
            sb.append("\n").append(ctx.getString(R.string.msg_local_none));
        }
        return sb.toString();
    }

    /** 目标 App 是否装了：没装的话提示用户先去装米家。*/
    static boolean targetInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(Consts.TARGET_PACKAGE, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ---------- 文件读写（SAF Uri） ---------- */

    static boolean writeText(Context ctx, Uri uri, String text) {
        if (ctx == null || uri == null || text == null) return false;
        try (OutputStream out = ctx.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) return false;
            out.write(text.getBytes(UTF8));
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static String readText(Context ctx, Uri uri) {
        if (ctx == null || uri == null) return null;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (in == null) return null;
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), UTF8);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 导出文件名带上时间，省得每次覆盖。*/
    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date());
    }

    static String timeText(long ts) {
        if (ts <= 0) return "—";
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date(ts));
    }

    /** Hook 侧回传的两份 JSON（报告 / 调用流水）：界面进程自己那份内存是空的，只能读 Provider。*/
    static String readReport(Context ctx) {
        String s = ConfigChannel.readReport(ctx);
        return s == null ? "{}" : s;
    }

    static String readCallLog(Context ctx) {
        String s = ConfigChannel.readCallLog(ctx);
        return s == null ? "[]" : s;
    }
}
