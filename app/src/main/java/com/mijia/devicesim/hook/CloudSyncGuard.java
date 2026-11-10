/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 抗检测 / 防云端覆盖（plan Phase 6 的「云同步隔离」）。
 *
 * <p>先把方向讲清楚：米家云端不会主动来删我们的假设备，因为假设备根本没进过它的数据库 ——
 * 列表注入全部发生在「读」的一侧（见 {@link DeviceListHook} 的复制返回策略）。
 * 真正需要防的是反过来的两件事：
 * <ol>
 *   <li><b>别把假 did 写回家庭数据</b>：{@code Home#setDids(List)} / {@code Room#setDids(List)}
 *       是云端下发和家庭编辑时的落地口，一旦假 did 进了这个字段，就会被序列化、上报、
 *       然后在下次同步时被云端当成不存在成员而清掉 —— 表现就是「假设备过一会自己消失」。
 *       在这两个 setter 里把保留段 did 摘掉，数据链路就干净了。</li>
 *   <li><b>云端整份覆盖时不慌</b>：即使云端某次把本地列表整体重写，我们是在读取处重新追加，
 *       下一次读列表假设备又回来了，天然自愈。</li>
 * </ol>
 *
 * <p>{@code SimConfig.cloudGuard} 关掉时这两个 setter 直接放行，用于对照测试。
 */
public final class CloudSyncGuard {

    private static final String GROUP = "cloud-guard";
    private static final String CL_HOME = "com.xiaomi.smarthome.homeroom.model.Home";
    private static final String CL_ROOM = "com.xiaomi.smarthome.homeroom.model.Room";

    private CloudSyncGuard() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        boolean any = false;
        any |= HookUtil.hookByClassName(GROUP, lpparam.classLoader, CL_HOME, "setDids", STRIP_SIMULATED, List.class);
        any |= HookUtil.hookByClassName(GROUP, lpparam.classLoader, CL_ROOM, "setDids", STRIP_SIMULATED, List.class);
        any |= HookUtil.hookByClassName(GROUP, lpparam.classLoader, CL_HOME, "setAllDids", STRIP_SIMULATED, List.class);
        if (!any) {
            HookReport.skip(GROUP, "Home/Room#setDids", "没找到写回口，仅依赖读侧注入（不影响功能）");
        }
    }

    private static final XC_MethodHook STRIP_SIMULATED = new XC_MethodHook() {
        @Override
        @SuppressWarnings("unchecked")
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                SimConfig cfg = ConfigRepository.current();
                if (!cfg.cloudGuard) return;
                Object arg = param.args[0];
                if (!(arg instanceof Collection)) return;
                List<Object> in = new ArrayList<>((Collection<Object>) arg);
                int stripped = 0;
                for (int i = in.size() - 1; i >= 0; i--) {
                    Object o = in.get(i);
                    if (o instanceof String && Consts.isSimulatedDid((String) o)) {
                        in.remove(i);
                        stripped++;
                    }
                }
                if (stripped == 0) return;
                param.args[0] = in;
                Log.v("已从写回口摘掉 " + stripped + " 个保留段 did（防止假设备被云端同步清掉）");
            } catch (Throwable t) {
                Log.e("did 过滤失败，放行原写入", t);
            }
        }
    };
}
