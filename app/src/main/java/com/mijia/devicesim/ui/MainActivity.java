/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-12 10:17:28 +08:00
 */
package com.mijia.devicesim.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mijia.devicesim.R;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;
import com.mijia.devicesim.databinding.ActivityMainBinding;
import com.mijia.devicesim.databinding.ItemDeviceBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主界面：模拟设备清单 + 全局开关 + 配置导入导出。
 *
 * <p>大白话：这一页是假设备的户口本。加一台、改一台、拖顺序，点保存就写进 Provider；
 * Provider 一 notifyChange，米家进程下次读设备列表看到的名单就跟着变，
 * 不需要重编模块、不需要杀进程。
 *
 * <p>行的交互：点整行 = 进编辑；按住左边 ≡ = 拖拽排序；长按整行 = 复制/删除/重置状态；
 * 行尾开关 = 启用/停用这一台（停用不删数据，随时开回来）。
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding b;
    private DeviceAdapter adapter;
    private ItemTouchHelper touchHelper;
    private SimConfig cfg;
    /** 「新增设备」刚建出来、还没进编辑器的那台半成品 did；编辑器没存就把它清掉。*/
    private String pendingBlankDid;
    /** 顶部状态块是否展开；记在 SharedPreferences 里，下次进来还是你上次的姿势。*/
    private boolean statusExpanded;
    private static final String PREF_STATUS_OPEN = "status_open";
    /** 顶部三块（状态 / 设备库 / 总开关）是否还摆着；往下翻列表就收起，往上翻再放回来。*/
    private boolean topExpanded = true;
    /** 顶部三块的收/放过渡；正在跑时再触发会先取消旧的，别让两次动画互相抢高度。*/
    private ValueAnimator topAnim;
    private static final long TOP_ANIM_MS = 220;

    private final ActivityResultLauncher<Intent> editorLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                // 先重载，让 cfg 反映编辑器里真正存进去的内容，再判断半成品要不要清
                loadAndRefresh();
                dropUnsavedBlank();
            });
    private final ActivityResultLauncher<String> importLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::doImport);
    private final ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::doExport);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        adapter = new DeviceAdapter();
        b.deviceList.setLayoutManager(new LinearLayoutManager(this));
        b.deviceList.setAdapter(adapter);
        touchHelper = new ItemTouchHelper(new ReorderCallback());
        touchHelper.attachToRecyclerView(b.deviceList);

        // 顶部三块跟着翻页走：列表往下翻就整块收起，把高度让给设备列表；往上翻再放回来。
        // 判断跟设备库筛选面板同一套：只认真正的滚动（列表还得有内容翻上去了），
        // 收放动画正跑着的时候不理新的滚动事件，否则会自己跟自己的布局打架。
        b.deviceList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (topAnim != null || !rv.canScrollVertically(-1)) return;
                if (dy > 0 && topExpanded) setTopExpanded(false);
                else if (dy < 0 && !topExpanded) setTopExpanded(true);
            }
        });

        // 已经贴顶、没得再往上翻时，往下拽那一下就是「把顶部三块拉回来」。
        // 动作放在松手时做，拖动过程中不动列表高度，免得跟顶部回弹抢布局。
        // 监听只观察不拦截（返回 false），点击 / 长按 / 拖拽排序都不受影响。
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        b.deviceList.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            /** 到达顶部那一刻的指位：从这之后继续往下拽超过 slop 才算「拉出」。*/
            private float topY;
            /** 本次手势里列表是否已经到顶（按下时就在顶部也算）。*/
            private boolean reachedTop;
            /** 本次手势有没有在到顶之后继续往下拽过。*/
            private boolean pulledAtTop;

            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                track(rv, e);
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                track(rv, e);
            }

            private void track(RecyclerView rv, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        // 用屏幕坐标：顶部收起后列表整体上移，用视图内坐标会把「列表上移」误算成「手指下拽」
                        topY = e.getRawY();
                        reachedTop = !rv.canScrollVertically(-1);
                        pulledAtTop = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!reachedTop && !rv.canScrollVertically(-1)) {
                            reachedTop = true;
                            topY = e.getRawY();
                        }
                        if (!topExpanded && reachedTop && e.getRawY() - topY > touchSlop) {
                            pulledAtTop = true;
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // 松手那一刻列表还得在顶部，才说明这一下是「拉出」而不是顺手滑下去了
                        if (pulledAtTop && !topExpanded && !rv.canScrollVertically(-1)) {
                            setTopExpanded(true);
                        }
                        pulledAtTop = false;
                        break;
                }
            }
        });

        b.btnAdd.setOnClickListener(v -> addBlank());
        b.btnOpenLibrary.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, ModelLibraryActivity.class)));
        b.btnSortName.setOnClickListener(v -> toggleSort(1));
        b.btnSortTime.setOnClickListener(v -> toggleSort(2));
        updateSortButtons();   // 开局不排序：两颗都是空闲态，列表就是配置里存的原顺序

        statusExpanded = getPreferences(MODE_PRIVATE).getBoolean(PREF_STATUS_OPEN, false);
        applyStatusExpanded();
        b.statusHeader.setOnClickListener(v -> {
            statusExpanded = !statusExpanded;
            applyStatusExpanded();
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_STATUS_OPEN, statusExpanded).apply();
        });
        b.switchInject.setOnCheckedChangeListener((v, checked) -> {
            if (cfg == null || cfg.injectEnabled == checked) return;
            cfg.injectEnabled = checked;
            persist();
        });

        loadAndRefresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 界面能显示 = 模块进程活着；趁这个窗口喊米家把配置重读一遍
        UiBackend.wakeHost(this);
        loadAndRefresh();
    }

    /* ---------- 刷新 ---------- */

    private void loadAndRefresh() {
        UiBackend.ensure(this);
        cfg = UiBackend.config();
        b.switchInject.setChecked(cfg.injectEnabled);
        adapter.reload();
        refreshStatus();
    }

    /** 收起时只留一行「生效状态 + 设备数」，展开才铺完整诊断；箭头跟着翻。
     *  背景也跟着换：收起是 40dp 药丸（99dp 全圆角），展开是多行文字用的 26dp 卡片，
     *  底色和 1dp 描边两态一模一样，只有圆角变。*/
    private void applyStatusExpanded() {
        b.statusText.setVisibility(statusExpanded ? View.VISIBLE : View.GONE);
        b.statusCard.setBackgroundResource(statusExpanded
                ? R.drawable.bg_glass_pill_panel : R.drawable.bg_glass_pill);
        b.statusToggle.setText(statusExpanded ? R.string.status_arrow_open : R.string.status_arrow_closed);
        b.statusHeader.setContentDescription(getString(statusExpanded
                ? R.string.action_collapse_status : R.string.action_expand_status));
    }

    /* ---------- 顶部三块的收放 ---------- */

    /** 收起 / 放回顶部三块。跟设备库筛选面板一样只做高度过渡，内容不变形。*/
    private void setTopExpanded(boolean expanded) {
        if (topExpanded == expanded) return;
        topExpanded = expanded;
        animateTop(expanded);
    }

    /**
     * 顶部三块的高度过渡：收起时从当前高度缩到 0，展开时从 0 长回自然高度。
     * 展开前必须现量：状态块可能正展开着，自然高度跟上次收起时不一样。
     */
    private void animateTop(final boolean expand) {
        final View panel = b.topPanel;

        // 先放掉旧动画：回调里 topAnim 已经换人，不会误收尾
        if (topAnim != null) {
            ValueAnimator old = topAnim;
            topAnim = null;
            old.cancel();
        }

        int from;
        if (expand) {
            from = 0;
            // 先按 0 高摆出来，避免动画前闪一帧全高
            panel.getLayoutParams().height = 0;
            panel.setVisibility(View.VISIBLE);
        } else {
            from = panel.getHeight();
        }
        final int to = expand ? measureHeight(panel) : 0;
        ((ViewGroup.MarginLayoutParams) panel.getLayoutParams()).height = from;

        panel.setAlpha(expand ? 0f : 1f);

        topAnim = ValueAnimator.ofFloat(0f, 1f);
        topAnim.setDuration(TOP_ANIM_MS);
        topAnim.setInterpolator(new DecelerateInterpolator());
        topAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            panel.getLayoutParams().height = Math.max(0, Math.round(from + (to - from) * f));
            panel.requestLayout();
            panel.setAlpha(expand ? f : 1f - f);
        });
        topAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (topAnim != a) return;
                topAnim = null;
                panel.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                panel.setAlpha(1f);
                // 收到底就整个 GONE：不占位、不吃点击，展开时再按自然高度长回来
                panel.setVisibility(expand ? View.VISIBLE : View.GONE);
                panel.requestLayout();
            }
        });
        topAnim.start();
    }

    /** 按父容器可用宽度量 view 的自然高度；GONE 状态下 getHeight() 是 0，只能现量。*/
    private int measureHeight(View v) {
        View parent = (View) v.getParent();
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        int avail = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight()
                - lp.leftMargin - lp.rightMargin;
        v.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, avail), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return v.getMeasuredHeight();
    }

    private void refreshStatus() {
        boolean self = UiBackend.moduleActive();
        boolean host = UiBackend.hostInjected(this);
        // 真正起作用的是米家进程那份；自己的探针只说明作用域有没有勾模块自身
        boolean active = host || self;
        boolean provider = UiBackend.providerAlive(this);
        boolean installed = UiBackend.targetInstalled(this);

        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.lbl_active)).append("：").append(Labels.yesNo(this, active))
                .append(host ? "（" + getString(R.string.lbl_host_reported) + "）"
                        : "（" + getString(R.string.lbl_host_not_reported) + "）")
                .append("　")
                .append(getString(R.string.lbl_provider)).append("：").append(Labels.okFail(this, provider)).append("　")
                .append(getString(R.string.lbl_target)).append("：")
                .append(installed ? getString(R.string.lbl_installed) : getString(R.string.lbl_missing)).append('\n');
        sb.append(getString(R.string.lbl_devices)).append("：").append(cfg.size()).append(" 台（启用 ")
                .append(cfg.enabledDevices().size()).append("）　")
                .append(getString(R.string.lbl_last_saved)).append("：").append(UiBackend.timeText(cfg.updatedAt));
        if (!installed) sb.append('\n').append(getString(R.string.msg_not_installed));
        if (!active) sb.append('\n').append(getString(R.string.msg_module_inactive));
        else if (!provider) sb.append('\n').append(getString(R.string.msg_provider_dead));
        if (StateBridge.rootConfigShadowed()) sb.append('\n').append(getString(R.string.lbl_root_hint));
        sb.append('\n').append(UiBackend.hostStatus(this));
        b.statusText.setText(sb.toString());
        b.statusSummary.setText(getString(R.string.lbl_active) + "：" + Labels.yesNo(this, active)
                + "　" + getString(R.string.lbl_devices) + "：" + cfg.size() + " 台");

        int valid = 0;
        for (SimDevice d : cfg.devices) if (d.enabled && d.isValid()) valid++;
        b.deviceCount.setText("列表 " + cfg.size() + " 台 · 注入 " + valid + " 台");
        b.emptyHint.setVisibility(cfg.size() == 0 ? View.VISIBLE : View.GONE);
        b.emptyHint.setText(getString(R.string.hint_no_device) + (active ? "" : "\n" + getString(R.string.msg_module_inactive)));
    }

    /* ---------- 全局开关 ---------- */

    private void showMoreSwitches() {
        String[] titles = {
                getString(R.string.label_cloud_guard),
                getString(R.string.label_scan_injection),
                getString(R.string.label_persist_state),
                getString(R.string.label_verbose),
        };
        boolean[] state = {cfg.cloudGuard, cfg.scanInjection, cfg.persistState, cfg.verboseLog};
        new AlertDialog.Builder(this)
                .setTitle(R.string.title_more_switches)
                .setMultiChoiceItems(titles, state, (d, which, checked) -> state[which] = checked)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    cfg.cloudGuard = state[0];
                    cfg.scanInjection = state[1];
                    cfg.persistState = state[2];
                    cfg.verboseLog = state[3];
                    Log.setVerbose(state[3]);
                    persist();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /* ---------- 增删改 ---------- */

    /**
     * 新增：先落一条最小可用记录再进编辑器。
     * Intent 只带 did，不塞整份 JSON —— 省得 Binder 大小限制和「改了但没保存」的两份状态。
     */
    private void addBlank() {
        Set<String> taken = new HashSet<>();
        for (SimDevice d : cfg.devices) taken.add(d.did);
        SimDevice d = new SimDevice();
        d.did = SimDevice.newDid(taken);
        d.name = "模拟设备 " + (cfg.size() + 1);
        d.type = Consts.TYPE_WIFI;
        d.token = SimDevice.newToken();
        d.mac = SimDevice.newMac();
        d.sort = cfg.size() + 1;
        d.addedAt = System.currentTimeMillis();
        cfg.devices.add(d);
        pendingBlankDid = d.did;
        persist();
        openEditor(d.did);
    }

    /**
     * 编辑器没保存就回来了：那台型号还是空的半成品直接从配置里清掉。
     *
     * <p>为什么要在回来这一瞬间清：型号空的设备 Hook 侧根本不会注入（SimDevice.isValid()
     * 直接过滤），留在列表里只会让人以为「我加了设备但米家没动静」。清完配置立刻生效，
     * 列表和实际注入数量对得上。
     */
    private void dropUnsavedBlank() {
        if (pendingBlankDid == null) return;
        String did = pendingBlankDid;
        pendingBlankDid = null;
        if (cfg == null) return;
        SimDevice d = cfg.findByDid(did);
        if (d == null || d.isValid()) return;
        cfg.devices.remove(d);
        persistQuiet();
        loadAndRefresh();
        toast(getString(R.string.msg_blank_dropped));
    }

    private void openEditor(String did) {
        Intent i = new Intent(this, DeviceEditActivity.class);
        i.putExtra(DeviceEditActivity.EXTRA_DID, did);
        editorLauncher.launch(i);
    }

    private void copyDevice(SimDevice src) {
        cfg.devices.add(src.copyAsNew(cfg.devices));
        persist();
        // 复制出来的是新行，必须重载适配器，否则要退出重进才看得见
        adapter.reload();
        toast(getString(R.string.msg_copied));
    }

    private void confirmDelete(SimDevice d) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.confirm_delete_title) + "\n" + d.name + " / " + d.did)
                .setPositiveButton(R.string.action_delete, (x, y) -> {
                    cfg.devices.remove(d);
                    persist();
                    // 删除后列表要立刻少一行：适配器持有的是 cfg.devices 的副本，不 reload 就不会变
                    adapter.reload();
                    StateBridge.resetDevice(this, d.did);
                    toast(getString(R.string.msg_deleted));
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showRowMenu(SimDevice d) {
        String[] items = {
                getString(R.string.title_device_detail),
                getString(R.string.title_edit_device),
                getString(R.string.action_copy),
                getString(R.string.action_delete),
                getString(R.string.action_reset_state),
        };
        new AlertDialog.Builder(this)
                .setTitle(d.name + "\n" + d.did)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) openDetail(d.model, d.name);
                    else if (which == 1) openEditor(d.did);
                    else if (which == 2) copyDevice(d);
                    else if (which == 3) confirmDelete(d);
                    else if (which == 4) {
                        if (StateBridge.resetDevice(this, d.did)) toast(getString(R.string.msg_state_reset));
                    }
                })
                .show();
    }

    /** 详情页只认型号和名字：字段从型号库快照里现查，不依赖配置里存了什么。*/
    private void openDetail(String model, String name) {
        Intent i = new Intent(this, DeviceDetailActivity.class);
        i.putExtra(DeviceDetailActivity.EXTRA_MODEL, model);
        i.putExtra(DeviceDetailActivity.EXTRA_NAME, name);
        startActivity(i);
    }

    /* ---------- 排序 ---------- */

    /** 名称按中文拼音比，不按码位。*/
    private final java.text.Collator coll = java.text.Collator.getInstance(java.util.Locale.CHINA);

    private String nameOf(SimDevice d) {
        String n = d.name == null ? "" : d.name.trim();
        return n.isEmpty() ? "(未命名)" : n;
    }

    /** 老配置没有 addedAt（0），统一当最早的一批，按原 sort 兜底。*/
    private long addedAtOf(SimDevice d) {
        return d.addedAt > 0 ? d.addedAt : 0L;
    }

    /** 首页排序：0 = 不排序（配置里存的原始顺序），1 = 名称，2 = 时间（加入时间）。
     *  一颗按钮点一下正序、再点倒序、第三下取消排序回到原顺序；换了另一颗就按那颗的正序来。
     *  默认 0：配置本来就是按加入先后存的，一进来就是原顺序，两颗按钮都不亮。*/
    private int sortField = 0;
    private boolean sortDesc = false;
    /** 开始排序前的顺序（did 序列）：排序是立刻落库的，不先记一份，第三次点取消就没得还原。*/
    private List<String> orderBeforeSort;

    private void toggleSort(int field) {
        if (cfg == null || cfg.devices.isEmpty()) return;
        if (sortField != field) {
            // 从「不排序」或另一颗切过来：先记原顺序（已经在排序里换字段就不重记），这颗从正序开始
            if (sortField == 0) orderBeforeSort = didsOf(cfg.devices);
            sortField = field;
            sortDesc = false;
        } else if (!sortDesc) {
            sortDesc = true;
        } else {
            // 第三下：取消排序，恢复成点排序之前那份顺序
            sortField = 0;
            sortDesc = false;
            restoreOrderBeforeSort();
            updateSortButtons();
            return;
        }
        List<SimDevice> ordered = new ArrayList<>(cfg.devices);
        if (field == 1) {
            ordered.sort((x, y) -> coll.compare(nameOf(x), nameOf(y)));
        } else {
            ordered.sort((x, y) -> {
                int c = Long.compare(addedAtOf(x), addedAtOf(y));
                return c != 0 ? c : Integer.compare(x.sort, y.sort);
            });
        }
        if (sortDesc) Collections.reverse(ordered);
        updateSortButtons();
        applyOrder(ordered);
    }

    private List<String> didsOf(List<SimDevice> list) {
        List<String> dids = new ArrayList<>(list.size());
        for (SimDevice d : list) dids.add(d.did);
        return dids;
    }

    /** 照快照把顺序摆回去：快照里没有的设备（排序期间新加的）按当前顺序补在最后。*/
    private void restoreOrderBeforeSort() {
        List<String> snapshot = orderBeforeSort;
        orderBeforeSort = null;
        if (snapshot == null || snapshot.isEmpty()) {
            adapter.reload();
            return;
        }
        Map<String, SimDevice> byDid = new HashMap<>();
        for (SimDevice d : cfg.devices) byDid.put(d.did, d);
        List<SimDevice> restored = new ArrayList<>(cfg.devices.size());
        for (String did : snapshot) {
            SimDevice d = byDid.remove(did);
            if (d != null) restored.add(d);
        }
        for (SimDevice d : cfg.devices) {
            if (byDid.containsKey(d.did)) restored.add(byDid.get(d.did));
        }
        applyOrder(restored);
    }

    /** 字眼固定「名称 / 时间」，方向交给右边那颗固定 12dp 的双箭头，按钮宽度不跟着跳。*/
    private void updateSortButtons() {
        b.btnSortName.setText(R.string.sort_name);
        b.btnSortTime.setText(R.string.sort_time);
        setSortIcon(b.btnSortName, sortField == 1);
        setSortIcon(b.btnSortTime, sortField == 2);
    }

    /** 正在排序的那颗：正序亮上箭头、倒序亮下箭头；没参与的上下都淡，形状占位照留。*/
    private void setSortIcon(TextView v, boolean active) {
        int icon = !active ? R.drawable.ic_sort_idle
                : (sortDesc ? R.drawable.ic_sort_desc : R.drawable.ic_sort_asc);
        v.setCompoundDrawablesWithIntrinsicBounds(0, 0, icon, 0);
    }

    /** 排完立刻落库：界面顺序就是注入顺序（sort 重新编号）。
     *  排序只影响显示/注入顺序，动作本身就是即时可见的，不再弹 toast 挡住列表。*/
    private void applyOrder(List<SimDevice> ordered) {
        cfg.devices.clear();
        cfg.devices.addAll(ordered);
        persistQuiet();
        adapter.reload();
    }

    /* ---------- 保存 ---------- */

    /** 拖完/删完按当前顺序重排 sort：注入顺序就是界面顺序，看得见的就是生效的。*/
    private void resequence() {
        for (int i = 0; i < cfg.devices.size(); i++) cfg.devices.get(i).sort = i + 1;
    }

    private void persist() {
        resequence();
        toast(getString(UiBackend.save(this, cfg) ? R.string.msg_saved : R.string.msg_save_failed));
        refreshStatus();
    }

    /** 界面自己收尾的清理（比如删掉没填完的半成品）：写进去就行，别再弹「已保存」打扰。*/
    private void persistQuiet() {
        resequence();
        UiBackend.save(this, cfg);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /* ---------- 菜单 ---------- */

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_more_switches) {
            showMoreSwitches();
            return true;
        }
        if (id == R.id.menu_model_library) {
            startActivity(new Intent(this, ModelLibraryActivity.class));
            return true;
        }
        if (id == R.id.menu_debug) {
            startActivity(new Intent(this, DebugActivity.class));
            return true;
        }
        if (id == R.id.menu_import_config) {
            importLauncher.launch("application/json");
            return true;
        }
        if (id == R.id.menu_export_config) {
            exportLauncher.launch("mijia-devicesim-config-" + UiBackend.stamp() + ".json");
            return true;
        }
        if (id == R.id.menu_usage) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.title_usage)
                    .setMessage(R.string.usage_text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 导入：整份配置替换，导入前确认数量，坏 JSON 直接拒。*/
    private void doImport(Uri uri) {
        if (uri == null) return;
        String text = UiBackend.readText(this, uri);
        SimConfig parsed = text == null ? null : SimConfig.parse(text);
        if (parsed == null || parsed.size() == 0) {
            toast(getString(R.string.msg_import_failed));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.title_import)
                .setMessage(getString(R.string.confirm_import_body, parsed.size()))
                .setPositiveButton(R.string.action_import, (d, w) -> {
                    if (UiBackend.save(this, parsed)) {
                        loadAndRefresh();
                        toast(getString(R.string.msg_imported) + " " + parsed.size());
                    } else {
                        toast(getString(R.string.msg_save_failed));
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void doExport(Uri uri) {
        if (uri == null) return;
        boolean ok = UiBackend.writeText(this, uri, UiBackend.config().toJsonString(  ));
        toast(getString(ok ? R.string.msg_exported : R.string.msg_export_failed));
    }

    /* ---------- 列表 ---------- */

    private class DeviceAdapter extends RecyclerView.Adapter<RowHolder> {

        private final List<SimDevice> rows = new ArrayList<>();

        void reload() {
            rows.clear();
            rows.addAll(cfg.devices);
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RowHolder(ItemDeviceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder h, int position) {
            h.bind(rows.get(position));
        }
    }

    private class RowHolder extends RecyclerView.ViewHolder {

        private final ItemDeviceBinding i;
        private SimDevice device;

        RowHolder(ItemDeviceBinding binding) {
            super(binding.getRoot());
            i = binding;
            i.dragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touchHelper.startDrag(this);
                return false;
            });
            i.getRoot().setOnClickListener(v -> {
                if (device != null) openEditor(device.did);
            });
            i.getRoot().setOnLongClickListener(v -> {
                if (device != null) showRowMenu(device);
                return true;
            });
            i.deviceMore.setOnClickListener(v -> {
                if (device != null) showRowMenu(device);
            });
        }

        void bind(SimDevice d) {
            device = d;
            i.deviceName.setText(d.name.isEmpty() ? "(未命名)" : d.name);
            i.deviceName.setTextColor(ContextCompat.getColor(MainActivity.this, Labels.typeColorRes(d.type)));
            i.deviceMeta.setText(Labels.meta(MainActivity.this, d));
            i.deviceType.setText(Labels.typeRowLabel(MainActivity.this, d.type));
            i.deviceType.setTextColor(ContextCompat.getColor(MainActivity.this, Labels.typeColorRes(d.type)));
            i.onlineBadge.setText(d.online
                    ? getString(R.string.lbl_online_word) : getString(R.string.lbl_offline_word));
            i.onlineBadge.setTextColor(ContextCompat.getColor(MainActivity.this,
                    d.online ? R.color.accent : R.color.offline));
            i.deviceSwitch.setOnCheckedChangeListener(null);
            i.deviceSwitch.setChecked(d.enabled);
            i.deviceSwitch.setOnCheckedChangeListener((v, checked) -> {
                if (device != null && device.enabled != checked) {
                    device.enabled = checked;
                    persist();
                }
            });
        }
    }

    /** 只处理上下拖动；左右滑动手势留空，避免误删。*/
    private class ReorderCallback extends ItemTouchHelper.SimpleCallback {

        ReorderCallback() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        @Override
        public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                              @NonNull RecyclerView.ViewHolder to) {
            // recyclerview 1.1.0 里还没有 getBindingAdapterPosition，用当时的等价 API
            int a = from.getAdapterPosition();
            int c = to.getAdapterPosition();
            if (a < 0 || c < 0 || a >= cfg.devices.size() || c >= cfg.devices.size()) return false;
            Collections.swap(cfg.devices, a, c);
            adapter.notifyItemMoved(a, c);
            return true;
        }

        @Override
        public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
            super.clearView(rv, vh);
            persist();
            // 手动拖过就是自己排的顺序：排序状态即时作废，两颗按钮回到空闲，别让箭头继续骗人
            if (sortField != 0) {
                sortField = 0;
                sortDesc = false;
                orderBeforeSort = null;
                updateSortButtons();
            }
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
        }
    }
}
