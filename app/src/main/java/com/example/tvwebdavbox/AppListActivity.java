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
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 已安装应用多选页：勾选若干应用后批量备份（上传）到当前 WebDAV 目录。
 * 文件名沿用 PushTV 规则 `<应用名>_v<版本号>.apk`。
 */
public class AppListActivity extends Activity {

    public static final String EXTRA_REMOTE_DIR = "remote_dir";
    public static final String EXTRA_PATHS = "apk_paths";
    public static final String EXTRA_NAMES = "apk_names";

    private ListView listView;
    private TextView title;
    private TextView subtitle;
    private TextView emptyText;
    private TextView selectionText;
    private Button selectAllButton;
    private Button uploadButton;

    private AppAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private List<InstalledApp> allApps = new ArrayList<>();
    /** 电视上大量应用是预装的系统应用，默认全部显示，否则很容易看到空列表。 */
    private boolean showSystemApps = true;
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
            final List<InstalledApp> loaded = apps;
            ui.post(() -> {
                allApps = loaded;
                adapter.clearSelection();
                applyFilter();
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
        uploadButton.setText(getString(R.string.upload_selected, count));
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
