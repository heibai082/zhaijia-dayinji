package com.shi.remoteprinter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 内网直连打印：raw TCP 9100 + ESC/P2 文本作业。
 *
 * <p>为什么要有它：主打印路径是"发到打印机云邮箱"（走 Epson Connect，家里打印机自己收），
 * 那条路能打中文、PDF、图片，但绕了一圈云端。9100 是打印机自己开的 raw 端口，
 * 手机和打印机在同一 Wi-Fi 时可以直接把排版好的数据塞进去，不经任何服务器。
 *
 * <p>已验证（2026-11-21，L3150 固件 01.39.VD21NB，从 192.168.5.26 直连 192.168.5.4）：
 * ESC/P2 文本作业（ESC @ + 单行文本 + FF）能正常出纸，字迹清晰。
 *
 * <p>限制，写清楚免得误用：
 * <ul>
 *   <li>这台机器没有可用的中文字库，ESC/P2 文本只能打 ASCII（英文、数字、常见符号），
 *       中文页面仍然要走云端邮件打印。</li>
 *   <li>整页图片/PDF 需要 ESC/P-R 光栅编码器（官方驱动那一套）。手写 ESC . 条带在这台
 *       机器上会被拒，并把打印机卡在 Busy 状态，所以这里不提供光栅打印。</li>
 *   <li>9100 没有回执：数据发完连接关闭，打印机不出纸也算"发送成功"。所以本类只报告
 *       "发出去多少字节"，不谎称"打印成功"。</li>
 * </ul>
 */
public class RawPrintClient {

    public static final int PORT = 9100;
    private static final int CONNECT_TIMEOUT_MS = 8000;

    /**
     * 打一张英文测试页。返回给界面显示的一句话（如实描述做到哪一步）。
     * 失败抛 IOException，消息可直接展示给用户。
     */
    public static String printTestPage(String ip) throws IOException {
        byte[] job = buildTestPage();
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(8000);
            OutputStream out = socket.getOutputStream();
            out.write(job);
            out.flush();
        } catch (IOException e) {
            throw new IOException("连不上 " + ip + ":" + PORT + "（" + e.getMessage()
                    + "）。请确认手机和打印机在同一个 Wi-Fi，打印机已开机", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        return "已把测试页（" + job.length + " 字节）直连发到 " + ip + ":" + PORT
                + "，打印机应该马上出纸。注意：9100 没有回执，"
                + "如果 10 秒内没出纸，说明这台机器不接受 ESC/P2 文本作业，"
                + "中文页面继续走云端邮件打印。";
    }

    /** ESC @ 初始化 + 单向打印 + 几行 ASCII + FF 出纸。 */
    static byte[] buildTestPage() {
        StringBuilder sb = new StringBuilder();
        sb.append("\u001b@");          // ESC @ 复位
        sb.append("\u001bU\u0001");    // ESC U 1 单向打印
        sb.append("Epson L3150  LAN RAW PRINT TEST\r\n");
        sb.append("--------------------------------\r\n");
        sb.append("tcp/9100  ESC/P2  text mode\r\n");
        sb.append("sent by ZhaiJiaDaYinJi (com.shi.remoteprinter)\r\n");
        sb.append("\r\n");
        sb.append("If you can read this, the phone can print\r\n");
        sb.append("straight to the printer LAN IP - no cloud.\r\n");
        sb.append("\u000c");           // FF 出纸
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
