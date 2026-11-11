/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 18:55:10 +08:00
 */
package com.mijia.devicesim.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
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
 * {@code tools/gen_catalog.mjs} 压进 {@code assets/catalog/catalog.bin} ——
 * 所以「清洁电器 / 安防」和「洗衣烘干机 / 扫地机器人」这些分组跟米家「手动添加设备」一致，
 * 每行还带官方产品图、中文名和型号。页面上那颗「同步米家目录」会现场再读一遍手机上米家的
 * 同一份资产（不用等模块发版），结果存成快照，下次启动直接读快照。目录读不出来时退回旧的
 * 已知型号清单，不至于空白。
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

    /** 汉字 → 拼音首字母（品牌弹窗右侧 A-Z 导览用）；ICU 转一次就缓存，别每行都转。*/
    private static final java.util.Map<Character, String> HAN_INITIAL = new java.util.HashMap<>();

    private ActivityModelLibraryBinding b;
    private DeviceCatalog catalog;
    private final List<DeviceCatalog.Item> shown = new ArrayList<>();
    private final List<String> fallbackModels = new ArrayList<>();
    private Adapter adapter;
    private boolean pickMode;

    /** 当前筛选：空串 = 全部。*/
    private String selCat = "";
    private String selSub = "";
    /** 品牌多选：空 = 不限；多个品牌之间是「或」。*/
    private final java.util.LinkedHashSet<String> selBrands = new java.util.LinkedHashSet<>();

    /** 排序字段：0 = 品牌→名称（目录自带顺序），1 = 名称，2 = 上市时间。*/
    private int sortField = 0;
    /** 当前字段是否倒序：点同一个按钮再切一次就翻，不用并列两个方向按钮。*/
    private boolean sortDesc = false;
    /** 同步/恢复正在后台跑：两个按钮都先锁住，别点两下同时解 17MB。*/
    private boolean syncing = false;

    /** 品牌名按中文拼音比：排序和 chip 顺序都用它。*/
    private final java.text.Collator coll = java.text.Collator.getInstance(Locale.CHINA);
    /** 型号行上的上市时间格式：精确到日。*/
    private final SimpleDateFormat listStamp = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);

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

        b.btnPickCat.setOnClickListener(v -> pickCategory());
        b.btnPickSub.setOnClickListener(v -> pickSubcategory());
        b.btnPickBrand.setOnClickListener(v -> showBrandPicker());
        b.btnSearchOnline.setOnClickListener(v -> searchOnline());
        b.btnSortDefault.setOnClickListener(v -> setSortField(0));
        b.btnSortName.setOnClickListener(v -> setSortField(1));
        b.btnSortTime.setOnClickListener(v -> setSortField(2));
        updateSortButtons();

        loadCatalog();
    }

    /* ---------- 右上角三点菜单 ---------- */

    /** 同步目录 / 恢复内置 / 导入 spec / 导出型号库都收在这里，界面把高度留给列表。*/
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_model_library, menu);
        return true;
    }

    /** 同步跑着的时候两项目录操作先置灰，别同时解两遍 17MB。*/
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        setMenuEnabled(menu, R.id.action_sync_mijia, !syncing);
        setMenuEnabled(menu, R.id.action_restore_builtin, !syncing);
        return super.onPrepareOptionsMenu(menu);
    }

    private static void setMenuEnabled(Menu menu, int id, boolean enabled) {
        MenuItem item = menu.findItem(id);
        if (item != null) item.setEnabled(enabled);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_sync_mijia) {
            syncCatalog();
            return true;
        }
        if (id == R.id.action_restore_builtin) {
            restoreCatalog();
            return true;
        }
        if (id == R.id.action_import_spec_file) {
            importFile.launch("application/json");
            return true;
        }
        if (id == R.id.action_export_specs) {
            exportFile.launch("mijia-devicesim-specs-" + UiBackend.stamp() + ".json");
            return true;
        }
        return super.onOptionsItemSelected(item);
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
        buildFilterBar();
        buildBrandFilter();
        refresh();
    }

    /* ---------- 同步米家目录 ---------- */

    /**
     * 现场读手机上米家 APK 自带的 {@code device_config/plugin_config_cn-zh_cn}，
     * 解完写一份快照到模块私有目录 —— 以后米家升级不用等模块发版。
     * 17MB 的 JSON 走流式解析，扔后台线程，界面先给提示。
     */
    private void syncCatalog() {
        if (syncing) return;
        syncing = true;
        invalidateOptionsMenu();
        b.libraryHint.setText(R.string.msg_catalog_syncing);
        new Thread(() -> {
            DeviceCatalog.SyncResult r = null;
            String error = null;
            try {
                r = DeviceCatalog.syncFromMijia(ModelLibraryActivity.this);
            } catch (Throwable t) {
                error = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final DeviceCatalog.SyncResult ok = r;
            final String err = error;
            runOnUiThread(() -> {
                syncing = false;
                if (isFinishing() || isDestroyed()) return;
                invalidateOptionsMenu();
                if (ok == null) {
                    b.libraryHint.setText(getString(R.string.msg_catalog_sync_failed, err == null ? "" : err));
                    return;
                }
                // 换了整份目录，旧的分类/品牌可能整个消失，筛选先清空
                selCat = "";
                selSub = "";
                selBrands.clear();
                onCatalogReady(ok.catalog);
                Toast.makeText(this, getString(R.string.msg_catalog_synced, ok.models, ok.mijiaVersion),
                        Toast.LENGTH_LONG).show();
            });
        }, "catalog-sync").start();
    }

    /** 删掉同步快照，回到 APK 里打包的那份（发版时由 tools/gen_catalog.mjs 生成）。*/
    private void restoreCatalog() {
        if (syncing) return;
        if (!DeviceCatalog.hasSyncedCache(this)) {
            Toast.makeText(this, R.string.msg_catalog_builtin_already, Toast.LENGTH_SHORT).show();
            return;
        }
        syncing = true;
        invalidateOptionsMenu();
        b.libraryHint.setText(R.string.msg_catalog_loading);
        new Thread(() -> {
            DeviceCatalog built = null;
            String error = null;
            try {
                built = DeviceCatalog.reloadBuiltin(ModelLibraryActivity.this);
            } catch (Throwable t) {
                error = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final DeviceCatalog ok = built;
            final String err = error;
            runOnUiThread(() -> {
                syncing = false;
                if (isFinishing() || isDestroyed()) return;
                invalidateOptionsMenu();
                if (ok == null) {
                    b.libraryHint.setText(getString(R.string.msg_catalog_failed, err == null ? "" : err));
                    return;
                }
                selCat = "";
                selSub = "";
                selBrands.clear();
                onCatalogReady(ok);
                Toast.makeText(this, R.string.msg_catalog_restored, Toast.LENGTH_SHORT).show();
            });
        }, "catalog-restore").start();
    }

    /** 目录资源坏了/丢了：退回旧的「已知型号」清单，至少还能按型号挑。*/
    private void loadFallback(String error) {
        catalog = null;
        b.btnPickCat.setEnabled(false);
        b.btnPickSub.setEnabled(false);
        buildBrandFilter();
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
                shown.addAll(catalog.of(selCat, selSub, selBrands));
            }
        } else {
            String needle = DeviceCatalog.normalize(q);
            for (String m : fallbackModels) {
                if (needle.isEmpty() || DeviceCatalog.normalize(m).contains(needle)) {
                    shown.add(new DeviceCatalog.Item(m, "", "", "", 0, "", ""));
                }
            }
        }

        applySort();
        adapter.notifyDataSetChanged();
        updateHint(q);
    }

    /* ---------- 排序 ---------- */

    /** 点同一个按钮再点一次就是正↔倒；换字段从正序开始；「默认」回到目录自带的品牌→名称。*/
    private void setSortField(int field) {
        if (field != 0 && sortField == field) {
            sortDesc = !sortDesc;
        } else {
            sortField = field;
            sortDesc = false;
        }
        updateSortButtons();
        refresh();
    }

    /** 箭头直接写在按钮文字里，一眼看出当前方向。*/
    private void updateSortButtons() {
        b.btnSortName.setText(sortField == 1
                ? (sortDesc ? R.string.sort_name_desc : R.string.sort_name_asc)
                : R.string.sort_name);
        b.btnSortTime.setText(sortField == 2
                ? (sortDesc ? R.string.sort_time_desc : R.string.sort_time_asc)
                : R.string.sort_time);
        b.btnSortName.setSelected(sortField == 1);
        b.btnSortTime.setSelected(sortField == 2);
        b.btnSortDefault.setSelected(sortField == 0);
    }

    private String titleOf(DeviceCatalog.Item it) {
        return it.name.isEmpty() ? it.model : it.name;
    }

    /** 没给上市时间的永远排最后，别让它跑到「最老」那一头。*/
    private static int compareTime(DeviceCatalog.Item a, DeviceCatalog.Item b) {
        long x = a.releasedAt;
        long y = b.releasedAt;
        if (x == y) return 0;
        if (x == 0) return 1;
        if (y == 0) return -1;
        return Long.compare(x, y);
    }

    /** 倒序时「没时间」的照样压到最后：先比有没有时间，再比时间本身。*/
    private static int compareTimeDesc(DeviceCatalog.Item a, DeviceCatalog.Item b) {
        boolean x = a.releasedAt > 0;
        boolean y = b.releasedAt > 0;
        if (x != y) return x ? -1 : 1;
        if (!x) return 0;
        return Long.compare(b.releasedAt, a.releasedAt);
    }

    private void applySort() {
        if (sortField == 0) return;   // 目录本身就是「品牌 → 名称」，不动
        if (sortField == 1) {
            shown.sort((x, y) -> sortDesc
                    ? coll.compare(titleOf(y), titleOf(x))
                    : coll.compare(titleOf(x), titleOf(y)));
        } else {
            shown.sort(sortDesc
                    ? ModelLibraryActivity::compareTimeDesc
                    : ModelLibraryActivity::compareTime);
        }
    }

    private void updateHint(String q) {
        if (catalog == null) return;   // 退回清单时 hint 写的是失败原因，不要覆盖
        if (!q.isEmpty()) {
            b.libraryHint.setText(getString(R.string.msg_catalog_search, shown.size()));
            return;
        }
        if (!selBrands.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (String s : selBrands) {
                if (i == 3) {
                    sb.append(" 等");
                    break;
                }
                if (i > 0) sb.append("、");
                sb.append(s);
                i++;
            }
            b.libraryHint.setText(getString(R.string.msg_catalog_brand, sb.toString(), shown.size()));
            return;
        }
        String scope = selSub.isEmpty() ? selCat : selCat + " / " + selSub;
        if (!scope.isEmpty()) {
            b.libraryHint.setText(getString(R.string.msg_catalog_filter, scope, shown.size()));
            return;
        }
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(catalog.stamp() * 1000L));
        b.libraryHint.setText(getString(R.string.msg_catalog_ready, catalog.size(), date));
    }

    /* ---------- 分类 / 品牌筛选 ---------- */

    private DeviceCatalog.Cat currentCat() {
        if (catalog == null || selCat.isEmpty()) return null;
        for (DeviceCatalog.Cat c : catalog.cats()) if (c.name.equals(selCat)) return c;
        return null;
    }

    /** 分类改成两颗下拉按钮：当前选中项直接写在按钮上，比两排 chip 好认也不占地方。*/
    private void buildFilterBar() {
        if (catalog == null) return;
        b.btnPickCat.setEnabled(true);
        b.btnPickCat.setText(selCat.isEmpty() ? getString(R.string.filter_all_cats) : selCat);
        boolean hasCat = currentCat() != null;
        b.btnPickSub.setEnabled(hasCat);
        b.btnPickSub.setText(!hasCat ? getString(R.string.filter_pick_cat_first)
                : (selSub.isEmpty() ? getString(R.string.filter_all_subs) : selSub));
    }

    private void pickCategory() {
        if (catalog == null) return;
        final List<DeviceCatalog.Cat> cats = catalog.cats();
        String[] items = new String[cats.size() + 1];
        items[0] = getString(R.string.filter_all_cats);
        for (int i = 0; i < cats.size(); i++) {
            items[i + 1] = cats.get(i).name + "（" + cats.get(i).count + "）";
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.title_pick_cat)
                .setItems(items, (d, which) -> {
                    String chosen = which == 0 ? "" : cats.get(which - 1).name;
                    selCat = chosen;
                    selSub = "";
                    selBrands.clear();
                    buildFilterBar();
                    buildBrandFilter();
                    refresh();
                })
                .show();
    }

    private void pickSubcategory() {
        final DeviceCatalog.Cat cat = currentCat();
        if (cat == null) return;
        String[] items = new String[cat.subs.size() + 1];
        items[0] = getString(R.string.filter_all_subs);
        for (int i = 0; i < cat.subs.size(); i++) {
            items[i + 1] = cat.subs.get(i).name + "（" + cat.subs.get(i).items.size() + "）";
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.title_pick_sub)
                .setItems(items, (d, which) -> {
                    String chosen = which == 0 ? "" : cat.subs.get(which - 1).name;
                    if (chosen.equals(selSub)) return;
                    selSub = chosen;
                    buildFilterBar();
                    buildBrandFilter();
                    refresh();
                })
                .show();
    }

    /**
     * 品牌筛选收成一颗下拉按钮：当前筛了谁写在按钮上，点开就是全量多选弹窗。
     * 全部大类下有 1708 个品牌，做成 chip 根本摆不下，弹窗里勾完点确定一次生效。
     */
    private void buildBrandFilter() {
        if (catalog == null) {
            b.btnPickBrand.setEnabled(false);
            b.btnPickBrand.setText(R.string.filter_all_brands);
            return;
        }
        java.util.Map<String, Integer> brands = catalog.brands(selCat, selSub);
        selBrands.retainAll(brands.keySet());   // 换了分类，已经不存在的品牌自动出局
        b.btnPickBrand.setEnabled(!brands.isEmpty());
        b.btnPickBrand.setText(selBrands.isEmpty() ? getString(R.string.filter_all_brands) : brandSummary());
    }

    /** 按钮上只摆前两个名字，再多写成「等 N 个」，别把按钮撑成两行。*/
    private String brandSummary() {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String s : selBrands) {
            if (i == 2) {
                sb.append(" 等 ").append(selBrands.size()).append(" 个");
                break;
            }
            if (i > 0) sb.append("、");
            sb.append(s);
            i++;
        }
        return sb.toString();
    }

    /** 全量品牌清单：搜品牌名 + 勾选 + 右侧 # A-Z 导览，勾完点确定一次生效。*/
    private void showBrandPicker() {
        if (catalog == null) return;
        final java.util.Map<String, Integer> brands = catalog.brands(selCat, selSub);
        if (brands.isEmpty()) return;

        View view = getLayoutInflater().inflate(R.layout.dialog_brand_picker, null);
        final EditText search = view.findViewById(R.id.input_brand_search);
        final ListView list = view.findViewById(R.id.brand_list);
        final LinearLayout index = view.findViewById(R.id.brand_index);
        View holder = view.findViewById(R.id.brand_list_holder);
        list.setEmptyView(view.findViewById(R.id.brand_empty));

        // 列表高度按屏幕比例给：弹窗里用 weight 没戏（父容器是 wrap_content），直接算死更稳
        int listH = (int) (getResources().getDisplayMetrics().heightPixels * 0.45f);
        holder.getLayoutParams().height = listH;
        // 导览条跟着列表一样高：match_parent 在 wrap_content 行里量不出来，索性一起写死
        index.getLayoutParams().height = listH;

        final BrandAdapter adapter = new BrandAdapter(brands);
        list.setAdapter(adapter);
        rebuildBrandIndex(index, list, adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int s0, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence s, int s0, int i1, int i2) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                adapter.filter(e.toString());
                list.setSelection(0);
                rebuildBrandIndex(index, list, adapter);
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(R.string.title_pick_brand)
                .setView(view)
                .setPositiveButton(R.string.action_ok, (d, w) -> {
                    selBrands.clear();
                    selBrands.addAll(adapter.picked);
                    buildBrandFilter();
                    refresh();
                })
                .setNeutralButton(R.string.filter_all, (d, w) -> {
                    selBrands.clear();
                    buildBrandFilter();
                    refresh();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 右侧导览条：只列当前结果里出现过的首字母；点或上下滑都能跳过去。*/
    private void rebuildBrandIndex(LinearLayout bar, final ListView list, final BrandAdapter adapter) {
        bar.removeAllViews();
        final List<Character> letters = new ArrayList<>(adapter.sections.keySet());
        if (letters.size() < 2) return;   // 只剩一个字母或者没结果，导览没意义
        for (int i = 0; i < letters.size(); i++) {
            TextView tv = new TextView(this);
            tv.setText(String.valueOf(letters.get(i)));
            tv.setTextSize(10);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(getColor(R.color.ink_muted));
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            bar.addView(tv);
        }
        bar.setOnTouchListener((v, e) -> {
            int h = bar.getHeight();
            if (h <= 0) return false;
            int idx = (int) (e.getY() * letters.size() / h);
            if (idx < 0) idx = 0;
            if (idx >= letters.size()) idx = letters.size() - 1;
            Integer pos = adapter.sections.get(letters.get(idx));
            if (pos != null) list.setSelection(pos);
            return true;
        });
    }

    /**
     * 首字母归类：# / A-Z。中文用 ICU 的 Han-Latin 转拼音取首字母（小米 → x → X），
     * 转不出来的（生僻字、表情）统统归到 #，总比整条导览条错位强。
     */
    private static char sectionOf(String name) {
        if (name == null || name.isEmpty()) return '#';
        char c = name.charAt(0);
        if (c >= 'a' && c <= 'z') return (char) (c - 'a' + 'A');
        if (c >= 'A' && c <= 'Z') return c;
        if (c < 0x4E00 || c > 0x9FFF) return '#';
        String initial = HAN_INITIAL.get(c);
        if (initial == null) {
            initial = "";
            try {
                String s = android.icu.text.Transliterator.getInstance("Han-Latin")
                        .transliterate(String.valueOf(c));
                for (int i = 0; i < s.length(); i++) {
                    char x = Character.toLowerCase(s.charAt(i));
                    if (x >= 'a' && x <= 'z') {
                        initial = String.valueOf((char) (x - 'a' + 'A'));
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            HAN_INITIAL.put(c, initial);
        }
        return initial.isEmpty() ? '#' : initial.charAt(0);
    }

    /** 品牌多选列表：勾选状态自己存（ListView 会复用行），过滤和导览都基于当前结果。*/
    private class BrandAdapter extends BaseAdapter {

        private final java.util.Map<String, Integer> counts;
        private final List<String> all = new ArrayList<>();
        private final List<String> shown = new ArrayList<>();
        final java.util.Set<String> picked = new java.util.LinkedHashSet<>();
        final java.util.LinkedHashMap<Character, Integer> sections = new java.util.LinkedHashMap<>();

        BrandAdapter(java.util.Map<String, Integer> counts) {
            this.counts = counts;
            all.addAll(counts.keySet());
            all.sort(coll);
            picked.addAll(selBrands);
            filter("");
        }

        void filter(String q) {
            String nq = DeviceCatalog.normalize(q);
            shown.clear();
            for (String s : all) {
                if (nq.isEmpty() || DeviceCatalog.normalize(s).contains(nq)) shown.add(s);
            }
            sections.clear();
            for (int i = 0; i < shown.size(); i++) {
                Character c = sectionOf(shown.get(i));
                if (!sections.containsKey(c)) sections.put(c, i);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_brand_check, parent, false);
            final String name = shown.get(position);
            ((TextView) row.findViewById(R.id.brand_name)).setText(name);
            Integer n = counts.get(name);
            ((TextView) row.findViewById(R.id.brand_count)).setText(n == null ? "" : "（" + n + "）");
            ((CheckBox) row.findViewById(R.id.brand_check)).setChecked(picked.contains(name));
            row.setOnClickListener(v -> {
                if (!picked.remove(name)) picked.add(name);
                notifyDataSetChanged();
            });
            return row;
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
        String released = item.releasedAt > 0
                ? "\n上市 " + listStamp.format(new Date(item.releasedAt * 1000L)) : "";
        String[] items = {
                getString(R.string.title_device_detail),
                getString(R.string.action_create_with_model),
                "在线刷新该 spec",
                "匹配更多该型号版本",
                getString(R.string.action_delete_spec),
        };
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(SpecRepository.describe(this, model) + "\nurn=" + SpecRepository.urnFor(this, model)
                        + released)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        Intent i = new Intent(this, DeviceDetailActivity.class);
                        i.putExtra(DeviceDetailActivity.EXTRA_MODEL, model);
                        i.putExtra(DeviceDetailActivity.EXTRA_NAME, item.name);
                        startActivity(i);
                    } else if (which == 1) createDeviceWith(model);
                    else if (which == 2) fetchOneSpec(model);
                    else if (which == 3) listOnlineCandidates(model);
                    else if (which == 4) {
                        if (SpecRepository.deleteSpec(this, model)) {
                            Toast.makeText(this, R.string.msg_deleted, Toast.LENGTH_SHORT).show();
                            refresh();
                        } else {
                            Toast.makeText(this, R.string.msg_spec_not_imported, Toast.LENGTH_SHORT).show();
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
        d.addedAt = System.currentTimeMillis();
        cfg.devices.add(d);
        if (UiBackend.save(this, cfg)) {
            Intent i = new Intent(this, DeviceEditActivity.class);
            i.putExtra(DeviceEditActivity.EXTRA_DID, d.did);
            // 型号是刚挑的，编辑页进去顺手把 spec 拉下来
            i.putExtra(DeviceEditActivity.EXTRA_AUTO_SPEC, true);
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
            // 长按整行 = 行尾三点：同一个菜单，选型号模式下也能看详情
            i.getRoot().setOnLongClickListener(v -> {
                if (item != null) showRowMenu(item);
                return true;
            });
            i.modelMore.setOnClickListener(v -> {
                if (item != null) showRowMenu(item);
            });
        }

        void bind(DeviceCatalog.Item it) {
            item = it;
            boolean named = !it.name.isEmpty();
            i.modelName.setText(named ? it.name : it.model);
            // 第二行：品牌 + 型号 + 上市时间 —— 列表就是按「品牌 → 名称」排的，看得见依据
            StringBuilder desc = new StringBuilder();
            if (!it.brand.isEmpty()) desc.append(it.brand).append(" · ");
            desc.append(it.model);
            if (it.releasedAt > 0) {
                desc.append(" · ").append(listStamp.format(new Date(it.releasedAt * 1000L))).append(" 推出");
            }
            i.modelDesc.setText(desc.toString());
            ModelIconLoader.load(i.modelIcon, it.icon);
        }
    }
}
