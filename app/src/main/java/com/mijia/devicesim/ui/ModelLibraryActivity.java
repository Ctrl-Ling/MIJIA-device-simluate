/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 09:25:23 +08:00
 */
package com.mijia.devicesim.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mijia.devicesim.R;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.DeviceCatalog;
import com.mijia.devicesim.core.MiotSpec;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;
import com.mijia.devicesim.core.SpecRepository;
import com.mijia.devicesim.databinding.ActivityModelLibraryBinding;
import com.mijia.devicesim.databinding.ItemModelBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 型号库：米家官方分类目录（大类 → 子类 → 型号）+ 已导入 / 内置的 spec，一处看全。
 *
 * <p>目录数据来自米家 APK 自带的 {@code plugin_config_cn-zh_cn}，由
 * {@code tools/gen_catalog.mjs} 压进 {@code assets/catalog/devices.json.gz} ——
 * 所以「清洁电器 / 安防」和「洗衣烘干机 / 扫地机器人」这些分组跟米家「手动添加设备」一致，
 * 每行还带官方产品图、中文名和型号。目录读不出来时退回旧的已知型号清单，不至于空白。
 *
 * <p>两种进入方式：
 * <ul>
 *   <li>普通进入 —— 管理型号库（在线刷新、移除导入的、按型号新建设备）；</li>
 *   <li>选型号模式（{@link #EXTRA_PICK_MODE}）—— 点一行就把 model + 中文名回传给编辑页。</li>
 * </ul>
 */
public class ModelLibraryActivity extends AppCompatActivity {

    static final String EXTRA_PICK_MODE = "pickMode";
    static final String EXTRA_PICKED_MODEL = "model";
    static final String EXTRA_PICKED_NAME = "modelName";

    private ActivityModelLibraryBinding b;
    private DeviceCatalog catalog;
    private final List<DeviceCatalog.Item> shown = new ArrayList<>();
    private final List<String> fallbackModels = new ArrayList<>();
    private Adapter adapter;
    private boolean pickMode;

    /** 当前筛选：空串 = 全部。*/
    private String selCat = "";
    private String selSub = "";

    private final ActivityResultLauncher<String> importFile =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::doImportSpecs);
    private final ActivityResultLauncher<String> exportFile =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::doExportSpecs);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiBackend.ensure(this);
        pickMode = getIntent() != null && getIntent().getBooleanExtra(EXTRA_PICK_MODE, false);

        b = ActivityModelLibraryBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        adapter = new Adapter();
        b.modelList.setLayoutManager(new LinearLayoutManager(this));
        b.modelList.setAdapter(adapter);
        hideChipRows();
        // 进页面先别弹输入法：搜索框自动获焦会把半屏列表挡住
        b.getRoot().requestFocus();

        b.inputModelQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int s0, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence s, int s0, int i1, int i2) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                refresh();
            }
        });

        b.btnSearchOnline.setOnClickListener(v -> searchOnline());
        b.btnImportFile.setOnClickListener(v -> importFile.launch("application/json"));
        b.btnExportAll.setOnClickListener(v -> exportFile.launch("mijia-devicesim-specs-" + UiBackend.stamp() + ".json"));

        loadCatalog();
    }

    /* ---------- 目录装载 ---------- */

    private void loadCatalog() {
        DeviceCatalog ready = DeviceCatalog.cached();
        if (ready != null) {
            onCatalogReady(ready);
            return;
        }
        b.libraryHint.setText(R.string.msg_catalog_loading);
        new Thread(() -> {
            DeviceCatalog loaded = null;
            String error = null;
            try {
                loaded = DeviceCatalog.load(ModelLibraryActivity.this);
            } catch (Throwable t) {
                error = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final DeviceCatalog ok = loaded;
            final String err = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (ok != null) {
                    onCatalogReady(ok);
                } else {
                    loadFallback(err == null ? "" : err);
                }
            });
        }, "catalog-loader").start();
    }

    private void onCatalogReady(DeviceCatalog ready) {
        catalog = ready;
        if (selCat.isEmpty() && !ready.cats().isEmpty()) {
            selCat = ready.cats().get(0).name;
        }
        buildCatChips();
        buildSubChips();
        refresh();
    }

    /** 目录资源坏了/丢了：退回旧的「已知型号」清单，至少还能按型号挑。*/
    private void loadFallback(String error) {
        catalog = null;
        hideChipRows();
        fallbackModels.clear();
        fallbackModels.addAll(SpecRepository.knownModels(this));
        java.util.Collections.sort(fallbackModels);
        b.libraryHint.setText(getString(R.string.msg_catalog_failed, error));
        refresh();
    }

    /* ---------- 筛选 ---------- */

    private void refresh() {
        String q = b.inputModelQuery.getText() == null ? "" : b.inputModelQuery.getText().toString().trim();
        shown.clear();

        if (catalog != null) {
            if (!q.isEmpty()) {
                shown.addAll(catalog.search(q, 300));
            } else {
                shown.addAll(catalog.of(selCat, selSub));
            }
        } else {
            String needle = q.toLowerCase(Locale.ROOT);
            for (String m : fallbackModels) {
                if (needle.isEmpty() || m.toLowerCase(Locale.ROOT).contains(needle)) {
                    shown.add(new DeviceCatalog.Item(m, "", "", "", ""));
                }
            }
        }

        adapter.notifyDataSetChanged();
        updateHint(q);
    }

    private void updateHint(String q) {
        if (catalog == null) return;   // 退回清单时 hint 写的是失败原因，不要覆盖
        if (!q.isEmpty()) {
            b.libraryHint.setText(getString(R.string.msg_catalog_search, shown.size()));
            return;
        }
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(catalog.stamp() * 1000L));
        b.libraryHint.setText(getString(R.string.msg_catalog_ready, catalog.size(), date));
    }

    /* ---------- 分类 chips ---------- */

    private void hideChipRows() {
        b.catScroll.setVisibility(View.GONE);
        b.subScroll.setVisibility(View.GONE);
    }

    private void buildCatChips() {
        b.catChips.removeAllViews();
        b.catScroll.setVisibility(View.VISIBLE);
        b.catChips.addView(chip(getString(R.string.filter_all), selCat.isEmpty(), v -> {
            selCat = "";
            selSub = "";
            buildCatChips();
            buildSubChips();
            refresh();
        }));
        for (DeviceCatalog.Cat c : catalog.cats()) {
            final String name = c.name;
            b.catChips.addView(chip(name, name.equals(selCat), v -> {
                selCat = name;
                selSub = "";
                buildCatChips();
                buildSubChips();
                refresh();
            }));
        }
        b.catScroll.post(() -> b.catScroll.scrollTo(0, 0));
    }

    private void buildSubChips() {
        b.subChips.removeAllViews();
        DeviceCatalog.Cat cat = null;
        for (DeviceCatalog.Cat c : catalog.cats()) {
            if (c.name.equals(selCat)) {
                cat = c;
                break;
            }
        }
        if (cat == null) {
            b.subScroll.setVisibility(View.GONE);
            return;
        }
        b.subScroll.setVisibility(View.VISIBLE);
        b.subChips.addView(chip(getString(R.string.filter_all), selSub.isEmpty(), v -> {
            selSub = "";
            buildSubChips();
            refresh();
        }));
        for (DeviceCatalog.Sub s : cat.subs) {
            final String name = s.name;
            b.subChips.addView(chip(name, name.equals(selSub), v -> {
                selSub = name;
                buildSubChips();
                refresh();
            }));
        }
        b.subScroll.post(() -> b.subScroll.scrollTo(0, 0));
    }

    private interface ChipClick {
        void onClick(View v);
    }

    /** 一个圆角筛选标签；选中用主题强调色，未选中用淡底。尺寸按 dp 算，不写死像素。*/
    private TextView chip(String text, boolean on, ChipClick click) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        int ph = dp(12);
        int pv = dp(6);
        tv.setPadding(ph, pv, ph, pv);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        int accent = themeColor(android.R.attr.colorAccent, Color.GRAY);
        bg.setColor(on ? accent : 0x14000000);
        tv.setBackground(bg);
        tv.setTextColor(on ? Color.WHITE : themeColor(android.R.attr.textColorPrimary, Color.DKGRAY));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(click::onClick);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int themeColor(int attr, int fallback) {
        TypedArray a = obtainStyledAttributes(new int[]{attr});
        try {
            return a.getColor(0, fallback);
        } finally {
            a.recycle();
        }
    }

    /* ---------- 在线 ---------- */

    private void searchOnline() {
        final String model = b.inputModelQuery.getText().toString().trim();
        if (model.isEmpty()) {
            Toast.makeText(this, R.string.msg_need_model, Toast.LENGTH_SHORT).show();
            return;
        }
        b.onlineResult.setText("正在查 miot-spec.org 的 " + model + " …（型号要对得上，例如 xiaomi.plug.mcn003）");
        SpecFetcher.importByModel(this, model, (ok, name, payload) -> {
            if (isFinishing() || isDestroyed()) return;
            if (ok) {
                b.onlineResult.setText("已导入：" + name + "\n" + SpecRepository.describe(this, name));
                refresh();
                Toast.makeText(this, R.string.msg_imported, Toast.LENGTH_SHORT).show();
            } else {
                b.onlineResult.setText(payload);
            }
        });
    }

    /** 只列候选型号：不确定的时候先看有哪些，再点某一条入库。*/
    private void listOnlineCandidates(String model) {
        b.onlineResult.setText("匹配 " + model + " 中…");
        SpecFetcher.listByModel(this, model, (ok, name, payload) -> {
            if (isFinishing() || isDestroyed()) return;
            b.onlineResult.setText(payload);
        });
    }

    private void fetchOneSpec(String model) {
        b.onlineResult.setText("取 " + model + " 的 spec 中…");
        SpecFetcher.importByModel(this, model, (ok, name, payload) -> {
            if (isFinishing() || isDestroyed()) return;
            b.onlineResult.setText(ok ? "已更新 " + name + "：\n" + payload : payload);
            refresh();
        });
    }

    /* ---------- 文件导入导出 ---------- */

    /** 支持两种文件：单个 spec 对象，或 {model:spec} / [spec,...] 的批量格式。*/
    private void doImportSpecs(Uri uri) {
        if (uri == null) return;
        String text = UiBackend.readText(this, uri);
        if (text == null) {
            Toast.makeText(this, R.string.msg_import_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        int imported = 0;
        try {
            String trimmed = text.trim();
            if (trimmed.startsWith("[")) {
                JSONArray a = new JSONArray(trimmed);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && importOne(o)) imported++;
                }
            } else {
                JSONObject o = new JSONObject(trimmed);
                if (o.has("services")) {
                    if (importOne(o)) imported++;
                } else {
                    java.util.Iterator<String> keys = o.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        JSONObject one = o.optJSONObject(k);
                        if (one != null && importOne(one)) imported++;
                    }
                }
            }
        } catch (Throwable t) {
            Toast.makeText(this, R.string.msg_import_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, getString(R.string.msg_imported) + " " + imported + " 个型号", Toast.LENGTH_SHORT).show();
        refresh();
    }

    /** 一个 spec 对象能不能入库；model 缺失时从 urn 反推。*/
    private boolean importOne(JSONObject o) {
        String model = o.optString("model", "");
        if (model.isEmpty()) {
            String urn = o.optString("type", "");
            model = urn.startsWith("urn:") ? SpecFetcher.modelFromUrn(urn) : "";
        }
        return !model.isEmpty() && SpecRepository.importSpec(this, model, o.toString());
    }

    private void doExportSpecs(Uri uri) {
        if (uri == null) return;
        List<String> known = new ArrayList<>(SpecRepository.knownModels(this));
        java.util.Collections.sort(known);
        JSONArray out = new JSONArray();
        for (String model : known) {
            MiotSpec spec = SpecRepository.forModel(this, model);
            if (spec != null && spec.raw() != null && spec.raw().length() > 0) out.put(spec.raw());
        }
        String text;
        try {
            text = out.toString(2);
        } catch (org.json.JSONException e) {
            text = out.toString();   // 缩进失败不影响内容，退化成单行
        }
        boolean ok = UiBackend.writeText(this, uri, text);
        Toast.makeText(this, ok ? getString(R.string.msg_exported) + " " + out.length() + " 个"
                : getString(R.string.msg_export_failed), Toast.LENGTH_SHORT).show();
    }

    /* ---------- 行操作 ---------- */

    private void openRow(DeviceCatalog.Item item) {
        if (pickMode) {
            Intent data = new Intent();
            data.putExtra(EXTRA_PICKED_MODEL, item.model);
            data.putExtra(EXTRA_PICKED_NAME, item.name);
            setResult(RESULT_OK, data);
            finish();
            return;
        }
        showRowMenu(item);
    }

    private void showRowMenu(DeviceCatalog.Item item) {
        String model = item.model;
        String title = item.name.isEmpty() ? model : item.name + "\n" + model;
        String[] items = {getString(R.string.action_create_with_model), "在线刷新该 spec",
                "匹配更多该型号版本", "移除导入的 spec"};
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(SpecRepository.describe(this, model) + "\nurn=" + SpecRepository.urnFor(this, model))
                .setItems(items, (d, which) -> {
                    if (which == 0) createDeviceWith(model);
                    else if (which == 1) fetchOneSpec(model);
                    else if (which == 2) listOnlineCandidates(model);
                    else if (which == 3) {
                        if (SpecRepository.deleteSpec(this, model)) {
                            Toast.makeText(this, R.string.msg_deleted, Toast.LENGTH_SHORT).show();
                            refresh();
                        }
                    }
                })
                .show();
    }

    private void createDeviceWith(String model) {
        SimConfig cfg = UiBackend.config();
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (SimDevice d : cfg.devices) taken.add(d.did);
        SimDevice d = new SimDevice();
        d.did = SimDevice.newDid(taken);
        d.model = model;
        MiotSpec spec = SpecRepository.forModel(this, model);
        DeviceCatalog.Item item = catalog == null ? null : catalog.find(model);
        String base = item != null && !item.name.isEmpty() ? item.name
                : (spec == null ? "模拟设备" : spec.category());
        d.name = base + " 模拟设备";
        d.type = guessType(spec);
        d.token = SimDevice.newToken();
        d.mac = SimDevice.newMac();
        d.sort = cfg.size() + 1;
        cfg.devices.add(d);
        if (UiBackend.save(this, cfg)) {
            Intent i = new Intent(this, DeviceEditActivity.class);
            i.putExtra(DeviceEditActivity.EXTRA_DID, d.did);
            startActivity(i);
        } else {
            Toast.makeText(this, R.string.msg_save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** spec 的设备类型决定默认接入类型，省得手工选一遍。*/
    private static String guessType(MiotSpec spec) {
        if (spec == null) return Consts.TYPE_WIFI;
        return spec.deviceType().contains("gateway") ? Consts.TYPE_GATEWAY : Consts.TYPE_WIFI;
    }

    /* ---------- 列表 ---------- */

    private class Adapter extends RecyclerView.Adapter<Holder> {

        @Override
        public int getItemCount() {
            return shown.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemModelBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            h.bind(shown.get(position));
        }
    }

    private class Holder extends RecyclerView.ViewHolder {

        private final ItemModelBinding i;
        private DeviceCatalog.Item item;

        Holder(ItemModelBinding binding) {
            super(binding.getRoot());
            i = binding;
            i.getRoot().setOnClickListener(v -> {
                if (item != null) openRow(item);
            });
        }

        void bind(DeviceCatalog.Item it) {
            item = it;
            boolean named = !it.name.isEmpty();
            i.modelName.setText(named ? it.name : it.model);
            i.modelDesc.setText(named ? it.model : "");
            ModelIconLoader.load(i.modelIcon, it.icon);
            Activity a = ModelLibraryActivity.this;
            i.modelDelete.setVisibility(SpecRepository.isImported(a, it.model) ? View.VISIBLE : View.GONE);
            i.modelDelete.setOnClickListener(v -> new AlertDialog.Builder(a)
                    .setTitle(R.string.confirm_delete_spec)
                    .setMessage(it.model)
                    .setPositiveButton(R.string.action_delete, (x, y) -> {
                        SpecRepository.deleteSpec(a, it.model);
                        refresh();
                    })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show());
        }
    }
}
