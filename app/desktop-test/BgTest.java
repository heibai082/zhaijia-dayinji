import com.shi.remoteprinter.BgReplace;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 桌面验收：直接用真图跑 BgReplace（换底核心，不依赖 android），
 * 输出对比图，肉眼确认"背景变蓝、人像/内容还在"。
 *
 * 用法: java BgTest <输入图> <输出图> [颜色hex] [容差]
 */
public class BgTest {

    public static void main(String[] args) throws Exception {
        String in = args.length > 0 ? args[0] : "synthetic.png";
        String out = args.length > 1 ? args[1] : "out-bg.png";
        int color = args.length > 2 ? (int) Long.parseLong(args[2], 16) : 0xFF438EDB;
        int tol = args.length > 3 ? Integer.parseInt(args[3]) : 40;

        BufferedImage img = ImageIO.read(new File(in));
        int w = img.getWidth(), h = img.getHeight();
        int[] px = new int[w * h];
        img.getRGB(0, 0, w, h, px, 0, w);

        long t0 = System.currentTimeMillis();
        int filled = BgReplace.fillFromEdges(px, w, h, color, tol);
        long ms = System.currentTimeMillis() - t0;

        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        o.setRGB(0, 0, w, h, px, 0, w);
        ImageIO.write(o, "png", new File(out));
        System.out.printf("%dx%d 填掉 %d 像素（%.1f%%），%d ms -> %s%n",
                w, h, filled, 100.0 * filled / (w * h), ms, out);
    }
}
