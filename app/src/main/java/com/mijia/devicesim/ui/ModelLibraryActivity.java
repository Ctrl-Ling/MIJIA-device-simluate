/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
 */
package com.mijia.devicesim.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.mijia.devicesim.core.MiotSpec;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;
import com.mijia.devicesim.core.SpecRepository;
import com.mijia.devicesim.databinding.ActivityModelLibraryBinding;
import com.mijia.devicesim.databinding.ItemModelBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 型号库：内置 spec + 界面导入/在线拉取的 spec，一处看全。
 *
 * <p>两种进入方式：
 * <ul>
 *   <li>普通进入 —— 管理型号库（在线刷新、移除导入的、按型号新建设备）；</li>
 *   <li>选型号模式（{@link #EXTRA_PICK_MODE}）—— 点一行就把 model 回传给编辑页。</li>
 * </ul>
 *
 * <p>在线导入走 miot-spec.org：先按型号查 instance 列表（拿 urn），再取完整 spec，
 * 校验语法以后交给 {@link SpecRepository}，落 Provider，Hook 侧立刻能读到。
 */
public class ModelLibraryActivity extends AppCompatActivity {

    static final String EXTRA_PICK_MODE = "pickMode";
    static final String EXTRA_PICKED_MODEL = "model";

    private ActivityModelLibraryBinding b;
    private final List<String> all = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    private Adapter adapter;
    private boolean pickMode;

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

        b.inputModelQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int s0, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence s, int s0, int i1, int i2) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                filter(e == null ? "" : e.toString());
            }
        });

        b.btnSearchOnline.setOnClickListener(v -> searchOnline());
        b.btnImportFile.setOnClickListener(v -> importFile.launch("application/json"));
        b.btnExportAll.setOnClickListener(v -> exportFile.launch("mijia-devicesim-specs-" + UiBackend.stamp() + ".json"));

        reload();
    }

    private void reload() {
        all.clear();
        all.addAll(SpecRepository.knownModels(this));
        java.util.Collections.sort(all);
        filter(b.inputModelQuery.getText() == null ? "" : b.inputModelQuery.getText().toString());
        b.libraryHint.setText("型号库 " + all.size() + " 个" + (pickMode ? "　点一行选用该型号" : ""));
    }

    private void filter(String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(java.util.Locale.ROOT);
        shown.clear();
        for (String m : all) {
            if (needle.isEmpty() || m.toLowerCase(java.util.Locale.ROOT).contains(needle)) shown.add(m);
        }
        adapter.notifyDataSetChanged();
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
                reload();
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
            reload();
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
        reload();
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
        JSONArray out = new JSONArray();
        for (String model : all) {
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

    private void openRow(String model) {
        if (pickMode) {
            Intent data = new Intent();
            data.putExtra(EXTRA_PICKED_MODEL, model);
            setResult(RESULT_OK, data);
            finish();
            return;
        }
        showRowMenu(model);
    }

    private void showRowMenu(String model) {
        String[] items = {"用这个型号新建设备", "在线刷新该 spec", "匹配更多该型号版本", "移除导入的 spec"};
        new AlertDialog.Builder(this)
                .setTitle(model)
                .setMessage(SpecRepository.describe(this, model) + "\nurn=" + SpecRepository.urnFor(this, model))
                .setItems(items, (d, which) -> {
                    if (which == 0) createDeviceWith(model);
                    else if (which == 1) fetchOneSpec(model);
                    else if (which == 2) listOnlineCandidates(model);
                    else if (which == 3) {
                        if (SpecRepository.deleteSpec(this, model)) {
                            Toast.makeText(this, R.string.msg_deleted, Toast.LENGTH_SHORT).show();
                            reload();
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
        d.name = (spec == null ? "模拟设备" : spec.category()) + " " + (cfg.size() + 1);
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
        private String model;

        Holder(ItemModelBinding binding) {
            super(binding.getRoot());
            i = binding;
            i.getRoot().setOnClickListener(v -> {
                if (model != null) openRow(model);
            });
        }

        void bind(String m) {
            model = m;
            i.modelName.setText(m);
            i.modelDesc.setText(SpecRepository.describe(ModelLibraryActivity.this, m));
            Activity a = ModelLibraryActivity.this;
            i.modelDelete.setVisibility(SpecRepository.isImported(a, m) ? View.VISIBLE : View.GONE);
            i.modelDelete.setOnClickListener(v -> new AlertDialog.Builder(a)
                    .setTitle(R.string.confirm_delete_spec)
                    .setMessage(m)
                    .setPositiveButton(R.string.action_delete, (x, y) -> {
                        SpecRepository.deleteSpec(a, m);
                        reload();
                    })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show());
        }
    }
}
