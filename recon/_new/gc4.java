package _m_j;

import android.net.Uri;
import android.net.wifi.ScanResult;
import android.text.TextUtils;
import android.util.Log;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.controller.AbstractDraweeController;
import com.facebook.drawee.drawable.ScalingUtils;
import com.facebook.drawee.generic.GenericDraweeHierarchyBuilder;
import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import com.xiaomi.flow.application.CommonApplication;
import com.xiaomi.flow.camera.panel.xiaoyi.XYCameraDevice;
import com.xiaomi.flow.core.BleDevice;
import com.xiaomi.flow.core.Device;
import com.xiaomi.flow.core.DeviceFactory$AP_TYPE;
import com.xiaomi.flow.core.MiioDeviceV2;
import com.xiaomi.flow.core.api.spec.definitions.SpecDefinition;
import com.xiaomi.flow.core.entity.plugin.PluginDeviceInfo;
import com.xiaomi.flow.feedback.FeedbackApi;
import com.xiaomi.flow.scene.ui.sleep.SleepModeActivity;
import com.xiaomi.smarthome.frame.core.o0000O00;
import com.xiaomi.smarthome.library.log.LogType;
import com.xiaomi.smarthome.newui.MiSimpleDraweeView;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/* JADX INFO: loaded from: D:\Code\MIJIA-device-simluator\recon\dex\classes10.dex */
public abstract class gc4 extends fub {
    public static boolean OooooO0(String str) {
        if (str.length() != 8) {
            return false;
        }
        String upperCase = str.toUpperCase();
        for (int i = 0; i < 4; i++) {
            try {
                if (Integer.parseInt(String.valueOf(upperCase.charAt(i)), 16) + Integer.parseInt(String.valueOf(upperCase.charAt(i + 4)), 16) != 15) {
                    return false;
                }
            } catch (Exception unused) {
                return false;
            }
        }
        return true;
    }

    public static String OooooOO(ScanResult scanResult) {
        String[] strArrSplit;
        String str = scanResult.SSID;
        if ((TextUtils.isEmpty(str) || !str.startsWith("Xiaomi")) ? false : OooooO0(str.substring(str.indexOf("Xiaomi") + 6).replace("_", ""))) {
            return "xiaomi.router.rmo15";
        }
        String str2 = scanResult.SSID;
        if ((TextUtils.isEmpty(str2) || !str2.startsWith("Redmi")) ? false : OooooO0(str2.substring(str2.indexOf("Redmi") + 5).replace("_", ""))) {
            return "xiaomi.router.rmo15";
        }
        String str3 = scanResult.SSID;
        if (TextUtils.isEmpty(str3)) {
            return null;
        }
        if ((!str3.contains("_minet_") && !str3.startsWith("minet_")) || str3.contains(SleepModeActivity.SPLIT_SLEEP) || (strArrSplit = str3.split("_")) == null || strArrSplit.length < 3) {
            return null;
        }
        if ("minet".equals(strArrSplit[1]) && strArrSplit[2].length() == 4) {
            gq9.OooO0OO(3, "DeviceFactory", "ReduceSSID ssid=".concat(str3));
            return "xiaomi.router." + strArrSplit[0];
        }
        if (!"minet".equals(strArrSplit[0]) || strArrSplit[2].length() != 4) {
            return null;
        }
        gq9.OooOOO(LogType.KUAILIAN, "DeviceFactory", "ReduceSSID ssid=".concat(str3));
        return "xiaomi.router." + strArrSplit[1];
    }

    public static boolean OooooOo(ScanResult scanResult) {
        String strO00o0O = o00o0O(scanResult);
        return (strO00o0O.equalsIgnoreCase(FeedbackApi.COMMON_OTHER) || strO00o0O.equalsIgnoreCase("none")) ? false : true;
    }

    public static String Oooooo(ScanResult scanResult) {
        int iIndexOf;
        String str = scanResult.SSID;
        return (TextUtils.isEmpty(str) || (iIndexOf = str.indexOf("_midev")) <= 1) ? "" : str.substring(0, iIndexOf).replace('-', '.');
    }

    public static boolean Oooooo0(ScanResult scanResult) {
        return Ooooooo(scanResult) == DeviceFactory$AP_TYPE.AP_MIDEVICE;
    }

