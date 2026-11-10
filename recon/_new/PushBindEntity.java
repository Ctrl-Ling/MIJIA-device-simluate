package com.xiaomi.smarthome.smartconfig;

import _m_j.eh3;
import _m_j.h4a;
import _m_j.leb;
import _m_j.oOo00o0o;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import com.xiaomi.smarthome.core.entity.plugin.PluginDeviceInfo;
import com.xiaomi.smarthome.library.log.LogType;
import org.json.JSONObject;

/* JADX INFO: loaded from: D:\Code\MIJIA-device-simluator\recon\dex\classes12.dex */
public class PushBindEntity implements Parcelable {
    public static final Parcelable.Creator<PushBindEntity> CREATOR = new leb(18);
    public final PluginDeviceInfo OooOo;
    public final String OooOoO;
    public final String OooOoO0;
    public final String OooOoOO;
    public final String OooOoo;
    public final String OooOoo0;
    public final String OooOooO;
    public byte OooOooo;
    public final String Oooo0;
    public final String Oooo000;
    public final String Oooo00O;
    public final String Oooo00o;
    public boolean Oooo0O0;
    public final String Oooo0OO;

    public PushBindEntity(PluginDeviceInfo pluginDeviceInfo, JSONObject jSONObject) {
        this.OooOo = pluginDeviceInfo;
        this.OooOoO0 = jSONObject.optString("router_did");
        this.OooOoO = jSONObject.optString("ssid");
        if (jSONObject.has("bssid")) {
            this.OooOoOO = jSONObject.optString("bssid");
        } else {
            this.OooOoOO = jSONObject.optString("mac");
        }
        this.OooOoo = jSONObject.optString("router_bssid");
        this.OooOoo0 = jSONObject.optString("router_ssid");
        this.OooOooO = jSONObject.optString("rssi");
        this.Oooo000 = jSONObject.optString("did");
        this.Oooo00O = jSONObject.optString("model");
        this.Oooo00o = jSONObject.optString("public_key");
        this.Oooo0 = jSONObject.optString("token");
        String strOptString = jSONObject.optString("sn");
        if (TextUtils.isEmpty(strOptString) || strOptString.length() <= 4) {
            this.Oooo0OO = "";
        } else {
            this.Oooo0OO = strOptString.substring(strOptString.length() - 4);
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:22:0x0051, code lost:
    
        if ((android.text.TextUtils.isEmpty(r0) ? false : java.util.regex.Pattern.matches(".+\\.camera\\..+", r0)) == false) goto L26;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static com.xiaomi.smarthome.smartconfig.PushBindEntity OooO0O0(org.json.JSONObject r5, boolean r6) {
        /*
            java.lang.String r0 = "model"
            java.lang.String r0 = r5.optString(r0)
            boolean r1 = android.text.TextUtils.isEmpty(r0)
            if (r1 == 0) goto L16
            java.lang.String r0 = "ssid"
            java.lang.String r0 = r5.optString(r0)
            java.lang.String r0 = _m_j.gc4.o00ooo(r0)
        L16:
            com.xiaomi.smarthome.frame.core.o0000O00 r1 = com.xiaomi.smarthome.frame.core.o0000O00.OooOooO()
            com.xiaomi.smarthome.core.entity.plugin.PluginDeviceInfo r1 = r1.Oooo0oO(r0)
            r2 = 1
            if (r6 == 0) goto L2d
            com.xiaomi.smarthome.smartconfig.PushBindEntity r6 = new com.xiaomi.smarthome.smartconfig.PushBindEntity
            r6.<init>(r1, r5)
            r6.OooO0OO(r2)
            r6.OooO00o()
            return r6
        L2d:
            boolean r6 = _m_j.t30.o00000OO(r1)
            java.lang.String r3 = "DevicePushBindManager"
            if (r6 != 0) goto L69
            if (r1 == 0) goto L3c
            int r6 = r1.OooooO0
            if (r6 != 0) goto L3c
            goto L43
        L3c:
            if (r1 == 0) goto L54
            int r6 = r1.OooooO0
            r4 = 3
            if (r6 != r4) goto L54
        L43:
            boolean r6 = android.text.TextUtils.isEmpty(r0)
            if (r6 == 0) goto L4b
            r6 = 0
            goto L51
        L4b:
            java.lang.String r6 = ".+\\.camera\\..+"
            boolean r6 = java.util.regex.Pattern.matches(r6, r0)
        L51:
            if (r6 != 0) goto L54
            goto L69
        L54:
            com.xiaomi.smarthome.library.log.LogType r6 = com.xiaomi.smarthome.library.log.LogType.PUSH
            java.lang.StringBuilder r0 = new java.lang.StringBuilder
            java.lang.String r1 = "PushBindEntity.create null pluginrecord json:"
            r0.<init>(r1)
            r0.append(r5)
            java.lang.String r5 = r0.toString()
            _m_j.gq9.OooOOO(r6, r3, r5)
            r5 = 0
            return r5
        L69:
            java.lang.StringBuilder r6 = new java.lang.StringBuilder
            java.lang.String r4 = "PushBindEntity.create pluginrecord model:"
            r6.<init>(r4)
            r6.append(r0)
            java.lang.String r6 = r6.toString()
            com.xiaomi.smarthome.library.common.util.InjectUtils.i(r3, r6)
            com.xiaomi.smarthome.smartconfig.PushBindEntity r6 = new com.xiaomi.smarthome.smartconfig.PushBindEntity
            r6.<init>(r1, r5)
            r6.OooO0OO(r2)
            r6.OooO00o()
            return r6
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaomi.smarthome.smartconfig.PushBindEntity.OooO0O0(org.json.JSONObject, boolean):com.xiaomi.smarthome.smartconfig.PushBindEntity");
    }

    public final void OooO00o() {
        int iO000OoOo = eh3.o000OoOo(0, this.OooOooO);
        if (iO000OoOo > -60) {
            return;
        }
        oOo00o0o.OooOo("PushBindEntity checkRssi rssi:", iO000OoOo, LogType.PUSH, DevicePushBindManager.TAG);
    }

    public final void OooO0OO(boolean z) {
        if (z) {
            this.OooOooo = (byte) (this.OooOooo & (-5));
        } else {
            this.OooOooo = (byte) (this.OooOooo | 4);
        }
    }

    public final boolean OooO0o0() {
        return (this.OooOooo & 4) != 4;
    }

    @Override // android.os.Parcelable
    public final int describeContents() {
        return 0;
    }

    public final boolean equals(Object obj) {
        if (!(obj instanceof PushBindEntity)) {
            return false;
        }
        PushBindEntity pushBindEntity = (PushBindEntity) obj;
        return TextUtils.equals(this.OooOoO, pushBindEntity.OooOoO) && TextUtils.equals(this.OooOoOO, pushBindEntity.OooOoOO) && TextUtils.equals(this.OooOoo0, pushBindEntity.OooOoo0) && TextUtils.equals(this.OooOoo, pushBindEntity.OooOoo);
    }

    public final String toString() {
        StringBuilder sb = new StringBuilder("PushBindEntity{routerDid='");
        sb.append(this.OooOoO0);
        sb.append("', ssid='");
        sb.append(this.OooOoO);
        sb.append("', bssid='");
        sb.append(this.OooOoOO);
        sb.append("', model='");
        sb.append(this.OooOo.OooOO0o());
        sb.append("', routerSsid='");
        sb.append(this.OooOoo0);
        sb.append("', routerBssid='");
        sb.append(this.OooOoo);
        sb.append("', rssi='");
        sb.append(this.OooOooO);
        sb.append("', sn='");
        sb.append(this.Oooo0OO);
        sb.append("', isHideSsid=");
        return h4a.OooOOO("'}", sb, this.Oooo0O0);
    }

    @Override // android.os.Parcelable
    public final void writeToParcel(Parcel parcel, int i) {
        parcel.writeParcelable(this.OooOo, i);
        parcel.writeString(this.OooOoO0);
        parcel.writeString(this.OooOoO);
        parcel.writeString(this.OooOoOO);
        parcel.writeString(this.OooOoo0);
        parcel.writeString(this.OooOoo);
        parcel.writeString(this.OooOooO);
        parcel.writeByte(this.OooOooo);
        parcel.writeString(this.Oooo000);
        parcel.writeString(this.Oooo00O);
        parcel.writeString(this.Oooo00o);
        parcel.writeString(this.Oooo0);
        parcel.writeInt(this.Oooo0O0 ? 1 : 0);
        parcel.writeString(this.Oooo0OO);
    }

    public PushBindEntity(Parcel parcel) {
        this.OooOo = parcel.readParcelable(PluginDeviceInfo.class.getClassLoader());
        this.OooOoO0 = parcel.readString();
        this.OooOoO = parcel.readString();
        this.OooOoOO = parcel.readString();
        this.OooOoo0 = parcel.readString();
        this.OooOoo = parcel.readString();
        this.OooOooO = parcel.readString();
        this.OooOooo = parcel.readByte();
        this.Oooo000 = parcel.readString();
        this.Oooo00O = parcel.readString();
        this.Oooo00o = parcel.readString();
        this.Oooo0 = parcel.readString();
        this.Oooo0O0 = parcel.readInt() == 1;
        this.Oooo0OO = parcel.readString();
    }
}
