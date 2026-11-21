import com.shi.remoteprinter.MailSender;

/** 桌面验收：把 MailSender 指向本地假 SMTP，验证协议与报文组装。 */
public class MailTest {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 2525;

        MailSender.Config c = new MailSender.Config();
        c.host = "127.0.0.1";
        c.port = port;
        c.ssl = false;                 // 假服务器没有证书，走明文分支
        c.requireTls = false;          // 仅测试：允许明文（生产默认强制 TLS）
        c.user = "sender@qq.com";
        c.pass = "authcode123";
        c.from = "sender@qq.com";
        c.to = "printer@example.com";   // 测试用占位，真实地址不要写进仓库

        // 造一段"像 JPEG"的假数据：含 0x00、0xFF、以及行首的点（测点转义）
        byte[] payload = new byte[3000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 256);
        }
        payload[100] = '.';
        payload[101] = '\r';
        payload[102] = '\n';

        System.out.println("测试连接（不发信）…");
        System.out.println("  " + MailSender.testLogin(c));

        System.out.println("发送带附件的邮件…");
        String r = MailSender.send(c, "打印：测试照片.jpg".replace(".jpg", ""),
                "宅家打印机 APP 直接发送，请打印。",
                "测试 照片.jpg", "image/jpeg", payload);
        System.out.println("  服务器回复: " + r);
        System.out.println("完成");
    }
}