    public static String OoooooO(String str, String str2) {
        if (str == null) {
            return null;
        }
        PluginDeviceInfo pluginDeviceInfoOooo0oO = o0000O00.OooOooO().Oooo0oO(str);
        boolean z = false;
        if (pluginDeviceInfoOooo0oO != null && (pluginDeviceInfoOooo0oO.OooooO0 == 3 || pluginDeviceInfoOooo0oO.OooooO0 == 32)) {
            z = true;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(str.replace('.', '-'));
        return y06.OooOOo(sb, z ? "_mibt" : "_miap", str2);
    }

    public static DeviceFactory$AP_TYPE Ooooooo(ScanResult scanResult) {
        String[] strArrSplit = scanResult.BSSID.split("\\:");
        if (strArrSplit.length < 4) {
            return DeviceFactory$AP_TYPE.AP_NONE;
        }
        String str = strArrSplit[strArrSplit.length - 2];
        String str2 = strArrSplit[strArrSplit.length - 1];
        int iIndexOf = scanResult.SSID.indexOf("_midev");
        return (iIndexOf <= 0 || iIndexOf + 5 >= scanResult.SSID.length()) ? DeviceFactory$AP_TYPE.AP_NONE : DeviceFactory$AP_TYPE.AP_MIDEVICE;
    }

    /* JADX WARN: Removed duplicated region for block: B:18:0x0037  */
    /* JADX WARN: Removed duplicated region for block: B:20:0x0042 A[RETURN] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static com.xiaomi.flow.core.Device o0000(java.lang.String r5) {
        /*
            if (r5 != 0) goto L5
            java.lang.String r0 = ""
            goto L6
        L5:
            r0 = r5
        L6:
            java.lang.Class r0 = _m_j.ac4.OooooOO(r0)
            r1 = 0
            if (r0 == 0) goto L2d
            r2 = 0
            java.lang.Object[] r2 = new java.lang.Object[r2]     // Catch: java.lang.Exception -> L21
            java.lang.Object r0 = _m_j.n37.OooOoO(r0, r2)     // Catch: java.lang.Exception -> L21
            com.xiaomi.smarthome.device.Device r0 = (com.xiaomi.flow.core.Device) r0     // Catch: java.lang.Exception -> L21
            r0.model = r5     // Catch: java.lang.Exception -> L1f
            r1 = 1
            r0.isOnline = r1     // Catch: java.lang.Exception -> L1f
            o00Oo0(r0)     // Catch: java.lang.Exception -> L1f
            return r0
        L1f:
            r1 = move-exception
            goto L25
        L21:
            r0 = move-exception
            r4 = r1
            r1 = r0
            r0 = r4
        L25:
            java.lang.String r2 = "DeviceFactory"
            java.lang.String r3 = "fatal"
            android.util.Log.e(r2, r3, r1)
            r1 = r0
        L2d:
            com.xiaomi.smarthome.frame.core.o0000O00 r0 = com.xiaomi.smarthome.frame.core.o0000O00.OooOooO()
            boolean r0 = r0.OooooO0(r5)
            if (r0 == 0) goto L42
            com.xiaomi.smarthome.device.MiioDeviceV2 r0 = new com.xiaomi.smarthome.device.MiioDeviceV2
            r0.<init>()
            r0.model = r5
            o00Oo0(r0)
            return r0
        L42:
            return r1
        */
        throw new UnsupportedOperationException("Method not decompiled: _m_j.gc4.o0000(java.lang.String):com.xiaomi.smarthome.device.Device");
    }

    public static DeviceFactory$AP_TYPE o00000(ScanResult scanResult) {
        return scanResult == null ? DeviceFactory$AP_TYPE.AP_NONE : o00000O0(scanResult.SSID, scanResult.BSSID);
    }

    public static boolean o000000(ScanResult scanResult) {
        return scanResult != null && o00000(scanResult) == DeviceFactory$AP_TYPE.AP_MIBAP;
    }

    public static boolean o000000O(BleDevice bleDevice) {
        if (bleDevice == null) {
            return false;
        }
        String str = bleDevice.mac;
        String str2 = bleDevice.model;
        if (TextUtils.isEmpty(str) || TextUtils.isEmpty(str2) || m8h.Oooo00o(str) != 1) {
            if (TextUtils.isEmpty(str2)) {
                return false;
            }
            la8 la8Var = tjb.OooOooo;
            PluginDeviceInfo pluginDeviceInfoOooO0oO = rv2.OooO0oO().OooO0oO(str2);
            if (pluginDeviceInfoOooO0oO == null) {
                return false;
            }
            if (pluginDeviceInfoOooO0oO.OooooO0 != 3 && pluginDeviceInfoOooO0oO.OooooO0 != 32) {
                return false;
            }
        }
        return true;
    }

    public static boolean o000000o(String str, String str2) {
        if (str == null || str2 == null) {
            return false;
        }
        return str.equalsIgnoreCase(str2);
    }

    public static boolean o00000O(String str) {
        PluginDeviceInfo pluginDeviceInfoOooo0oO = o0000O00.OooOooO().Oooo0oO(str);
        boolean z = pluginDeviceInfoOooo0oO != null && pluginDeviceInfoOooo0oO.o0OoOo0;
        Iterator it = (pluginDeviceInfoOooo0oO != null ? pluginDeviceInfoOooo0oO.ooOO : new ArrayList()).iterator();
        while (it.hasNext()) {
            if (wah.Ooooo00(s6e.OooO00o.OooO00o(CommonApplication.getAppContext()), (String) it.next())) {
                return false;
            }
        }
        return "mijia.camera.v1".equalsIgnoreCase(str) || str.equalsIgnoreCase("xiaomi.wifispeaker.v2") || str.equalsIgnoreCase("xiaomi.wifispeaker.v1") || str.equalsIgnoreCase("yeelink.wifispeaker.v1") || str.equalsIgnoreCase("chuangmi.camera.v6") || str.equalsIgnoreCase("xiaomi.wifispeaker.s12") || z;
    }

