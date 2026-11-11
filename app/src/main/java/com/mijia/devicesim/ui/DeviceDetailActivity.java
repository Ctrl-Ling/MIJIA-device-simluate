/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 18:55:10 +08:00
 */
package com.mijia.devicesim.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.mijia.devicesim.R;
import com.mijia.devicesim.core.DeviceCatalog;
import com.mijia.devicesim.databinding.ActivityDeviceDetailBinding;
import com.mijia.devicesim.databinding.RowDetailFieldBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设备详情：把米家型号目录里这台设备的 68 个原始字段全摆出来。
 *
 * <p>为什么单独开一页：列表只要 7 个字段就能排好，但排查「为什么这个型号显示得不对」时，
 * 剩余 61 个字段（渠道、配网方式、网关标记、版本门槛……）才是线索。这些字段只在需要时才扫
 * details.bin / details-synced.bin，不拖慢列表。
 *
 * <p>排版照苹果技术规格页：顶部产品大图 + 名称，下面按「设备与型号 / 商品与运营 / 配网与连接」
 * 分三张卡片，每行左边中文名 + 英文键，右边原值；值太长就右对齐换行。图能点开看大图。
 */
public class DeviceDetailActivity extends AppCompatActivity {

    static final String EXTRA_MODEL = "model";
    static final String EXTRA_NAME = "name";

    /** 分组一：这台设备是谁。*/
    private static final String[][] BASIC = {
            {"icon_real", "产品图"},
            {"model", "型号"},
            {"name", "设备名称"},
            {"name_smartconfig", "智能配网名称"},
            {"desc", "描述"},
            {"brand_name", "品牌名称"},
            {"brand_id", "品牌 ID"},
            {"cate_name", "大类"},
            {"subcategory_id", "子类 ID"},
            {"pid", "产品 ID"},
            {"pd_id", "产品库 ID"},
            {"inherit_id", "继承型号 ID"},
            {"model_regex", "型号正则"},
            {"min_app_version", "最低 App 版本"},
            {"min_app_version_ios", "最低 iOS 版本"},
            {"verified_time", "认证时间"},
            {"change_time", "更新时间"},
    };

    /** 分组二：米家怎么运营它、能不能绑。*/
    private static final String[][] PRODUCT = {
            {"rank", "排序权重"},
            {"status", "状态"},
            {"ios_status", "iOS 状态"},
            {"display_type_list", "展示类型"},
            {"channel", "渠道"},
            {"space", "空间"},
            {"sc_type", "配网类型"},
            {"sc_status", "配网状态"},
            {"sc_type_more", "扩展配网类型"},
            {"sc_type_more_v2", "扩展配网类型 v2"},
            {"sc_failed", "配网失败标记"},
            {"sc_visible", "配网可见"},
            {"ios_sc_visible", "iOS 配网可见"},
            {"bind_confirm", "绑定确认"},
            {"permission_control", "权限控制"},
            {"wexin_share", "微信分享"},
            {"ct_offline_enter", "离线进入"},
            {"op_history", "运营历史"},
            {"voice_control", "语音控制"},
            {"voice_ctrl_ed", "语音控制编辑"},
            {"member_cnt", "成员数"},
            {"ios_asset", "iOS 资源"},
            {"relations", "关联设备"},
            {"resolve_type", "解析类型"},
            {"privacy_ver", "隐私版本"},
            {"is_visible_thirdcloud_device", "三方云可见"},
            {"is_native", "原生设备"},
            {"localizations", "本地化语言"},
            {"use_xiaoaidev", "使用小爱设备"},
    };

    /** 分组三：用什么协议、怎么连。*/
    private static final String[][] NET = {
            {"fiveG_wifi", "5G Wi-Fi"},
            {"eth_type", "以太网类型"},
            {"bt_rssi", "蓝牙 RSSI"},
            {"bt_bind_style", "蓝牙绑定方式"},
            {"bt_match", "蓝牙匹配"},
            {"bt_is_secure", "蓝牙加密"},
            {"bt_gateway", "蓝牙网关"},
            {"mesh_gateway", "Mesh 网关"},
            {"infrared_gateway", "红外网关"},
            {"zigbee_gateway", "Zigbee 网关"},
            {"plc_gateway", "PLC 网关"},
            {"matter_gateway", "Matter 网关"},
            {"proxy_gateway", "代理网关"},
            {"link_rssi", "链路 RSSI"},
            {"support_uwb", "UWB 支持"},
            {"ssid_type", "SSID 类型"},
            {"centre", "中心节点"},
            {"car_iot_tag", "车联标签"},
            {"is_matter_type", "Matter 类型"},
            {"is_mesh_wifi_device", "Mesh Wi-Fi 设备"},
            {"wpa3_securitymode", "WPA3 模式"},
            {"icon_bluetooth_pairing", "蓝牙配对图"},
    };

