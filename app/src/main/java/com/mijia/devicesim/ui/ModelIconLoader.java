/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 18:47:14 +08:00
 */
package com.mijia.devicesim.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 型号图片加载器（米家官方的产品图 URL，懒加载 + 内存/磁盘缓存）。
 *
 * <p>为什么不用 Glide/Picasso：模块只依赖 appcompat，多引一个图片库就多一份体积和混淆风险；
 * 这里只做「按 URL 取图、缩到列表尺寸、丢进缓存」这一件事，几十行就够。
 *
 * <p>列表复用 View 时必须靠 tag 判断回填是否过期，否则快速滚动会串图。
 */
final class ModelIconLoader {

    private ModelIconLoader() {
    }

    private static final int TARGET_PX = 144;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEM =
            new LruCache<String, Bitmap>(8 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    static void load(ImageView iv, String url) {
        load(iv, url, TARGET_PX);
    }

    /**
     * 按目标像素加载：列表用小图，详情页大图和「查看大图」用屏幕宽度。
     * 缓存键带上尺寸，避免大图把列表的小图槽位占掉。
     */
    static void load(ImageView iv, String url, int targetPx) {
        final String key = (url == null ? "" : url) + "@" + targetPx;
        iv.setTag(key);
        if (url == null || url.isEmpty()) {
            iv.setImageDrawable(null);
            return;
        }
        Bitmap cached = MEM.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageDrawable(null);
        final Context app = iv.getContext().getApplicationContext();
        POOL.execute(() -> {
            Bitmap bm = readDisk(app, url, targetPx);
            if (bm == null) bm = download(app, url, targetPx);
            if (bm == null) return;
            MEM.put(key, bm);
            final Bitmap ready = bm;
            MAIN.post(() -> {
                if (key.equals(iv.getTag())) iv.setImageBitmap(ready);
            });
        });
    }

    /* ---------- 缓存读写 ---------- */

    private static File diskFile(Context ctx, String url) {
        File dir = new File(ctx.getCacheDir(), "model_icons");
        if (!dir.exists() && !dir.mkdirs()) return null;
        return new File(dir, sha1(url) + ".img");
    }

    private static Bitmap readDisk(Context ctx, String url, int targetPx) {
        File f = diskFile(ctx, url);
        if (f == null || !f.exists() || f.length() == 0) return null;
        try {
            return decode(f.getAbsolutePath(), targetPx);
        } catch (Throwable t) {
            f.delete();
            return null;
        }
    }

    private static Bitmap download(Context ctx, String url, int targetPx) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "MIJIA-device-simulate/1");
            if (c.getResponseCode() != 200) return null;
            byte[] data;
            try (InputStream in = c.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                data = bos.toByteArray();
            }
            Bitmap bm = decode(data, 0, 0, targetPx);
            if (bm != null) {
                File f = diskFile(ctx, url);
                if (f != null) {
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(data);
                    } catch (Throwable ignored) {
                    }
                }
            }
            return bm;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* ---------- 解码 ---------- */

    private static Bitmap decode(String path, int targetPx) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, probe);
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample(probe.outWidth, probe.outHeight, targetPx);
        return BitmapFactory.decodeFile(path, opt);
    }

    private static Bitmap decode(byte[] data, int width, int height, int targetPx) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, probe);
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample(probe.outWidth, probe.outHeight, targetPx);
        return BitmapFactory.decodeByteArray(data, 0, data.length, opt);
    }

    private static int sample(int w, int h, int targetPx) {
        int s = 1;
        if (w <= 0 || h <= 0 || targetPx <= 0) return s;
        while (w / (s * 2) >= targetPx && h / (s * 2) >= targetPx) s *= 2;
        return s;
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(s.hashCode());
        }
    }
}
