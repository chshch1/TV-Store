package com.example.tvwebdavbox;

import android.text.TextUtils;

/** WebDAV 连接参数。 */
public final class WebDavConfig {

    public final String serverUrl;
    public final String username;
    public final String password;
    public final String remoteRoot;
    public final boolean insecureTls;

    public WebDavConfig(String serverUrl, String username, String password,
                        String remoteRoot, boolean insecureTls) {
        this.serverUrl = serverUrl == null ? "" : serverUrl.trim();
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        this.remoteRoot = PreferenceStore.normalizeRoot(remoteRoot);
        this.insecureTls = insecureTls;
    }

    public boolean isConfigured() {
        return !TextUtils.isEmpty(serverUrl);
    }

    public boolean hasCredentials() {
        return !TextUtils.isEmpty(username) || !TextUtils.isEmpty(password);
    }

    /** 规范化后的服务器根地址，不带尾部斜杠。 */
    public String baseUrl() {
        String url = serverUrl.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
