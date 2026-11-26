package com.example.tvwebdavbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 电视端主界面：浏览 WebDAV 目录，下载播放 / 安装 APK / 上传 / 删除 / 建目录。
 * 全部交互围绕遥控器方向键与确认键设计，不依赖触屏。
 */
public class MainActivity extends Activity {

    private static final int REQUEST_SETTINGS = 2001;
    private static final int REQUEST_PICK_FILE = 2002;
    private static final int REQUEST_PICK_APPS = 2003;

    private static final int COLOR_BADGE_INFO = 0xFF4FC3F7;
    private static final int COLOR_BADGE_UPGRADE = 0xFFFFC857;
    private static final int COLOR_BADGE_OK = 0xFF7BD88F;
    private static final int COLOR_BADGE_MUTED = 0xFF9AA5B1;

    private ListView listView;
    private TextView pathText;
    private TextView statusText;
    private TextView emptyText;
    private TextView progressText;
    private TextView hintText;
    private ProgressBar progressBar;
    private LinearLayout progressPanel;
    private Button buttonSettings;
    private Button buttonRefresh;
    private Button buttonApps;
    private Button buttonUpload;
    private Button buttonMkcol;

    private EntryAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private WebDavClient client;
    private String currentUrl;
    private String rootUrl;
    private volatile boolean busy;

    /** 当前目录的原始条目，用于应用索引就绪后重绘角标。 */
    private List<RemoteEntry> currentEntries = new ArrayList<>();
    private volatile boolean appIndexReady;
    private volatile boolean warmingUp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = findViewById(R.id.list_files);
        pathText = findViewById(R.id.path_text);
        statusText = findViewById(R.id.status_text);
        emptyText = findViewById(R.id.empty_text);
        progressText = findViewById(R.id.progress_text);
        progressBar = findViewById(R.id.progress_bar);
        progressPanel = findViewById(R.id.progress_panel);
        hintText = findViewById(R.id.hint_text);
        buttonSettings = findViewById(R.id.btn_settings);
        buttonRefresh = findViewById(R.id.btn_refresh);
        buttonApps = findViewById(R.id.btn_apps);
        buttonUpload = findViewById(R.id.btn_upload);
        buttonMkcol = findViewById(R.id.btn_mkcol);

        adapter = new EntryAdapter(this);
        listView.setAdapter(adapter);
        listView.setLongClickable(true);