    private ActivityDeviceDetailBinding b;
    private String model;
    private String displayName;
    private String iconUrl;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityDeviceDetailBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setTitle(R.string.title_device_detail);

        model = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_MODEL);
        displayName = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_NAME);
        if (model == null) model = "";
        if (displayName == null) displayName = "";

        b.detailName.setText(displayName.isEmpty() ? model : displayName);
        b.detailModel.setText(model.isEmpty() ? getString(R.string.msg_detail_no_model) : model);

        b.detailImage.setOnClickListener(v -> showLargeImage());

        if (model.trim().isEmpty()) {
            b.detailState.setText(R.string.msg_detail_no_model);
            return;
        }

        b.detailState.setText(R.string.msg_detail_loading);
        pool.execute(() -> {
            JSONObject fields = DeviceCatalog.detailFields(this, model);
            runOnUiThread(() -> applyFields(fields));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    /* ---------- 数据 → 界面 ---------- */

    private void applyFields(JSONObject f) {
        if (isFinishing() || isDestroyed()) return;
        if (f == null) {
            b.detailState.setText(R.string.msg_detail_not_found);
            return;
        }
        b.detailState.setVisibility(View.GONE);

        // 名字听调用方的：从设备列表进来显示你自己起的名字，从型号库进来就是官方名；
        // 只有没传名字时才回落到目录里的官方名。
        String name = f.optString("name", "");
        if (displayName.isEmpty() && !name.isEmpty()) b.detailName.setText(name);
        String m = f.optString("model", "");
        if (!m.isEmpty()) b.detailModel.setText(m);

        StringBuilder meta = new StringBuilder();
        String brand = f.optString("brand_name", "");
        if (!brand.isEmpty()) meta.append(brand).append(" · ");
        String cate = f.optString("cate_name", "");
        if (!cate.isEmpty()) meta.append(cate);
        long released = f.optLong("verified_time", 0);
        if (released > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append("上市 ").append(stamp.format(new Date(released * 1000L)));
        }
        b.detailMeta.setText(meta);

        iconUrl = f.optString("icon_real", "").trim();
        if (!iconUrl.isEmpty()) {
            ModelIconLoader.load(b.detailImage, iconUrl, heroPx());
        } else {
            b.detailImage.setImageDrawable(null);
        }

        renderGroup(b.listBasic, BASIC, f);
        renderGroup(b.listProduct, PRODUCT, f);
        renderGroup(b.listNet, NET, f);
    }

    private void renderGroup(LinearLayout list, String[][] defs, JSONObject fields) {
        list.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < defs.length; i++) {
            RowDetailFieldBinding row = RowDetailFieldBinding.inflate(inf, list, false);
            row.fieldLabel.setText(defs[i][1]);
            row.fieldKey.setText(defs[i][0]);
            row.fieldValue.setText(valueOf(fields, defs[i][0]));
            // 每张卡片最后一行不画发丝线，否则卡片底边会多出一道悬浮的线
            row.fieldDivider.setVisibility(i == defs.length - 1 ? View.GONE : View.VISIBLE);
            list.addView(row.getRoot());
        }
    }

    /** 原值怎么显示：时间字段转成可读日期，数组拼成逗号串，空值标「（空）」。*/
    private String valueOf(JSONObject f, String key) {
        Object v = f.opt(key);
        if (v == null || v == JSONObject.NULL) return getString(R.string.label_detail_empty);
        if (v instanceof String) {
            String s = ((String) v).trim();
            return s.isEmpty() ? getString(R.string.label_detail_empty) : s;
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(a.opt(i));
            }
            return sb.length() == 0 ? "[]" : sb.toString();
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? "true" : "false";
        }
        if (v instanceof Number) {
            long n = ((Number) v).longValue();
            if (("verified_time".equals(key) || "change_time".equals(key)) && n > 0) {
                return stamp.format(new Date(n * 1000L));
            }
            return String.valueOf(v);
        }
        return String.valueOf(v);
    }

    /* ---------- 大图 ---------- */

    private void showLargeImage() {
        if (iconUrl == null || iconUrl.isEmpty()) return;
        int width = Math.max(dp(240),
                getResources().getDisplayMetrics().widthPixels - dp(48));
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setView(iv)
                .setPositiveButton(R.string.action_close, null)
                .show();
        // 大图按屏宽解码，缩略图那套 144px 缓存在这里不够用
        ModelIconLoader.load(iv, iconUrl, width);
    }

    private int heroPx() {
        return Math.max(dp(180), getResources().getDisplayMetrics().widthPixels - dp(72));
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
