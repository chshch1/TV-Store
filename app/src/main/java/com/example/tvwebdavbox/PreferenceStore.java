package com.example.tvwebdavbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * WebDAV 连接配置的本地存储。
 * 只保存 WebDAV 相关字段，不做任何多余状态持久化。
 */
public final class PreferenceStore {

    private static final String FILE = "webdav_prefs";

    private static final String KEY_SERVER = "server_url";
    private static final String KEY_USER = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_ROOT = "remote_root";
    private static final String KEY_INSECURE = "insecure_tls";
    /** 默认配置只植入一次，之后完全以用户保存的为准。 */
    private static final String KEY_SEEDED = "defaults_seeded";

    private PreferenceStore() {
    }

    public static SharedPreferences prefs(Context context) {
        SharedPreferences prefs =
                context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        seedDefaults(prefs);
        return prefs;
    }

    /**
     * 首次运行时把编译期写入的默认 WebDAV 配置落盘一次。
     * 只在键不存在时写入，所以用户之后改过的配置不会被覆盖；
     * 只有 clear() 重来一次才会再次植入。
     */
    private static void seedDefaults(SharedPreferences prefs) {
        if (prefs.getBoolean(KEY_SEEDED, false)) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_SEEDED, true);
        if (!prefs.contains(KEY_SERVER)) {
            editor.putString(KEY_SERVER, BuildConfig.DAV_URL);
        }
        if (!prefs.contains(KEY_USER)) {
            editor.putString(KEY_USER, BuildConfig.DAV_USER);
        }
        if (!prefs.contains(KEY_PASSWORD)) {
            editor.putString(KEY_PASSWORD, BuildConfig.DAV_PASSWORD);
        }
        if (!prefs.contains(KEY_ROOT)) {
            editor.putString(KEY_ROOT, BuildConfig.DAV_ROOT);
        }
        if (!prefs.contains(KEY_INSECURE)) {
            editor.putBoolean(KEY_INSECURE, BuildConfig.DAV_INSECURE);
        }
        editor.apply();
    }

    public static String getServerUrl(Context c) {
        return prefs(c).getString(KEY_SERVER, "");
    }

    public static String getUsername(Context c) {
        return prefs(c).getString(KEY_USER, "");
    }

    public static String getPassword(Context c) {
        return prefs(c).getString(KEY_PASSWORD, "");
    }

    public static String getRemoteRoot(Context c) {
        String root = prefs(c).getString(KEY_ROOT, "/");
        if (TextUtils.isEmpty(root)) {
            root = "/";
        }
        if (!root.startsWith("/")) {
            root = "/" + root;
        }
        return root;
    }

    public static boolean isInsecureTls(Context c) {
        return prefs(c).getBoolean(KEY_INSECURE, false);
    }

    public static void save(Context c, String serverUrl, String username, String password,
                           String remoteRoot, boolean insecureTls) {
        prefs(c).edit()
                .putString(KEY_SERVER, trim(serverUrl))
                .putString(KEY_USER, trim(username))
                .putString(KEY_PASSWORD, password == null ? "" : password)
                .putString(KEY_ROOT, normalizeRoot(remoteRoot))
                .putBoolean(KEY_INSECURE, insecureTls)
                .apply();
    }

    public static void clear(Context c) {
        prefs(c).edit().clear().apply();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    static String normalizeRoot(String root) {
        if (root == null) {
            return "/";
        }
        String trimmed = root.trim();
        if (trimmed.isEmpty()) {
            return "/";
        }
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        if (!trimmed.endsWith("/")) {
            trimmed = trimmed + "/";
        }
        // 收敛重复斜杠
        while (trimmed.contains("//")) {
            trimmed = trimmed.replace("//", "/");
        }
        return trimmed;
    }

    /** 由存储的字段拼出一个可用的 WebDavConfig。 */
    public static WebDavConfig loadConfig(Context c) {
        return new WebDavConfig(getServerUrl(c), getUsername(c), getPassword(c),
                getRemoteRoot(c), isInsecureTls(c));
    }
}