    public static DeviceFactory$AP_TYPE o00000O0(String str, String str2) {
        int i;
        if (str == null || str2 == null) {
            return DeviceFactory$AP_TYPE.AP_NONE;
        }
        String[] strArrSplit = str2.split("\\:");
        if (strArrSplit.length < 4) {
            return DeviceFactory$AP_TYPE.AP_NONE;
        }
        String str3 = strArrSplit[strArrSplit.length - 2] + strArrSplit[strArrSplit.length - 1];
        int iIndexOf = str.indexOf("_miio");
        if (iIndexOf > 0 && (i = iIndexOf + 5) < str.length() && str3.equalsIgnoreCase(str.substring(i))) {
            return DeviceFactory$AP_TYPE.AP_MIIO;
        }
        int iIndexOf2 = str.indexOf("_miap");
        if (iIndexOf2 > 0 && iIndexOf2 + 5 < str.length()) {
            return DeviceFactory$AP_TYPE.AP_MIAP;
        }
        int iIndexOf3 = str.indexOf("_mibt");
        return (iIndexOf3 <= 0 || iIndexOf3 + 5 >= str.length()) ? str.contains("midea_ac") ? DeviceFactory$AP_TYPE.AP_MIDEA : str.contains("midea_AC") ? DeviceFactory$AP_TYPE.AP_MIDEA_AC : DeviceFactory$AP_TYPE.AP_NONE : DeviceFactory$AP_TYPE.AP_MIBAP;
    }

    public static boolean o00000OO(String str) {
        if (TextUtils.isEmpty(str)) {
            return false;
        }
        return str.contains("5G") || str.contains("5g");
    }

    public static boolean o00000Oo(String str, String str2) {
        if (!TextUtils.isEmpty(str) && !TextUtils.isEmpty(str2)) {
            if (TextUtils.equals(str, str2)) {
                return true;
            }
            PluginDeviceInfo pluginDeviceInfoOooo0oO = o0000O00.OooOooO().Oooo0oO(str);
            PluginDeviceInfo pluginDeviceInfoOooo0oO2 = o0000O00.OooOooO().Oooo0oO(str2);
            if (pluginDeviceInfoOooo0oO != null && pluginDeviceInfoOooo0oO2 != null && pluginDeviceInfoOooo0oO.OoooOO0 == pluginDeviceInfoOooo0oO2.OoooOO0) {
                return true;
            }
        }
        return false;
    }

    public static boolean o00000o0(String str, String str2) {
        if (TextUtils.isEmpty(str) || TextUtils.isEmpty(str2) || TextUtils.equals(str, str2)) {
            return false;
        }
        String[] strArrSplit = str.split("[.]");
        String[] strArrSplit2 = str2.split("[.]");
        if (strArrSplit.length == 3 && strArrSplit2.length == 3) {
            return strArrSplit[1].equalsIgnoreCase(strArrSplit2[1]);
        }
        return false;
    }

    public static boolean o00000oO(String str) {
        return !TextUtils.isEmpty(str) && str.startsWith("xiaomi.router.");
    }

    public static boolean o00000oo(String str) {
        return !TextUtils.isEmpty(str) && (str.startsWith("xiaoxun.watch") || str.startsWith("xiaomi.watch.sw"));
    }

    public static void o0000O(SimpleDraweeView simpleDraweeView, String str, int i) {
        if (simpleDraweeView == null) {
            return;
        }
        if (i == 0) {
            i = 2130839389;
        }
        o0000oo(simpleDraweeView, i, ScalingUtils.ScaleType.FIT_CENTER);
        if (TextUtils.isEmpty(str) || !str.startsWith("http")) {
            o0000OO0(simpleDraweeView, null);
        } else {
            o0000OO0(simpleDraweeView, Uri.parse(str));
        }
    }

    public static void o0000O0(Device device, SimpleDraweeView simpleDraweeView, hp2 hp2Var) {
        Uri uriO0000OO;
        if (simpleDraweeView == null) {
            return;
        }
        String strO00oO0O = o00oO0O(device);
        o0000oo(simpleDraweeView, 2130839389, ScalingUtils.ScaleType.FIT_START);
        if (TextUtils.isEmpty(strO00oO0O) || !strO00oO0O.startsWith("http")) {
            int iOooO00o = fs2.OooO00o(device.model);
            uriO0000OO = iOooO00o != 0 ? lee.o0000OO(iOooO00o) : lee.o0000OO(2130839389);
        } else {
            uriO0000OO = Uri.parse(strO00oO0O);
        }
        simpleDraweeView.setController(Fresco.newDraweeControllerBuilder().setImageRequest(ImageRequestBuilder.newBuilderWithSource(uriO0000OO).setPostprocessor(hp2Var).build()).setOldController(simpleDraweeView.getController()).setControllerListener(new fc4(uriO0000OO, 0)).build());
    }

    public static String o0000O00(String str) {
        Pattern patternCompile = Pattern.compile("[^0-9]+");
        String[] strArrSplit = str.split("\\.");
        if (strArrSplit.length != 3) {
            return null;
        }
        Matcher matcher = patternCompile.matcher(strArrSplit[2]);
        String strGroup = matcher.find() ? matcher.group() : "";
        StringBuilder sb = new StringBuilder();
        sb.append(strArrSplit[0]);
        return y06.OooOOo(sb, strArrSplit[1], strGroup);
    }

