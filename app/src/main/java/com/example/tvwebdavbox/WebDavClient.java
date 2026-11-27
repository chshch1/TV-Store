package com.example.tvwebdavbox;

import android.net.Uri;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * 极简 WebDAV 客户端，只实现本应用需要的五个方法：
 * PROPFIND（列目录）、MKCOL（建目录）、PUT（上传）、GET（下载）、DELETE（删除）。
 */
public final class WebDavClient {

    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    private static final String TAG = "WebDavClient";

    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<propfind xmlns=\"DAV:\"><prop>"
                    + "<displayname/><getcontentlength/><getlastmodified/><resourcetype/>"
                    + "</prop></propfind>";

    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");

    /** 同一类 TLS 策略共用一个连接池。 */
    private static volatile OkHttpClient plainClient;
    private static volatile OkHttpClient insecureClient;

    private final WebDavConfig config;

    public WebDavClient(WebDavConfig config) {
        this.config = config;
    }

    public WebDavConfig getConfig() {
        return config;
    }

    // ---------------------------------------------------------------- 公共工具

    static String encodeSegment(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String withSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    /** 服务器 origin，例如 https://nas.example.com:5006 */
    private String origin() {
        Uri uri = Uri.parse(config.baseUrl());
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return config.baseUrl();
        }
        int port = uri.getPort();
        return scheme + "://" + host + (port > 0 ? ":" + port : "");
    }

    /** 配置的远端根目录完整 URL，一定以 / 结尾。 */
    public String rootUrl() {
        String base = config.baseUrl();
        String root = config.remoteRoot;
        if ("/".equals(root)) {
            return withSlash(base);
        }
        StringBuilder sb = new StringBuilder(base);
        for (String part : root.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            sb.append('/').append(encodeSegment(part));
        }
        return withSlash(sb.toString());
    }

    public static String joinUrl(String dirUrl, String name) {
        return withSlash(dirUrl) + encodeSegment(name);
    }

    /** 取 URL 的路径部分（已还原 percent 编码、去掉末尾斜杠），用于识别目录自身那条 PROPFIND 记录。 */
    private static String pathOf(String url) {
        int schemeIdx = url.indexOf("://");
        int pathStart = url.indexOf('/', schemeIdx > 0 ? schemeIdx + 3 : 0);
        if (pathStart < 0) {
            return "";
        }
        String path = url.substring(pathStart);
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        String decoded = decode(path);
        // 部分老 NAS 用 GBK 做 percent 编码，UTF-8 解码会得到替换字符，这里再试一次 GBK
        if (Mojibake.hasReplacementChar(decoded)) {
            String gbk = Mojibake.decodePercentAsGbk(path);
            if (!Mojibake.hasReplacementChar(gbk) && Mojibake.hasCjk(gbk)) {
                decoded = gbk;
            }
        }
        decoded = Mojibake.repair(decoded);
        while (decoded.length() > 1 && decoded.endsWith("/")) {
            decoded = decoded.substring(0, decoded.length() - 1);
        }
        return decoded;
    }

    private String absolute(String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        return origin() + (href.startsWith("/") ? href : "/" + href);
    }

    // ---------------------------------------------------------------- HTTP 通道

    private OkHttpClient http() {
        if (config.insecureTls) {
            if (insecureClient == null) {
                synchronized (WebDavClient.class) {
                    if (insecureClient == null) {
                        insecureClient = newClient(true);
                    }
                }
            }
            return insecureClient;
        }
        if (plainClient == null) {
            synchronized (WebDavClient.class) {
                if (plainClient == null) {
                    plainClient = newClient(false);
                }
            }
        }
        return plainClient;
    }

