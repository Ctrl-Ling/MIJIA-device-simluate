/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-12 10:06:33 +08:00
 */
package com.mijia.devicesim.ui;

import android.content.Context;

import com.mijia.devicesim.R;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.SimDevice;

/**
 * 界面上的文字/颜色映射。类型只存在配置里的字符串，展示文案走 strings.xml 的数组，
 * 顺序改了不会牵动 Hook 层。
 */
final class Labels {

    private Labels() {
    }

    static int typeIndex(String type) {
        if (Consts.TYPE_BLE.equals(type)) return 1;
        if (Consts.TYPE_BLE_MESH.equals(type)) return 2;
        if (Consts.TYPE_ZIGBEE_SUB.equals(type)) return 3;
        if (Consts.TYPE_GATEWAY.equals(type)) return 4;
        return 0;
    }

    static String typeFromIndex(Context ctx, int index) {
        String[] names = ctx.getResources().getStringArray(R.array.device_types);
        switch (index) {
            case 1:
                return Consts.TYPE_BLE;
            case 2:
                return Consts.TYPE_BLE_MESH;
            case 3:
                return Consts.TYPE_ZIGBEE_SUB;
            case 4:
                return Consts.TYPE_GATEWAY;
            default:
                return Consts.TYPE_WIFI;
        }
    }

    static String typeLabel(Context ctx, String type) {
        String[] names = ctx.getResources().getStringArray(R.array.device_types);
        int i = typeIndex(type);
        return i >= 0 && i < names.length ? names[i] : type;
    }

    /** 首页设备行用的短标签：WiFi / 蓝牙BLE / 蓝牙Mesh / Zigbee / 网关本体。
     *  编辑页下拉仍用全称 typeLabel，那边要说明「miio/cloud」「子设备」。*/
    static String typeRowLabel(Context ctx, String type) {
        String[] names = ctx.getResources().getStringArray(R.array.device_types_short);
        int i = typeIndex(type);
        return i >= 0 && i < names.length ? names[i] : type;
    }

    /** 列表第二行：型号 · 房间 · 网关 · 异常；接入类型挪到 did 后面单独一行，不在这。*/
    static String meta(Context ctx, SimDevice d) {
        StringBuilder sb = new StringBuilder(d.model.isEmpty() ? "?" : d.model);
        if (!d.roomName.isEmpty()) sb.append(" · ").append(d.roomName);
        if (!d.parentDid.isEmpty()) sb.append(" · 网关 ").append(d.parentDid);
        if (!d.isValid()) sb.append(" · ").append(ctx.getString(R.string.msg_row_invalid));
        return sb.toString();
    }

    /** 米家侧读取来源的代码 -> 看得见的中文；未知代码原样返回，方便老报告也能显示。*/
    static String sourceText(Context ctx, String source) {
        if (Consts.SOURCE_PROVIDER.equals(source)) return ctx.getString(R.string.src_provider);
        if (Consts.SOURCE_MIRROR.equals(source)) return ctx.getString(R.string.src_mirror);
        if (Consts.SOURCE_ROOT.equals(source)) return ctx.getString(R.string.src_root);
        if (Consts.SOURCE_NONE.equals(source) || source == null || source.isEmpty()) {
            return ctx.getString(R.string.src_none);
        }
        return source;
    }

    /** 标题颜色按接入类型区分：WiFi 绿、蓝牙/蓝牙 Mesh 蓝、Zigbee 紫、网关橙。*/
    static int typeColorRes(String type) {
        if (Consts.TYPE_BLE.equals(type)) return R.color.type_ble;
        if (Consts.TYPE_BLE_MESH.equals(type)) return R.color.type_mesh;
        if (Consts.TYPE_ZIGBEE_SUB.equals(type)) return R.color.type_zigbee;
        if (Consts.TYPE_GATEWAY.equals(type)) return R.color.type_gateway;
        return R.color.type_wifi;
    }

    static String yesNo(Context ctx, boolean value) {
        return ctx.getString(value ? R.string.lbl_yes : R.string.lbl_no);
    }

    static String okFail(Context ctx, boolean value) {
        return ctx.getString(value ? R.string.lbl_ok : R.string.lbl_fail);
    }
}
