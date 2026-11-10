package com.xiaomi.smarthome.wificonfig;

import _m_j.bm4;
import _m_j.bv3;
import _m_j.c23;
import _m_j.cec;
import _m_j.cm4;
import _m_j.cuc;
import _m_j.dbd;
import _m_j.dg9;
import _m_j.dn9;
import _m_j.duc;
import _m_j.eof;
import _m_j.euc;
import _m_j.f7g;
import _m_j.fha;
import _m_j.fuc;
import _m_j.gc4;
import _m_j.gq9;
import _m_j.gqb;
import _m_j.guc;
import _m_j.ib0;
import _m_j.ile;
import _m_j.iq9;
import _m_j.iza;
import _m_j.j8a;
import _m_j.jle;
import _m_j.jm0;
import _m_j.kf9;
import _m_j.kmc;
import _m_j.kwg;
import _m_j.l3c;
import _m_j.la8;
import _m_j.lf9;
import _m_j.lle;
import _m_j.lq9;
import _m_j.lwg;
import _m_j.lwh;
import _m_j.mm8;
import _m_j.oe1;
import _m_j.p78;
import _m_j.qb9;
import _m_j.qgd;
import _m_j.r1d;
import _m_j.r6;
import _m_j.rv2;
import _m_j.st9;
import _m_j.tjb;
import _m_j.ud1;
import _m_j.vt7;
import _m_j.wa3;
import _m_j.wab;
import _m_j.wt9;
import _m_j.y06;
import _m_j.y3f;
import _m_j.y9e;
import _m_j.z54;
import _m_j.zvg;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Message;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.airbnb.lottie.LottieAnimationView;
import com.xiaomi.smarthome.application.CommonApplication;
import com.xiaomi.smarthome.core.entity.bluetooth.SearchRequest;
import com.xiaomi.smarthome.core.entity.bluetooth.SearchTask;
import com.xiaomi.smarthome.core.entity.plugin.PluginDeviceInfo;
import com.xiaomi.smarthome.core.server.internal.bluetooth.recognizer.beacon.MiotBleAdvPacket;
import com.xiaomi.smarthome.device.BleDevice;
import com.xiaomi.smarthome.device.Device;
import com.xiaomi.smarthome.device.DeviceFactory;
import com.xiaomi.smarthome.device.DeviceRouterFactory;
import com.xiaomi.smarthome.device.bluetooth.BleDeviceGroup;
import com.xiaomi.smarthome.device.choosedevice.ScanDeviceProgressBar;
import com.xiaomi.smarthome.domain.log.KResultCode;
import com.xiaomi.smarthome.frame.core.o0000O00;
import com.xiaomi.smarthome.framework.page.BaseActivity;
import com.xiaomi.smarthome.framework.page.CommonActivity;
import com.xiaomi.smarthome.framework.statistic.BindStep;
import com.xiaomi.smarthome.infrastruction.wifi.BaseWifiSettingUtils;
import com.xiaomi.smarthome.library.common.util.InjectUtils;
import com.xiaomi.smarthome.library.common.widget.FixHeightGridView;
import com.xiaomi.smarthome.router.annotation.RouterUri;
import com.xiaomi.smarthome.smartconfig.SmartConfigRouterFactory;
import com.xiaomi.smarthome.smartconfig.step.ApConfigV2Step;
import com.xiaomi.smarthome.smartconfig.step.ApConnectFailedStep;
import com.xiaomi.smarthome.smartconfig.subdevice.BindSubDeviceActivity;
import com.xiaomi.smarthome.udp.UdpGatewayBean;
import com.xiaomi.spmstatsdk.oneId.util.StringUtils;
import com.xiaomi.youpin.login.ui.web.LoginBindBaseWebActivity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/* JADX INFO: loaded from: D:\Code\MIJIA-device-simluator\recon\dex\classes12.dex */
@RouterUri(path = "RescanWifiV2Activity")
public class RescanWifiV2Activity extends BaseActivity implements qgd {
    public static final String SELECT_PASSWORD = "select_password";
    public static final String SELECT_SCAN_RESULT = "select_scan_result";
    public static final String SELECT_SSID = "select_ssid";
    public String OooOoO;
    public ScanResult OooOoO0;
    public String OooOoOO;
    public View OooOoo;
    public ScanDeviceProgressBar OooOoo0;
    public BleDeviceGroup OooOooO;
    public ApConnectFailedStep OooOooo;
    public iza Oooo;
    public LottieAnimationView Oooo0;
    public View Oooo000;
    public TextView Oooo00O;
    public TextView Oooo00o;
    public View Oooo0O0;
    public FixHeightGridView Oooo0OO;
    public View Oooo0o;
    public iza Oooo0o0;
    public View Oooo0oO;
    public FixHeightGridView Oooo0oo;
    public final ArrayList OoooO00 = new ArrayList();
    public final ArrayList OoooO0 = new ArrayList();
    public final ArrayList OoooO0O = new ArrayList();
    public final ArrayList OoooO = new ArrayList();
    public int OoooOO0 = 30000;
    public long o000oOoO = -1;
    public boolean OoooOOO = false;
    public int OoooOOo = -1;
    public boolean OoooOo0 = false;
    public boolean OoooOoO = false;
    public int OoooOoo = 0;
    public final ArrayList Ooooo00 = new ArrayList();
    public final ArrayList Ooooo0o = new ArrayList();
    public final j8a OooooO0 = new j8a(this, 25);
    public final BroadcastReceiver OooooOO = new BroadcastReceiver() { // from class: com.xiaomi.smarthome.wificonfig.RescanWifiV2Activity.2
        @Override // android.content.BroadcastReceiver
        public final void onReceive(Context context, Intent intent) {
            boolean booleanExtra = intent.getBooleanExtra("resultsUpdated", false);
            RescanWifiV2Activity rescanWifiV2Activity = RescanWifiV2Activity.this;
            rescanWifiV2Activity.writeLog(eof.OooOOO0("onReceive wifiScan Receiver ", booleanExtra), new Object[0]);
            if (booleanExtra) {
                RescanWifiV2Activity.access$300(rescanWifiV2Activity);
                if (((CommonActivity) rescanWifiV2Activity).mIsPaused && rescanWifiV2Activity.OooOooo != null) {
                    rescanWifiV2Activity.OooOOO0();
                }
            }
        }
    };
    public final BroadcastReceiver OooooOo = new BroadcastReceiver() { // from class: com.xiaomi.smarthome.wificonfig.RescanWifiV2Activity.3
        @Override // android.content.BroadcastReceiver
        public final void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            boolean zEquals = "wifi_scan_result_broadcast".equals(action);
            RescanWifiV2Activity rescanWifiV2Activity = RescanWifiV2Activity.this;
            if (zEquals) {
                if (!rescanWifiV2Activity.OooOOo0()) {
                    RescanWifiV2Activity.access$300(rescanWifiV2Activity);
                    return;
                } else {
                    rescanWifiV2Activity.OoooOOO = true;
                    rescanWifiV2Activity.reportScanResult();
                    return;
                }
            }
            if ("ble_refresh_choose".equals(action)) {
                RescanWifiV2Activity.access$900(rescanWifiV2Activity);
                RescanWifiV2Activity.access$1000(rescanWifiV2Activity);
            } else if ("UdpScanSuccess".equals(action)) {
                RescanWifiV2Activity.access$1100(rescanWifiV2Activity);
            } else if ("PlcSubDeviceScanSuccess".equals(action)) {
                RescanWifiV2Activity.access$1200(rescanWifiV2Activity);
            } else {
                rescanWifiV2Activity.OooOOo();
            }
        }
    };
    public final ib0 Oooooo0 = new ib0(this);
    public final BroadcastReceiver Oooooo = new BroadcastReceiver() { // from class: com.xiaomi.smarthome.wificonfig.RescanWifiV2Activity.5
        @Override // android.content.BroadcastReceiver
        public final void onReceive(Context context, Intent intent) {
            if (intent.getAction().equalsIgnoreCase("mj_kuailian_config_success")) {
                Intent intentOooO0oO = y06.OooO0oO("finish", true);
                qgd qgdVar = RescanWifiV2Activity.this;
                qgdVar.setResult(-1, intentOooO0oO);
                qgdVar.finish();
            }
        }
    };
    public final HashSet OoooooO = new HashSet();
    public boolean Ooooooo = false;
    public final ArrayList o0OoOo0 = new ArrayList();
    public final HashSet ooOO = new HashSet();
    public final ArrayList o00O0O = new ArrayList();

    public static void access$1000(RescanWifiV2Activity rescanWifiV2Activity) {
        rescanWifiV2Activity.getClass();
        ConcurrentHashMap concurrentHashMap = ud1.OooO00o().OooO0OO;
        ArrayList arrayList = new ArrayList();
        if (concurrentHashMap.size() > 0) {
            Iterator it = concurrentHashMap.keySet().iterator();
            while (it.hasNext()) {
                MiotBleAdvPacket miotBleAdvPacket = (MiotBleAdvPacket) concurrentHashMap.get((String) it.next());
                int i = miotBleAdvPacket.OooOoO0;
                if (!arrayList.contains(String.valueOf(i)) && miotBleAdvPacket.OooO00o() && !TextUtils.isEmpty(miotBleAdvPacket.OooOoo) && i > 0) {
                    String strOooo0o0 = o0000O00.OooOooO().Oooo0o0(i);
                    if (TextUtils.isEmpty(strOooo0o0)) {
                        arrayList.add(String.valueOf(i));
                    } else {
                        PluginDeviceInfo pluginDeviceInfoOooo0oO = o0000O00.OooOooO().Oooo0oO(strOooo0o0);
                        if (pluginDeviceInfoOooo0oO == null || pluginDeviceInfoOooo0oO.OooOOo() != 3) {
                            arrayList.add(String.valueOf(i));
                        }
                    }
                }
            }
        }
        if (arrayList.size() > 0) {
            int size = arrayList.size();
            HashSet hashSet = rescanWifiV2Activity.ooOO;
            if (size == hashSet.size()) {
                Iterator it2 = arrayList.iterator();
                while (it2.hasNext()) {
                    if (!hashSet.contains((String) it2.next())) {
                    }
                }
                rescanWifiV2Activity.writeLog("same pids ,so ignore!, pids=" + TextUtils.join(StringUtils.DELIMITER_COMMA, arrayList), new Object[0]);
                rescanWifiV2Activity.OooOo00();
                return;
            }
            ile ileVarOooOO0 = ile.OooOO0();
            duc ducVar = new duc(rescanWifiV2Activity, 1);
            ileVarOooOO0.getClass();
            ile.OooO(rescanWifiV2Activity, null, arrayList, ducVar);
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public static void access$1100(RescanWifiV2Activity rescanWifiV2Activity) {
        rescanWifiV2Activity.getClass();
        ArrayList arrayListOooO = lf9.OooO0oO().OooO();
        for (int i = 0; i < arrayListOooO.size(); i++) {
            f7g f7gVar = (f7g) arrayListOooO.get(i);
            if (f7gVar.OooO00o.equals(rescanWifiV2Activity.OooOoO)) {
                Iterator it = f7gVar.OooO0O0.entrySet().iterator();
                UdpGatewayBean udpGatewayBean = it.hasNext() ? (UdpGatewayBean) ((Map.Entry) it.next()).getValue() : null;
                if (udpGatewayBean != null) {
                    la8 la8Var = tjb.OooOooo;
                    tjb tjbVarOooO0oO = rv2.OooO0oO();
                    String str = udpGatewayBean.OooOo;
                    PluginDeviceInfo pluginDeviceInfoOooO0oO = tjbVarOooO0oO.OooO0oO(str);
                    lq9 lq9Var = new lq9(rescanWifiV2Activity.getContext(), "SmartConfigMainActivity");
                    lq9Var.OooO0O0(23, "strategy_id");
                    lq9Var.OooO0o("model", str);
                    lq9Var.OooO0o("deviceName", pluginDeviceInfoOooO0oO.OooOOO0());
                    lq9Var.OooO0Oo("plc_udp_bean", udpGatewayBean);
                    iq9.OooO0o0((Fragment) null, lq9Var);
                    rescanWifiV2Activity.OooOOoo();
                    Intent intent = new Intent();
                    intent.putExtra("finish", false);
                    rescanWifiV2Activity.setResult(-1, intent);
                    rescanWifiV2Activity.finish();
                    return;
                }
            }
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public static void access$1200(RescanWifiV2Activity rescanWifiV2Activity) {
        rescanWifiV2Activity.getClass();
        ArrayList arrayListOooO0oo = lf9.OooO0oO().OooO0oo();
        for (int i = 0; i < arrayListOooO0oo.size(); i++) {
            dg9 dg9Var = (dg9) arrayListOooO0oo.get(i);
            if (dg9Var.OooO00o.equals(rescanWifiV2Activity.OooOoO) && dg9Var.OooO0O0.size() > 0) {
                lq9 lq9Var = new lq9(rescanWifiV2Activity.getContext(), "MeshSubDeviceConnectActivity");
                lq9Var.OooO0o("chosen_model", rescanWifiV2Activity.OooOoO);
                iq9.OooO0o0((Fragment) null, lq9Var);
                rescanWifiV2Activity.OooOOoo();
                Intent intent = new Intent();
                intent.putExtra("finish", false);
                rescanWifiV2Activity.setResult(-1, intent);
                rescanWifiV2Activity.finish();
                return;
            }
        }
    }

    public static /* synthetic */ int access$2408(RescanWifiV2Activity rescanWifiV2Activity) {
        int i = rescanWifiV2Activity.OoooOoo;
        rescanWifiV2Activity.OoooOoo = i + 1;
        return i;
    }

    /* JADX WARN: Multi-variable type inference failed */
    public static void access$2600(RescanWifiV2Activity rescanWifiV2Activity, bm4 bm4Var) {
        rescanWifiV2Activity.getClass();
        ArrayList arrayList = bm4Var.OooO0OO;
        if (arrayList == null || arrayList.size() <= 0) {
            return;
        }
        cm4 cm4Var = (cm4) arrayList.get(0);
        String str = cm4Var.OooO0o0;
        String str2 = cm4Var.OooO0O0;
        String strOooO00o = bm4Var.OooO00o();
        String str3 = cm4Var.OooO0OO;
        if (!TextUtils.isEmpty(str3)) {
            ArrayList arrayList2 = rescanWifiV2Activity.Ooooo0o;
            if (!arrayList2.contains(str3)) {
                ((com.xiaomi.smarthome.stat.report.OooO00o) dbd.OooO0Oo.OooOo).OooO0Oo("shuihuo_4wifi_combo_click", "foundModel", str3);
                arrayList2.add(str3);
            }
        }
        OtherRegionDeviceActivity.invokeActivity(rescanWifiV2Activity, str, str2, strOooO00o, rescanWifiV2Activity.getIntent().getIntExtra("connect_source", 0), cm4Var.OooO0OO);
    }

    public static boolean access$300(RescanWifiV2Activity rescanWifiV2Activity) {
        List<ScanResult> scanResults;
        PluginDeviceInfo pluginDeviceInfoOooo0oO;
        WifiManager wifiManager = (WifiManager) InjectUtils.getSystemService(rescanWifiV2Activity.getApplicationContext(), "wifi");
        ArrayList arrayList = new ArrayList();
        if (wifiManager != null && (scanResults = wifiManager.getScanResults()) != null) {
            for (ScanResult scanResult : scanResults) {
                String strO0ooOoO = gc4.o0ooOoO(scanResult.SSID);
                if (!TextUtils.isEmpty(strO0ooOoO) && gc4.o00000o0(rescanWifiV2Activity.OooOoO, strO0ooOoO) && ((pluginDeviceInfoOooo0oO = o0000O00.OooOooO().Oooo0oO(strO0ooOoO)) == null || pluginDeviceInfoOooo0oO.OooOOo() != 3)) {
                    ArrayList arrayList2 = rescanWifiV2Activity.OoooO0;
                    if (arrayList2.size() > 0) {
                        Iterator it = arrayList2.iterator();
                        while (it.hasNext()) {
                            if (((ScanResult) it.next()).SSID.equals(scanResult.SSID)) {
                                break;
                            }
                        }
                    }
                    if (!arrayList.contains(strO0ooOoO)) {
                        arrayList.add(strO0ooOoO);
                    }
                }
            }
        }
        if (arrayList.isEmpty()) {
            return false;
        }
        ile ileVarOooOO0 = ile.OooOO0();
        duc ducVar = new duc(rescanWifiV2Activity, 2);
        ileVarOooOO0.getClass();
        ile.OooO(rescanWifiV2Activity, arrayList, null, ducVar);
        return true;
    }

    public static void access$900(RescanWifiV2Activity rescanWifiV2Activity) {
        if (rescanWifiV2Activity.OooOooO != null || rescanWifiV2Activity.OooOoO0 != null) {
            return;
        }
        List<BleDeviceGroup> listOooo0o0 = vt7.Oooo0o0();
        if (rescanWifiV2Activity.OooOoO == null) {
            return;
        }
        ArrayList arrayList = rescanWifiV2Activity.OoooO0O;
        arrayList.clear();
        Iterator it = listOooo0o0.iterator();
        while (true) {
            boolean zHasNext = it.hasNext();
            Runnable runnable = rescanWifiV2Activity.OooooO0;
            if (!zHasNext) {
                for (BleDeviceGroup bleDeviceGroup : listOooo0o0) {
                    for (String str : rescanWifiV2Activity.OoooO00) {
                        if (bleDeviceGroup.getPacket() != null && bleDeviceGroup.getPacket().OooOo != null && bleDeviceGroup.getPacket().OooOoo != null) {
                            if (bleDeviceGroup.containModel(str)) {
                                rescanWifiV2Activity.writeLog(y06.OooOooO("found a combo device suit dest model=", str), new Object[0]);
                                rescanWifiV2Activity.OooOooO = bleDeviceGroup;
                                ((CommonActivity) rescanWifiV2Activity).mHandler.postDelayed(runnable, 5000L);
                                return;
                            } else if (gc4.o00000Oo(str, ((Device) bleDeviceGroup).model)) {
                                arrayList.add(bleDeviceGroup);
                            }
                        }
                    }
                }
                if (arrayList.size() > 0) {
                    rescanWifiV2Activity.OooOo00();
                    return;
                }
                return;
            }
            BleDeviceGroup bleDeviceGroup2 = (BleDeviceGroup) it.next();
            String str2 = rescanWifiV2Activity.OooOoOO;
            if (str2 != null && ((Device) bleDeviceGroup2).mac != null) {
                String strOoOO = gc4.ooOO(str2, (String) null);
                if (bleDeviceGroup2.getPacket() != null && bleDeviceGroup2.getPacket().OooOo != null && bleDeviceGroup2.getPacket().OooOoo != null && strOoOO.equalsIgnoreCase(bleDeviceGroup2.getPacket().OooOoo)) {
                    rescanWifiV2Activity.OooOooO = bleDeviceGroup2;
                    ((CommonActivity) rescanWifiV2Activity).mHandler.postDelayed(runnable, 5000L);
                    return;
                }
            }
        }
    }

    public final void OooOO0o(ScanResult scanResult) {
        if (scanResult == null || !gc4.o00000Oo(gc4.o00o0O(scanResult), this.OooOoO)) {
            return;
        }
        this.OoooO0.add(scanResult);
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:20:0x0071  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final boolean OooOOO(com.xiaomi.smarthome.device.BleDevice r6) {
        /*
            r5 = this;
            r0 = 0
            if (r6 != 0) goto L4
            return r0
        L4:
            java.lang.String r1 = r6.model
            java.lang.String r2 = r6.mac
            r5.OooOOOO(r1, r2)
            com.xiaomi.smarthome.core.server.internal.bluetooth.recognizer.beacon.MiotBleAdvPacket r1 = r6.getPacket()
            r2 = 1
            if (r1 == 0) goto L34
            com.xiaomi.smarthome.core.server.internal.bluetooth.recognizer.beacon.MiotBleAdvPacket r1 = r6.getPacket()
            com.xiaomi.smarthome.core.server.internal.bluetooth.recognizer.beacon.MiotBleAdvPacket$FrameControl r1 = r1.OooOo
            if (r1 == 0) goto L34
            com.xiaomi.smarthome.core.server.internal.bluetooth.recognizer.beacon.MiotBleAdvPacket r1 = r6.getPacket()
            com.xiaomi.smarthome.core.server.internal.bluetooth.recognizer.beacon.MiotBleAdvPacket$FrameControl r1 = r1.OooOo
            int r1 = r1.OooOooo
            com.xiaomi.smarthome.core.server.internal.bluetooth.recognizer.beacon.MiotBleAdvPacket r3 = r6.getPacket()
            com.xiaomi.smarthome.core.server.internal.bluetooth.recognizer.beacon.MiotBleAdvPacket$FrameControl r3 = r3.OooOo
            int r3 = r3.Oooo000
            boolean r3 = com.xiaomi.smarthome.core.server.internal.bluetooth.OooO0OO.OooO0oo(r3)
            if (r3 != 0) goto L32
            r3 = r0
            goto L36
        L32:
            r3 = r2
            goto L36
        L34:
            r1 = r0
            goto L32
        L36:
            if (r1 == 0) goto L3b
            r4 = 2
            if (r1 != r4) goto L87
        L3b:
            if (r3 == 0) goto L87
            java.lang.String r1 = "configComDeviceConnection"
            java.lang.Object[] r0 = new java.lang.Object[r0]
            r5.writeLog(r1, r0)
            _m_j.ye7 r0 = com.xiaomi.smarthome.smartconfig.SmartConfigRouterFactory.getStatBindManager()
            java.lang.String r1 = r5.OooOoO
            com.xiaomi.smarthome.framework.statistic.BindStep r3 = com.xiaomi.smarthome.framework.statistic.BindStep.STEP_SCAN_DEVICE
            r0.OooO00o(r1, r3)
            android.content.Intent r0 = new android.content.Intent
            java.lang.Class<com.xiaomi.smarthome.smartconfig.SmartConfigMainActivity> r1 = com.xiaomi.smarthome.smartconfig.SmartConfigMainActivity.class
            r0.<init>(r5, r1)
            java.lang.String r1 = "strategy_id"
            r3 = 13
            r0.putExtra(r1, r3)
            java.lang.String r1 = "model"
            java.lang.String r3 = r6.model
            r0.putExtra(r1, r3)
            java.lang.String r1 = "combo_ble_mac"
            java.lang.String r3 = r6.mac
            r0.putExtra(r1, r3)
            com.xiaomi.smarthome.core.server.internal.bluetooth.recognizer.beacon.MiotBleAdvPacket r1 = r6.getPacket()
            if (r1 == 0) goto L7c
            com.xiaomi.smarthome.core.server.internal.bluetooth.recognizer.beacon.MiotBleAdvPacket r6 = r6.getPacket()
            java.lang.String r6 = r6.OooOoo
            java.lang.String r1 = "combo_ble_key"
            r0.putExtra(r1, r6)
        L7c:
            r5.OoooOOO = r2
            r5.reportScanResult()
            r6 = 100
            r5.startActivityForResult(r0, r6)
            return r2
        L87:
            java.lang.String r6 = "don't support authMode "
            java.lang.String r6 = _m_j.y06.OooOO0(r1, r6)
            java.lang.Object[] r1 = new java.lang.Object[r0]
            r5.writeLog(r6, r1)
            return r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaomi.smarthome.wificonfig.RescanWifiV2Activity.OooOOO(com.xiaomi.smarthome.device.BleDevice):boolean");
    }

    /* JADX WARN: Multi-variable type inference failed */
    public final boolean OooOOO0() {
        ScanResult scanResult;
        if (!wab.OooOOO0(this, false, (r6) null, (String) null, (dn9) null, getApplicationContext().getResources().getString(2131499192))) {
            writeLog("checkScanResult ,no location permission ,then return !", new Object[0]);
            return false;
        }
        WifiManager wifiManager = (WifiManager) InjectUtils.getSystemService(getApplicationContext(), "wifi");
        List<ScanResult> scanResults = null;
        if (wifiManager != null) {
            try {
                scanResults = wifiManager.getScanResults();
            } catch (Exception unused) {
                writeLog("checkScanResult: WifiManager.getScanResults fail", new Object[0]);
            }
        }
        if (scanResults != null && !this.OoooOOO) {
            for (ScanResult scanResult2 : scanResults) {
                if (scanResult2 != null) {
                    writeLog("checkScanResult, item ssid =" + scanResult2.SSID, new Object[0]);
                    if (gc4.o000000o(gc4.o00o0O(scanResult2), this.OooOoO) || ((scanResult = this.OooOoO0) != null && gc4.ooOO(scanResult.BSSID, scanResult.SSID).equals(gc4.ooOO(scanResult2.BSSID, scanResult2.SSID)))) {
                        this.OoooOOO = true;
                        reportScanResult();
                        if (((CommonActivity) this).mIsPaused && this.OooOooo != null) {
                            finishActivity(102);
                        }
                        return gotoWifiSettingPage(scanResult2);
                    }
                }
            }
        }
        return false;
    }

    public final void OooOOOO(String str, String str2) {
        String str3;
        jm0.OooOo0 = str2;
        PluginDeviceInfo pluginDeviceInfoOooo0oO = o0000O00.OooOooO().Oooo0oO(str);
        if (pluginDeviceInfoOooo0oO != null) {
            r1d.o0OO00O(pluginDeviceInfoOooo0oO.OoooOO0, pluginDeviceInfoOooo0oO.Oooo000, pluginDeviceInfoOooo0oO.OooooO0, r1d.OooOO0O, pluginDeviceInfoOooo0oO.OooOO0o());
        }
        if (!this.OoooO00.contains(str) || TextUtils.isEmpty(jm0.OooOoO)) {
            jm0.OoooOo0();
            str3 = "BIND_START";
        } else {
            str3 = "BINDING";
        }
        String str4 = str3;
        kmc kmcVar = kmc.OooO0Oo;
        kmcVar.OooO0Oo(new Object[]{"ref", r1d.OoooOO0(), "sub_ref", r1d.OoooOO0(), "item_type", "device", "card_id", "10122", "mac", str2, "did", "", r1d.Oooo0()});
        kmcVar.OooO00o(new Object[]{"ref", r1d.OoooOO0(), "sub_ref", r1d.OoooOO0(), "item_type", "device", "card_id", "10122", "mac", str2, "did", "", r1d.Oooo0()});
        kmcVar.OooOO0(LoginBindBaseWebActivity.SOURCE_BIND, lwh.Oooo0oO(new Object[]{"ref", r1d.OoooOO0(), "sub_ref", r1d.OoooOO0(), "item_type", "device", "card_id", "10122", "bind_status", str4, "mac", str2, "did", "", r1d.Oooo0()}));
    }

    /* JADX WARN: Multi-variable type inference failed */
    public final void OooOOOo() {
        writeLog("用户点击：手动连接", new Object[0]);
        SmartConfigRouterFactory.getStatClickManager().OooOo00(this.OooOoO);
        if (this.OooOooo == null) {
            this.Ooooooo = true;
            ViewGroup viewGroup = (ViewGroup) findViewById(2131434432);
            if (this.OooOoO0 == null) {
                jle.OooO0oo().OooO0O0("device_ap");
            } else {
                jle.OooO0oo().OooOOOO("device_ap", this.OooOoO0);
            }
            jle jleVarOooO0oo = jle.OooO0oo();
            ScanResult scanResult = this.OooOoO0;
            jleVarOooO0oo.OooOOOO("wifi_ssid", scanResult != null ? scanResult.SSID : gc4.o0OoOo0(this.OooOoO));
            ApConnectFailedStep apConnectFailedStep = new ApConnectFailedStep();
            this.OooOooo = apConnectFailedStep;
            apConnectFailedStep.registerListener(this.Oooooo0);
            this.OooOooo.createCurrentStep(this);
            viewGroup.addView(this.OooOooo.getView());
            this.OooOoo0.stop();
            SmartConfigRouterFactory.getCoreApiManager().stopScan();
            SmartConfigRouterFactory.getStatBindManager().OooO00o(this.OooOoO, BindStep.STEP_SCAN_DEVICE);
        }
    }

    public final boolean OooOOo() {
        if (DeviceRouterFactory.getApDeviceManager().OooO0OO() == null || DeviceRouterFactory.getApDeviceManager().OooO0OO().size() <= 0) {
            return false;
        }
        for (ScanResult scanResult : DeviceRouterFactory.getApDeviceManager().OooO0OO()) {
            if (this.OooOoO0 != null && gc4.Ooooooo(scanResult) == DeviceFactory.AP_TYPE.AP_MIDEVICE && gc4.oo000o(this.OooOoO0).equals(gc4.ooOO(scanResult.BSSID, scanResult.SSID))) {
                this.OooOoO0 = scanResult;
                gotoWifiSettingPage(scanResult);
                OooOOoo();
                return true;
            }
            for (String str : this.OoooO00) {
                if (str != null && gc4.o000000o(gc4.Oooooo(scanResult), str)) {
                    gotoWifiSettingPage(scanResult);
                    OooOOoo();
                    return true;
                }
            }
        }
        return false;
    }

    public final boolean OooOOo0() {
        StringBuilder sb = new StringBuilder("findApDevice ");
        ArrayList<ScanResult> arrayList = zvg.OooO0o;
        sb.append(arrayList);
        gq9.OooO0OO(3, "RescanWifi", sb.toString());
        ArrayList arrayList2 = this.OoooO0;
        arrayList2.clear();
        if (arrayList != null) {
            for (ScanResult scanResult : arrayList) {
                if (this.OooOoO0 != null && gc4.o00000(scanResult) == DeviceFactory.AP_TYPE.AP_MIAP && gc4.oo000o(this.OooOoO0).equals(gc4.oo000o(scanResult))) {
                    this.OooOoO0 = scanResult;
                    writeLog("found a ap device suit dest scanResult", new Object[0]);
                    gotoWifiSettingPage(scanResult);
                    OooOOoo();
                    return true;
                }
                String str = this.OooOoOO;
                if (str != null) {
                    if (gc4.o00000(scanResult) == DeviceFactory.AP_TYPE.AP_MIAP && gc4.ooOO(this.OooOoOO, (String) null).equalsIgnoreCase(gc4.oo000o(scanResult))) {
                        this.OooOoO0 = scanResult;
                        writeLog("found a ap device suit dest mac", new Object[0]);
                        gotoWifiSettingPage(scanResult);
                        OooOOoo();
                        return true;
                    }
                    OooOO0o(scanResult);
                } else if (!(this.OooOoO0 == null && str == null) && (y3f.OooO0o0(this.OooOoO) || y3f.OooO0o(this.OooOoO))) {
                    OooOO0o(scanResult);
                } else {
                    for (String str2 : this.OoooO00) {
                        if (str2 != null && gc4.o000000o(gc4.o00o0O(scanResult), str2)) {
                            gotoWifiSettingPage(scanResult);
                            OooOOoo();
                            gq9.OooO0OO(3, "RescanWifi", "find device use mSearchModels ");
                            return true;
                        }
                    }
                    OooOO0o(scanResult);
                }
            }
            if (arrayList2.size() > 0) {
                OooOo00();
            }
        }
        return false;
    }

    public final void OooOOoo() {
        this.OooOoo0.stop();
    }

    /* JADX WARN: Multi-variable type inference failed */
    public final void OooOo() {
        if (wab.OooO0o(this)) {
            ArrayList arrayList = new ArrayList();
            int i = this.OoooOO0 / 5000;
            for (int i2 = 0; i2 < i; i2++) {
                SearchTask searchTask = new SearchTask();
                searchTask.OooOo = 2;
                searchTask.OooOoO0 = 5000;
                searchTask.OooOoO = null;
                arrayList.add(searchTask);
            }
            SearchRequest searchRequest = new SearchRequest();
            searchRequest.OooOo = arrayList;
            vt7.oo000o(searchRequest, (st9) null);
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public final void OooOo0() {
        this.OooOoo0.stop();
        mm8.OooO0O0(this).OooO0o0(this.OooooOo);
        SmartConfigRouterFactory.getCoreApiManager().setScanTimePeriod(10000);
        SmartConfigRouterFactory.getCoreApiManager().stopScan();
        if (wab.OooO0o(this)) {
            vt7.o0OO00O();
        }
        SmartConfigRouterFactory.getStatPageV2Manager().OooO0oo(((CommonActivity) this).mEnterTime, this.OooOoO);
        SmartConfigRouterFactory.getStatBindManager().OooO0OO(this.OooOoO, BindStep.STEP_SCAN_DEVICE, "pause scan");
    }

    /* JADX WARN: Multi-variable type inference failed */
    public final void OooOo00() {
        String str;
        if (this.Oooo0o.getVisibility() == 0 || !this.OoooOoO) {
            return;
        }
        ArrayList arrayList = new ArrayList();
        ArrayList arrayList2 = this.OoooO0;
        ArrayList<BleDevice> arrayList3 = this.OoooO0O;
        HashMap map = new HashMap();
        for (int size = arrayList2.size() - 1; size >= 0; size--) {
            ScanResult scanResult = (ScanResult) arrayList2.get(size);
            String strOoOO = gc4.ooOO(scanResult.BSSID, scanResult.SSID);
            if (strOoOO != null) {
                map.put(strOoOO.toUpperCase(Locale.ENGLISH), scanResult);
            }
        }
        for (int size2 = arrayList3.size() - 1; size2 >= 0; size2--) {
            BleDevice bleDevice = (BleDevice) arrayList3.get(size2);
            MiotBleAdvPacket packet = bleDevice.getPacket();
            if (gc4.o000000O(bleDevice) && packet != null && (str = packet.OooOoo) != null && map.containsKey(str.toUpperCase(Locale.ENGLISH))) {
                arrayList3.remove(size2);
            }
        }
        arrayList.addAll(arrayList2);
        arrayList.addAll(arrayList3);
        ArrayList<bm4> arrayList4 = this.o00O0O;
        ArrayList<bm4> arrayList5 = this.o0OoOo0;
        HashSet hashSet = new HashSet();
        ArrayList arrayList6 = this.OoooO;
        arrayList6.clear();
        int size3 = arrayList4.size();
        HashSet hashSet2 = this.OoooooO;
        if (size3 > 0) {
            for (bm4 bm4Var : arrayList4) {
                String str2 = bm4Var.OooO00o;
                if (!hashSet.contains(str2)) {
                    hashSet.add(str2);
                    arrayList6.add(bm4Var);
                    if (!hashSet2.contains(str2)) {
                        hashSet2.add(str2);
                        OooOo0O(str2);
                    }
                }
            }
        }
        if (arrayList5.size() > 0) {
            for (bm4 bm4Var2 : arrayList5) {
                String str3 = bm4Var2.OooO00o;
                if (!hashSet.contains(str3)) {
                    hashSet.add(str3);
                    arrayList6.add(bm4Var2);
                    if (!hashSet2.contains(str3)) {
                        hashSet2.add(str3);
                        OooOo0O(str3);
                    }
                }
            }
        }
        hashSet.clear();
        ArrayList arrayList7 = new ArrayList();
        Iterator it = arrayList2.iterator();
        while (it.hasNext()) {
            String strO00ooo = gc4.o00ooo(((ScanResult) it.next()).SSID);
            if (!arrayList7.contains(strO00ooo)) {
                arrayList7.add(strO00ooo);
            }
        }
        for (BleDevice bleDevice2 : arrayList3) {
            if (!arrayList7.contains(((Device) bleDevice2).model)) {
                arrayList7.add(((Device) bleDevice2).model);
            }
        }
        for (int size4 = arrayList6.size() - 1; size4 >= 0; size4--) {
            String str4 = ((bm4) arrayList6.get(size4)).OooO00o;
            if (!TextUtils.isEmpty(str4) && arrayList7.contains(str4)) {
                arrayList6.remove(size4);
            }
        }
        arrayList.addAll(arrayList6);
        runOnUiThread(new l3c(7, this, arrayList));
    }

    public final void OooOo0O(String str) {
        if (TextUtils.isEmpty(str)) {
            return;
        }
        ArrayList arrayList = this.Ooooo00;
        if (arrayList.contains(str)) {
            return;
        }
        ((com.xiaomi.smarthome.stat.report.OooO00o) dbd.OooO0OO.OooOo).OooO0Oo("shuihuo_4wifi_combo_show", "destModel", this.OooOoO, "foundModel", str);
        arrayList.add(str);
    }

    /* JADX WARN: Multi-variable type inference failed */
    public final void OooOo0o() {
        writeLog("onResume, WLAN 打开 = %s, 地理位置权限打开 = %s", Boolean.valueOf(fha.OooO0o0()), Boolean.valueOf(wab.OooOOoo() && wab.OooOo00()));
        SmartConfigRouterFactory.getStatBindManager().OooO0o(this.OooOoO, BindStep.STEP_SCAN_DEVICE);
        if (this.OooOooo == null) {
            boolean zOooOOo0 = OooOOo0();
            this.OoooOOo = zOooOOo0 ? 1 : 2;
            if (zOooOOo0) {
                this.OoooOOO = true;
                reportScanResult();
            }
            if (!zOooOOo0 && !OooOOo()) {
                IntentFilter intentFilter = new IntentFilter("wifi_scan_result_broadcast");
                intentFilter.addAction("ap_device_msg");
                intentFilter.addAction("ble_refresh_choose");
                intentFilter.addAction("UdpScanSuccess");
                intentFilter.addAction("PlcSubDeviceScanSuccess");
                mm8.OooO0O0(this).OooO0OO(this.OooooOo, intentFilter);
                SmartConfigRouterFactory.getCoreApiManager().setScanTimePeriod(BindSubDeviceActivity.RESULT_OK);
                lwg lwgVarOooOO0O = lwg.OooOO0O();
                String str = this.OooOoO;
                lwgVarOooOO0O.getClass();
                SmartConfigRouterFactory.getCoreApiManager().OooO0O0(str, new kwg(lwgVarOooOO0O, 1));
                OooOo();
                this.OooOoo0.reset();
                this.Oooo00O.setText(2131497896);
                this.Oooo00o.setText(2131500387);
                this.OooOoo.setVisibility(8);
                this.Oooo0o.setVisibility(8);
                this.Oooo0.setVisibility(0);
                this.Oooo0O0.setVisibility(8);
                lle lleVarOooO0OO = lle.OooO0OO();
                if (lle.OooO0o(this.OooOoO, lleVarOooO0OO.OoooOOo, lleVarOooO0OO.OoooOo0, lleVarOooO0OO.OoooOOO)) {
                    this.OoooOO0 = 40000;
                } else {
                    this.OoooOO0 = 30000;
                }
                this.OooOoo0.setTime(this.OoooOO0);
                this.OooOoo0.start();
            }
        }
        SmartConfigRouterFactory.getStatPageV2Manager().OooO0oo(0L, this.OooOoO);
    }

    public String getRefName() {
        iza izaVar = this.Oooo0o0;
        if ((izaVar != null && izaVar.getCount() != 0) || this.OoooOOO) {
            return r1d.OoooOO0();
        }
        return "device_scanstart/" + this.OooOoO;
    }

    /* JADX WARN: Multi-variable type inference failed */
    public Object[] getRefParams() {
        la8 la8Var = tjb.OooOooo;
        PluginDeviceInfo pluginDeviceInfoOooO0oO = rv2.OooO0oO().OooO0oO(this.OooOoO);
        int intExtra = getIntent().getIntExtra("connect_source", 0);
        return pluginDeviceInfoOooO0oO != null ? new Object[]{"addtype", Integer.valueOf(intExtra), "device_model", this.OooOoO, "subcategoryid", Integer.valueOf(pluginDeviceInfoOooO0oO.OoooOO0), "pid", Integer.valueOf(pluginDeviceInfoOooO0oO.Oooo000), "sctype", Integer.valueOf(pluginDeviceInfoOooO0oO.OooooO0)} : new Object[]{"addtype", Integer.valueOf(intExtra)};
    }

    /* JADX WARN: Multi-variable type inference failed */
    public boolean gotoWifiSettingPage(ScanResult scanResult) {
        Intent intentOooO0OO = wa3.OooO0OO(this, scanResult, gc4.o00o0O(scanResult));
        if (intentOooO0OO == null) {
            return false;
        }
        if (scanResult != null) {
            OooOOOO(gc4.o0ooOoO(scanResult.SSID), scanResult.BSSID);
        }
        SmartConfigRouterFactory.getStatBindManager().OooO00o(this.OooOoO, BindStep.STEP_SCAN_DEVICE);
        p78.OooooOO(this, intentOooO0OO);
        intentOooO0OO.putExtra("select_scan_result", getIntent().getParcelableExtra("select_scan_result"));
        intentOooO0OO.putExtra(SELECT_SSID, getIntent().getStringExtra(SELECT_SSID));
        intentOooO0OO.putExtra("select_password", getIntent().getStringExtra("select_password"));
        if (getIntent() != null) {
            intentOooO0OO.putExtra("from_miui", getIntent().getBooleanExtra("from_miui", false));
        }
        if (getIntent() != null && getIntent().hasExtra("key_qrcode_oob")) {
            intentOooO0OO.putExtra("key_qrcode_oob", getIntent().getStringExtra("key_qrcode_oob"));
        }
        Object objOooO0OO = jle.OooO0oo().OooO0OO("wifi_bind_key");
        if (objOooO0OO != null) {
            gq9.OooO0OO(3, ApConfigV2Step.LOGGER_AP_TAG, "check exist bindKey");
            intentOooO0OO.putExtra("wifi_bind_key", (String) objOooO0OO);
        }
        startActivityForResult(intentOooO0OO, 100);
        return true;
    }

    public void handleMessage(Message message) {
        if (message.what != 89) {
            super/*com.xiaomi.smarthome.framework.page.CommonActivity*/.handleMessage(message);
            return;
        }
        PluginDeviceInfo pluginDeviceInfoOooo0oO = o0000O00.OooOooO().Oooo0oO(this.OooOoO);
        if (!"midr.cardvr.v1".equalsIgnoreCase(this.OooOoO) || !BaseWifiSettingUtils.isUseQWifiConnectInterface(getContext())) {
            if (pluginDeviceInfoOooo0oO == null || pluginDeviceInfoOooo0oO.OooooO0 != 25) {
                this.OooOoo.setVisibility(0);
            } else {
                this.OooOoo.setVisibility(8);
            }
        }
        if (pluginDeviceInfoOooo0oO != null && pluginDeviceInfoOooo0oO.OooooO0 == 26) {
            this.OooOoo.setVisibility(8);
            this.Oooo00o.setText(2131497031);
        }
        this.OoooOoO = true;
        if (this.OoooO0.size() > 0 || this.OoooO0O.size() > 0) {
            OooOo00();
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public boolean isValid() {
        return (isFinishing() || isDestroyed()) ? false : true;
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onActivityResult(int i, int i2, @Nullable Intent intent) {
        z54.OooOo0(i, i2, "onActivityResult requestCode=", ":102,resultCode=", "RescanWifiV2Activity");
        super/*com.xiaomi.smarthome.framework.page.CommonActivity*/.onActivityResult(i, i2, intent);
        boolean booleanExtra = intent != null ? intent.getBooleanExtra("finish", true) : false;
        if (i == 102 && this.OooOooo != null) {
            if (OooOOO0()) {
                return;
            }
            onBackPressed();
        } else {
            if (booleanExtra) {
                Intent intent2 = new Intent();
                intent2.putExtra("finish", true);
                setResult(-1, intent2);
            }
            finish();
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onBackPressed() {
        PluginDeviceInfo pluginInfo;
        View view;
        if (this.Oooo0oO.getVisibility() == 0) {
            OooOo0o();
            this.Oooo0oO.setVisibility(8);
            return;
        }
        ApConnectFailedStep apConnectFailedStep = this.OooOooo;
        if (apConnectFailedStep != null && (view = apConnectFailedStep.getView()) != null) {
            ViewGroup viewGroup = (ViewGroup) view.getParent();
            if (viewGroup != null) {
                viewGroup.removeView(view);
            }
            this.OooOooo.onBackPressed();
            jle.OooO0oo().OooO0O0("device_ap");
            jle.OooO0oo().OooO0O0("wifi_ssid");
            this.OooOooo = null;
            this.OooOoo0.reset();
            this.Oooo00O.setText(2131497896);
            this.Oooo00o.setText(2131500387);
            this.OooOoo.setVisibility(8);
            this.Oooo0o.setVisibility(8);
            this.Oooo0.setVisibility(0);
            this.Oooo0O0.setVisibility(8);
            this.OooOoo0.start();
            lwg lwgVarOooOO0O = lwg.OooOO0O();
            String str = this.OooOoO;
            lwgVarOooOO0O.getClass();
            SmartConfigRouterFactory.getCoreApiManager().OooO0O0(str, new kwg(lwgVarOooOO0O, 1));
            OooOo();
            SmartConfigRouterFactory.getStatBindManager().OooO0o(this.OooOoO, BindStep.STEP_SCAN_DEVICE);
            return;
        }
        if (!wab.OooOOo(CommonApplication.getAppContext(), "android.permission.ACCESS_FINE_LOCATION")) {
            y9e.Ooooo0o(KResultCode.LocationPermissionClosed, "RescanWifiV2Activity");
        } else if (!wab.OooO0o(this)) {
            y9e.Ooooo0o(KResultCode.BluetoothPermissionClosed, "RescanWifiV2Activity");
        } else if (this.OoooO.size() > 0) {
            Iterator it = this.OoooO.iterator();
            while (it.hasNext()) {
                writeLog("存在水货设备：" + ((bm4) it.next()).OooO00o, new Object[0]);
            }
            y9e.Ooooo0o(KResultCode.ShuiHuo, "RescanWifiV2Activity");
        } else if (!TextUtils.isEmpty(this.OooOoO) && (pluginInfo = SmartConfigRouterFactory.getCoreApiManager().getPluginInfo(this.OooOoO)) != null && ((pluginInfo.OooooO0 == 3 || pluginInfo.OooooO0 == 20 || pluginInfo.OooooO0 == 18 || pluginInfo.OooooO0 == 32) && !oe1.o0000ooO())) {
            y9e.Ooooo0o(KResultCode.BluetoothIsNotOpen, "RescanWifiV2Activity");
        } else if (!fha.OooO0o0()) {
            y9e.Ooooo0o(KResultCode.WlanClosed, "RescanWifiV2Activity");
        } else if (this.OoooOo0 || this.OoooOoo > 0) {
            y9e.Ooooo0o(KResultCode.ScanTimeOut, "RescanWifiV2Activity");
        } else {
            y9e.Ooooo0o(KResultCode.USER_WANTED_CANCEL, "RescanWifiV2Activity");
        }
        Intent intent = new Intent();
        intent.putExtra("finish", false);
        setResult(-1, intent);
        finish();
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onCreate(Bundle bundle) {
        PluginDeviceInfo pluginDeviceInfoOooo0oO;
        ArrayList arrayList;
        int i;
        super.onCreate(bundle);
        setContentView(2130905779);
        Intent intent = getIntent();
        if (intent != null && intent.getParcelableExtra("scanResult") != null) {
            this.OooOoO0 = (ScanResult) intent.getParcelableExtra("scanResult");
        }
        this.o000oOoO = System.currentTimeMillis();
        if (intent != null) {
            this.OooOoO = intent.getStringExtra("model");
            this.OooOoOO = intent.getStringExtra("mac");
            writeLog("onCreate ,dest model = %s", this.OooOoO);
            intent.putExtra("connect_unique", UUID.randomUUID().toString());
            qb9.OooOOO0(1000L, jle.OooO0oo(), "kuailian_cost_start");
            if (!TextUtils.isEmpty(this.OooOoO)) {
                la8 la8Var = tjb.OooOooo;
                Map mapOooO = rv2.OooO0oO().OooO();
                ArrayList arrayList2 = new ArrayList();
                if (mapOooO.size() > 0) {
                    Iterator it = mapOooO.entrySet().iterator();
                    while (it.hasNext()) {
                        arrayList2.add((PluginDeviceInfo) ((Map.Entry) it.next()).getValue());
                    }
                }
                PluginDeviceInfo pluginInfo = SmartConfigRouterFactory.getCoreApiManager().getPluginInfo(this.OooOoO);
                Iterator it2 = arrayList2.iterator();
                while (true) {
                    boolean zHasNext = it2.hasNext();
                    arrayList = this.OoooO00;
                    if (!zHasNext) {
                        break;
                    }
                    PluginDeviceInfo pluginDeviceInfo = (PluginDeviceInfo) it2.next();
                    if (pluginInfo != null && (i = pluginDeviceInfo.o00o0O) != 0 && i == pluginInfo.OooOOOO()) {
                        arrayList.add(pluginDeviceInfo.OooOO0o());
                    }
                }
                arrayList.add(this.OooOoO);
            }
            la8 la8Var2 = tjb.OooOooo;
            PluginDeviceInfo pluginDeviceInfoOooO0oO = rv2.OooO0oO().OooO0oO(this.OooOoO);
            if (pluginDeviceInfoOooO0oO != null) {
                r1d.o0OO00O(pluginDeviceInfoOooO0oO.OoooOO0, pluginDeviceInfoOooO0oO.Oooo000, pluginDeviceInfoOooO0oO.OooooO0, r1d.OooOO0O, this.OooOoO);
            }
        }
        if (!TextUtils.isEmpty(this.OooOoO) && (pluginDeviceInfoOooo0oO = o0000O00.OooOooO().Oooo0oO(this.OooOoO)) != null) {
            ((TextView) findViewById(2131429245)).setText(pluginDeviceInfoOooo0oO.OooOOO0());
        }
        findViewById(2131431879).setOnClickListener(new euc(this));
        this.Oooo00O = (TextView) findViewById(2131433233);
        this.Oooo00o = (TextView) findViewById(2131433210);
        this.Oooo0 = findViewById(2131433220);
        this.Oooo0O0 = findViewById(2131433225);
        this.OooOoo0 = findViewById(2131432650);
        this.OooOoo = findViewById(2131428851);
        this.OooOoo0.registerTimeOutListener(this);
        this.OooOoo.setOnClickListener(new gqb(this, 16));
        findViewById(2131432894).setOnClickListener(new fuc(this));
        findViewById(2131431482).setOnClickListener(new guc(this));
        registerReceiver(this.OooooOO, new IntentFilter("android.net.wifi.SCAN_RESULTS"));
        ile ileVarOooOO0 = ile.OooOO0();
        duc ducVar = new duc(this, 0);
        ileVarOooOO0.getClass();
        ile.OooO0o0(this, ducVar);
        SmartConfigRouterFactory.getStatPageV2Manager().OooOOOO(this.OooOoO);
        ((CommonActivity) this).mHandler.sendEmptyMessageDelayed(89, 10000L);
        this.Oooo000 = findViewById(2131432323);
        this.Oooo0OO = findViewById(2131432321);
        iza izaVar = new iza(this, new bv3(this, 12));
        this.Oooo0o0 = izaVar;
        izaVar.OooOoo0 = 2;
        izaVar.OooOoOO = new cuc(this);
        this.Oooo0OO.setAdapter(izaVar);
        this.Oooo0o = findViewById(2131428111);
        this.Oooo0oO = findViewById(2131427793);
        this.Oooo0oo = findViewById(2131427792);
        iza izaVar2 = new iza(this, new c23(this, 13));
        this.Oooo = izaVar2;
        this.Oooo0oo.setAdapter(izaVar2);
        try {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction("mj_kuailian_config_success");
            mm8.OooO0O0(CommonApplication.getAppContext()).OooO0OO(this.Oooooo, intentFilter);
        } catch (Exception unused) {
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onDestroy() {
        super.onDestroy();
        iza izaVar = this.Oooo;
        if (izaVar != null) {
            izaVar.OooOoO = null;
            izaVar.OooOoOO = null;
        }
        iza izaVar2 = this.Oooo0o0;
        if (izaVar2 != null) {
            izaVar2.OooOoO = null;
            izaVar2.OooOoOO = null;
        }
        ((CommonActivity) this).mHandler.removeCallbacks(this.OooooO0);
        unregisterReceiver(this.OooooOO);
        try {
            mm8.OooO0O0(CommonApplication.getAppContext()).OooO0o0(this.Oooooo);
        } catch (Exception unused) {
        }
        this.OoooooO.clear();
    }

    public void onPause() {
        super/*com.xiaomi.smarthome.framework.page.CommonActivity*/.onPause();
        OooOo0();
    }

    public void onResume() {
        super/*com.xiaomi.smarthome.framework.page.CommonActivity*/.onResume();
        OooOo0o();
    }

    public void onStart() {
        super/*com.xiaomi.smarthome.framework.page.CommonActivity*/.onStart();
        lf9 lf9VarOooO0oO = lf9.OooO0oO();
        lf9VarOooO0oO.OooO00o = false;
        kf9 kf9Var = (kf9) lf9VarOooO0oO.OooO0o0;
        kf9Var.sendEmptyMessage(1001);
        kf9Var.sendEmptyMessage(1002);
    }

    public void onStop() {
        super/*com.xiaomi.smarthome.framework.page.CommonActivity*/.onStop();
        gq9.OooO0OO(3, "RescanWifiV2Activity", "onStop");
        if (!this.OoooOOO && this.OoooOo0 && !this.Ooooooo) {
            reportScanResult();
            this.OoooOo0 = false;
        }
        lf9 lf9VarOooO0oO = lf9.OooO0oO();
        lf9VarOooO0oO.OooO00o = true;
        wt9 wt9Var = (wt9) lf9VarOooO0oO.OooO0Oo;
        if (wt9Var != null) {
            wt9Var.OooO00o = true;
        }
        ((kf9) lf9VarOooO0oO.OooO0o0).removeCallbacksAndMessages(null);
    }

    public void onTimeOut() {
        if (!isValid() || this.Oooo0oO.getVisibility() == 0) {
            this.Oooo000.setVisibility(8);
            return;
        }
        this.Oooo00O.setText(2131500109);
        PluginDeviceInfo pluginDeviceInfoOooo0oO = o0000O00.OooOooO().Oooo0oO(this.OooOoO);
        if (pluginDeviceInfoOooo0oO == null || !(pluginDeviceInfoOooo0oO.OooooO0 == 26 || pluginDeviceInfoOooo0oO.OooooO0 == 25 || pluginDeviceInfoOooo0oO.OooooO0 == 20)) {
            this.Oooo00o.setText(2131500379);
            findViewById(2131431482).setVisibility(0);
        } else {
            this.Oooo00o.setText(2131494072);
            findViewById(2131431482).setVisibility(8);
        }
        this.OooOoo.setVisibility(8);
        this.Oooo000.setVisibility(8);
        this.Oooo0o.setVisibility(0);
        this.Oooo0.setVisibility(8);
        this.Oooo0O0.setVisibility(0);
        if (this.OooOooo != null) {
            return;
        }
        writeLog("trigger scan timeout", new Object[0]);
        if (this.OooOooO != null) {
            ((CommonActivity) this).mHandler.removeCallbacks(this.OooooO0);
            if (OooOOO(this.OooOooO)) {
                return;
            }
        }
        gq9.OooO0oo(3000, "3000.0.2", "");
        this.OoooOo0 = true;
        SmartConfigRouterFactory.getStatBindManager().OooO0OO(this.OooOoO, BindStep.STEP_SCAN_DEVICE, "scan timeout");
        SmartConfigRouterFactory.getStatPageV2Manager().OooO0OO(this.OooOoO);
    }

    public void reportScanResult() {
        long jCurrentTimeMillis = System.currentTimeMillis();
        long j = this.o000oOoO;
        int i = 0;
        int i2 = j > 0 ? (int) ((jCurrentTimeMillis - j) / 1000) : 0;
        if (!this.OoooOOO) {
            i = !wab.OooOOo(CommonApplication.getAppContext(), "android.permission.ACCESS_FINE_LOCATION") ? 1 : !fha.OooO0o0() ? 2 : !oe1.o0000ooO() ? 3 : 50;
        }
        SmartConfigRouterFactory.getStatResultManager().OooO00o(i2, this.OoooOOo, this.OoooOoo, i, this.OooOoO, this.OoooOOO);
    }

    public void writeLog(String str, Object... objArr) {
        cec.OooOoO("RescanWifiV2Activity=>", str, "Common-Scan", objArr);
    }
}
