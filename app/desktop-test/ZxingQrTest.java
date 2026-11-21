import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 桌面验收：用 vendored 的 ZXing 解 qr/ 目录里的真二维码。
 * 这一步通过，说明二维码这条链路在手机上也是同一条代码路径。
 */
public class ZxingQrTest {

    public static void main(String[] args) throws Exception {
        String[] list = args.length > 0 ? args : new String[]{
                "qr/qr-email-M.png", "qr/qr-email-Q.png", "qr/qr-email-L.png",
                "qr/qr-url.png", "qr/qr-num.png", "qr/qr-big.png"};
        int ok = 0;
        for (String path : list) {
            File f = new File(path);
            if (!f.exists()) {
                System.out.println("[SKIP] 找不到 " + path);
                continue;
            }
            BufferedImage img = ImageIO.read(f);
            int w = img.getWidth(), h = img.getHeight();
            int[] pixels = new int[w * h];
            img.getRGB(0, 0, w, h, pixels, 0, w);
            LuminanceSource src = new RGBLuminanceSource(w, h, pixels);
            BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(src));
            long t0 = System.currentTimeMillis();
            try {
                Result r = new QRCodeReader().decode(bmp);
                long ms = System.currentTimeMillis() - t0;
                System.out.printf("[OK]   %-18s %4d ms  ->  %s%n", f.getName(), ms, r.getText());
                ok++;
            } catch (Exception e) {
                System.out.printf("[FAIL] %-18s %s%n", f.getName(), e.getClass().getSimpleName());
            }
        }
        System.out.println("通过 " + ok + " / " + list.length);
    }
}