    public static void o0000O0O(SimpleDraweeView simpleDraweeView, String str, int i) {
        if (simpleDraweeView == null) {
            return;
        }
        if (simpleDraweeView instanceof MiSimpleDraweeView) {
            MiSimpleDraweeView miSimpleDraweeView = (MiSimpleDraweeView) simpleDraweeView;
            if (TextUtils.equals(miSimpleDraweeView.model, str)) {
                return;
            } else {
                miSimpleDraweeView.model = str;
            }
        }
        if (TextUtils.equals(str, "matter.matter.matter")) {
            simpleDraweeView.setImageURI(lee.o0000OO0(2130842893));
            return;
        }
        if (i == 0) {
            i = 2130839389;
        }
        String strO0OOO0o = o0OOO0o(str);
        o0000oo(simpleDraweeView, i, ScalingUtils.ScaleType.FIT_START);
        if (!TextUtils.isEmpty(strO0OOO0o) && strO0OOO0o.startsWith("http")) {
            o0000OO0(simpleDraweeView, Uri.parse(strO0OOO0o));
            return;
        }
        int iOooO00o = fs2.OooO00o(str);
        if (iOooO00o != 0) {
            simpleDraweeView.setImageURI(lee.o0000OO(iOooO00o));
        } else {
            simpleDraweeView.setImageURI(lee.o0000OO(i));
        }
    }

    public static void o0000OO0(SimpleDraweeView simpleDraweeView, Uri uri) {
        simpleDraweeView.setImageURI(uri);
        AbstractDraweeController controller = simpleDraweeView.getController();
        if (controller == null || !(controller instanceof AbstractDraweeController)) {
            return;
        }
        controller.addControllerListener(new fc4(uri, 1));
    }

    public static boolean o0000Ooo(String str, String str2) {
        int iLastIndexOf;
        if (str == null || str2 == null || str.isEmpty() || str2.isEmpty() || (iLastIndexOf = str.lastIndexOf(SpecDefinition.SPLIT_DOT)) <= 0) {
            return false;
        }
        return str2.startsWith(str.substring(0, iLastIndexOf));
    }

    public static void o0000oO(Device device, SimpleDraweeView simpleDraweeView, int i) {
        if (simpleDraweeView == null || device == null) {
            return;
        }
        if (i == 0) {
            i = 2130839389;
        }
        String strO00oO0O = o00oO0O(device);
        if (TextUtils.isEmpty(strO00oO0O)) {
            strO00oO0O = o0OOO0o(device.model);
        }
        o0000oo(simpleDraweeView, i, ScalingUtils.ScaleType.FIT_START);
        if (!TextUtils.isEmpty(strO00oO0O) && strO00oO0O.startsWith("http")) {
            o0000OO0(simpleDraweeView, Uri.parse(strO00oO0O));
            return;
        }
        int iOooO00o = fs2.OooO00o(device.getModel());
        if (iOooO00o != 0) {
            simpleDraweeView.setImageURI(lee.o0000OO(iOooO00o));
        } else {
            simpleDraweeView.setImageURI(lee.o0000OO(i));
        }
    }

    public static void o0000oo(SimpleDraweeView simpleDraweeView, int i, ScalingUtils.ScaleType scaleType) {
        if (simpleDraweeView.getHierarchy() == null) {
            simpleDraweeView.setHierarchy(new GenericDraweeHierarchyBuilder(simpleDraweeView.getResources()).setFadeDuration(200).setPlaceholderImage(i).setActualImageScaleType(scaleType).setPlaceholderImageScaleType(scaleType).build());
        }
    }

    public static void o000OO(SimpleDraweeView simpleDraweeView, String str) {
        o0000O(simpleDraweeView, str, 2130839389);
    }

    public static boolean o000OOo(ScanResult scanResult) {
        if (TextUtils.isEmpty(scanResult.SSID)) {
            return false;
        }
        String strO00ooo = o00ooo(scanResult.SSID);
        lle lleVarOooO0OO = lle.OooO0OO();
        return lle.OooO0o(strO00ooo, lleVarOooO0OO.OooOoO, lleVarOooO0OO.OooOoOO, lleVarOooO0OO.OooOoO0);
    }

