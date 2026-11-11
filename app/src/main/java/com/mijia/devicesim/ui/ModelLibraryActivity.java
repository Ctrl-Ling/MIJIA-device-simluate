/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 19:29:13 +08:00
 */
package com.mijia.devicesim.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.view.animation.DecelerateInterpolator;
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

    /** 分类 / 子类 / 品牌都是多选：空集合 = 不限，集合内部是「或」。*/
    private final java.util.LinkedHashSet<String> selCats = new java.util.LinkedHashSet<>();
    private final java.util.LinkedHashSet<String> selSubs = new java.util.LinkedHashSet<>();
    private final java.util.LinkedHashSet<String> selBrands = new java.util.LinkedHashSet<>();

    /** 排序字段：0 = 品牌→名称（目录自带顺序），1 = 名称，2 = 上市时间。*/
    private int sortField = 0;
    /** 当前字段是否倒序：点同一个按钮再切一次就翻，不用并列两个方向按钮。*/
    private boolean sortDesc = false;
    /** 同步/恢复正在后台跑：两个按钮都先锁住，别点两下同时解 17MB。*/
    private boolean syncing = false;
    /** 筛选面板是否展开；进页面默认展开，向下滑列表时自动收起，点摘要重新展开。*/
    private boolean filterExpanded = true;
    /** 面板收/放的过渡动画；正在跑时再次触发会先取消旧的。*/
    private ValueAnimator filterAnim;
    private static final long FILTER_ANIM_MS = 220;

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

        // 筛选面板默认展开；向下浏览列表时收起，只留一行属性摘要，点摘要再展开
        b.filterSummary.setOnClickListener(v -> setFilterExpanded(true));
        b.modelList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 0 && filterExpanded) setFilterExpanded(false);
            }
        });

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
                selCats.clear();
                selSubs.clear();
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
                selCats.clear();
                selSubs.clear();
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
                shown.addAll(catalog.of(selCats, selSubs, selBrands));
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
        updateFilterSummary();
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
            b.libraryHint.setText(getString(R.string.msg_catalog_brand,
                    joinNames(selBrands, 3), shown.size()));
            return;
        }
        String scope = scopeSummary();
        if (!scope.isEmpty()) {
            b.libraryHint.setText(getString(R.string.msg_catalog_filter, scope, shown.size()));
            return;
        }
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(catalog.stamp() * 1000L));
        b.libraryHint.setText(getString(R.string.msg_catalog_ready, catalog.size(), date));
    }

    /* ---------- 分类 / 品牌筛选 ---------- */

    /**
     * 分类 / 子类两颗下拉按钮：都常亮，全部类型时子类照样能点（看的是全量子类）。
     * 当前选择写成一行摘要，点开是多选弹窗。
     */
    private void buildFilterBar() {
        if (catalog == null) return;
        b.btnPickCat.setEnabled(true);
        b.btnPickCat.setText(selCats.isEmpty() ? getString(R.string.filter_all_cats)
                : joinNames(selCats, 2));
        b.btnPickSub.setEnabled(true);
        b.btnPickSub.setText(selSubs.isEmpty() ? getString(R.string.filter_all_subs)
                : joinNames(selSubs, 2));
    }

    /** 当前大类范围里出现过的子类 → 型号数；没选大类时就是全部子类。*/
    private java.util.Map<String, Integer> availableSubs() {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        if (catalog == null) return counts;
        for (DeviceCatalog.Cat c : catalog.cats()) {
            if (!selCats.isEmpty() && !selCats.contains(c.name)) continue;
            for (DeviceCatalog.Sub s : c.subs) counts.merge(s.name, s.items.size(), Integer::sum);
        }
        List<String> names = new ArrayList<>(counts.keySet());
        names.sort(coll);
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (String n : names) out.put(n, counts.get(n));
        return out;
    }

    private void pickCategory() {
        if (catalog == null) return;
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (DeviceCatalog.Cat c : catalog.cats()) counts.put(c.name, c.count);
        showCheckPicker(R.string.title_pick_cat, R.string.hint_cat_search, counts, selCats, picked -> {
            selCats.clear();
            selCats.addAll(picked);
            // 换了大类，已经不在新范围里的子类自动出局
            selSubs.retainAll(availableSubs().keySet());
            afterFilterChange();
        });
    }

    private void pickSubcategory() {
        if (catalog == null) return;
        showCheckPicker(R.string.title_pick_sub, R.string.hint_sub_search, availableSubs(), selSubs, picked -> {
            selSubs.clear();
            selSubs.addAll(picked);
            afterFilterChange();
        });
    }

    /** 任意一级筛选变了：按钮文案、品牌候选、列表结果一起刷新。*/
    private void afterFilterChange() {
        buildFilterBar();
        buildBrandFilter();
        refresh();
    }

    /** 展开 / 收起筛选面板：高度和透明度一起过渡，收起后只留一行属性摘要。*/
    private void setFilterExpanded(boolean expanded) {
        if (filterExpanded == expanded) return;
        filterExpanded = expanded;
        if (!expanded) updateFilterSummary();
        animateFilter(expanded);
    }

    /**
     * 面板和摘要做交叉过渡：一边长高一边缩短，两块的总高度连续变化，
     * 下方列表不会先跳一下再滑。expand = true 表示「摘要退场、面板长出来」。
     */
    private void animateFilter(final boolean expand) {
        final View panel = b.filterPanel;
        final View summary = b.filterSummary;

        // 先放掉旧动画：取消回调里 filterAnim 已经换人，不会误收尾
        if (filterAnim != null) {
            ValueAnimator old = filterAnim;
            filterAnim = null;
            old.cancel();
        }

        int panelFrom;
        int summaryFrom;
        if (expand) {
            panelFrom = 0;
            summaryFrom = summary.getHeight();
            // 先把面板按 0 高摆出来，避免动画前闪一帧全高
            panel.getLayoutParams().height = 0;
            panel.setVisibility(View.VISIBLE);
        } else {
            panelFrom = panel.getHeight();
            summaryFrom = 0;
            // 摘要同样按 0 高现身，跟着动画一起长出来
            summary.getLayoutParams().height = 0;
            summary.setVisibility(View.VISIBLE);
        }

        final int panelTo = expand ? measureHeight(panel) : 0;
        final int summaryTo = expand ? 0 : measureHeight(summary);

        panel.setAlpha(expand ? 0f : 1f);
        summary.setAlpha(expand ? 1f : 0f);

        filterAnim = ValueAnimator.ofFloat(0f, 1f);
        filterAnim.setDuration(FILTER_ANIM_MS);
        filterAnim.setInterpolator(new DecelerateInterpolator());
        filterAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            setAnimHeight(panel, panelFrom + (panelTo - panelFrom) * f);
            setAnimHeight(summary, summaryFrom + (summaryTo - summaryFrom) * f);
            panel.setAlpha(expand ? f : 1f - f);
            summary.setAlpha(expand ? 1f - f : f);
        });
        filterAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (filterAnim != a) return;
                filterAnim = null;
                panel.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                summary.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                panel.setAlpha(1f);
                summary.setAlpha(1f);
                panel.setVisibility(expand ? View.VISIBLE : View.GONE);
                summary.setVisibility(expand ? View.GONE : View.VISIBLE);
                panel.requestLayout();
                summary.requestLayout();
            }
        });
        filterAnim.start();
    }

    /** 动画里直接改 layoutParams 高度，浮点截成整像素。*/
    private void setAnimHeight(View v, float px) {
        v.getLayoutParams().height = Math.max(0, Math.round(px));
        v.requestLayout();
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

    /** 摘要行把当前筛选和排序压成一句，收起来也一眼知道自己在看什么范围。*/
    private void updateFilterSummary() {
        if (b == null || catalog == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(selCats.isEmpty() ? getString(R.string.filter_all_cats) : joinNames(selCats, 2));
        if (!selSubs.isEmpty()) sb.append(" / ").append(joinNames(selSubs, 2));
        sb.append(" · ")
                .append(selBrands.isEmpty() ? getString(R.string.filter_all_brands) : joinNames(selBrands, 2))
                .append(" · ").append(shown.size()).append(" 个 · ")
                .append(sortField == 0 ? getString(R.string.sort_default)
                        : sortField == 1 ? b.btnSortName.getText() : b.btnSortTime.getText());
        b.filterSummaryText.setText(sb.toString());
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
        java.util.Map<String, Integer> brands = catalog.brands(selCats, selSubs);
        selBrands.retainAll(brands.keySet());   // 换了分类，已经不存在的品牌自动出局
        b.btnPickBrand.setEnabled(!brands.isEmpty());
        b.btnPickBrand.setText(selBrands.isEmpty() ? getString(R.string.filter_all_brands) : brandSummary());
    }

    /** 按钮上只摆前两个名字，再多写成「等 N 个」，别把按钮撑成两行。*/
    private String brandSummary() {
        return joinNames(selBrands, 2);
    }

    /** 多选集合拼成一行摘要：前 max 个用顿号连，超出的写「等 N 个」。*/
    private static String joinNames(java.util.Collection<String> names, int max) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String s : names) {
            if (i == max) {
                sb.append(" 等 ").append(names.size()).append(" 个");
                break;
            }
            if (i > 0) sb.append("、");
            sb.append(s);
            i++;
        }
        return sb.toString();
    }

    /** 提示条上的筛选范围：大类在前、子类在后，都用摘要写法。*/
    private String scopeSummary() {
        StringBuilder sb = new StringBuilder();
        if (!selCats.isEmpty()) sb.append(joinNames(selCats, 3));
        if (!selSubs.isEmpty()) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(joinNames(selSubs, 3));
        }
        return sb.toString();
    }

    /** 全量品牌清单：搜品牌名 + 勾选 + 右侧 # A-Z 导览，勾完点确定一次生效。*/
    private void showBrandPicker() {
        if (catalog == null) return;
        java.util.Map<String, Integer> brands = catalog.brands(selCats, selSubs);
        if (brands.isEmpty()) return;
        showCheckPicker(R.string.title_pick_brand, R.string.hint_brand_search, brands, selBrands, picked -> {
            selBrands.clear();
            selBrands.addAll(picked);
            buildBrandFilter();
            refresh();
        });
    }

    /**
     * 通用多选弹窗：搜索 + 勾选 + 右侧 # A-Z 导览。
     * 分类、子类、品牌共用一套 —— 选项上千个时靠搜索定位，勾完点确定一次生效。
     */
    private void showCheckPicker(int titleRes, int hintRes, java.util.Map<String, Integer> options,
                                 java.util.Set<String> selected,
                                 java.util.function.Consumer<java.util.Set<String>> apply) {
        if (options.isEmpty()) return;

        View view = getLayoutInflater().inflate(R.layout.dialog_brand_picker, null);
        final EditText search = view.findViewById(R.id.input_brand_search);
        search.setHint(hintRes);
        final ListView list = view.findViewById(R.id.brand_list);
        final LinearLayout index = view.findViewById(R.id.brand_index);
        View holder = view.findViewById(R.id.brand_list_holder);
        list.setEmptyView(view.findViewById(R.id.brand_empty));

        // 列表高度按屏幕比例给：弹窗里用 weight 没戏（父容器是 wrap_content），直接算死更稳
        int listH = (int) (getResources().getDisplayMetrics().heightPixels * 0.45f);
        holder.getLayoutParams().height = listH;
        // 导览条跟着列表一样高：match_parent 在 wrap_content 行里量不出来，索性一起写死
        index.getLayoutParams().height = listH;

        final CheckAdapter adapter = new CheckAdapter(options, selected);
        list.setAdapter(adapter);
        rebuildCheckIndex(index, list, adapter);

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
                rebuildCheckIndex(index, list, adapter);
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(view)
                .setPositiveButton(R.string.action_ok,
                        (d, w) -> apply.accept(new java.util.LinkedHashSet<>(adapter.picked)))
                .setNeutralButton(R.string.filter_all,
                        (d, w) -> apply.accept(new java.util.LinkedHashSet<>()))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 右侧导览条：只列当前结果里出现过的首字母；点或上下滑都能跳过去。*/
    private void rebuildCheckIndex(LinearLayout bar, final ListView list, final CheckAdapter adapter) {
        bar.removeAllViews();
        final List<Character> letters = new ArrayList<>(adapter.sections.keySet());
        if (letters.size() < 2) return;   // 只剩一个字母或者没结果，导览没意义
        // 导览必须按 A→Z 排，'#' 压最后；之前顺着品牌列表取键，中文拼音一混就乱了
        letters.sort((x, y) -> {
            boolean hx = x == '#';
            boolean hy = y == '#';
            if (hx != hy) return hx ? 1 : -1;
            return Character.compare(x, y);
        });
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

    /** 多选列表（分类/子类/品牌共用）：勾选状态自己存（ListView 会复用行），过滤和导览都基于当前结果。*/
    private class CheckAdapter extends BaseAdapter {

        private final java.util.Map<String, Integer> counts;
        private final List<String> all = new ArrayList<>();
        private final List<String> shown = new ArrayList<>();
        final java.util.Set<String> picked = new java.util.LinkedHashSet<>();
        final java.util.LinkedHashMap<Character, Integer> sections = new java.util.LinkedHashMap<>();

        CheckAdapter(java.util.Map<String, Integer> counts, java.util.Set<String> selected) {
            this.counts = counts;
            all.addAll(counts.keySet());
            all.sort(coll);
            if (selected != null) picked.addAll(selected);
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
                    if (which == 0) openDetail(item);
                    else if (which == 1) createDeviceWith(model);
                    else if (which == 2) fetchOneSpec(model);
                    else if (which == 3) listOnlineCandidates(model);
                    else if (which == 4) deleteImportedSpec(model);
                })
                .show();
    }

    /**
     * 行尾三点只放两个高频项：看详情、删掉导入的 spec。
     * 建/刷新/匹配这些仍留在长按菜单里，免得三点菜单又变成一长串。
     */
    private void showQuickMenu(DeviceCatalog.Item item) {
        String[] items = {
                getString(R.string.title_device_detail),
                getString(R.string.action_delete_spec),
        };
        new AlertDialog.Builder(this)
                .setTitle(item.name.isEmpty() ? item.model : item.name)
                .setItems(items, (d, which) -> {
                    if (which == 0) openDetail(item);
                    else deleteImportedSpec(item.model);
                })
                .show();
    }

    private void openDetail(DeviceCatalog.Item item) {
        Intent i = new Intent(this, DeviceDetailActivity.class);
        i.putExtra(DeviceDetailActivity.EXTRA_MODEL, item.model);
        i.putExtra(DeviceDetailActivity.EXTRA_NAME, item.name);
        startActivity(i);
    }

    private void deleteImportedSpec(String model) {
        if (SpecRepository.deleteSpec(this, model)) {
            Toast.makeText(this, R.string.msg_deleted, Toast.LENGTH_SHORT).show();
            refresh();
        } else {
            Toast.makeText(this, R.string.msg_spec_not_imported, Toast.LENGTH_SHORT).show();
        }
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
                if (item != null) showQuickMenu(item);
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
