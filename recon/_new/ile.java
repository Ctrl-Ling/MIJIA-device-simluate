package _m_j;

import android.content.Context;
import android.text.TextUtils;
import com.xiaomi.smarthome.device.ConfigRouterSubDeviceActivity;
import com.xiaomi.smarthome.device.Device;
import com.xiaomi.smarthome.device.api.DeviceApi;
import com.xiaomi.smarthome.device.api.IXmPluginHostActivity;
import com.xiaomi.smarthome.domain.callback.ConfigKeyValuePair;
import com.xiaomi.smarthome.domain.callback.ConfigNetRequest;
import com.xiaomi.smarthome.framework.page.BaseActivity;
import com.xiaomi.smarthome.homeroom.OooOo00;
import com.xiaomi.smarthome.homeroom.model.Home;
import com.xiaomi.smarthome.library.log.LogType;
import com.xiaomi.smarthome.smartconfig.SmartConfigRouterFactory;
import com.xiaomi.spmstatsdk.oneId.util.StringUtils;
import com.xiaomi.spmstatsdk.utils.StatConstant;
import com.xiaomi.youpin.core.net.NetRequest;
import com.xiaomiyoupin.ypdalert.duplo.YPDAlertAttr;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: D:\Code\MIJIA-device-simluator\recon\dex\classes12.dex */
public final class ile {
    public static volatile ile OooO00o;
    public static final Object OooO0O0 = new Object();

    public static void OooO(BaseActivity baseActivity, ArrayList arrayList, ArrayList arrayList2, v93 v93Var) {
        String strJoin = null;
        String strJoin2 = (arrayList == null || arrayList.isEmpty()) ? null : TextUtils.join(StringUtils.DELIMITER_COMMA, arrayList);
        if (arrayList2 != null && !arrayList2.isEmpty()) {
            strJoin = TextUtils.join(StringUtils.DELIMITER_COMMA, arrayList2);
        }
        if (TextUtils.isEmpty(strJoin2) && TextUtils.isEmpty(strJoin)) {
            return;
        }
        JSONObject jSONObject = new JSONObject();
        try {
            if (!TextUtils.isEmpty(strJoin2)) {
                jSONObject.put("models", strJoin2);
            }
            if (!TextUtils.isEmpty(strJoin)) {
                jSONObject.put("pdIds", strJoin);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        ArrayList arrayList3 = new ArrayList();
        arrayList3.add(new ConfigKeyValuePair(jSONObject.toString()));
        gq9.OooO0OO(3, "SmartConfigApi", "request region data:" + jSONObject.toString());
        new ArrayList(8);
        ConfigNetRequest configNetRequest = new ConfigNetRequest();
        configNetRequest.OooOo = NetRequest.METHOD_POST;
        configNetRequest.OooOoO0 = "/v2/product/region_info";
        configNetRequest.OooOoO = arrayList3;
        SmartConfigRouterFactory.getSmartConfigManager().Oooo0oo(configNetRequest, new bv3(v93Var, 14)).OooO00o(baseActivity);
    }

    public static void OooO00o(int i, String str) {
        if (str == null) {
            str = "0";
        }
        pub.OooO00o(i, str);
    }

    public static void OooO0O0(Device device, v93 v93Var) {
        ArrayList arrayList = new ArrayList();
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("did", device.did);
            jSONObject.put("token", device.token);
            jSONObject.put("pid", device.pid);
        } catch (JSONException unused) {
        }
        ConfigNetRequest configNetRequestOooO0o = cec.OooO0o(arrayList, new ConfigKeyValuePair(jSONObject.toString()), 8);
        configNetRequestOooO0o.OooOo = NetRequest.METHOD_POST;
        configNetRequestOooO0o.OooOoO0 = "/home/binddevice";
        configNetRequestOooO0o.OooOoO = arrayList;
        SmartConfigRouterFactory.getSmartConfigManager().Oooo0oo(configNetRequestOooO0o, new rob(16, new u5e(8), v93Var));
    }

    public static void OooO0OO(Context context, String str, String str2, String str3, String str4, v93 v93Var) {
        ArrayList arrayList = new ArrayList();
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("model", str);
            jSONObject.put("router_did", str2);
            jSONObject.put("ssid", str3);
            jSONObject.put("bssid", str4);
        } catch (JSONException unused) {
        }
        String string = jSONObject.toString();
        gq9.OooOOO(LogType.NETWORK, "NbiotStep", "request bindNbIotDevice:" + string);
        ConfigNetRequest configNetRequestOooO0o = cec.OooO0o(arrayList, new ConfigKeyValuePair(string), 8);
        configNetRequestOooO0o.OooOo = NetRequest.METHOD_POST;
        configNetRequestOooO0o.OooOoO0 = "/v2/aiot/bind_confirm";
        configNetRequestOooO0o.OooOoO = arrayList;
        SmartConfigRouterFactory.getSmartConfigManager().Oooo0oo(configNetRequestOooO0o, new n54(new u5e(7), 16, v93Var, false)).OooO00o(context);
    }

