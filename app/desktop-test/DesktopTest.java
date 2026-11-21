import com.shi.remoteprinter.EsciClient;
import com.shi.remoteprinter.RawPrintClient;

import java.io.FileOutputStream;

/**
 * 桌面验收台：用真正要打进 APK 的同一份 Java 源码，直连真机跑一遍。
 * 用法： java DesktopTest <ip> [diagnose|scan|print|all] [dpi]
 */
public class DesktopTest {

    public static void main(String[] args) throws Exception {
        String ip = args.length > 0 ? args[0] : "192.168.5.4";
        String mode = args.length > 1 ? args[1] : "all";
        int dpi = args.length > 2 ? Integer.parseInt(args[2]) : 300;

        if (mode.equals("all") || mode.equals("diagnose")) {
            System.out.println("===== EsciClient.diagnose =====");
            long t0 = System.currentTimeMillis();
            String d = EsciClient.diagnose(ip);
            System.out.println(d);
            System.out.println("(耗时 " + (System.currentTimeMillis() - t0) + " ms)\n");
        }

        if (mode.equals("all") || mode.equals("scan")) {
            System.out.println("===== EsciClient.scanPage (彩色 " + dpi + "dpi) =====");
            long t0 = System.currentTimeMillis();
            try {
                byte[] jpg = EsciClient.scanPage(ip, dpi, true);
                String out = "desktop-scan-" + dpi + ".jpg";
                FileOutputStream fos = new FileOutputStream(out);
                fos.write(jpg);
                fos.close();
                System.out.println("扫描成功：" + jpg.length + " 字节，"
                        + (System.currentTimeMillis() - t0) + " ms -> " + out);
                System.out.println("JPEG 头 " + String.format("%02X%02X", jpg[0], jpg[1])
                        + "，尾 " + String.format("%02X%02X",
                        jpg[jpg.length - 2], jpg[jpg.length - 1]));
            } catch (Exception e) {
                System.out.println("扫描失败：" + e.getMessage());
            }
            System.out.println();
        }

        if (mode.equals("all") || mode.equals("print")) {
            System.out.println("===== RawPrintClient.printTestPage =====");
            try {
                System.out.println(RawPrintClient.printTestPage(ip));
            } catch (Exception e) {
                System.out.println("打印失败：" + e.getMessage());
            }
        }
    }
}
