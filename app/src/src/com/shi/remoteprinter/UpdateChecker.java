package com.shi.remoteprinter;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 自动更新：查 update.json → 比 versionCode → 下载 APK 到"下载"目录 → 拉起系统安装界面。
 *
 * <p>为什么不能像桌面软件那样静默升级：安卓不允许普通 App 自安装（除非 root 或设备所有者），
 * 所以最后一步一定是系统弹出的"是否安装"，用户点一次。这是平台限制，不是我偷懒。
 *
 * <p>更新源：`update.json`（由 build.ps1 生成、serve_apk.py 托管），格式：
 * <pre>{"versionCode":29,"versionName":"0.9.19","url":"http://…/remote-printer.apk","notes":"…"}</pre>
 */
public final class UpdateChecker {

    private static final String TAG = "Update";
    private static final String DEFAULT_MANIFEST = "http://192.168.5.26:8021/update.json";

    private UpdateChecker() {
    }

    /** 当前安装包的 versionCode。 */
    public static int currentVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String currentVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 查到的更新信息；没有更新返回 null。 */
    public static class Info {
        public int versionCode;
        public String versionName;
        public String url;
        public String notes;
    }

    /** 读 update.json；失败抛异常（调用方显示原因）。 */
    public static Info fetch(String manifestUrl, int myVersionCode) throws Exception {
        String body = httpGet(manifestUrl, 8000);
        JSONObject o = new JSONObject(body);
        Info info = new Info();
        info.versionCode = o.optInt("versionCode", 0);
        info.versionName = o.optString("versionName", "");
        info.url = o.optString("url", "");
        info.notes = o.optString("notes", "");
        if (info.versionCode <= myVersionCode || info.url.isEmpty()) return null;
        return info;
    }

    /** 下载 APK 到系统"下载"目录，返回可安装的 content:// Uri。 */
    public static Uri download(Context ctx, String apkUrl, String versionName,
                              Progress cb) throws Exception {
        if (Build.VERSION.SDK_INT < 29) {
            throw new Exception("系统版本太低（需要 Android 10 以上）才能用应用内下载安装");
        }
        String name = "宅家打印机-" + versionName + ".apk";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri target = ctx.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (target == null) throw new Exception("无法在下载目录创建文件");

        HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(60000);
        conn.connect();
        int total = conn.getContentLength();
        InputStream in = conn.getInputStream();
        OutputStream out = ctx.getContentResolver().openOutputStream(target);
        if (out == null) throw new Exception("写不了下载目录");
        byte[] buf = new byte[65536];
        int n, done = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (cb != null) cb.onProgress(done, total);
        }
        out.flush();
        out.close();
        in.close();
        conn.disconnect();
        return target;
    }

    /** 拉起系统安装界面（会弹"是否安装"，这是安卓的规矩）。 */
    public static String install(Activity act, Uri apkUri) {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            // 没开"安装未知应用"权限：跳去设置页，用户开完再回来点一次
            try {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName()));
                act.startActivity(s);
            } catch (Exception ignored) {
            }
            return "请先允许「安装未知应用」，回来再点一次更新";
        }
        try {
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(apkUri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(it);
            return "已打开安装界面，按提示点「安装」即可";
        } catch (Exception e) {
            Log.e(TAG, "拉起安装失败", e);
            return "拉起安装失败：" + e.getMessage();
        }
    }

    public interface Progress {
        void onProgress(int done, int total);
    }

    private static String httpGet(String url, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "zhaijia-dayinji");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("服务器返回 " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            conn.disconnect();
        }
    }
}