    private static OkHttpClient newClient(boolean insecure) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true);

        if (insecure) {
            try {
                final TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }};
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, trustAll, new java.security.SecureRandom());
                SSLSocketFactory factory = ctx.getSocketFactory();
                builder.sslSocketFactory(factory);
                builder.hostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, SSLSession session) {
                        return true;
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "无法启用自签名证书信任", e);
            }
        }
        return builder.build();
    }

    private Request.Builder baseRequest(String url) {
        Request.Builder builder = new Request.Builder().url(url);
        if (config.hasCredentials()) {
            builder.header("Authorization",
                    Credentials.basic(config.username, config.password, java.nio.charset.Charset.forName("UTF-8")));
        }
        return builder;
    }

    /** 带业务语义的失败。 */
    public static final class WebDavException extends IOException {
        public final int code;

        WebDavException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static String describe(int code) {
        if (code == 401 || code == 403) {
            return "认证失败或没有权限（HTTP " + code + "），请检查用户名与密码";
        }
        if (code == 404) {
            return "远端路径不存在（HTTP 404）";
        }
        if (code == 405) {
            return "服务器不允许该操作（HTTP 405）";
        }
        if (code == 409) {
            return "目标父目录不存在（HTTP 409）";
        }
        if (code == 423) {
            return "远端文件被锁定（HTTP 423）";
        }
        if (code == 507) {
            return "远端存储空间不足（HTTP 507）";
        }
        return "请求失败（HTTP " + code + "）";
    }

    // ---------------------------------------------------------------- 目录列举

    /** PROPFIND Depth:1 列出目录内容（不含目录自身），目录排在前面。 */
    public List<RemoteEntry> list(String dirUrl) throws IOException {
        String target = withSlash(dirUrl);
        Request request = baseRequest(target)
                .method("PROPFIND", RequestBody.create(XML, PROPFIND_BODY))
                .header("Depth", "1")
                .build();

        try (Response response = http().newCall(request).execute()) {
            int code = response.code();
            if (code != 207 && (code < 200 || code > 299)) {
                throw new WebDavException(code, describe(code));
            }
            byte[] raw = response.body() == null ? new byte[0] : response.body().bytes();
            String body = decodeXmlBody(raw, response.header("Content-Type"));
            List<RemoteEntry> entries = parsePropfind(body, target);
            sort(entries);
            return entries;
        }
    }

    /**
     * 按响应头 / XML 声明决定编码，避免中文目录名变成乱码。
     * 顺序：Content-Type 的 charset → XML 头里的 encoding → UTF-8。
     */
    private static String decodeXmlBody(byte[] raw, String contentType) {
        Charset charset = null;
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.US);
            int index = lower.indexOf("charset=");
            if (index >= 0) {
                String name = contentType.substring(index + "charset=".length()).trim();
                int semicolon = name.indexOf(';');
                if (semicolon >= 0) {
                    name = name.substring(0, semicolon);
                }
                name = name.replace("\"", "").replace("'", "").trim();
                try {
                    charset = Charset.forName(name);
                } catch (Exception ignored) {
                    // 无法识别的 charset，继续走 XML 声明
                }
            }
        }
        if (charset == null) {
            charset = sniffXmlEncoding(raw);
        }
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }
        return new String(raw, charset);
    }

    private static final Pattern XML_ENCODING =
            Pattern.compile("encoding\\s*=\\s*[\"']([A-Za-z0-9_\\-]+)[\"']");

    private static Charset sniffXmlEncoding(byte[] raw) {
        int limit = Math.min(raw.length, 256);
        StringBuilder head = new StringBuilder(limit);
        for (int i = 0; i < limit; i++) {
            head.append((char) raw[i]);
        }
        Matcher matcher = XML_ENCODING.matcher(head);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private List<RemoteEntry> parsePropfind(String xml, String dirUrl) {
        List<RemoteEntry> result = new ArrayList<>();
        if (xml == null || xml.trim().isEmpty()) {
            return result;
        }
        String selfPath = pathOf(dirUrl);
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new StringReader(xml));

            RemoteEntry current = null;
            String tag;
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    tag = localName(parser.getName());
                    if ("response".equals(tag)) {
                        current = new RemoteEntry();
                    } else if (current != null) {
                        if ("href".equals(tag)) {
                            current.url = parser.nextText().trim();
                        } else if ("displayname".equals(tag)) {
                            current.displayName = parser.nextText().trim();
                        } else if ("getcontentlength".equals(tag)) {
                            current.size = parseLong(parser.nextText().trim());
                        } else if ("getlastmodified".equals(tag)) {
                            current.lastModified = parseHttpDate(parser.nextText().trim());
                        } else if ("collection".equals(tag)) {
                            current.directory = true;
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    tag = localName(parser.getName());
                    if ("response".equals(tag) && current != null) {
                        finishEntry(current, selfPath);
                        if (!current.url.isEmpty()) {
                            result.add(current);
                        }
                        current = null;
                    }
                }
                event = parser.next();
            }
        } catch (Exception e) {
            Log.w(TAG, "PROPFIND 解析失败", e);
        }
        return result;
    }

    /**
     * 文件名以 href 为准（与 PushTV 一致）：href 的 percent 编码是唯一可靠的中文来源。
     * 部分服务器会把 displayname 原样输出成 percent 编码或错误编码，只能作为兜底。
     */
    private void finishEntry(RemoteEntry entry, String selfPath) {
        String rawHref = entry.url;
        if (rawHref.isEmpty()) {
            return;
        }
        String decodedPath = pathOf(rawHref);
        // 目录自身那条记录要跳过
        if (decodedPath.equalsIgnoreCase(selfPath)) {
            entry.url = "";
            return;
        }

        String hrefName = Mojibake.repair(Mojibake.decodePercent(lastSegment(decodedPath)));
        String displayName = Mojibake.repair(Mojibake.decodePercent(entry.displayName));

        if (!hrefName.isEmpty() && !Mojibake.hasReplacementChar(hrefName)) {
            entry.name = hrefName;
        } else if (!displayName.isEmpty()) {
            entry.name = displayName;
        } else {
            entry.name = hrefName;
        }
        entry.name = Mojibake.repair(entry.name);

        String abs = absolute(rawHref);
        if (entry.directory) {
            abs = withSlash(abs);
        }
        entry.url = abs;
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String localName(String name) {
        if (name == null) {
            return "";
        }
        int colon = name.indexOf(':');
        return (colon >= 0 ? name.substring(colon + 1) : name).toLowerCase(Locale.US);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long parseHttpDate(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        String[] patterns = {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEE, dd-MMM-yy HH:mm:ss zzz",
                "EEE MMM dd HH:mm:ss yyyy"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("GMT"));
                return format.parse(value).getTime();
            } catch (ParseException ignored) {
                // 尝试下一种格式
            }
        }
        return 0L;
    }

    private static void sort(List<RemoteEntry> entries) {
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(entries, new Comparator<RemoteEntry>() {
            @Override
            public int compare(RemoteEntry a, RemoteEntry b) {
                if (a.directory != b.directory) {
                    return a.directory ? -1 : 1;
                }
                return collator.compare(a.name, b.name);
            }
        });
    }

    // ---------------------------------------------------------------- 连接测试

    /** 用 PROPFIND Depth:0 探测根目录是否可访问。 */
    public void testConnection() throws IOException {
        String target = rootUrl();
        Request request = baseRequest(target)
                .method("PROPFIND", RequestBody.create(XML, PROPFIND_BODY))
                .header("Depth", "0")
                .build();
        try (Response response = http().newCall(request).execute()) {
            int code = response.code();
            if (code == 207 || (code >= 200 && code <= 299)) {
                return;
            }
            throw new WebDavException(code, describe(code));
        }
    }

    /** 逐级 MKCOL，保证配置的远端根目录存在。已存在时服务器一般返回 405，视为成功。 */
    public void ensureRoot() throws IOException {
        String base = config.baseUrl();
        String root = config.remoteRoot;
        if ("/".equals(root)) {
            return;
        }
        StringBuilder current = new StringBuilder(base);
        for (String part : root.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            current.append('/').append(encodeSegment(part));
            String dirUrl = withSlash(current.toString());
            Request request = baseRequest(dirUrl).method("MKCOL", emptyBody()).build();
            try (Response response = http().newCall(request).execute()) {
                int code = response.code();
                if (code >= 200 && code <= 299) {
                    continue;
                }
                if (code == 405) {
                    continue; // 目录已存在
                }
                // 409 表示上一级还不存在，理论上不会发生（我们是逐级创建的）
                throw new WebDavException(code, describe(code));
            }
        }
    }

    // ---------------------------------------------------------------- 建目录

    public void mkcol(String dirUrl) throws IOException {
        Request request = baseRequest(withSlash(dirUrl)).method("MKCOL", emptyBody()).build();
        try (Response response = http().newCall(request).execute()) {
            int code = response.code();
            if (code >= 200 && code <= 299) {
                return;
            }
            if (code == 405) {
                throw new WebDavException(code, "同名目录已存在");
            }
            throw new WebDavException(code, describe(code));
        }
    }

    // ---------------------------------------------------------------- 删除

    public void delete(String url) throws IOException {
        Request request = baseRequest(url).delete().build();
        try (Response response = http().newCall(request).execute()) {
            int code = response.code();
            if ((code >= 200 && code <= 299) || code == 404) {
                return;
            }
            throw new WebDavException(code, describe(code));
        }
    }

    // ---------------------------------------------------------------- 上传

    public void upload(File source, String dirUrl, String remoteName,
                       ProgressListener listener) throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException("本地文件不存在");
        }
        final long total = source.length();
        final MediaType contentType = MediaType.parse(FileUtils.mimeTypeOf(source.getName()));

        RequestBody body = new RequestBody() {
            @Override
            public MediaType contentType() {
                return contentType;
            }

            @Override
            public long contentLength() {
                return total;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                InputStream input = new FileInputStream(source);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    long sent = 0;
                    long lastNotified = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        sink.write(buffer, 0, read);
                        sent += read;
                        if (listener != null && (sent - lastNotified >= 256 * 1024 || sent == total)) {
                            lastNotified = sent;
                            listener.onProgress(sent, total);
                        }
                    }
                } finally {
                    try {
                        input.close();
                    } catch (IOException ignoredIo) {
                        // 忽略
                    }
                }
            }
        };

        String target = joinUrl(dirUrl, remoteName);
        Request request = baseRequest(target).put(body).build();
        try (Response response = http().newCall(request).execute()) {
            int code = response.code();
            if ((code >= 200 && code <= 299)) {
                if (listener != null) {
                    listener.onProgress(total, total);
                }
                return;
            }
            throw new WebDavException(code, "上传失败：" + describe(code));
        }
    }

    // ---------------------------------------------------------------- 下载

    public void download(String fileUrl, File destination, ProgressListener listener) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建下载目录：" + parent.getAbsolutePath());
        }

        Request request = baseRequest(fileUrl).get().build();
        File temp = new File(parent, destination.getName() + ".part");

        try {
            try (Response response = http().newCall(request).execute()) {
                int code = response.code();
                if (code < 200 || code > 299) {
                    throw new WebDavException(code, "下载失败：" + describe(code));
                }
                if (response.body() == null) {
                    throw new IOException("服务器返回内容为空");
                }
                long total = response.body().contentLength();
                InputStream input = response.body().byteStream();
                FileOutputStream output = new FileOutputStream(temp);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    long received = 0;
                    long lastNotified = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        received += read;
                        if (listener != null && (received - lastNotified >= 256 * 1024
                                || (total > 0 && received == total))) {
                            lastNotified = received;
                            listener.onProgress(received, total);
                        }
                    }
                    if (total > 0 && received != total) {
                        throw new IOException("下载不完整：" + received + "/" + total);
                    }
                    output.flush();
                } finally {
                    try {
                        output.close();
                    } catch (IOException ignoredIo) {
                        // 忽略
                    }
                    try {
                        input.close();
                    } catch (IOException ignoredIo) {
                        // 忽略
                    }
                }
            }
        } catch (Throwable t) {
            // 下载中断/失败时删掉 .part 半截文件，避免长期占用存储
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException("下载失败：" + t.getMessage(), t);
        }

        if (destination.exists() && !destination.delete()) {
            Log.w(TAG, "旧文件删除失败：" + destination.getAbsolutePath());
        }
        if (!temp.renameTo(destination)) {
            FileUtils.copyFile(temp, destination);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
        if (listener != null) {
            listener.onProgress(destination.length(), destination.length());
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static RequestBody emptyBody() {
        return RequestBody.create((MediaType) null, new byte[0]);
    }
}
