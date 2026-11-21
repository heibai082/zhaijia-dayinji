import com.shi.remoteprinter.BgReplace;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 验证"手指涂背景"：从一个点扩散填色，不该越过人脸。 */
public class BgPointTest {
    public static void main(String[] a) throws Exception {
        BufferedImage img = ImageIO.read(new File(a[0]));
        int w = img.getWidth(), h = img.getHeight();
        int[] px = new int[w * h];
        img.getRGB(0, 0, w, h, px, 0, w);
        int sx = Integer.parseInt(a[1]), sy = Integer.parseInt(a[2]);
        int color = (int) Long.parseLong(a[3], 16);
        int tol = Integer.parseInt(a[4]);
        int faceBefore = px[(h / 2) * w + (w / 2)];
        long t0 = System.currentTimeMillis();
        int filled = BgReplace.fillFromPoint(px, w, h, sx, sy, color, tol);
        int faceAfter = px[(h / 2) * w + (w / 2)];
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        ImageIO.write(out, "png", new File(a[0].replace(".png", "-point.png")));
        System.out.printf("点(%d,%d) 容差%d → 填了 %d 像素（%.1f%%），%d ms%n",
                sx, sy, tol, filled, 100.0 * filled / (w * h), System.currentTimeMillis() - t0);
        System.out.printf("人脸中心像素: 前 %08X 后 %08X（应相同）%n", faceBefore, faceAfter);
    }
}