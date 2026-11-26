package com.example.tvwebdavbox;

import android.text.TextUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 零散工具方法。 */
public final class FileUtils {

    private FileUtils() {
    }

    public static String mimeTypeOf(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (lower.endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        if (lower.endsWith(".avi")) {
            return "video/x-msvideo";
        }
        if (lower.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (lower.endsWith(".ts")) {
            return "video/mp2t";
        }
        if (lower.endsWith(".rmvb")) {
            return "video/vnd.rn-realvideo";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".flac")) {
            return "audio/flac";
        }
        if (lower.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".log")) {
            return "text/plain";
        }
        if (lower.endsWith(".srt") || lower.endsWith(".ass") || lower.endsWith(".ssa")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    public static boolean isVideo(String fileName) {
        String mime = mimeTypeOf(fileName);
        return mime.startsWith("video/");
    }

    public static boolean isApk(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.US).endsWith(".apk");
    }

    public static boolean isImage(String fileName) {
        return mimeTypeOf(fileName).startsWith("image/");
    }

    public static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.US, unit == 1 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    public static String formatTime(long millis) {
        if (millis <= 0) {
            return "";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
    }

    /** 在目录内生成不冲突的文件名，重名时追加 (1)、(2)… */
    public static File uniqueFile(File dir, String fileName) {
        if (dir == null) {
            return null;
        }
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File candidate = new File(dir, fileName);
        if (!candidate.exists()) {
            return candidate;
        }
        String base = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        for (int index = 1; index < 1000; index++) {
            File next = new File(dir, base + "(" + index + ")" + extension);
            if (!next.exists()) {
                return next;
            }
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + extension);
    }

    public static void copyFile(File source, File target) throws IOException {
        InputStream input = new FileInputStream(source);
        OutputStream output = new FileOutputStream(target);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
                // 忽略
            }
            try {
                output.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    public static String displayNameOfPath(String path) {
        if (TextUtils.isEmpty(path)) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