    public static void OooO0Oo(Context context, String str, v93 v93Var) {
        ArrayList arrayList = new ArrayList();
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("bindkey", str);
            if (OooOo00.o0000oo().OooO0Oo() != null && OooOo00.o0000oo().OooO0Oo().getPermitLevel() == 9) {
                jSONObject.put("uid", OooOo00.o0000oo().OooO0Oo().getOwnerUid());
            }
        } catch (JSONException unused) {
        }
        ConfigNetRequest configNetRequestOooO0o = cec.OooO0o(arrayList, new ConfigKeyValuePair(jSONObject.toString()), 8);
        configNetRequestOooO0o.OooOo = NetRequest.METHOD_POST;
        configNetRequestOooO0o.OooOoO0 = "/v2/user/check_bindkey";
        configNetRequestOooO0o.OooOoO = arrayList;
        SmartConfigRouterFactory.getSmartConfigManager().Oooo0oo(configNetRequestOooO0o, new q3h(new m6a(14), 15, v93Var, false)).OooO00o(context);
    }

    public static void OooO0o(Context context, String str, String str2, v93 v93Var) {
        ArrayList arrayList = new ArrayList();
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("uid", str2);
            if (OooOo00.o0000oo().OooO0Oo() != null && OooOo00.o0000oo().OooO0Oo().getPermitLevel() >= 9) {
                jSONObject.put("ownerUid", OooOo00.o0000oo().OooO0Oo().getOwnerUid());
                jSONObject.put("homeId", Long.valueOf(OooOo00.o0000oo().o00000()));
            }
        } catch (JSONException unused) {
        }
        arrayList.add(new ConfigKeyValuePair(jSONObject.toString()));
        new ArrayList(8);
        ConfigNetRequest configNetRequest = new ConfigNetRequest();
        configNetRequest.OooOo = NetRequest.METHOD_POST;
        configNetRequest.OooOoO0 = "/appgateway/miot/appbindkeyservice/AppBindKeyService/GetBindKey";
        configNetRequest.OooOoO = arrayList;
        nm8 nm8Var = new nm8(14);
        if (lle.OooO0o(str, true, 100, lle.OooO0OO().OoooO0)) {
            v93Var.OooO00o(new ha3(-1, "设备不支持动态did"));
        } else {
            SmartConfigRouterFactory.getSmartConfigManager().Oooo0oo(configNetRequest, new a0h(14, nm8Var, v93Var)).OooO00o(context);
        }
    }

    public static void OooO0o0(Context context, v93 v93Var) {
        ArrayList arrayList = new ArrayList();
        Home homeOooO0Oo = OooOo00.o0000oo().OooO0Oo();
        JSONObject jSONObject = new JSONObject();
        if (homeOooO0Oo != null && homeOooO0Oo.getPermitLevel() >= 9) {
            try {
                jSONObject.put("homeId", Long.valueOf(homeOooO0Oo.getId()));
                jSONObject.put("uid", homeOooO0Oo.getOwnerUid());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        ConfigNetRequest configNetRequestOooO0o = cec.OooO0o(arrayList, new ConfigKeyValuePair(jSONObject.toString()), 8);
        configNetRequestOooO0o.OooOo = NetRequest.METHOD_POST;
        configNetRequestOooO0o.OooOoO0 = "/v2/user/get_bindkey";
        configNetRequestOooO0o.OooOoO = arrayList;
        SmartConfigRouterFactory.getSmartConfigManager().Oooo0oo(configNetRequestOooO0o, new m0h(new in9(14), 15, v93Var, false)).OooO00o(context);
    }

    public static void OooO0oO(Context context, v93 v93Var) {
        ArrayList arrayList = new ArrayList();
        Home homeOooO0Oo = OooOo00.o0000oo().OooO0Oo();
        JSONObject jSONObject = new JSONObject();
        if (homeOooO0Oo != null && homeOooO0Oo.getPermitLevel() >= 9) {
            try {
                jSONObject.put("homeId", Long.valueOf(homeOooO0Oo.getId()));
                jSONObject.put("uid", homeOooO0Oo.getOwnerUid());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        ConfigNetRequest configNetRequestOooO0o = cec.OooO0o(arrayList, new ConfigKeyValuePair(jSONObject.toString()), 8);
        configNetRequestOooO0o.OooOo = NetRequest.METHOD_POST;
        configNetRequestOooO0o.OooOoO0 = "/v2/user/get_bindkey";
        configNetRequestOooO0o.OooOoO = arrayList;
        SmartConfigRouterFactory.getSmartConfigManager().Oooo0oo(configNetRequestOooO0o, new eo5(14, new u5e(7), v93Var)).OooO00o(context);
    }

    public static ile OooOO0() {
        if (OooO00o == null) {
            synchronized (OooO0O0) {
                try {
                    if (OooO00o == null) {
                        OooO00o = new ile();
                    }
                } finally {
                }
            }
        }
        return OooO00o;
    }

    public static ConfigNetRequest OooOO0O(String str, JSONObject jSONObject) {
        ArrayList arrayList = new ArrayList();
        ConfigNetRequest configNetRequestOooO0o = cec.OooO0o(arrayList, new ConfigKeyValuePair(jSONObject.toString()), 8);
        configNetRequestOooO0o.OooOo = NetRequest.METHOD_POST;
        configNetRequestOooO0o.OooOoO0 = str;
        configNetRequestOooO0o.OooOoO = arrayList;
        return configNetRequestOooO0o;
    }

    public static void OooOOO(ConfigRouterSubDeviceActivity configRouterSubDeviceActivity, String str, IXmPluginHostActivity.AsyncCallback asyncCallback) {
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("id", (int) ((Math.random() * 100000.0d) + 100000.0d));
            jSONObject.put("method", "_sync.subdev_max");
            JSONObject jSONObject2 = new JSONObject();
            jSONObject2.put("did", str);
            jSONObject.put(YPDAlertAttr.EVENT_PROP_PARAMS, jSONObject2);
        } catch (Exception unused) {
        }
        DeviceApi.getInstance().rpcAsyncRemote(configRouterSubDeviceActivity, str, jSONObject.toString(), new hle(asyncCallback));
    }

    public static void OooOOO0(ConfigRouterSubDeviceActivity configRouterSubDeviceActivity, String str, int i, v93 v93Var) {
        ArrayList arrayList = new ArrayList();
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("did", str);
            JSONArray jSONArray = new JSONArray();
            int i2 = 0;
            while (i2 < i) {
                StringBuilder sb = new StringBuilder();
                sb.append(StatConstant.PublicParamsKey.CHANNEL);
                i2++;
                sb.append(i2);
                jSONArray.put(sb.toString());
            }
            jSONObject.put("keys", jSONArray);
        } catch (JSONException unused) {
        }
        ConfigNetRequest configNetRequestOooO0o = cec.OooO0o(arrayList, new ConfigKeyValuePair(jSONObject.toString()), 8);
        configNetRequestOooO0o.OooOo = NetRequest.METHOD_POST;
        configNetRequestOooO0o.OooOoO0 = "/v2/device/get_extra_data";
        configNetRequestOooO0o.OooOoO = arrayList;
        SmartConfigRouterFactory.getSmartConfigManager().Oooo0oo(configNetRequestOooO0o, new jad(16, new s30(i, 28), v93Var)).OooO00o(configRouterSubDeviceActivity);
    }

    public static void OooOOOO(String str, v93 v93Var) {
        try {
            JSONObject jSONObject = new JSONObject(str);
            int iOptInt = jSONObject.optInt("code");
            if (iOptInt != 0) {
                v93Var.OooO0OO(new ha3(iOptInt, ""));
            } else {
                JSONObject jSONObjectOptJSONObject = jSONObject.optJSONObject("result");
                if (jSONObjectOptJSONObject != null) {
                    jSONObject = jSONObjectOptJSONObject;
                }
                v93Var.OooO0Oo(jSONObject);
            }
        } catch (JSONException unused) {
            v93Var.OooO0OO(new ha3(-31, ""));
        }
    }

    public static void OooOOo0(String str, Object... objArr) {
        cec.OooOoO("SmartConfigApi=>", str, "Common-Poll", objArr);
    }

    public final void OooO0oo(Context context, String[] strArr, v93 v93Var) {
        if (context == null) {
            return;
        }
        JSONObject jSONObject = new JSONObject();
        try {
            JSONArray jSONArray = new JSONArray();
            for (String str : strArr) {
                jSONArray.put(str);
            }
            jSONObject.put("dids", jSONArray);
            String strOooo0 = l59.Oooo0(context.getApplicationContext());
            String strOooOoo0 = l59.OooOoo0(context.getApplicationContext());
            if (!TextUtils.isEmpty(strOooo0) && !TextUtils.isEmpty(strOooOoo0) && !TextUtils.equals(strOooOoo0, "02:00:00:00:00:00")) {
                jSONObject.put("ssid", strOooo0);
                jSONObject.put("bssid", strOooOoo0.toUpperCase());
            }
        } catch (JSONException unused) {
        }
        SmartConfigRouterFactory.getSmartConfigManager().Oooo0oo(OooOO0O("/v2/home/device_list_page", jSONObject), new o00000(new zgf(this, jSONObject, v93Var, context, 13), 16, v93Var, false)).OooO00o(context);
    }

    /* JADX WARN: Removed duplicated region for block: B:15:0x0040  */
    /* JADX WARN: Removed duplicated region for block: B:17:0x0045  */
    /* JADX WARN: Removed duplicated region for block: B:18:0x0048  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void OooOO0o(android.content.Context r19, java.lang.String r20, boolean r21, java.lang.String r22, java.lang.String r23, java.lang.String r24, java.lang.String r25, java.lang.String r26, _m_j.v93 r27) {
        /*
            Method dump skipped, instruction units count: 530
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: _m_j.ile.OooOO0o(android.content.Context, java.lang.String, boolean, java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String, _m_j.v93):void");
    }

    public final void OooOOOo(Context context, String str, ca3 ca3Var, v93 v93Var) {
        JSONArray jSONArray = new JSONArray();
        jSONArray.put(str);
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("dids", jSONArray);
        } catch (JSONException unused) {
        }
        OooO00o(44, str);
        gq9.OooOOO(LogType.NETWORK, "getNewDevice", "device_list" + jSONObject);
        OooOOo0("start do http request: /v2/home/device_list_page did=" + str, new Object[0]);
        SmartConfigRouterFactory.getSmartConfigManager().Oooo0oo(OooOO0O("/v2/home/device_list_page", jSONObject), new mn9(this, 13, ca3Var, v93Var)).OooO00o(context);
    }
}
