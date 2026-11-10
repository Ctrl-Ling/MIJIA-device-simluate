/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 10:35:40 +08:00
 */
package com.mijia.devicesim.core;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/**
 * 跨进程读写通道。米家进程拿不到本模块的私有目录（SELinux 挡死），所以：
 * <ol>
 *   <li>主通道 —— 本模块导出的 {@link com.mijia.devicesim.provider.ConfigProvider}，界面写、米家读；</li>
 *   <li>兜底通道 —— {@code /data/local/tmp/mijia_devicesim/}，有 root/adb 的用户可以直接放文件，
 *       存在且能解析时优先级高于 Provider，方便离线灌配置和回归对比。</li>
 *   <li>本地镜像 —— 每次真从上面两条读到东西，都在「本进程自己的」files 目录留一份 last-known-good。
 *       MIUI 的自启动管控（WakePathChecker）会在模块进程没活着时拒绝米家拉起 Provider，
 *       那时读到的就是这份镜像，冷启动也不会整场空转。</li>
 * </ol>
 * 任何一步失败都只返回 null，绝不抛异常到调用方：Hook 层抛异常就是米家崩。
 */
public final class ConfigChannel {

    private ConfigChannel() {
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    public static final String EXTRA_PAYLOAD = "payload";
    public static final String EXTRA_ARG = "arg";
    /** Provider 返回 Bundle 里的结果键。*/
    public static final String EXTRA_OK = "ok";
    /** 本地镜像上限：配置最多几百 KB，超了说明数据有问题，宁可不写也别把宿主目录塞满。*/
    public static final int MAX_MIRROR_BYTES = 1024 * 1024;

    /**
     * 让所有注册了配置观察的进程（= 米家那几个进程）立刻重读一次配置。
     * 界面每次打开、每次保存都喊一嗓子，米家侧就不需要靠定时器去猜配置有没有变。
     */
    public static boolean notifyConfigChanged(Context ctx) {
        if (ctx == null) return false;
        try {
            ctx.getContentResolver().notifyChange(uri(Consts.PATH_CONFIG), null);
            return true;
        } catch (Throwable t) {
            Log.v("配置变更广播失败: " + t);
            return false;
        }
    }

    public static Uri uri(String path) {
        return Uri.parse(Consts.PROVIDER_BASE + path);
    }

    /* ---------- Provider ---------- */

    public static String providerGet(Context ctx, String method, String arg) {
        if (ctx == null) return null;
        try {
            Bundle in = new Bundle();
            if (arg != null) in.putString(EXTRA_ARG, arg);
            Bundle out = ctx.getContentResolver().call(uri(Consts.PATH_CONFIG), method, arg, in);
            if (out == null) return null;
            return out.getString(EXTRA_PAYLOAD);
        } catch (Throwable t) {
            Log.v("providerGet " + method + " 失败: " + t);
            return null;
        }
    }

    public static boolean providerPut(Context ctx, String method, String arg, String payload) {
        if (ctx == null) return false;
        try {
            Bundle in = new Bundle();
            in.putString(EXTRA_PAYLOAD, payload);
            if (arg != null) in.putString(EXTRA_ARG, arg);
            Bundle out = ctx.getContentResolver().call(uri(Consts.PATH_CONFIG), method, arg, in);
            return out != null && out.getBoolean(EXTRA_OK, false);
        } catch (Throwable t) {
            Log.w("providerPut " + method + " 失败: " + t);
            return false;
        }
    }

    public static boolean providerAlive(Context ctx) {
        return "1".equals(providerGet(ctx, Consts.CALL_PING, null));
    }

    /* ---------- 兜底文件 ---------- */

    public static File rootDir() {
        return new File(Consts.ROOT_DIR);
    }

    public static String rootFile(String name) {
        File f = new File(rootDir(), name);
        if (!f.isFile() || !f.canRead()) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(64, f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String s = new String(bos.toByteArray(), UTF8).trim();
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            Log.v("读兜底配置失败 " + name + ": " + t);
            return null;
        }
    }

    /** 只在目录本身可写时成功（root/shell 场景），普通应用进程会静默失败。*/
    public static boolean writeRootFile(String name, String payload) {
        File dir = rootDir();
        File f = new File(dir, name);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        if (!dir.canWrite()) return false;
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(payload.getBytes(UTF8));
            return true;
        } catch (Throwable t) {
            Log.v("写兜底配置失败 " + name + ": " + t);
            return false;
        }
    }

    /* ---------- 宿主本地镜像 ---------- */

    /**
     * 镜像文件放在「当前进程自己的」files 目录：米家进程写的是米家的目录，
     * 界面进程写的是模块的目录。这是唯一一条 MIUI 拦不住的通道 ——
     * 它不需要跨应用启动任何东西，SELinux 也本来就允许进程读写自己的数据。
     */
    public static File mirrorFile(Context ctx, String name) {
        if (ctx == null) return null;
        File dir = ctx.getFilesDir();
        return dir == null ? null : new File(dir, name);
    }

