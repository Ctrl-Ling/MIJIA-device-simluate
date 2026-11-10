/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 12:28:06 +08:00
 */
package com.mijia.devicesim.core;

/**
 * 「他刚在米家添加设备向导里选了哪个型号」——米家进程里的现场状态。
 *
 * <p>需求原话（2026-11-10）：不是先把模拟设备配到本模块里再等扫描扫出来，而是
 * <b>他在米家选了什么型号，就当场把那台设备模拟出来给他添加</b>。所以配置清单
 * 不是这个功能的数据源，米家向导页的 intent 才是。
 *
 * <p>型号来自哪里（11.9.705 字节码实测，见 docs/HOOKS.md）：
 * <ul>
 *   <li>{@code device.ResetDevicePage}：{@code showActivity()} 里
 *       {@code intent.putExtra("model", pluginDeviceInfo.getModel())}，
 *       {@code onCreate} 再读回 {@code "model"}；读不到还会退到 {@code "device_model"}，
 *       最后退到 {@code "plugin_record"} 这个 Parcelable；</li>
 *   <li>{@code device.choosedevice.ResetPageRoute}：{@code EXTRA_MODEL = "extra_model"}；</li>
 *   <li>{@code device.ResetDevicePageScan}：{@code "model"}。</li>
 * </ul>
 * 三个入口都挂，谁先到算谁的。
 *
 * <p>身份一次合成、反复复用：扫描页每秒都会派发一次结果，如果每次都换 did/token，
 * 列表会抖、还会越堆越多。所以 {@link #capture} 只在<b>型号变了</b>时才重造 did/token/mac；
 * 同一个型号被重新捕获（页面重建）只更新页面键。页面销毁时按页面键释放，
 * 没有定时器、没有额外开关，判的一直是「那个向导页还在不在」这个真实状态。
 */
public final class RequestedModel {

    private RequestedModel() {
    }

    /** 一次「用户想要这台型号」的记录，含现场合成出来的设备身份。*/
    public static final class Record {
        public final String model;
        /** 是哪个页面报上来的（类简名），界面和日志都靠它定位。*/
        public final String page;
        /** 页面实例的 identityHashCode：只有同一个实例销毁时才清除，防止 A 页把 B 页的请求顶掉。*/
        public final int pageKey;
        public final SimDevice device;
        public final long capturedAt;

        Record(String model, String page, int pageKey, SimDevice device, long capturedAt) {
            this.model = model;
            this.page = page;
            this.pageKey = pageKey;
            this.device = device;
            this.capturedAt = capturedAt;
        }
    }

    private static volatile Record sCurrent;

    public static Record get() {
        return sCurrent;
    }

    /**
     * 记下「他现在要添加的型号」。
     *
     * @param model 米家向导 intent 里读到的型号；空字符串/null 直接忽略（不覆盖已有请求）
     * @param page  捕获到型号的 Activity 实例，用于算页面键
     * @return 真的发生了变更（新型号）时返回新记录，同型号重复捕获返回 null
     */
    public static Record capture(String model, Object page) {
        if (model == null || model.isEmpty()) return null;
        Record old = sCurrent;
        int key = System.identityHashCode(page);
        String pageName = page == null ? "?" : page.getClass().getSimpleName();
        if (old != null && old.model.equals(model)) {
            // 同一个型号：只在页面实例变了时换键，身份（did/token/mac）保持不变
            if (old.pageKey != key) {
                sCurrent = new Record(model, pageName, key, old.device, old.capturedAt);
            }
            return null;
        }
        SimDevice d = new SimDevice();
        d.model = model;
        d.did = SimDevice.newDid(null);
        d.token = SimDevice.newToken();
        d.mac = SimDevice.newMac();
        d.name = "";
        d.type = Consts.TYPE_WIFI;
        d.enabled = true;
        Record fresh = new Record(model, pageName, key, d, System.currentTimeMillis());
        sCurrent = fresh;
        return fresh;
    }

    /** 页面销毁：只有销毁的正是当初报型号的那个实例才清除。*/
    public static void release(Object page) {
        if (page == null) return;
        Record cur = sCurrent;
        if (cur != null && cur.pageKey == System.identityHashCode(page)) {
            sCurrent = null;
        }
    }

    /** 一行话，进钩子报告和界面：「xiaomi.vacuum.clean.wyvm (来自 ResetDevicePage)」。*/
    public static String describe() {
        Record r = sCurrent;
        if (r == null) return "";
        return r.model + " ← " + r.page + " did=" + r.device.did;
    }
}
