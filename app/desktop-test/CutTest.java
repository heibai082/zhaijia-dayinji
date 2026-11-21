import android.graphics.Bitmap;
import com.shi.remoteprinter.PortraitCut;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 桌面验收：用真正要打进 APK 的 PortraitCut 换底，输出对比图。 */
public class CutTest {
    public static void main(String[] a) throws Exception {
        String in = a.length > 0 ? a[0] : "src.jpg";
        String out = a.length > 1 ? a[1] : "cut-blue.jpg";
        int color = a.length > 2 ? (int) Long.parseLong(a[2], 16) : 0xFF438EDB;
        BufferedImage img = ImageIO.read(new File(in));
        int w = img.getWidth(), h = img.getHeight();
        int[] px = new int[w * h];
        img.getRGB(0, 0, w, h, px, 0, w);
        Bitmap src = Bitmap.fromPixels(w, h, px);
        long t0 = System.currentTimeMillis();
        Bitmap res = PortraitCut.replaceBackground(src, color);
        long ms = System.currentTimeMillis() - t0;
        if (res == null) {
            System.out.println("失败：返回 null");
            return;
        }
        int[] o = res.toPixels();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        bi.setRGB(0, 0, w, h, o, 0, w);
        ImageIO.write(bi, "jpg", new File(out));
        System.out.printf("%dx%d 用时 %d ms -> %s%n", w, h, ms, out);
    }
}