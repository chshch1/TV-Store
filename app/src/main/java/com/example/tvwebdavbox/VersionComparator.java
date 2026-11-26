package com.example.tvwebdavbox;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本号比较：按点分段做数值比较，末位补 0。
 * 兼容 "1.2.3"、"v1.2"、"1.2.3-beta"、"10.0.0 (12)" 等写法。
 */
public final class VersionComparator {

    public enum Result {
        /** 云端版本更新 */
        REMOTE_NEWER,
        /** 版本相同 */
        EQUAL,
        /** 本机版本更新 */
        LOCAL_NEWER,
        /** 无法解析，不能比较 */
        UNKNOWN
    }

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("(?i)(?:^|[^0-9])v?(\\d+(?:\\.\\d+)*)");

    private VersionComparator() {
    }

    public static Result compare(String local, String remote) {
        List<BigInteger> localParts = parse(local);
        List<BigInteger> remoteParts = parse(remote);
        if (localParts == null || remoteParts == null) {
            return Result.UNKNOWN;
        }
        int count = Math.max(localParts.size(), remoteParts.size());
        for (int i = 0; i < count; i++) {
            BigInteger l = i < localParts.size() ? localParts.get(i) : BigInteger.ZERO;
            BigInteger r = i < remoteParts.size() ? remoteParts.get(i) : BigInteger.ZERO;
            int order = r.compareTo(l);
            if (order > 0) {
                return Result.REMOTE_NEWER;
            }
            if (order < 0) {
                return Result.LOCAL_NEWER;
            }
        }
        return Result.EQUAL;
    }

    public static boolean isRemoteNewer(String local, String remote) {
        return compare(local, remote) == Result.REMOTE_NEWER;
    }

    public static boolean canCompare(String local, String remote) {
        return compare(local, remote) != Result.UNKNOWN;
    }

    private static List<BigInteger> parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        Matcher matcher = VERSION_PATTERN.matcher(value.trim());
        if (!matcher.find()) {
            return null;
        }
        String[] parts = matcher.group(1).split("\\.");
        List<BigInteger> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty()) {
                return null;
            }
            try {
                result.add(new BigInteger(part));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return result.isEmpty() ? null : result;
    }
}
