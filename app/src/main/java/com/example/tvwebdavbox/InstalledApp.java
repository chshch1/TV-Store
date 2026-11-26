package com.example.tvwebdavbox;

import android.graphics.drawable.Drawable;

/** 一条本机已安装应用信息（仅用于备份上传与版本比较）。 */
public final class InstalledApp {

    public final String label;
    public final String packageName;
    public final String versionName;
    public final long versionCode;
    public final String sourceDir;
    public final long size;
    public final boolean systemApp;
    public Drawable icon;

    public InstalledApp(String label, String packageName, String versionName, long versionCode,
                        String sourceDir, long size, boolean systemApp) {
        this.label = label;
        this.packageName = packageName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.sourceDir = sourceDir;
        this.size = size;
        this.systemApp = systemApp;
    }

    /** 上传时使用的远端文件名。 */
    public String remoteFileName() {
        return ApkFileNames.build(label, versionName);
    }

    public String metaText() {
        StringBuilder sb = new StringBuilder();
        sb.append('v').append(versionName == null || versionName.isEmpty() ? "?" : versionName);
        String sizeText = FileUtils.humanSize(size);
        if (!sizeText.isEmpty()) {
            sb.append("  ·  ").append(sizeText);
        }
        if (systemApp) {
            sb.append("  ·  系统");
        }
        sb.append("  ·  ").append(packageName);
        return sb.toString();
    }
}
