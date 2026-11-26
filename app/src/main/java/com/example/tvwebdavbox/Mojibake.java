package com.example.tvwebdavbox;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * 中文乱码修复。
 * 老式 NAS / Windows 共享常见的两类问题：
 * 1) UTF-8 字节被按 ISO-8859-1 或 windows-1252 解释，得到 "ä¸­æ–‡" 这类乱码；
 * 2) 文件名仍是百分号编码未还原，得到 "%E4%B8%AD%E6%96%87"。
 */
public final class Mojibake {

    private Mojibake() {
    }

    /** 百分号编码还原；失败或不像编码时原样返回。 */
    public static String decodePercent(String value) {
        if (value == null || value.isEmpty() || value.indexOf('%') < 0) {
            return value;
        }
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            // 含单独出现的 % 时 URLDecoder 会抛错，保留原文
            return value;
        }
    }

    /** 把 percent 编码按 GBK 再试一次，供 UTF-8 解出替换字符时兜底。 */
    public static String decodePercentAsGbk(String value) {
        if (value == null || value.isEmpty() || value.indexOf('%') < 0) {
            return value;
        }
        try {
            return URLDecoder.decode(value, "GBK");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return value;
        }
    }

    public static boolean hasReplacementChar(String value) {
        return value != null && value.indexOf('\uFFFD') >= 0;
    }

    public static boolean hasCjk(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF)      // CJK 统一汉字
                    || (c >= 0x3400 && c <= 0x4DBF) // 扩展 A
                    || (c >= 0xF900 && c <= 0xFAFF) // 兼容汉字
                    || (c >= 0x3040 && c <= 0x30FF)) { // 日文假名
                return true;
            }
        }
        return false;
    }

    /**
     * 乱码修复：只对"没有中日韩字符、但把字节按 Latin-1/CP1252 解释后能还原出中日韩字符"的字符串生效，
     * 因此对正常的英文、数字文件名不产生影响。
     */
    public static String repair(String value) {
        if (value == null || value.isEmpty() || hasCjk(value)) {
            return value;
        }
        String latin1 = reinterpret(value, "ISO-8859-1");
        if (latin1 != null && hasCjk(latin1)) {
            return latin1;
        }
        String cp1252 = reinterpret(value, "windows-1252");
        if (cp1252 != null && hasCjk(cp1252)) {
            return cp1252;
        }
        return value;
    }

    /** 先按指定单字节字符集取回原始字节，再按 UTF-8 解码。 */
    private static String reinterpret(String value, String sourceCharset) {
        try {
            byte[] bytes = value.getBytes(sourceCharset);
            String decoded = new String(bytes, "UTF-8");
            return hasReplacementChar(decoded) ? null : decoded;
        } catch (Exception e) {
            return null;
        }
    }
}
