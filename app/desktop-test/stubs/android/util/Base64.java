package android.util;

/** 桌面测试用桩：MailSender 只用到 encodeToString/decode。 */
public final class Base64 {

    public static final int NO_WRAP = 2;
    public static final int DEFAULT = 0;

    private Base64() {
    }

    public static String encodeToString(byte[] data, int flags) {
        return java.util.Base64.getEncoder().encodeToString(data);
    }

    public static byte[] decode(String text, int flags) {
        return java.util.Base64.getMimeDecoder().decode(text);
    }
}
