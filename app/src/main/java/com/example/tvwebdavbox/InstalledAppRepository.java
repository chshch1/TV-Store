package com.example.tvwebdavbox;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 已安装应用索引：既用于「批量上传应用」，也用于远端 APK 的版本比较。
 * 读取 PackageManager 较慢，结果做进程内缓存；进入应用列表页时可手动失效。
 */
public final class InstalledAppRepository {

    private static final Object LOCK = new Object();

    private static volatile List<InstalledApp> cache;
    private static volatile Map<String, InstalledApp> keyIndex;

    /** 诊断信息：上次读取时 PackageManager 返回的条目数与异常描述，用于界面提示。 */
    private static volatile int lastRawCount;
    private static volatile String lastError = "";

    private InstalledAppRepository() {
    }

    public static int getLastRawCount() {
        return lastRawCount;
    }

    public static String getLastError() {
        return lastError;
    }

    public static void invalidate() {
        cache = null;
        keyIndex = null;
    }

    public static List<InstalledApp> cached(Context context) {
        List<InstalledApp> current = cache;
        if (current == null) {
            synchronized (LOCK) {
                if (cache == null) {
                    List<InstalledApp> loaded = load(context);
                    cache = loaded;
                }
                current = cache;
            }
        }
        return current;
    }

    /** 阻塞读取，请放在后台线程调用。 */
    public static List<InstalledApp> load(Context context) {
        Context app = context.getApplicationContext();
        PackageManager pm = app.getPackageManager();
        List<InstalledApp> result = new ArrayList<>();
        Map<String, InstalledApp> index = new HashMap<>();
        StringBuilder error = new StringBuilder();

        List<ApplicationInfo> applications = queryApplications(pm, error);
        lastRawCount = applications.size();

        for (ApplicationInfo info : applications) {
            // 单个应用读取异常不应拖垮整个列表（部分老 ROM 的包信息会抛异常）
            try {
                InstalledApp item = toInstalledApp(app, pm, info);
                if (item != null) {
                    result.add(item);
                    index.put(ApkFileNames.normalize(ApkFileNames.sanitize(item.label)), item);
                    index.put(ApkFileNames.normalize(item.label), item);
                    index.put(ApkFileNames.normalize(item.packageName), item);
                }
            } catch (Throwable t) {
                // 忽略这一个应用，继续读取其余应用
            }
        }
        lastError = error.toString();

        try {
            final Collator collator = Collator.getInstance(Locale.CHINA);
            Collections.sort(result, (a, b) -> collator.compare(a.label, b.label));
        } catch (Throwable ignored) {
            // 排序失败时保留 PackageManager 的原始顺序
        }

        synchronized (LOCK) {
            keyIndex = index;
        }
        return result;
    }

    private static List<ApplicationInfo> queryApplications(PackageManager pm, StringBuilder error) {
        List<ApplicationInfo> applications = new ArrayList<>();
        try {
            List<ApplicationInfo> list = pm.getInstalledApplications(0);
            if (list != null && !list.isEmpty()) {
                return list;
            }
            error.append("getInstalledApplications 返回 0 项");
        } catch (Throwable t) {
            error.append("getInstalledApplications 失败：").append(t);
        }
        // 兜底：改按 PackageInfo 读取
        try {
            List<PackageInfo> packages = pm.getInstalledPackages(0);
            if (packages != null) {
                for (PackageInfo pkg : packages) {
                    if (pkg != null && pkg.applicationInfo != null) {
                        applications.add(pkg.applicationInfo);
                    }
                }
            }
            if (applications.isEmpty()) {
                error.append("；getInstalledPackages 也返回 0 项");
            }
        } catch (Throwable t) {
            error.append("；getInstalledPackages 失败：").append(t);
        }
        return applications;
    }

    private static InstalledApp toInstalledApp(Context app, PackageManager pm, ApplicationInfo info) {
        if (info == null || info.packageName == null) {
            return null;
        }
        if (app.getPackageName().equals(info.packageName)) {
            return null;
        }
        // 某些老 ROM 只填了 publicSourceDir，两者都取不到才跳过
        String sourceDir = info.sourceDir;
        if (sourceDir == null || sourceDir.isEmpty()) {
            sourceDir = info.publicSourceDir;
        }
        if (sourceDir == null || sourceDir.isEmpty()) {
            return null;
        }

        String label;
        try {
            CharSequence value = pm.getApplicationLabel(info);
            label = value == null ? "" : value.toString().trim();
        } catch (Throwable t) {
            label = info.packageName;
        }
        if (label.isEmpty()) {
            label = info.packageName;
        }

        String versionName = "";
        long versionCode = 0;
        try {
            PackageInfo packageInfo = pm.getPackageInfo(info.packageName, 0);
            versionName = packageInfo.versionName == null ? "" : packageInfo.versionName;
            versionCode = readVersionCode(packageInfo);
        } catch (Throwable ignored) {
            // 个别应用读不到版本信息，仍允许备份
        }

        Drawable icon = null;
        try {
            icon = pm.getApplicationIcon(info);
        } catch (Throwable ignored) {
            // 图标缺失不影响上传
        }

        boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;

        long size = 0;
        try {
            size = new File(sourceDir).length();
        } catch (Throwable ignored) {
            // 取不到大小时按 0 显示
        }

        InstalledApp item = new InstalledApp(label, info.packageName, versionName, versionCode,
                sourceDir, size, system);
        item.icon = icon;
        return item;
    }

    @SuppressWarnings("deprecation")
    private static long readVersionCode(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.getLongVersionCode();
        }
        return info.versionCode;
    }

    public static boolean isReady() {
        return keyIndex != null;
    }

    /**
     * 按备份文件名反查本机应用。只查内存索引，索引未就绪时返回 null，
     * 绝不在调用线程（通常是 UI 线程）里读 PackageManager。
     */
    public static InstalledApp findByBackupFile(String fileName) {
        String key = ApkFileNames.appKey(fileName);
        if (key == null) {
            String[] parsed = ApkFileNames.parse(fileName);
            key = parsed == null ? null : ApkFileNames.normalize(parsed[0]);
        }
        if (key == null) {
            return null;
        }
        Map<String, InstalledApp> index = keyIndex;
        return index == null ? null : index.get(key);
    }
}
