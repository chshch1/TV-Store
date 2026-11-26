package com.example.tvwebdavbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.Manifest;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Environment;
import android.content.pm.PackageManager;
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
import android.widget.GridView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;

/**
 * 电视端主界面：浏览 WebDAV 目录，下载播放 / 安装 APK / 上传 / 删除 / 建目录。
 * 全部交互围绕遥控器方向键与确认键设计，不依赖触屏。
 */
public class MainActivity extends Activity {

    private static final int REQUEST_SETTINGS = 2001;
    private static final int REQUEST_PICK_FILE = 2002;
    private static final int REQUEST_PICK_APPS = 2003;
    private static final int REQUEST_STORAGE_PERMISSION = 2004;

    private static final int COLOR_BADGE_INFO = 0xFF4FC3F7;
    private static final int COLOR_BADGE_UPGRADE = 0xFFFFC857;
    private static final int COLOR_BADGE_OK = 0xFF7BD88F;
    private static final int COLOR_BADGE_MUTED = 0xFF9AA5B1;

    private GridView listView;
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
        requestStoragePermissionIfNeeded();

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

        hintText.setText(R.string.key_hint);
        if (!packageReceiverRegistered) {
            registerPackageReceiver();
        }
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
            final Map<String, Drawable> icons = new HashMap<>();
            try {
                for (InstalledApp app : InstalledAppRepository.cached(this)) {
                    String key = ApkFileNames.normalize(app.label);
                    if (app.icon != null && !icons.containsKey(key)) {
                        icons.put(key, app.icon);
                    }
                }
            } catch (Exception ignored) {
                // 取不到图标就退回通用图标
            }
            ui.post(() -> {
                appIcons.clear();
                appIcons.putAll(icons);
                if (!isFinishing() && !currentEntries.isEmpty()) {
                    renderRows();
                }
            });
        }, "app-index").start();
    }

    /** 安装/卸载完成后系统广播：让应用索引失效并重绘角标，不然列表还显示"未安装" */
    private final BroadcastReceiver packageChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (intent.getData() == null) {
                return;
            }
            String pkg = intent.getData().getSchemeSpecificPart();
            // 自己这个包被替换时也要重算
            InstalledAppRepository.invalidate();
            appIndexReady = false;
            warmUpAppIndex();
            if (Intent.ACTION_PACKAGE_ADDED.equals(action) && awaitingInstallResult) {
                awaitingInstallResult = false;
                ui.post(MainActivity.this::finishInstallAndMaybeCleanup);
            }
            if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                    || Intent.ACTION_PACKAGE_REMOVED.equals(action)
                    || Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                ui.post(() -> {
                    if (!isFinishing() && !currentEntries.isEmpty()) {
                        renderRows();
                    }
                });
            }
        }
    };

    private void registerPackageReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        registerReceiver(packageChangeReceiver, filter);
        packageReceiverRegistered = true;
    }

    private boolean packageReceiverRegistered;

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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (packageReceiverRegistered) {
            try {
                unregisterReceiver(packageChangeReceiver);
            } catch (Exception ignored) {
                // 忽略
            }
            packageReceiverRegistered = false;
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

    /** 应用名（归一化）→ 图标，来自已安装应用索引 */
    private final Map<String, Drawable> appIcons = new HashMap<>();
    /** 本地已下载过的包名（归一化）集合 */
    private final Set<String> downloadedKeys = new HashSet<>();
    private long downloadedScanAt;

    private void renderRows() {
        refreshDownloadedKeys();
        List<RowItem> rows = new ArrayList<>(currentEntries.size());
        for (RemoteEntry entry : currentEntries) {
            rows.add(toRow(entry));
        }
        adapter.submit(rows);
        pathText.setText(currentPath());
        statusText.setText(getString(R.string.status_connected, adapter.getCount()));
    }

    private RowItem toRow(RemoteEntry entry) {
        if (entry.directory || !ApkFileNames.isApk(entry.name)) {
            String meta = entry.directory ? getString(R.string.type_folder)
                    : joinMeta(FileUtils.humanSize(entry.size), FileUtils.formatTime(entry.lastModified));
            RowItem row = new RowItem(entry.name, meta, entry.directory, entry);
            return row;
        }

        // APK 行：文件名去掉版本号和 .apk，只显示应用名；副标题显示版本与大小
        String displayName = displayNameOf(entry.name);
        String version = versionOf(entry.name);
        String meta = version.isEmpty()
                ? FileUtils.humanSize(entry.size)
                : getString(R.string.row_version_size, version, FileUtils.humanSize(entry.size));

        ApkBadge badge = evaluateApk(entry.name);
        String badgeText = null;
        int badgeColor = 0;
        if (badge != null) {
            if (badge.app == null) {
                badgeText = getString(R.string.badge_not_installed);
                badgeColor = COLOR_BADGE_INFO;
            } else if (badge.color == COLOR_BADGE_UPGRADE) {
                badgeText = getString(R.string.badge_short_upgrade);
                badgeColor = COLOR_BADGE_UPGRADE;
            } else {
                badgeText = getString(R.string.badge_short_installed);
                badgeColor = COLOR_BADGE_OK;
            }
        }
        boolean downloaded = downloadedKeys.contains(ApkFileNames.normalize(displayName));
        RowItem row = new RowItem(displayName, meta, false, entry,
                badgeText, badgeColor, downloaded);
        row.icon = appIcons.get(ApkFileNames.normalize(displayName));
        return row;
    }

    /** 去掉 _v<版本> 与 .apk，只留应用名 */
    private static String displayNameOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String stem = ApkFileNames.isApk(fileName)
                ? fileName.substring(0, fileName.length() - 4) : fileName;
        int index = stem.toLowerCase(java.util.Locale.US).lastIndexOf("_v");
        if (index > 0) {
            String tail = stem.substring(index + 2);
            // 只有后面确实是版本号（以数字开头）才认为这是版本段
            if (!tail.isEmpty() && Character.isDigit(tail.charAt(0))) {
                stem = stem.substring(0, index);
            }
        }
        return stem;
    }

    private static String versionOf(String fileName) {
        String[] parsed = ApkFileNames.parse(fileName);
        return parsed == null ? "" : parsed[1];
    }

    /** 扫描本地已下载过的包：内部下载目录 + 公共 Download 目录 */
    private void refreshDownloadedKeys() {
        long now = System.currentTimeMillis();
        if (now - downloadedScanAt < 3000) {
            return;
        }
        downloadedScanAt = now;
        downloadedKeys.clear();
        collectDownloadedKeys(downloadDir());
        try {
            collectDownloadedKeys(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS));
        } catch (Throwable ignored) {
            // 外部存储不可用就算了
        }
    }

    private void collectDownloadedKeys(File dir) {
        if (dir == null) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file == null || !file.isFile() || !ApkFileNames.isApk(file.getName())) {
                continue;
            }
            String key = ApkFileNames.normalize(displayNameOf(file.getName()));
            if (!key.isEmpty()) {
                downloadedKeys.add(key);
            }
        }
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

    /**
     * Android 6 起读写外部存储需要运行时授权；未授权时浏览 /sdcard、回退到外部目录都会失败。
     * 下载本身已经改为优先走内部存储，这里只是让"本机文件上传"能选到外置存储的内容。
     */
    private void requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        String[] permissions = {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(permissions, REQUEST_STORAGE_PERMISSION);
                return;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 拒绝也不影响主流程：下载走内部存储，仍然可用
    }

    private File downloadDir() {
        // 优先用内部存储：/data/data/<pkg>/files 一定可写，不受外部存储挂载状态、
        // ROM 权限限制或 sdcardfs bug 影响（部分 Android 6 电视盒子对
        // /storage/emulated/0/Android/data/<pkg> 会返回 EACCES）。
        File internal = new File(getFilesDir(), "downloads");
        if (ensureWritableDir(internal)) {
            return internal;
        }
        File external = getExternalFilesDir("downloads");
        if (ensureWritableDir(external)) {
            return external;
        }
        // 两者都不可写时仍返回内部路径，让上层明确报错而不是静默失败
        return internal;
    }

    private static boolean ensureWritableDir(File dir) {
        if (dir == null) {
            return false;
        }
        if (dir.isDirectory()) {
            return dir.canWrite();
        }
        //noinspection ResultOfMethodCallIgnored
        return dir.mkdirs() && dir.canWrite();
    }

    private void downloadAnd(final RemoteEntry entry, final boolean openAfter) {
        File dir = downloadDir();
        if (!ensureWritableDir(dir)) {
            toast(getString(R.string.storage_unavailable));
            return;
        }
        final File destination = FileUtils.uniqueFile(dir, entry.name);
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
                installApk(file);
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

    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String ACTION_INSTALL_RESULT = "com.example.tvwebdavbox.INSTALL_RESULT";

    /**
     * 调起安装。安卓 6 的坑在于：系统安装器只声明了 file 方案的 intent-filter，
     * 而我们的文件在内部私有目录（file:// 安装器读不到 → "解析软件包时出现问题"），
     * content:// 又匹配不到 filter（→ 没有可以打开该文件的应用）。
     * 所以按"最不依赖外部权限"的顺序依次尝试。
     */
    private void installApk(File file) {
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

        pendingInstallFile = file;
        sharedCopyFile = null;
        awaitingInstallResult = true;
        installCleanupHandled = false;

        String restriction = detectInstallRestriction();
        if (restriction != null) {
            showInstallRestrictedDialog(restriction);
            return;
        }

        // Android 6/7 的盒子：会话安装常被 ROM 判定为"用户限制"
        // （INSTALL_FAILED_USER_RESTRICTED / INSTALL_CANCELED_BY_USER），
        // 这几代系统最认的是"公共目录 + file:// → 原生安装确认框"，所以先走老路。
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
            if (installFromSharedCopy(file)) {
                return;
            }
            if (installBySession(file)) {
                return;
            }
            if (installVisible(file)) {
                return;
            }
            toast(getString(R.string.install_no_handler, file.getAbsolutePath()));
            return;
        }

        // Android 8+：会话安装最稳
        if (installBySession(file)) {
            return;
        }
        if (installVisible(file)) {
            return;
        }

        toast(getString(R.string.install_no_handler, file.getAbsolutePath()));
    }

    /** 显式组件 → 隐式 content Intent → 复制到公共目录走 file:// */
    private boolean installVisible(File file) {
        pendingInstallFile = file;
        if (installExplicit(file)) {
            return true;
        }
        Uri contentUri = LocalFileProvider.uriFor(this, file);
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        install.setDataAndType(contentUri, APK_MIME);
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(install)) {
            return true;
        }
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(contentUri, APK_MIME);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(view)) {
            return true;
        }
        return installFromSharedCopy(file);
    }

    private File pendingInstallFile;
    /** 为安装而复制到公共目录的副本，安装成功后可以一起清理。 */
    private File sharedCopyFile;
    private boolean awaitingInstallResult;
    private boolean installCleanupHandled;

    /** 预先探测系统的安装限制，能直接给出原因而不是等安装器报错 */
    private String detectInstallRestriction() {
        try {
            android.os.UserManager um =
                    (android.os.UserManager) getSystemService(USER_SERVICE);
            if (um != null) {
                if (um.hasUserRestriction(android.os.UserManager.DISALLOW_INSTALL_APPS)) {
                    return "系统策略禁止安装应用（DISALLOW_INSTALL_APPS）";
                }
                if (um.hasUserRestriction(android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)) {
                    return "未知来源安装被系统策略关闭（DISALLOW_INSTALL_UNKNOWN_SOURCES）";
                }
            }
            int nonMarket = android.provider.Settings.Global.getInt(
                    getContentResolver(), "install_non_market_apps", -1);
            if (nonMarket == 0) {
                return "系统设置里的「未知来源」开关仍是关闭状态";
            }
        } catch (Throwable ignored) {
            // 探测失败就不拦
        }
        return null;
    }

    /** 安装被系统限制时：给出可操作的解释，并提供「设置 / 重试」 */
    private void showInstallRestrictedDialog(final String detail) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.install_restricted_title)
                .setMessage(getString(R.string.install_restricted_message, detail))
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.retry, (dialog, which) -> {
                    if (pendingInstallFile != null) {
                        if (!installVisible(pendingInstallFile)) {
                            toast(getString(R.string.install_no_handler,
                                    pendingInstallFile.getAbsolutePath()));
                        }
                    }
                })
                .setPositiveButton(R.string.settings, (dialog, which) -> openInstallSettings())
                .show();
    }

    private void openInstallSettings() {
        Intent[] candidates = {
                new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES"),
                new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS),
                new Intent(android.provider.Settings.ACTION_SETTINGS)
        };
        for (Intent intent : candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (tryStart(intent)) {
                return;
            }
        }
        toast(getString(R.string.install_no_handler, getString(R.string.install_restricted_title)));
    }

    private boolean installBySession(File file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        int sessionId = -1;
        try {
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);

            InputStream input = new FileInputStream(file);
            OutputStream output = session.openWrite("base.apk", 0, file.length());
            try {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
                session.fsync(output);
            } finally {
                try {
                    output.close();
                } catch (IOException ignored) {
                    // 忽略
                }
                input.close();
            }

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                    if (status == PackageInstaller.STATUS_SUCCESS) {
                        toast(getString(R.string.install_started));
                        finishInstallAndMaybeCleanup();
                    } else {
                        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                        if (TextUtils.isEmpty(message)) {
                            message = "status=" + status;
                        }
                        if (pendingInstallFile != null && installVisible(pendingInstallFile)) {
                            return;
                        }
                        showInstallRestrictedDialog(message);
                    }
                    try {
                        unregisterReceiver(this);
                    } catch (Exception ignored) {
                        // 忽略
                    }
                }
            };
            registerReceiver(receiver, new IntentFilter(ACTION_INSTALL_RESULT));

            Intent result = new Intent(ACTION_INSTALL_RESULT).setPackage(getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(this, sessionId, result,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pending.getIntentSender());
            session.close();
            return true;
        } catch (Throwable t) {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
            if (sessionId != -1) {
                try {
                    installer.abandonSession(sessionId);
                } catch (Exception ignored) {
                    // 忽略
                }
            }
            return false;
        }
    }

    private boolean installExplicit(File file) {
        Uri uri = LocalFileProvider.uriFor(this, file);
        String[][] candidates = {
                {"com.android.packageinstaller", "com.android.packageinstaller.PackageInstallerActivity"},
                {"com.google.android.packageinstaller", "com.android.packageinstaller.PackageInstallerActivity"},
                {"com.android.packageinstaller", "com.android.packageinstaller.InstallAppProgress"},
                {"com.lenovo.packageinstaller", "com.lenovo.packageinstaller.PackageInstallerActivity"}
        };
        for (String[] candidate : candidates) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setClassName(candidate[0], candidate[1]);
            intent.setDataAndType(uri, APK_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            if (tryStart(intent)) {
                return true;
            }
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            install.setClassName(candidate[0], candidate[1]);
            install.setDataAndType(uri, APK_MIME);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            if (tryStart(install)) {
                return true;
            }
        }
        return false;
    }

    private boolean installFromSharedCopy(File file) {
        try {
            File publicDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (publicDir == null || (!publicDir.exists() && !publicDir.mkdirs())) {
                return false;
            }
            File copy = FileUtils.uniqueFile(publicDir, file.getName());
            if (copy == null) {
                return false;
            }
            FileUtils.copyFile(file, copy);
            if (!copy.exists() || copy.length() != file.length()) {
                return false;
            }
            sharedCopyFile = copy;
            toast(getString(R.string.install_shared_copy_hint, copy.getAbsolutePath()));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(copy), APK_MIME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return tryStart(intent);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 安装流程结束：按设置自动清理安装包，或弹窗询问 */
    private void finishInstallAndMaybeCleanup() {
        if (installCleanupHandled) {
            return;
        }
        installCleanupHandled = true;
        awaitingInstallResult = false;
        if (isFinishing()) {
            return;
        }
        String detail = describeInstallFiles();
        if (PreferenceStore.isAutoCleanAfterInstall(this)) {
            deletePendingApks();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.install_clean_title)
                .setMessage(getString(R.string.install_clean_message, detail))
                .setNegativeButton(R.string.install_clean_keep, (dialog, which) -> {
                    pendingInstallFile = null;
                    sharedCopyFile = null;
                })
                .setPositiveButton(R.string.install_clean_delete, (dialog, which) -> deletePendingApks())
                .show();
    }

    private String describeInstallFiles() {
        StringBuilder builder = new StringBuilder();
        if (pendingInstallFile != null && pendingInstallFile.exists()) {
            builder.append(pendingInstallFile.getAbsolutePath());
        }
        if (sharedCopyFile != null && sharedCopyFile.exists()
                && (pendingInstallFile == null
                || !sharedCopyFile.getAbsolutePath().equals(pendingInstallFile.getAbsolutePath()))) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(sharedCopyFile.getAbsolutePath());
        }
        return builder.length() == 0 ? getString(R.string.install_clean_none) : builder.toString();
    }

    private void deletePendingApks() {
        int deleted = 0;
        File download = pendingInstallFile;
        File copy = sharedCopyFile;
        if (download != null && download.exists() && download.delete()) {
            deleted++;
        }
        if (copy != null && copy.exists()
                && (download == null || !copy.getAbsolutePath().equals(download.getAbsolutePath()))
                && copy.delete()) {
            deleted++;
        }
        pendingInstallFile = null;
        sharedCopyFile = null;
        toast(getString(R.string.install_clean_done_fmt, deleted));
        // 本地下载目录可能被清空，重绘一次列表即可
        InstalledAppRepository.invalidate();
    }

    private boolean tryStart(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (Throwable t) {
            // ActivityNotFoundException / FileUriExposedException / SecurityException
            return false;
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
            List<String> uploadedNames = new ArrayList<>();
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
                    uploadedNames.add(name);
                } catch (Exception e) {
                    failures.add(name + "：" + describeErrorText(e));
                }
            }
            final int uploaded = success;
            final int cleaned = removeOlderBackups(dirUrl, uploadedNames);
            ui.post(() -> {
                setBusy(false, null);
                refresh(false);
                if (failures.isEmpty()) {
                    toast(cleaned > 0
                            ? getString(R.string.upload_batch_done_cleaned, uploaded, cleaned)
                            : getString(R.string.upload_batch_done, uploaded));
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

    /**
     * 上传新版本后删除云端同目录里同一应用的旧备份，
     * 否则新旧两份并存，列表每次都会提示"可升级"。
     * 只删版本比新上传低的同名备份；版本更高或无法比较的一律保留。
     */
    private int removeOlderBackups(String dirUrl, List<String> uploadedNames) {
        if (dirUrl == null || uploadedNames == null || uploadedNames.isEmpty()) {
            return 0;
        }
        // 被上传的应用键 → 新版本号
        Map<String, String> newVersions = new HashMap<>();
        for (String name : uploadedNames) {
            String[] parsed = ApkFileNames.parse(name);
            if (parsed != null) {
                newVersions.put(ApkFileNames.normalize(parsed[0]), parsed[1]);
            }
        }
        if (newVersions.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        try {
            List<RemoteEntry> entries = client.list(dirUrl);
            for (RemoteEntry entry : entries) {
                if (entry == null || entry.directory) {
                    continue;
                }
                String[] parsed = ApkFileNames.parse(entry.name);
                if (parsed == null) {
                    continue;
                }
                String newVersion = newVersions.get(ApkFileNames.normalize(parsed[0]));
                if (newVersion == null) {
                    continue;
                }
                // compare(旧版本, 新版本) == REMOTE_NEWER → 新版本更高，可以删旧的
                if (VersionComparator.compare(parsed[1], newVersion)
                        != VersionComparator.Result.REMOTE_NEWER) {
                    continue;
                }
                try {
                    client.delete(entry.url);
                    deleted++;
                } catch (Exception ignored) {
                    // 单个旧文件删不掉不影响整体
                }
            }
        } catch (Exception ignored) {
            // 列目录失败就跳过清理
        }
        return deleted;
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
        final int[] removed = {0};
        runTask(getString(R.string.uploading, source.getName()),
                listener -> {
                    client.upload(source, dirUrl, source.getName(), listener);
                    removed[0] = removeOlderBackups(
                            dirUrl, Collections.singletonList(source.getName()));
                },
                () -> {
                    if (removed[0] > 0) {
                        toast(getString(R.string.upload_done_cleaned, removed[0]));
                    } else {
                        toast(getString(R.string.upload_done));
                    }
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
                if (position == GridView.INVALID_POSITION && adapter.getCount() > 0) {
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
