package com.shi.remoteprinter;

import android.graphics.Bitmap;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

/**
 * 二维码扫描：把二维码图片解成文本，并判断是不是打印机邮箱。
 *
 * <p>解码用 vendored 的 ZXing 源码（Apache-2.0，见 third_party/zxing/LICENSE），
 * 只拷了 QR 解码需要的包，不当依赖引入——没有 Gradle、没有 AAR。
 * 这样离线、无 AI key、无 Google 服务都能用。
 */
public final class QrScan {

    private QrScan() {
    }

    /** 解码一张二维码图片；失败返回 null。 */
    public static String decode(Bitmap bm) {
        if (bm == null) return null;
        try {
            int w = bm.getWidth();
            int h = bm.getHeight();
            int[] px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h);
            LuminanceSource src = new RGBLuminanceSource(w, h, px);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(src));
            Result r = new QRCodeReader().decode(bitmap);
            return r.getText();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解出来的内容像不像一个邮箱地址（打印机卡片上的二维码就是邮箱）。 */
    public static boolean looksLikeEmail(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 6 || t.length() > 120) return false;
        return t.matches("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    }

    /** 脱敏显示：abc1****@example.com（界面、截图里不出现完整地址）。 */
    public static String mask(String email) {
        if (email == null || email.isEmpty()) return "未设置";
        int at = email.indexOf('@');
        if (at <= 0) return "****";
        String user = email.substring(0, at);
        String domain = email.substring(at);
        String head = user.length() <= 2 ? user.substring(0, 1) : user.substring(0, Math.min(4, user.length()));
        return head + "****" + domain;
    }
}