        listView.setOnItemClickListener((parent, view, position, id) -> onEntryClicked(position));
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(position);
            return true;
        });

        buttonSettings.setOnClickListener(v ->
                startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS));
        buttonRefresh.setOnClickListener(v -> refresh(true));
        buttonApps.setOnClickListener(v -> openAppPicker());
        buttonUpload.setOnClickListener(v -> pickLocalFile());
        buttonMkcol.setOnClickListener(v -> promptNewFolder());

        hintText.setText(R.string.key_hint);
        warmUpAppIndex();
    }

    /** 应用索引在后台加载，就绪后重绘列表角标（避免在 UI 线程读 PackageManager）。 */
    private void warmUpAppIndex() {
        if (warmingUp) {
            return;
        }
        warmingUp = true;
        new Thread(() -> {
            try {
                InstalledAppRepository.cached(this);
            } catch (Exception ignored) {
                // 读取失败时只是不显示版本角标
            }
            appIndexReady = true;
            warmingUp = false;
            ui.post(() -> {
                if (!isFinishing() && !currentEntries.isEmpty()) {
                    renderRows();
                }
            });
        }, "app-index").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 应用列表页会失效索引，回到主界面时按需重建（后台线程，不卡 UI）
        if (!appIndexReady || !InstalledAppRepository.isReady()) {
            appIndexReady = false;
            warmUpAppIndex();
        }
        WebDavConfig config = PreferenceStore.loadConfig(this);
        if (!config.isConfigured()) {
            showEmpty(getString(R.string.status_unconfigured));
            pathText.setText(R.string.path_placeholder);
            buttonSettings.requestFocus();
            return;
        }
        String root = client == null ? null : client.rootUrl();
        boolean changed = client == null || !root.equals(new WebDavClient(config).rootUrl())
                || !config.serverUrl.equals(client.getConfig().serverUrl)
                || !config.username.equals(client.getConfig().username)
                || !config.password.equals(client.getConfig().password);
        client = new WebDavClient(config);
        rootUrl = client.rootUrl();
        if (currentUrl == null || changed) {
            currentUrl = rootUrl;
        }
        refresh(false);
        if (!buttonRefresh.hasFocus() && !listView.hasFocus()) {
            listView.requestFocus();
        }
    }

    // ------------------------------------------------------------ 列表加载

    private void refresh(boolean showBusy) {
        if (client == null || currentUrl == null) {
            return;
        }
        final String target = currentUrl;
        if (showBusy) {
            setBusy(true, getString(R.string.loading));
        }
        new Thread(() -> {
            try {
                List<RemoteEntry> entries = client.list(target);
                ui.post(() -> {
                    setBusy(false, null);
                    applyEntries(target, entries);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    setBusy(false, null);
                    applyEntries(target, new ArrayList<>());
                    showEmpty(describeError(e));
                });
            }
        }, "webdav-list").start();
    }

    private void applyEntries(String dirUrl, List<RemoteEntry> entries) {
        currentUrl = dirUrl;
        currentEntries = entries;
        renderRows();
        emptyText.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        if (entries.isEmpty()) {
            emptyText.setText(R.string.empty_directory);
        }
        if (adapter.getCount() > 0) {
            listView.requestFocus();
            listView.setSelection(0);
        }
    }

    private void renderRows() {
        List<RowItem> rows = new ArrayList<>(currentEntries.size());
        for (RemoteEntry entry : currentEntries) {
            rows.add(toRow(entry));
        }
        adapter.submit(rows);
        pathText.setText(currentPath());
        statusText.setText(getString(R.string.status_connected, adapter.getCount()));
    }

    private RowItem toRow(RemoteEntry entry) {
        String meta = entry.directory ? getString(R.string.type_folder)
                : joinMeta(FileUtils.humanSize(entry.size), FileUtils.formatTime(entry.lastModified));
        if (entry.directory || !ApkFileNames.isApk(entry.name)) {
            return new RowItem(entry.name, meta, entry.directory, entry);
        }
        ApkBadge badge = evaluateApk(entry.name);
        if (badge == null) {
            return new RowItem(entry.name, meta, false, entry);
        }
        if (badge.app != null) {
            meta = joinMeta(meta, getString(R.string.local_version, badge.app.versionName));
        }
        return new RowItem(entry.name, meta, false, entry, badge.text, badge.color);
    }

    /** 远端 APK 与本机已安装版本比较，结果作为行角标。 */
    private ApkBadge evaluateApk(String fileName) {
        if (!appIndexReady || !InstalledAppRepository.isReady()) {
            return null;
        }
        String[] parsed = ApkFileNames.parse(fileName);
        String remoteVersion = parsed == null ? null : parsed[1];
        InstalledApp app = InstalledAppRepository.findByBackupFile(fileName);
        if (app == null) {
            return new ApkBadge(getString(R.string.badge_not_installed), COLOR_BADGE_INFO, null);
        }
        if (remoteVersion == null) {
            return new ApkBadge(getString(R.string.badge_uncomparable), COLOR_BADGE_MUTED, app);
        }
        VersionComparator.Result result = VersionComparator.compare(app.versionName, remoteVersion);
        switch (result) {
            case EQUAL:
                return new ApkBadge(getString(R.string.badge_same), COLOR_BADGE_OK, app);
            case REMOTE_NEWER:
                return new ApkBadge(
                        getString(R.string.badge_upgrade, app.versionName, remoteVersion),
                        COLOR_BADGE_UPGRADE, app);
            case LOCAL_NEWER:
                return new ApkBadge(
                        getString(R.string.badge_local_newer, app.versionName, remoteVersion),
                        COLOR_BADGE_MUTED, app);
            default:
                return new ApkBadge(getString(R.string.badge_uncomparable), COLOR_BADGE_MUTED, app);
        }
    }

    private static final class ApkBadge {
        final String text;
        final int color;
        final InstalledApp app;

        ApkBadge(String text, int color, InstalledApp app) {
            this.text = text;
            this.color = color;
            this.app = app;
        }
    }

    private String joinMeta(String size, String time) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(size)) {
            sb.append(size);
        }
        if (!TextUtils.isEmpty(time)) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(time);
        }
        return sb.toString();
    }

    private String currentPath() {
        if (currentUrl == null) {
            return "/";
        }
        Uri uri = Uri.parse(currentUrl);
        String path = uri.getPath();
        return TextUtils.isEmpty(path) ? "/" : path;
    }

    // ------------------------------------------------------------ 点击行为

    private void onEntryClicked(int position) {
        RowItem row = adapter.getItemAt(position);
        if (row == null || !(row.tag instanceof RemoteEntry)) {
            return;
        }
        RemoteEntry entry = (RemoteEntry) row.tag;
        if (entry.directory) {
            refreshInto(entry.url);
        } else {
            downloadAnd(entry, true);
        }
    }

    private void refreshInto(String dirUrl) {
        String target = dirUrl.endsWith("/") ? dirUrl : dirUrl + "/";
        if (target.equals(currentUrl)) {
            refresh(true);
            return;
        }
        currentUrl = target;
        currentEntries = new ArrayList<>();
        pathText.setText(currentPath());
        adapter.submit(new ArrayList<>());
        emptyText.setVisibility(View.VISIBLE);
        emptyText.setText(R.string.loading);
        refresh(false);
    }

    private void showActions(int position) {
        RowItem row = adapter.getItemAt(position);
        if (row == null || !(row.tag instanceof RemoteEntry)) {
            toast(getString(R.string.select_first));
            return;
        }
        final RemoteEntry entry = (RemoteEntry) row.tag;

        if (entry.directory) {
            String[] actions = {
                    getString(R.string.action_open),
                    getString(R.string.action_new_folder),
                    getString(R.string.action_delete)
            };
            new AlertDialog.Builder(this)
                    .setTitle(entry.name)
                    .setItems(actions, (dialog, which) -> {
                        if (which == 0) {
                            refreshInto(entry.url);
                        } else if (which == 1) {
                            promptNewFolder(entry.url);
                        } else {
                            confirmDelete(entry);
                        }
                    })
                    .show();
            return;
        }

        String[] actions = {
                getString(FileUtils.isApk(entry.name) ? R.string.action_download_install
                        : R.string.action_download_open),
                getString(R.string.action_download_save),
                getString(R.string.action_delete)
        };
        new AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        downloadAnd(entry, true);
                    } else if (which == 1) {
                        downloadAnd(entry, false);
                    } else {
                        confirmDelete(entry);
                    }
                })
                .show();
    }

    private void confirmDelete(final RemoteEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_delete)
                .setMessage(getString(R.string.delete_confirm, entry.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) ->
                        runTask(getString(R.string.deleting),
                                listener -> client.delete(entry.url),
                                () -> {
                                    toast(getString(R.string.delete_done));
                                    refresh(false);
                                }))
                .show();
    }

    // ------------------------------------------------------------ 下载

    private File downloadDir() {
        File dir = getExternalFilesDir("downloads");
        if (dir == null) {
            dir = new File(getFilesDir(), "downloads");
        }
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private void downloadAnd(final RemoteEntry entry, final boolean openAfter) {
        final File destination = FileUtils.uniqueFile(downloadDir(), entry.name);
        if (destination == null) {
            toast(getString(R.string.storage_unavailable));
            return;
        }
        runTask(getString(R.string.downloading, entry.name),
                listener -> client.download(entry.url, destination, listener),
                () -> {
                    toast(getString(R.string.download_done, destination.getAbsolutePath()));
                    if (openAfter) {
                        openLocalFile(destination);
                    }
                });
    }

    private void openLocalFile(File file) {
        if (file == null || !file.exists()) {
            toast(getString(R.string.file_missing));
            return;
        }
        Uri uri = LocalFileProvider.uriFor(this, file);
        try {
            if (FileUtils.isVideo(file.getName())) {
                Intent intent = new Intent(this, PlayerActivity.class);
                intent.setData(uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
                return;
            }
            if (FileUtils.isApk(file.getName())) {
                installApk(uri);
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, FileUtils.mimeTypeOf(file.getName()));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast(getString(R.string.no_handler));
        }
    }

    private void installApk(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            toast(getString(R.string.allow_unknown_sources));
            try {
                startActivity(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES",
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
                // 部分老机型没有该设置页
            }
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast(getString(R.string.no_handler));
        }
    }

    // ------------------------------------------------------------ 上传

    private void pickLocalFile() {
        if (client == null || currentUrl == null) {
            toast(getString(R.string.status_unconfigured));
            return;
        }
        Intent intent = new Intent(this, LocalPickerActivity.class);
        startActivityForResult(intent, REQUEST_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS) {
            currentUrl = null;
            client = null;
            return;
        }
        if (requestCode == REQUEST_PICK_FILE && resultCode == RESULT_OK && data != null) {
            String path = data.getStringExtra(LocalPickerActivity.EXTRA_PICKED_PATH);
            if (!TextUtils.isEmpty(path)) {
                uploadLocalFile(new File(path));
            }
        }
        if (requestCode == REQUEST_PICK_APPS && resultCode == RESULT_OK && data != null) {
            String[] paths = data.getStringArrayExtra(AppListActivity.EXTRA_PATHS);
            String[] names = data.getStringArrayExtra(AppListActivity.EXTRA_NAMES);
            if (paths != null && names != null && paths.length == names.length && paths.length > 0) {
                uploadApps(paths, names);
            }
        }
    }

    private void openAppPicker() {
        if (client == null || currentUrl == null) {
            toast(getString(R.string.status_unconfigured));
            return;
        }
        Intent intent = new Intent(this, AppListActivity.class);
        intent.putExtra(AppListActivity.EXTRA_REMOTE_DIR, currentUrl);
        startActivityForResult(intent, REQUEST_PICK_APPS);
    }

    /** 批量上传已安装应用（逐个 PUT，整体进度条按文件数推进）。 */
    private void uploadApps(final String[] paths, final String[] names) {
        if (busy) {
            toast(getString(R.string.busy));
            return;
        }
        final String dirUrl = currentUrl;
        final int total = paths.length;
        setBusy(true, getString(R.string.upload_batch_start, total));

        new Thread(() -> {
            int success = 0;
            List<String> failures = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                File source = new File(paths[i]);
                final String name = names[i];
                final int index = i;
                try {
                    if (!source.isFile()) {
                        throw new IOException(getString(R.string.file_missing));
                    }
                    client.upload(source, dirUrl, name, (done, size) -> {
                        double fraction = size > 0 ? (double) done / size : 0;
                        int percent = (int) Math.min(100, (index + fraction) * 100 / total);
                        ui.post(() -> updateBatchProgress(index + 1, total, percent, name));
                    });
                    success++;
                } catch (Exception e) {
                    failures.add(name + "：" + describeErrorText(e));
                }
            }
            final int uploaded = success;
            ui.post(() -> {
                setBusy(false, null);
                refresh(false);
                if (failures.isEmpty()) {
                    toast(getString(R.string.upload_batch_done, uploaded));
                } else {
                    StringBuilder sb = new StringBuilder(
                            getString(R.string.upload_batch_failed, failures.size()));
                    sb.append('\n');
                    for (int i = 0; i < Math.min(5, failures.size()); i++) {
                        sb.append('\n').append(failures.get(i));
                    }
                    if (failures.size() > 5) {
                        sb.append("\n…");
                    }
                    showErrorDialog(sb.toString());
                }
            });
        }, "webdav-upload-batch").start();
    }

    private String describeErrorText(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private void updateBatchProgress(int index, int total, int percent, String fileName) {
        progressPanel.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setProgress(percent);
        progressText.setText(getString(R.string.upload_batch_progress, index, total, percent));
    }

    private void uploadLocalFile(final File source) {
        if (!source.isFile()) {
            toast(getString(R.string.file_missing));
            return;
        }
        final String dirUrl = currentUrl;
        runTask(getString(R.string.uploading, source.getName()),
                listener -> client.upload(source, dirUrl, source.getName(), listener),
                () -> {
                    toast(getString(R.string.upload_done));
                    refresh(false);
                });
    }

    // ------------------------------------------------------------ 新建目录

    private void promptNewFolder() {
        if (currentUrl == null) {
            toast(getString(R.string.status_unconfigured));
            return;
        }
        promptNewFolder(currentUrl);
    }

    private void promptNewFolder(final String parentUrl) {
        final EditText input = new EditText(this);
        input.setHint(R.string.hint_folder_name);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_new_folder)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
                        toast(getString(R.string.invalid_folder_name));
                        return;
                    }
                    final String target = WebDavClient.joinUrl(parentUrl, name);
                    runTask(getString(R.string.creating_folder),
                            listener -> client.mkcol(target),
                            () -> {
                                toast(getString(R.string.folder_created));
                                refresh(false);
                            });
                })
                .show();
    }

    // ------------------------------------------------------------ 后台任务与进度

    private interface ProgressTask {
        void run(WebDavClient.ProgressListener listener) throws Exception;
    }

    private void runTask(String label, ProgressTask task, Runnable onSuccess) {
        if (busy) {
            toast(getString(R.string.busy));
            return;
        }
        setBusy(true, label);
        new Thread(() -> {
            try {
                task.run((done, total) -> ui.post(() -> updateProgress(done, total)));
                ui.post(() -> {
                    setBusy(false, null);
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    setBusy(false, null);
                    showErrorDialog(describeError(e));
                });
            }
        }, "webdav-task").start();
    }

    private void updateProgress(long done, long total) {
        progressPanel.setVisibility(View.VISIBLE);
        if (total > 0) {
            progressBar.setIndeterminate(false);
            progressBar.setMax(100);
            progressBar.setProgress((int) (done * 100 / total));
            progressText.setText(getString(R.string.progress_percent,
                    FileUtils.humanSize(done), FileUtils.humanSize(total),
                    (int) (done * 100 / total)));
        } else {
            progressBar.setIndeterminate(true);
            progressText.setText(FileUtils.humanSize(done));
        }
    }

    private void setBusy(boolean value, String label) {
        busy = value;
        progressPanel.setVisibility(value ? View.VISIBLE : View.GONE);
        if (value) {
            progressBar.setIndeterminate(true);
            progressText.setText(label == null ? "" : label);
        }
        buttonSettings.setEnabled(!value);
        buttonRefresh.setEnabled(!value);
        buttonApps.setEnabled(!value);
        buttonUpload.setEnabled(!value);
        buttonMkcol.setEnabled(!value);
    }

    // ------------------------------------------------------------ 提示与错误

    private void showEmpty(String message) {
        currentEntries = new ArrayList<>();
        adapter.submit(new ArrayList<>());
        statusText.setText(message);
        emptyText.setVisibility(View.VISIBLE);
        emptyText.setText(message);
    }

    private String describeError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            message = e.getClass().getSimpleName();
        }
        String lower = message.toLowerCase();
        if (lower.contains("trust anchor") || lower.contains("certpath")
                || lower.contains("certificate") || lower.contains("ssl")) {
            message = getString(R.string.ssl_error_hint) + "\n" + message;
        }
        if (lower.contains("failed to connect") || lower.contains("timeout")
                || lower.contains("unable to resolve")) {
            message = getString(R.string.network_error_hint) + "\n" + message;
        }
        return message;
    }

    private void showErrorDialog(String message) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.error_title)
                .setMessage(message)
                .setPositiveButton(R.string.confirm, null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 遥控器按键

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                int position = listView.getSelectedItemPosition();
                if (position == ListView.INVALID_POSITION && adapter.getCount() > 0) {
                    position = 0;
                }
                showActions(position);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (currentUrl != null && rootUrl != null && !currentUrl.equals(rootUrl)) {
            String parent = parentOf(currentUrl);
            if (parent != null) {
                refreshInto(parent);
                return;
            }
        }
        super.onBackPressed();
    }

    private String parentOf(String url) {
        String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        int slash = trimmed.lastIndexOf('/');
        if (slash < 0) {
            return null;
        }
        String parent = trimmed.substring(0, slash + 1);
        if (rootUrl != null && parent.length() < rootUrl.length()) {
            return rootUrl;
        }
        return parent;
    }
}
