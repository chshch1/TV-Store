package com.example.tvwebdavbox;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * APK 备份文件命名规则（与 PushTV 保持一致，可直接复用其已有备份）：
 *   <应用名>_v<版本号>.apk
 * 例如 "微信_v8.0.42.apk"、"Google_Play_v24.1.apk"。
 * 应用名中的非法文件名字符与空白统一替换为下划线。
 */
public final class ApkFileNames {

    private static final Pattern INVALID_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\s]");
    private static final Pattern BACKUP_NAME = Pattern.compile("^(.*)_v(.+)\\.apk$", Pattern.CASE_INSENSITIVE);

    private ApkFileNames() {
    }

    public static boolean isApk(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.US).endsWith(".apk");
    }

    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return INVALID_CHARS.matcher(value.trim()).replaceAll("_");
    }

    /** 生成远端备份文件名。 */
    public static String build(String appName, String versionName) {
        String cleanName = sanitize(appName);
        String cleanVersion = sanitize(versionName);
        if (cleanName.isEmpty()) {
            cleanName = "app";
        }
        return cleanVersion.isEmpty() ? cleanName + ".apk" : cleanName + "_v" + cleanVersion + ".apk";
    }

    /**
     * 从文件名解析出 应用名 + 版本号，返回长度为 2 的数组；无法解析时返回 null。
     */
    public static String[] parse(String fileName) {
        if (!isApk(fileName)) {
            return null;
        }
        Matcher matcher = BACKUP_NAME.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        String name = matcher.group(1).replace("_", " ");
        String version = matcher.group(2);
        return new String[]{name, version};
    }

    /** 归一化用于匹配的应用名键：去掉版本段、下划线转空格、小写。 */
    public static String appKey(String fileName) {
        if (!isApk(fileName)) {
            return null;
        }
        String stem = fileName.substring(0, fileName.length() - 4);
        int index = stem.toLowerCase(Locale.US).lastIndexOf("_v");
        if (index <= 0) {
            return null;
        }
        String name = stem.substring(0, index).replace("_", " ").trim();
        return name.isEmpty() ? null : name.toLowerCase(Locale.US);
    }

    /** 仅用于比较的键：把任意字符串归一化成可匹配的形式。 */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('_', ' ').trim().toLowerCase(Locale.US);
    }
}