    public static String o00O0O(ScanResult scanResult) {
        String strO00o0O = o00o0O(scanResult);
        if (strO00o0O.equals("none")) {
            strO00o0O = Oooooo(scanResult);
        }
        PluginDeviceInfo pluginDeviceInfoOooo0oO = o0000O00.OooOooO().Oooo0oO(strO00o0O);
        if (pluginDeviceInfoOooo0oO != null) {
            return pluginDeviceInfoOooo0oO.OooOOO0() + oo000o(scanResult);
        }
        if (strO00o0O.equalsIgnoreCase("chuangmi.plug.v1")) {
            return o00Ooo("chuangmi.plug.v1") + oo000o(scanResult);
        }
        if (strO00o0O.equalsIgnoreCase("chuangmi.plug.v2")) {
            return o00Ooo("chuangmi.plug.v2") + oo000o(scanResult);
        }
        if (strO00o0O.equalsIgnoreCase(XYCameraDevice.MODEL) || strO00o0O.equalsIgnoreCase("yunyi.camera.v2")) {
            return o00Ooo(XYCameraDevice.MODEL) + oo000o(scanResult);
        }
        if (strO00o0O.equalsIgnoreCase("zhimi.airpurifier.v1")) {
            return o00Ooo("zhimi.airpurifier.v1") + oo000o(scanResult);
        }
        if (strO00o0O.equalsIgnoreCase("zhimi.airpurifier.v2")) {
            return o00Ooo("zhimi.airpurifier.v2") + oo000o(scanResult);
        }
        if (strO00o0O.equalsIgnoreCase("zhimi.airpurifier.v3")) {
            return o00Ooo("zhimi.airpurifier.v3") + oo000o(scanResult);
        }
        if (strO00o0O.equalsIgnoreCase("lumi.gateway.v1")) {
            return o00Ooo("lumi.gateway.v1") + oo000o(scanResult);
        }
        if (strO00o0O.equalsIgnoreCase("lumi.gateway.v2")) {
            return o00Ooo("lumi.gateway.v2") + oo000o(scanResult);
        }
        if (strO00o0O.equalsIgnoreCase("midea.aircondition.v1")) {
            return o00Ooo("midea.aircondition.v1") + oo000o(scanResult);
        }
        if (!strO00o0O.equalsIgnoreCase(FeedbackApi.COMMON_OTHER)) {
            return scanResult.SSID;
        }
        return o00Ooo(FeedbackApi.COMMON_OTHER) + oo000o(scanResult);
    }

    public static void o00Oo0(Device device) {
        PluginDeviceInfo pluginDeviceInfoOooo0oO = o0000O00.OooOooO().Oooo0oO(device.model);
        if (pluginDeviceInfoOooo0oO != null) {
            if (TextUtils.isEmpty(device.name)) {
                device.name = pluginDeviceInfoOooo0oO.OooOOO0();
                return;
            }
            return;
        }
        if (o0OO00O(device.model, "xiaomi.ir.v1")) {
            if (TextUtils.isEmpty(device.name)) {
                device.name = o00Ooo("xiaomi.ir.v1");
                return;
            }
            return;
        }
        if (o0OO00O(device.model, "chuangmi.plug.v1")) {
            if (TextUtils.isEmpty(device.name)) {
                device.name = o00Ooo("chuangmi.plug.v1");
                return;
            }
            return;
        }
        if (o0OO00O(device.model, "chuangmi.plug.v2")) {
            if (TextUtils.isEmpty(device.name)) {
                device.name = o00Ooo("chuangmi.plug.v2");
                return;
            }
            return;
        }
        if (o0OO00O(device.model, "zhimi.airpurifier.v1")) {
            if (TextUtils.isEmpty(device.name)) {
                device.name = o00Ooo("zhimi.airpurifier.v1");
                return;
            }
            return;
        }
        if (o0OO00O(device.model, "zhimi.airpurifier.v2")) {
            if (TextUtils.isEmpty(device.name)) {
                device.name = o00Ooo("zhimi.airpurifier.v2");
                return;
            }
            return;
        }
        if (o0OO00O(device.model, "zhimi.airpurifier.v3")) {
            if (TextUtils.isEmpty(device.name)) {
                device.name = o00Ooo("zhimi.airpurifier.v3");
                return;
            }
            return;
        }
        if (o0OO00O(device.model, "lumi.gateway.v1")) {
            if (TextUtils.isEmpty(device.name)) {
                device.name = o00Ooo("lumi.gateway.v1");
                return;
            }
            return;
        }
        if (o0OO00O(device.model, "lumi.gateway.v2")) {
            if (TextUtils.isEmpty(device.name)) {
                device.name = o00Ooo("lumi.gateway.v2");
                return;
            }
            return;
        }
        if (o0OO00O(device.model, "lumi.sensor_motion.v1")) {
            if (TextUtils.isEmpty(device.name)) {
                device.name = o00Ooo("lumi.sensor_motion.v1");
                return;
            }
            return;
        }
        if (o0OO00O(device.model, "lumi.sensor_switch.v1")) {
            if (TextUtils.isEmpty(device.name)) {
                device.name = o00Ooo("lumi.sensor_switch.v1");
                return;
            }
            return;
        }
        if (o0OO00O(device.model, "lumi.sensor_magnet.v1")) {
            if (TextUtils.isEmpty(device.name)) {
                device.name = o00Ooo("lumi.sensor_magnet.v1");
                return;
            }
            return;
        }
        if (o0OO00O(device.model, "midea.aircondition.v1")) {
            if (TextUtils.isEmpty(device.name)) {
                device.name = o00Ooo("midea.aircondition.v1");
            }
        } else if (o0OO00O(device.model, "chuangmi.ir.v2")) {
            if (TextUtils.isEmpty(device.name)) {
                device.name = o00Ooo("chuangmi.ir.v2");
            }
        } else if (o0OO00O(device.model, "xiaomi.phone_ir.t1")) {
            if (TextUtils.isEmpty(device.name)) {
                device.name = o00Ooo("xiaomi.phone_ir.t1");
            }
        } else if (o0OO00O(device.model, "xiaomi.phone_ir.v1") && TextUtils.isEmpty(device.name)) {
            device.name = o00Ooo("xiaomi.phone_ir.v1");
        }
    }

