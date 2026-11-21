import com.shi.remoteprinter.PaperClean;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 桌面验收：用真试卷照片跑 PaperClean 三档强度。 */
public class PaperTest {
    public static void main(String[] a) throws Exception {
        BufferedImage img = ImageIO.read(new File(a[0]));
        int w = img.getWidth(), h = img.getHeight();
        int[] px = new int[w * h];
        img.getRGB(0, 0, w, h, px, 0, w);
        for (int s = 0; s <= 2; s++) {
            long t0 = System.currentTimeMillis();
            int[] out = PaperClean.clean(px, w, h, s);
            long ms = System.currentTimeMillis() - t0;
            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            bi.setRGB(0, 0, w, h, out, 0, w);
            String dst = a[0].replace(".jpg", "-clean" + s + ".jpg");
            ImageIO.write(bi, "jpg", new File(dst));
            System.out.printf("strength=%d  %d ms -> %s%n", s, ms, dst);
        }
    }
}