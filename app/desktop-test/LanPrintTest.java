import com.shi.remoteprinter.EscprClient;
import com.shi.remoteprinter.SnmpClient;

/** 桌面验收：用真正要打进 APK 的 EscprClient 打一页 A4，并用 SNMP 页计数确认出纸。 */
public class LanPrintTest {
    public static void main(String[] a) throws Exception {
        String ip = a.length > 0 ? a[0] : "192.168.5.4";
        String jpg = a.length > 1 ? a[1] : "escpr-test-a4.jpg";
        byte[] data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(jpg));
        long before = SnmpClient.pageCount(ip);
        System.out.println("发之前页数: " + before + "，JPEG " + data.length + " 字节");
        long t0 = System.currentTimeMillis();
        int sent = EscprClient.printJpeg(ip, data);
        System.out.println("已发送 " + sent + " 字节，用时 " + (System.currentTimeMillis()-t0) + " ms");
        long deadline = System.currentTimeMillis() + 90000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3000);
            long now = SnmpClient.pageCount(ip);
            if (before >= 0 && now > before) {
                System.out.println("页数 " + before + " -> " + now + "：出纸了 ✓");
                return;
            }
        }
        System.out.println("90 秒内页数没变（当前 " + SnmpClient.pageCount(ip) + "）");
    }
}