    public static String o00Ooo(String str) {
        if (str.equalsIgnoreCase("xiaomi.ir.v1")) {
            return CommonApplication.getAppContext().getString(2131497773);
        }
        if (str.equalsIgnoreCase("chuangmi.plug.v1")) {
            return CommonApplication.getAppContext().getString(2131496782);
        }
        if (str.equalsIgnoreCase("chuangmi.plug.v2")) {
            return CommonApplication.getAppContext().getString(2131496784);
        }
        if (str.equalsIgnoreCase("zhimi.airpurifier.v1")) {
            return CommonApplication.getAppContext().getString(2131493197);
        }
        if (str.equalsIgnoreCase("lumi.gateway.v1")) {
            return CommonApplication.getAppContext().getString(2131496105);
        }
        if (str.equalsIgnoreCase("lumi.gateway.v2")) {
            return CommonApplication.getAppContext().getString(2131496105);
        }
        if (str.equalsIgnoreCase("lumi.sensor_motion.v1")) {
            return CommonApplication.getAppContext().getString(2131496106);
        }
        if (str.equalsIgnoreCase("lumi.sensor_switch.v1")) {
            return CommonApplication.getAppContext().getString(2131496110);
        }
        if (str.equalsIgnoreCase("lumi.sensor_magnet.v1")) {
            return CommonApplication.getAppContext().getString(2131496104);
        }
        if (str.equalsIgnoreCase("lumi.sensor_switch.v2")) {
            return CommonApplication.getAppContext().getString(2131496110);
        }
        if (str.equalsIgnoreCase("lumi.sensor_magnet.v2")) {
            return CommonApplication.getAppContext().getString(2131496104);
        }
        if (str.equalsIgnoreCase(FeedbackApi.COMMON_OTHER)) {
            return CommonApplication.getAppContext().getString(2131499216);
        }
        if (str.equalsIgnoreCase("xiaomi.myphone.v1")) {
            return CommonApplication.getAppContext().getString(2131498772);
        }
        if (str.equalsIgnoreCase("xiaomi.phone_ir.t1")) {
            return CommonApplication.getAppContext().getString(2131498771);
        }
        if (str.equalsIgnoreCase("xiaomi.phone_ir.v1")) {
            return CommonApplication.getAppContext().getString(2131498771);
        }
        if (str.equalsIgnoreCase("midea.aircondition.v1")) {
            return CommonApplication.getAppContext().getString(2131497732);
        }
        if (str.equalsIgnoreCase("xiaomi.ble.v1")) {
            return CommonApplication.getAppContext().getString(2131497454);
        }
        if (str.equalsIgnoreCase(FeedbackApi.COMMON_OTHER)) {
            return CommonApplication.getAppContext().getString(2131493329);
        }
        PluginDeviceInfo pluginDeviceInfoOooo0oO = o0000O00.OooOooO().Oooo0oO(str);
        return pluginDeviceInfoOooo0oO != null ? pluginDeviceInfoOooo0oO.OooOOO0() : "";
    }

    public static String o00o0O(ScanResult scanResult) {
        if (scanResult != null && !TextUtils.isEmpty(scanResult.SSID)) {
            String strO0ooOoO = o0ooOoO(scanResult.SSID);
            if (!TextUtils.isEmpty(strO0ooOoO) && o0000O00.OooOooO().OooooO0(strO0ooOoO)) {
                return strO0ooOoO;
            }
        }
        return "none";
    }

    public static String o00oO0O(Device device) {
        JSONObject extra;
        if (device == null) {
            return null;
        }
        return (device.isSpeakerGroupDevice() && (extra = device.getExtra()) != null && extra.has("speaker_group")) ? extra.optJSONObject("speaker_group").optString("img") : device.getIcon();
    }

    public static String o00oO0o(String str) {
        String modelBySSID;
        int iIndexOf = str.indexOf("_miio");
        if (iIndexOf > 0) {
            return str.substring(iIndexOf + 5);
        }
        int iIndexOf2 = str.indexOf("_miap");
        if (iIndexOf2 > 0) {
            return str.substring(iIndexOf2 + 5);
        }
        int iIndexOf3 = str.indexOf("_mibt");
        if (iIndexOf3 > 0) {
            return str.substring(iIndexOf3 + 5);
        }
        int iIndexOf4 = str.indexOf("_midev");
        if (iIndexOf4 > 0) {
            return str.substring(iIndexOf4 + 6);
        }
        o0000O00 o0000o00OooOooO = o0000O00.OooOooO();
        o0000o00OooOooO.getClass();
        try {
            modelBySSID = o0000o00OooOooO.OooOo().getModelBySSID(str);
        } catch (Throwable th) {
            th.printStackTrace();
            modelBySSID = "";
        }
        if (modelBySSID != null) {
            return "";
        }
        int iIndexOf5 = str.indexOf("midea_ac_");
        if (iIndexOf5 >= 0) {
            return str.substring(iIndexOf5 + 9);
        }
        int iIndexOf6 = str.indexOf("minet_");
        return iIndexOf6 >= 0 ? str.substring(iIndexOf6 + 6, iIndexOf6 + 10) : str;
    }

