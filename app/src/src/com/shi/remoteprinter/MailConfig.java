package com.shi.remoteprinter;

/**
 * 内置发信账号 —— 由 build.ps1 生成，请勿手改。
 *
 * <p>来源是仓库根目录的 mail-account.properties（形如 user=/pass=/host=/port=）。
 * 值按位异或后 Base64 存着，避免授权码明文躺在 APK 里。没有那个文件时 T 为空，
 * APP 就走"设置里手填"的老路。
 */
public final class MailConfig {

    private static final int KEY = 0x5A;
    private static final String[] T = {};

    private MailConfig() {
    }

    private static String dec(String b64) {
        if (b64 == null || b64.isEmpty()) return "";
        byte[] raw = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
        StringBuilder sb = new StringBuilder(raw.length);
        for (byte b : raw) {
            sb.append((char) ((b ^ KEY) & 0xff));
        }
        return sb.toString();
    }

    /** 内置配置；没内置返回 null。 */
    public static MailSender.Config defaults() {
        if (T.length < 3) return null;
        MailSender.Config c = new MailSender.Config();
        c.user = dec(T[0]);
        c.pass = dec(T[1]);
        c.host = dec(T[2]);
        c.port = T.length > 3 ? parseIntSafe(dec(T[3]), 465) : 465;
        c.ssl = true;
        c.from = c.user;
        if (c.user.isEmpty() || c.pass.isEmpty()) return null;
        return c;
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
