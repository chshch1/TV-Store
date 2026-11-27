package com.example.tvwebdavbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 已安装应用多选页：勾选若干应用后批量备份（上传）到当前 WebDAV 目录。
 * 文件名沿用 PushTV 规则 `<应用名>_v<版本号>.apk`。
 */
public class AppListActivity extends Activity {

    public static final String EXTRA_REMOTE_DIR = "remote_dir";
    public static final String EXTRA_PATHS = "apk_paths";
    public static final String EXTRA_NAMES = "apk_names";

    private GridView listView;
    private TextView title;
    private TextView subtitle;
    private TextView emptyText;
    private TextView selectionText;
    private Button selectAllButton;
    private Button uploadButton;
    private CheckBox showSystemBox;

    private AppAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private List<InstalledApp> allApps = new ArrayList<>();
    private final Map<String, String> cloudTexts = new HashMap<>();
    private final Map<String, Integer> cloudColors = new HashMap<>();
    private static final int CLOUD_OK = 0xFF7BD88F;
    private static final int CLOUD_PENDING = 0xFFFFC857;
    private static final int CLOUD_NEWER = 0xFF4FC3F7;
    private static final int CLOUD_MUTED = 0xFF9AA5B1;
    /** 默认只显示用户应用，系统应用需要手动勾选后才显示（避免一次列出几十项预装应用）。 */
    private boolean showSystemApps = false;
    private String remoteDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_list);

        remoteDir = getIntent().getStringExtra(EXTRA_REMOTE_DIR);

        listView = findViewById(R.id.app_list);
        title = findViewById(R.id.app_title);
        subtitle = findViewById(R.id.app_subtitle);
        emptyText = findViewById(R.id.app_empty);
        selectionText = findViewById(R.id.app_selection);
        selectAllButton = findViewById(R.id.btn_select_all);
        uploadButton = findViewById(R.id.btn_upload_selected);
        showSystemBox = findViewById(R.id.app_show_system);
        showSystemBox.setChecked(showSystemApps);
        showSystemBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showSystemApps = isChecked;
            applyFilter();
        });
        // 文字与勾选框是分开的两个控件（为了分别对齐列表里的勾选框与图标），点文字也能切换
        TextView showSystemLabel = findViewById(R.id.app_show_system_label);
        if (showSystemLabel != null) {
            showSystemLabel.setOnClickListener(v ->
                    showSystemBox.setChecked(!showSystemBox.isChecked()));
        }

        title.setText(R.string.app_list_title);
        subtitle.setText(remoteDir == null ? "" : remoteDir);

        adapter = new AppAdapter(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            adapter.toggle(position);
            updateSelection();
        });

        selectAllButton.setOnClickListener(v -> {
            if (adapter.selectedCount() == adapter.getCount() && adapter.getCount() > 0) {
                adapter.clearSelection();
            } else {
                adapter.selectAll();
            }
            updateSelection();
        });

        uploadButton.setOnClickListener(v -> finishWithSelection());

        emptyText.setText(R.string.app_list_loading);
        emptyText.setVisibility(View.VISIBLE);
        loadApps();
    }

    private void loadApps() {
        emptyText.setText(R.string.app_list_loading);
        emptyText.setVisibility(View.VISIBLE);
        new Thread(() -> {
            List<InstalledApp> apps;
            try {
                InstalledAppRepository.invalidate();
                apps = InstalledAppRepository.load(this);
            } catch (Throwable t) {
                apps = new ArrayList<>();
            }

            // 同时拉取云端目录，做「本机 vs 云端」版本对比
            final Map<String, String> texts = new HashMap<>();
            final Map<String, Integer> colors = new HashMap<>();
            final List<InstalledApp> outdated = new ArrayList<>();
            Map<String, String> cloudVersions = new HashMap<>();
            String cloudError = null;
            try {
                WebDavConfig config = PreferenceStore.loadConfig(this);
                if (!config.isConfigured()) {
                    throw new IOException("未配置服务器");
                }
                if (remoteDir == null || remoteDir.isEmpty()) {
                    throw new IOException("没有远端目录");
                }
                List<RemoteEntry> entries = new WebDavClient(config).list(remoteDir);
                for (RemoteEntry entry : entries) {
                    if (entry == null || entry.directory) {
                        continue;
                    }
                    String[] parsed = ApkFileNames.parse(entry.name);
                    if (parsed == null) {
                        continue;
                    }
                    String key = ApkFileNames.normalize(parsed[0]);
                    String existing = cloudVersions.get(key);
                    // 同一应用有多个备份版本时保留最高的那个
                    if (existing == null || VersionComparator.compare(existing, parsed[1])
                            == VersionComparator.Result.LOCAL_NEWER) {
                        cloudVersions.put(key, parsed[1]);
                    }
                }
            } catch (Throwable t) {
                cloudError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }

            for (InstalledApp app : apps) {
                String cloudVersion = cloudVersions.get(ApkFileNames.normalize(app.label));
                if (cloudError != null) {
                    texts.put(app.packageName, getString(R.string.cloud_error));
                    colors.put(app.packageName, CLOUD_MUTED);
                    continue;
                }
                if (cloudVersion == null) {
                    texts.put(app.packageName, getString(R.string.cloud_missing));
                    colors.put(app.packageName, CLOUD_MUTED);
                    continue;
                }
                VersionComparator.Result result = VersionComparator.compare(app.versionName, cloudVersion);
                switch (result) {
                    case LOCAL_NEWER:
                        texts.put(app.packageName, getString(R.string.cloud_outdated, cloudVersion));
                        colors.put(app.packageName, CLOUD_PENDING);
                        outdated.add(app);
                        break;
                    case REMOTE_NEWER:
                        texts.put(app.packageName, getString(R.string.cloud_newer, cloudVersion));
                        colors.put(app.packageName, CLOUD_NEWER);
                        break;
                    case EQUAL:
                        texts.put(app.packageName, getString(R.string.cloud_synced, cloudVersion));
                        colors.put(app.packageName, CLOUD_OK);
                        break;
                    default:
                        texts.put(app.packageName, getString(R.string.cloud_incomparable, cloudVersion));
                        colors.put(app.packageName, CLOUD_MUTED);
                        break;
                }
            }

            final List<InstalledApp> loaded = apps;
            final String finalError = cloudError;
            // 只勾选当前筛选条件下真正可见的行，避免"已选 N"里混进被隐藏的系统应用
            final List<InstalledApp> outdatedVisible = new ArrayList<>();
            for (InstalledApp app : outdated) {
                if (!app.systemApp || showSystemApps) {
                    outdatedVisible.add(app);
                }
            }
            final int autoCount = outdatedVisible.size();
            ui.post(() -> {
                allApps = loaded;
                cloudTexts.clear();
                cloudTexts.putAll(texts);
                cloudColors.clear();
                cloudColors.putAll(colors);
                adapter.setCloudInfo(cloudTexts, cloudColors);
                adapter.clearSelection();
                applyFilter();
                if (autoCount > 0) {
                    adapter.selectPackages(outdatedVisible);
                    updateSelection();
                    toast(getString(R.string.cloud_auto_selected, autoCount));
                }
                if (finalError != null) {
                    toast(getString(R.string.cloud_read_failed, finalError));
                }
            });
        }, "app-list").start();
    }

    private void applyFilter() {
        List<InstalledApp> visible = new ArrayList<>();
        int systemCount = 0;
        for (InstalledApp app : allApps) {
            if (app.systemApp) {
                systemCount++;
            }
            if (app.systemApp && !showSystemApps) {
                continue;
            }
            visible.add(app);
        }
        subtitle.setText(getString(R.string.app_list_subtitle_fmt,
                allApps.size(), allApps.size() - systemCount, systemCount));
        // 切换“只看用户应用”时保留已勾选项，避免选完又被清空
        adapter.submit(visible, true);

        if (visible.isEmpty()) {
            if (allApps.isEmpty()) {
                String error = InstalledAppRepository.getLastError();
                emptyText.setText(getString(R.string.app_list_empty_diag,
                        InstalledAppRepository.getLastRawCount(),
                        error == null || error.isEmpty() ? "" : "（" + error + "）"));
            } else {
                emptyText.setText(R.string.app_list_empty);
            }
            emptyText.setVisibility(View.VISIBLE);
        } else {
            emptyText.setVisibility(View.GONE);
            listView.requestFocus();
            if (listView.getSelectedItemPosition() < 0) {
                listView.setSelection(0);
            }
        }
        updateSelection();
    }

    private void updateSelection() {
        int count = adapter.selectedCount();
        selectionText.setText(getString(R.string.app_selection, count, adapter.getCount()));
        uploadButton.setText(R.string.app_upload_action);
        uploadButton.setEnabled(count > 0);
        selectAllButton.setText(count > 0 && count == adapter.getCount()
                ? R.string.app_select_none : R.string.app_select_all);
    }

    private void finishWithSelection() {
        List<InstalledApp> selected = adapter.selectedItems();
        if (selected.isEmpty()) {
            toast(getString(R.string.app_none_selected));
            return;
        }
        String[] paths = new String[selected.size()];
        String[] names = new String[selected.size()];
        for (int i = 0; i < selected.size(); i++) {
            InstalledApp app = selected.get(i);
            paths[i] = app.sourceDir;
            names[i] = app.remoteFileName();
        }
        Intent result = new Intent();
        result.putExtra(EXTRA_PATHS, paths);
        result.putExtra(EXTRA_NAMES, names);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                showOptions();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void showOptions() {
        String systemLabel = showSystemApps
                ? getString(R.string.app_hide_system) : getString(R.string.app_show_system);
        String[] actions = {
                getString(R.string.app_select_all),
                getString(R.string.app_select_none),
                systemLabel,
                getString(R.string.app_reload)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_list_title)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        adapter.selectAll();
                        updateSelection();
                    } else if (which == 1) {
                        adapter.clearSelection();
                        updateSelection();
                    } else if (which == 2) {
                        showSystemApps = !showSystemApps;
                        showSystemBox.setChecked(showSystemApps);
                        applyFilter();
                    } else {
                        loadApps();
                    }
                })
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