    public static String o00ooo(String str) {
        int iIndexOf = str.indexOf("_miio");
        if (iIndexOf > 1) {
            return str.substring(0, iIndexOf).replace('-', '.');
        }
        int iIndexOf2 = str.indexOf("_miap");
        if (iIndexOf2 > 1) {
            return str.substring(0, iIndexOf2).replace('-', '.');
        }
        int iIndexOf3 = str.indexOf("_mibt");
        if (iIndexOf3 > 1) {
            return str.substring(0, iIndexOf3).replace('-', '.');
        }
        int iIndexOf4 = str.indexOf("_midev");
        return iIndexOf4 > 1 ? str.substring(0, iIndexOf4).replace('-', '.') : (str.contains("midea_AC") || str.contains("midea_ac")) ? "midea.aircondition.v1" : str;
    }

    public static String o0O0O00(ScanResult scanResult) {
        String strO00o0O = o00o0O(scanResult);
        if (strO00o0O.equals("none")) {
            strO00o0O = Oooooo(scanResult);
        }
        PluginDeviceInfo pluginDeviceInfoOooo0oO = o0000O00.OooOooO().Oooo0oO(strO00o0O);
        return pluginDeviceInfoOooo0oO != null ? pluginDeviceInfoOooo0oO.OooOOO0() : strO00o0O.equalsIgnoreCase("chuangmi.plug.v1") ? o00Ooo("chuangmi.plug.v1") : strO00o0O.equalsIgnoreCase("chuangmi.plug.v2") ? o00Ooo("chuangmi.plug.v2") : (strO00o0O.equalsIgnoreCase(XYCameraDevice.MODEL) || strO00o0O.equalsIgnoreCase("yunyi.camera.v2")) ? o00Ooo(XYCameraDevice.MODEL) : strO00o0O.equalsIgnoreCase("zhimi.airpurifier.v1") ? o00Ooo("zhimi.airpurifier.v1") : strO00o0O.equalsIgnoreCase("zhimi.airpurifier.v2") ? o00Ooo("zhimi.airpurifier.v2") : strO00o0O.equalsIgnoreCase("zhimi.airpurifier.v3") ? o00Ooo("zhimi.airpurifier.v3") : strO00o0O.equalsIgnoreCase("lumi.gateway.v1") ? o00Ooo("lumi.gateway.v1") : strO00o0O.equalsIgnoreCase("lumi.gateway.v2") ? o00Ooo("lumi.gateway.v2") : strO00o0O.equalsIgnoreCase("midea.aircondition.v1") ? o00Ooo("midea.aircondition.v1") : strO00o0O.equalsIgnoreCase(FeedbackApi.COMMON_OTHER) ? o00Ooo(FeedbackApi.COMMON_OTHER) : scanResult.SSID;
    }

    public static boolean o0OO00O(String str, String str2) {
        return str != null && str.equalsIgnoreCase(str2);
    }

    public static String o0OOO0o(String str) {
        PluginDeviceInfo pluginDeviceInfoOooOoO;
        if (str == null) {
            return null;
        }
        if (o0000O00.OooOooO().OooooO0(str)) {
            PluginDeviceInfo pluginDeviceInfoOooo0oO = o0000O00.OooOooO().Oooo0oO(str);
            if (pluginDeviceInfoOooo0oO != null) {
                return pluginDeviceInfoOooo0oO.OooO();
            }
        } else if (TextUtils.equals(str, "yeelink.uwb.tag1") && (pluginDeviceInfoOooOoO = o0000O00.OooOooO().OooOoO(str)) != null) {
            return pluginDeviceInfoOooOoO.OooO();
        }
        return null;
    }

    public static int o0Oo0oo(String str) {
        int iOooO00o = fs2.OooO00o(str);
        if (str.equalsIgnoreCase("xiaomi.ble.v1")) {
            return 2130844594;
        }
        if (str.equalsIgnoreCase("yeelink.light.rgb1") || str.equalsIgnoreCase("yeelight.rgb.v1")) {
            return 2130844695;
        }
        return iOooO00o;
    }

