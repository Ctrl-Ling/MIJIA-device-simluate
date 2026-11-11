/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 19:17:59 +08:00
 */
package com.mijia.devicesim.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.HashSet;
import java.util.List;
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

        b.btnAdd.setOnClickListener(v -> addBlank());
        b.btnOpenLibrary.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, ModelLibraryActivity.class)));
        b.btnSortName.setOnClickListener(v -> toggleSort(1));
        b.btnSortTime.setOnClickListener(v -> toggleSort(2));

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

    /** 收起时只留一行「生效状态 + 设备数」，展开才铺完整诊断；箭头跟着翻。*/
    private void applyStatusExpanded() {
        b.statusText.setVisibility(statusExpanded ? View.VISIBLE : View.GONE);
        b.statusToggle.setText(statusExpanded ? R.string.status_arrow_open : R.string.status_arrow_closed);
        b.statusHeader.setContentDescription(getString(statusExpanded
                ? R.string.action_collapse_status : R.string.action_expand_status));
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

    /** 首页排序：0 = 不动，1 = 名称，2 = 时间（加入时间）；点同一颗再点一次就是正↔倒。*/
    private int sortField = 0;
    private boolean sortDesc = false;

    private void toggleSort(int field) {
        if (cfg == null || cfg.devices.isEmpty()) return;
        if (sortField == field) {
            sortDesc = !sortDesc;
        } else {
            sortField = field;
            sortDesc = false;     // 换字段从正序开始
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
        applyOrder(ordered, getString(field == 1
                ? (sortDesc ? R.string.msg_sorted_name_desc : R.string.msg_sorted_name_asc)
                : (sortDesc ? R.string.msg_sorted_time_desc : R.string.msg_sorted_time_asc)));
    }

    /** 箭头写在按钮文字上，一眼看出当前方向。*/
    private void updateSortButtons() {
        b.btnSortName.setText(sortField == 1
                ? (sortDesc ? R.string.sort_name_desc : R.string.sort_name_asc)
                : R.string.sort_name);
        b.btnSortTime.setText(sortField == 2
                ? (sortDesc ? R.string.sort_time_desc : R.string.sort_time_asc)
                : R.string.sort_time);
    }

    /** 排完立刻落库：界面顺序就是注入顺序（sort 重新编号）。*/
    private void applyOrder(List<SimDevice> ordered, String msg) {
        cfg.devices.clear();
        cfg.devices.addAll(ordered);
        persistQuiet();
        adapter.reload();
        toast(msg);
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
            i.deviceDid.setText(d.did);
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
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
        }
    }
}
