package android.util;

/** 桌面测试用桩：把 android.util.Log 打到 stdout。 */
public final class Log {
    public static int i(String tag, String msg) {
        System.out.println("[I/" + tag + "] " + msg);
        return 0;
    }

    public static int w(String tag, String msg) {
        return w(tag, msg, null);
    }

    public static int w(String tag, String msg, Throwable t) {
        System.out.println("[W/" + tag + "] " + msg + (t == null ? "" : "  (" + t + ")"));
        return 0;
    }

    public static int e(String tag, String msg) {
        return e(tag, msg, null);
    }

    public static int e(String tag, String msg, Throwable t) {
        System.out.println("[E/" + tag + "] " + msg + (t == null ? "" : "  (" + t + ")"));
        return 0;
    }

    public static int d(String tag, String msg) {
        return 0;
    }
}