    public static String o0OoOo0(String str) {
        int i;
        if (TextUtils.isEmpty(str)) {
            return "";
        }
        ArrayList arrayList = new ArrayList();
        la8 la8Var = tjb.OooOooo;
        Map mapOooO = rv2.OooO0oO().OooO();
        ArrayList<PluginDeviceInfo> arrayList2 = new ArrayList();
        if (mapOooO.size() > 0) {
            Iterator it = mapOooO.entrySet().iterator();
            while (it.hasNext()) {
                arrayList2.add((PluginDeviceInfo) ((Map.Entry) it.next()).getValue());
            }
        }
        PluginDeviceInfo pluginDeviceInfoOooo0oO = o0000O00.OooOooO().Oooo0oO(str);
        for (PluginDeviceInfo pluginDeviceInfo : arrayList2) {
            if (pluginDeviceInfoOooo0oO != null && (i = pluginDeviceInfo.o00o0O) != 0 && i == pluginDeviceInfoOooo0oO.OooOOOO()) {
                arrayList.add(pluginDeviceInfo.OooOO0o());
            }
        }
        arrayList.add(str);
        boolean z = pluginDeviceInfoOooo0oO != null && (pluginDeviceInfoOooo0oO.OooooO0 == 3 || pluginDeviceInfoOooo0oO.OooooO0 == 32);
        if (arrayList.size() == 1) {
            StringBuilder sb = new StringBuilder();
            sb.append(str.replace('.', '-'));
            return y06.OooOOo(sb, z ? "_mibt" : "_miap", "xxxx");
        }
        String[] strArrSplit = ((String) arrayList.get(0)).split("\\.");
        if (strArrSplit.length != 3) {
            return "";
        }
        Iterator it2 = arrayList.iterator();
        while (it2.hasNext()) {
            if (!((String) it2.next()).split("\\.")[0].equals(strArrSplit[0])) {
                StringBuilder sb2 = new StringBuilder("xxxx-");
                sb2.append(strArrSplit[1]);
                sb2.append("-xxxx");
                return y06.OooOOo(sb2, z ? "_mibt" : "_miap", "xxxx");
            }
        }
        StringBuilder sb3 = new StringBuilder();
        sb3.append(strArrSplit[0]);
        sb3.append(SleepModeActivity.SPLIT_SLEEP);
        sb3.append(strArrSplit[1]);
        sb3.append("-xxxx");
        return y06.OooOOo(sb3, z ? "_mibt" : "_miap", "xxxx");
    }

    public static String o0ooOO0(String str) {
        String strO0OOO0o = o0OOO0o(str);
        return !TextUtils.isEmpty(strO0OOO0o) ? (strO0OOO0o.startsWith("http://") || strO0OOO0o.startsWith("https://")) ? strO0OOO0o : "" : "";
    }

    public static Device o0ooOOo(String str, String str2) {
        Device device;
        Exception e;
        Device deviceByDid = com.xiaomi.flow.core.o00oO0o.OooO0Oo.OooO0O0.getDeviceByDid(str);
        if (deviceByDid == null) {
            Class clsOooooOO = ac4.OooooOO(str2 == null ? "" : str2);
            if (clsOooooOO != null) {
                try {
                    device = (Device) n37.OooOoO(clsOooooOO, new Object[0]);
                } catch (Exception e2) {
                    device = deviceByDid;
                    e = e2;
                }
                try {
                    device.model = str2 != null ? str2 : "";
                } catch (Exception e3) {
                    e = e3;
                    Log.e("DeviceFactory", "fatal", e);
                }
                deviceByDid = device;
            } else {
                if (o0000O00.OooOooO().OooooO0(str2 == null ? "" : str2)) {
                    deviceByDid = new MiioDeviceV2();
                    deviceByDid.model = str2 != null ? str2 : "";
                    o00Oo0(deviceByDid);
                }
            }
            if (deviceByDid != null) {
                la8 la8Var = tjb.OooOooo;
                PluginDeviceInfo pluginDeviceInfoOooO0oO = rv2.OooO0oO().OooO0oO(str2);
                if (pluginDeviceInfoOooO0oO != null && !TextUtils.isEmpty(pluginDeviceInfoOooO0oO.OooO())) {
                    deviceByDid.setIcon(pluginDeviceInfoOooO0oO.OooO());
                }
            }
        }
        return deviceByDid;
    }

    public static String o0ooOoO(String str) {
        int iIndexOf = str.indexOf("_miio");
        if (iIndexOf > 1) {
            return str.substring(0, iIndexOf).replace('-', '.');
        }
        int iIndexOf2 = str.indexOf("_miap");
        if (iIndexOf2 > 1) {
            return str.substring(0, iIndexOf2).replace('-', '.');
        }
        int iIndexOf3 = str.indexOf("_mibt");
        if (iIndexOf3 > 1) {
            return str.substring(0, iIndexOf3).replace('-', '.');
        }
        int iIndexOf4 = str.indexOf("_midev");
        return iIndexOf4 > 1 ? str.substring(0, iIndexOf4).replace('-', '.') : (str.contains("midea_AC") || str.contains("midea_ac")) ? "midea.aircondition.v1" : "";
    }

    public static String oo000o(ScanResult scanResult) {
        return ooOO(scanResult.BSSID, scanResult.SSID);
    }

    public static boolean oo0o0Oo(String str, String[] strArr) {
        String strO0000O00 = o0000O00(str);
        if (strO0000O00 == null) {
            return false;
        }
        for (String str2 : strArr) {
            String strO0000O002 = o0000O00(str2);
            if (!TextUtils.isEmpty(strO0000O002) && strO0000O002.equalsIgnoreCase(strO0000O00)) {
                return true;
            }
        }
        return false;
    }

    public static String ooOO(String str, String str2) {
        String strReplace;
        int length;
        String strO00oO0o = str2 != null ? o00oO0o(str2) : "";
        return ((strO00oO0o == null || strO00oO0o.length() != 4) && str != null && (length = (strReplace = str.replace(":", "")).length()) >= 4) ? strReplace.substring(length - 4) : strO00oO0o;
    }
}