    public static String mirrorRead(Context ctx, String name) {
        File f = mirrorFile(ctx, name);
        if (f == null || !f.isFile() || !f.canRead()) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(64, f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String s = new String(bos.toByteArray(), UTF8).trim();
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            Log.v("读本地镜像失败 " + name + ": " + t);
            return null;
        }
    }

    /** 先写 .tmp 再 rename：镜像永远是一份完整 JSON，不会出现半截。*/
    public static boolean mirrorWrite(Context ctx, String name, String payload) {
        File f = mirrorFile(ctx, name);
        if (f == null || payload == null) return false;
        if (payload.getBytes(UTF8).length > MAX_MIRROR_BYTES) {
            Log.w("本地镜像内容过大，跳过写入: " + name);
            return false;
        }
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        File tmp = new File(parent, f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(payload.getBytes(UTF8));
            out.flush();
        } catch (Throwable t) {
            Log.v("写本地镜像失败 " + name + ": " + t);
            return false;
        }
        if (f.exists() && !f.delete()) return false;
        return tmp.renameTo(f);
    }

    /* ---------- 语义化读写 ---------- */

    /** 一次读取的结果：内容 + 到底从哪读来的（界面和报告都要显示这个）。*/
    public static final class Payload {
        public final String source;
        public final String json;

        public Payload(String source, String json) {
            this.source = source;
            this.json = json;
        }

        public boolean loaded() {
            return json != null;
        }
    }

    /**
     * 配置读取顺序：adb 兜底文件 → Provider → 宿主本地镜像。
     * 前两条都拿到 null 时才落到镜像，并且镜像只当「上次成功读到的那一份」用。
     */
    public static Payload readConfigWithSource(Context ctx) {
        String root = rootFile("config.json");
        if (root != null) return new Payload(Consts.SOURCE_ROOT, root);
        String viaProvider = providerGet(ctx, Consts.CALL_GET_CONFIG, null);
        if (viaProvider != null) return new Payload(Consts.SOURCE_PROVIDER, viaProvider);
        String mirror = mirrorRead(ctx, Consts.MIRROR_CONFIG);
        if (mirror != null) return new Payload(Consts.SOURCE_MIRROR, mirror);
        return new Payload(Consts.SOURCE_NONE, null);
    }

    /** 状态读取顺序和配置一致。*/
    public static Payload readStateWithSource(Context ctx) {
        String root = rootFile("state.json");
        if (root != null) return new Payload(Consts.SOURCE_ROOT, root);
        String viaProvider = providerGet(ctx, Consts.CALL_GET_STATE, null);
        if (viaProvider != null) return new Payload(Consts.SOURCE_PROVIDER, viaProvider);
        String mirror = mirrorRead(ctx, Consts.MIRROR_STATE);
        if (mirror != null) return new Payload(Consts.SOURCE_MIRROR, mirror);
        return new Payload(Consts.SOURCE_NONE, null);
    }

    /** 配置：兜底文件优先，其次 Provider，最后本地镜像。*/
    public static String readConfig(Context ctx) {
        return readConfigWithSource(ctx).json;
    }

    public static boolean writeConfig(Context ctx, String json) {
        return providerPut(ctx, Consts.CALL_PUT_CONFIG, null, json);
    }

    public static String readState(Context ctx) {
        return readStateWithSource(ctx).json;
    }

    public static boolean writeState(Context ctx, String json) {
        return providerPut(ctx, Consts.CALL_PUT_STATE, null, json);
    }

    public static String readSpec(Context ctx, String model) {
        String root = rootFile("specs/" + safeFileName(model) + ".json");
        if (root != null) return root;
        return providerGet(ctx, Consts.CALL_GET_SPEC, model);
    }

    public static boolean writeSpec(Context ctx, String model, String json) {
        return providerPut(ctx, Consts.CALL_PUT_SPEC, model, json);
    }

    /** 移除一份导入的 spec（内置 assets 里的那份不受影响）。*/
    public static boolean deleteSpec(Context ctx, String model) {
        return providerPut(ctx, Consts.CALL_DELETE_SPEC, model, null);
    }

    /** 型号库清单：JSON 数组，元素是 {model, urn, source, plugin}。*/
    public static String listSpecs(Context ctx) {
        return providerGet(ctx, Consts.CALL_LIST_SPEC, null);
    }

    public static String readReport(Context ctx) {
        return providerGet(ctx, Consts.CALL_GET_REPORT, null);
    }

    public static boolean writeReport(Context ctx, String json) {
        return providerPut(ctx, Consts.CALL_PUT_REPORT, null, json);
    }

    public static String readCallLog(Context ctx) {
        return providerGet(ctx, Consts.CALL_GET_CALLLOG, null);
    }

    public static boolean writeCallLog(Context ctx, String json) {
        return providerPut(ctx, Consts.CALL_PUT_CALLLOG, null, json);
    }

    /** 型号名会当文件名用，必须先把路径分隔符和奇怪字符换掉。*/
    public static String safeFileName(String raw) {
        if (raw == null || raw.isEmpty()) return "_empty";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && sb.length() < 160; i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }
}
