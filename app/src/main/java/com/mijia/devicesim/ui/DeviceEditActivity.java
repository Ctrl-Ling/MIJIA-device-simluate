/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 10:46:01 +08:00
 */
package com.mijia.devicesim.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.mijia.devicesim.R;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.MiotSpec;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;
import com.mijia.devicesim.core.SpecRepository;
import com.mijia.devicesim.databinding.ActivityDeviceEditBinding;
import com.mijia.devicesim.databinding.RowPropBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单台模拟设备的编辑页。
 *
 * <p>这里改的是同一个 {@link SimDevice} 对象 —— 它是 {@link SimConfig#devices} 里的那个实例，
 * 所以「保存并生效」只要把整份配置写回 Provider；点「取消」则整体重载，本地不留半成品。
 *
 * <p>属性初值一改动就同时写一份实时状态：这样还没保存设备也能立刻在米家面板上看到变化，
 * 但正式的值来源仍然是配置（下次没状态覆盖时回落到的地方）。
 */
public class DeviceEditActivity extends AppCompatActivity {

    static final String EXTRA_DID = "did";
    /** 从型号库新建设备时带上：进来就自动把 spec 拉下来，不用再点「在线取 spec」。*/
    static final String EXTRA_AUTO_SPEC = "autoSpec";
    private static final String EXTRA_MODEL = "model";

    private ActivityDeviceEditBinding b;
    private SimConfig cfg;
    private SimDevice dev;
    private MiotSpec spec;
    private final Map<String, RowPropBinding> propRows = new LinkedHashMap<>();

    /** 程序正在往控件回填时置真，避免回填触发监听器又写回一次值。*/
    private boolean filling;

    private final ActivityResultLauncher<Intent> modelPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() != RESULT_OK || r.getData() == null) return;
                String model = r.getData().getStringExtra(ModelLibraryActivity.EXTRA_PICKED_MODEL);
                String pickedName = r.getData().getStringExtra(ModelLibraryActivity.EXTRA_PICKED_NAME);
                if (model != null && !model.isEmpty()) {
                    b.inputModel.setText(model);
                    // 型号库里带中文名时，顺手把设备名填成「米家扫地机器人6Pro 模拟设备」
                    if (pickedName != null && !pickedName.isEmpty()) {
                        dev.name = pickedName + " 模拟设备";
                        b.inputName.setText(dev.name);
                    }
                    reloadSpec();
                    // 刚挑完型号：本地没有 spec 就直接去官方库拉，别让人再点一次
                    autoFetchSpecIfMissing();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiBackend.ensure(this);
        cfg = UiBackend.config();

        b = ActivityDeviceEditBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        String did = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_DID);
        dev = did == null ? null : cfg.findByDid(did);
        if (dev == null) {
            Toast.makeText(this, "配置里没有这台设备（可能已被删除）", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setTitle(dev.name.isEmpty() ? dev.did : dev.name);
        b.editDid.setText("did " + dev.did + "　sort " + dev.sort);

        fillIdentityFields();
        setupTypeSpinner();
        setupButtons();

        reloadSpec();
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTO_SPEC, false)) {
            autoFetchSpecIfMissing();
        }
    }

    /** 型号定了、本地又没 spec 时自动去 miot-spec.org 拉一份；已有 spec 就不打扰网络。*/
    private void autoFetchSpecIfMissing() {
        if (spec != null || dev.model == null || dev.model.trim().isEmpty()) return;
        fetchSpecOnline();
    }

    /* ---------- 基础信息 ---------- */

    private void fillIdentityFields() {
        filling = true;
        b.inputName.setText(dev.name);
        b.inputModel.setText(dev.model);
        b.inputRoom.setText(dev.roomName);
        b.inputParentDid.setText(dev.parentDid);
        b.inputToken.setText(dev.token);
        b.inputMac.setText(dev.mac);
        b.switchOnline.setChecked(dev.online);
        filling = false;

        b.inputName.addTextChangedListener(once((s) -> dev.name = s));
        b.inputRoom.addTextChangedListener(once((s) -> dev.roomName = s));
        b.inputParentDid.addTextChangedListener(once((s) -> dev.parentDid = s));
        b.inputToken.addTextChangedListener(once((s) -> dev.token = s.toUpperCase(java.util.Locale.ROOT)));
        b.inputMac.addTextChangedListener(once((s) -> dev.mac = s.toUpperCase(java.util.Locale.ROOT)));

        // 型号改动会影响 spec，单独处理：失焦时重载属性编辑器
        b.inputModel.addTextChangedListener(once((s) -> dev.model = s.trim()));
        b.inputModel.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) reloadSpec();
        });
        b.switchOnline.setOnCheckedChangeListener((v, checked) -> dev.online = checked);
    }

    private interface TextSink {
        void accept(String s);
    }

    /** 一个只关心「现在文本是什么」的 TextWatcher。*/
    private TextWatcher once(TextSink sink) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (filling) return;
                sink.accept(e == null ? "" : e.toString());
            }
        };
    }

    private void setupTypeSpinner() {
        ArrayAdapter<CharSequence> a = ArrayAdapter.createFromResource(this,
                R.array.device_types, android.R.layout.simple_spinner_item);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        b.spinnerType.setAdapter(a);
        b.spinnerType.setSelection(Labels.typeIndex(dev.type), false);
        b.spinnerType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                dev.type = Labels.typeFromIndex(DeviceEditActivity.this, position);
                updateParentRowVisibility();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updateParentRowVisibility();
    }

    /** 子设备才有「父网关」这一栏，WiFi 独立设备留着只会让人误填。*/
    private void updateParentRowVisibility() {
        boolean sub = Consts.TYPE_ZIGBEE_SUB.equals(dev.type) || Consts.TYPE_BLE_MESH.equals(dev.type);
        int vis = sub ? View.VISIBLE : View.GONE;
        b.parentRow.setVisibility(vis);
        b.labelParentRow.setVisibility(vis);
    }

    private void setupButtons() {
        b.btnPickModel.setOnClickListener(v -> {
            Intent i = new Intent(this, ModelLibraryActivity.class);
            i.putExtra(ModelLibraryActivity.EXTRA_PICK_MODE, true);
            modelPicker.launch(i);
        });
        b.btnPickParent.setOnClickListener(v -> pickGateway());
        b.btnToken.setOnClickListener(v -> {
            dev.token = SimDevice.newToken();
            b.inputToken.setText(dev.token);
        });
        b.btnMac.setOnClickListener(v -> {
            dev.mac = SimDevice.newMac();
            b.inputMac.setText(dev.mac);
        });
        b.btnFetchSpec.setOnClickListener(v -> fetchSpecOnline());
        b.btnResetState.setOnClickListener(v -> {
            if (StateBridge.resetDevice(this, dev.did)) {
                Toast.makeText(this, R.string.msg_state_reset, Toast.LENGTH_SHORT).show();
                rebuildPropRows();
            }
        });
        b.btnSave.setOnClickListener(v -> save());
        b.btnCancel.setOnClickListener(v -> {
            UiBackend.revert(this);
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    private void pickGateway() {
        List<SimDevice> cands = new ArrayList<>();
        for (SimDevice d : cfg.devices) {
            if (Consts.TYPE_GATEWAY.equals(d.type) || Consts.TYPE_WIFI.equals(d.type)) {
                if (!d.did.equals(dev.did)) cands.add(d);
            }
        }
        String[] names = new String[cands.size() + 1];
        names[0] = getString(R.string.label_none_gateway);
        for (int i = 0; i < cands.size(); i++) {
            SimDevice d = cands.get(i);
            names[i + 1] = d.name + " / " + d.did;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.title_pick_gateway)
                .setItems(names, (d, which) -> {
                    dev.parentDid = which == 0 ? "" : cands.get(which - 1).did;
                    b.inputParentDid.setText(dev.parentDid);
                })
                .show();
    }

    /* ---------- spec 与属性初值 ---------- */

    private void reloadSpec() {
        spec = SpecRepository.forModel(this, dev.model);
        b.specSummary.setText(spec == null
                ? getString(R.string.msg_spec_missing)
                : SpecRepository.describe(this, dev.model));
        rebuildPropRows();
    }

    private void rebuildPropRows() {
        filling = true;
        b.propContainer.removeAllViews();
        propRows.clear();
        if (spec != null) {
            // 一次读完全量实时状态，别每个属性都跑一趟 Provider
            Map<String, Object> live = StateBridge.readDevice(this, dev.did);
            List<MiotSpec.Prop> props = spec.editableProps();
            if (props.isEmpty()) props = spec.allProps();
            for (MiotSpec.Prop p : props) {
                Object v = currentValue(p, live);
                RowPropBinding row = PropRows.add(b.propContainer, this, spec, p, v, this::onPropEdited);
                propRows.put(p.key(), row);
            }
        }
        filling = false;
    }

    /** 取值顺序：实时状态 > 配置初值 > spec 默认值 —— 和 Hook 侧 DeviceStateStore.read 一致。*/
    private Object currentValue(MiotSpec.Prop p, Map<String, Object> live) {
        Object v = live.get(p.key());
        if (v != null) return v;
        Object init = dev.initialProps.get(p.key());
        return init != null ? init : spec.defaultValue(p);
    }

    private void onPropEdited(MiotSpec.Prop p, Object value) {
        if (filling) return;
        dev.initialProps.put(p.key(), value);
        StateBridge.writeProp(this, dev.did, p.key(), value);
        RowPropBinding row = propRows.get(p.key());
        if (row != null) row.propValue.setText(PropRows.display(p, value));
    }

    private void fetchSpecOnline() {
        if (dev.model.trim().isEmpty()) {
            Toast.makeText(this, R.string.msg_need_model, Toast.LENGTH_SHORT).show();
            return;
        }
        b.specSummary.setText("正在从 miot-spec.org 取 " + dev.model + " …");
        SpecFetcher.importByModel(this, dev.model.trim(), (ok, model, payload) -> {
            if (!isFinishing() && !isDestroyed()) {
                b.specSummary.setText(ok ? "已导入 spec：" + model + "\n" + SpecRepository.describe(this, dev.model)
                        : "在线导入失败：" + payload);
            }
            if (ok) reloadSpec();
        });
    }

    /* ---------- 保存 ---------- */

    private void save() {
        if (dev.model.trim().isEmpty()) {
            Toast.makeText(this, R.string.msg_need_model, Toast.LENGTH_SHORT).show();
            return;
        }
        dev.model = dev.model.trim();
        if (dev.token.isEmpty() && !Consts.TYPE_ZIGBEE_SUB.equals(dev.type)) dev.token = SimDevice.newToken();
        if (UiBackend.save(this, cfg)) {
            Toast.makeText(this, R.string.msg_saved, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } else {
            Toast.makeText(this, R.string.msg_save_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
