package _m_j;

import android.text.TextUtils;
import com.xiaomi.smarthome.wificonfig.RescanWifiV2Activity;
import java.util.List;

/* JADX INFO: loaded from: D:\Code\MIJIA-device-simluator\recon\dex\classes12.dex */
public final class duc extends v93 {
    public final /* synthetic */ int OooO0O0;
    public final /* synthetic */ RescanWifiV2Activity OooO0OO;

    public /* synthetic */ duc(RescanWifiV2Activity rescanWifiV2Activity, int i) {
        this.OooO0O0 = i;
        this.OooO0OO = rescanWifiV2Activity;
    }

    public final void OooO00o(ha3 ha3Var) {
        switch (this.OooO0O0) {
            case 0:
                this.OooO0OO.writeLog("requestBindKey onFailure", new Object[0]);
                break;
        }
    }

    public final void OooO0O0(Object obj) {
        switch (this.OooO0O0) {
            case 0:
                String str = (String) obj;
                if (!TextUtils.isEmpty(str)) {
                    this.OooO0OO.writeLog("requestBindKey onSuccess", new Object[0]);
                    jle.OooO0oo().OooOOOO("wifi_bind_key", str);
                }
                break;
            case 1:
                List<bm4> list = (List) obj;
                if (list != null && list.size() > 0) {
                    RescanWifiV2Activity rescanWifiV2Activity = this.OooO0OO;
                    rescanWifiV2Activity.o0OoOo0.clear();
                    for (bm4 bm4Var : list) {
                        rescanWifiV2Activity.ooOO.add(String.valueOf(bm4Var.OooO0O0));
                        if (gc4.o00000o0(bm4Var.OooO00o, rescanWifiV2Activity.OooOoO)) {
                            rescanWifiV2Activity.o0OoOo0.add(bm4Var);
                        }
                    }
                    if (rescanWifiV2Activity.o0OoOo0.size() > 0) {
                        rescanWifiV2Activity.OooOo00();
                    }
                    break;
                }
                break;
            default:
                List list2 = (List) obj;
                if (list2 != null && list2.size() > 0) {
                    RescanWifiV2Activity rescanWifiV2Activity2 = this.OooO0OO;
                    rescanWifiV2Activity2.o00O0O.clear();
                    rescanWifiV2Activity2.o00O0O.addAll(list2);
                    rescanWifiV2Activity2.OooOo00();
                    break;
                }
                break;
        }
    }

    private final void OooO0o(ha3 ha3Var) {
    }

    private final void OooO0o0(ha3 ha3Var) {
    }
}
