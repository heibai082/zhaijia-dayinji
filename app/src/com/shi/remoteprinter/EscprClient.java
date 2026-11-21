package com.shi.remoteprinter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 内网直打：把一张 JPEG 用爱普生 ESC/P-R 协议直接塞进打印机的 9100 端口，全程不经云端。
 *
 * <p>命令字节全部来自爱普生官方驱动源码 epson-escpr-api.c（1.8.8）里的常量，
 * 2026-11-21 在本机 L3150 上逐条验证：
 * <ul>
 *   <li>握手（无图像）→ 打印机接受，不出错；</li>
 *   <li>200×40 小图 → 出纸，宽度 1.4cm，由此确认该模式原生 <b>360dpi</b>；</li>
 *   <li>2977×4208 的 A4 JPEG（分 8 块，共 437KB）→ 铺满整页、文字清晰。</li>
 * </ul>
 *
 * <p>三个容易踩的坑：
 * <ol>
 *   <li>`ESC ( R` 的长度字段**包含**前导 0x00（"REMOTE1" 要写 8 而不是 7）；</li>
 *   <li>远程命令（JS/JH/HD/PP/JE）的长度同样含那个 0x00；</li>
 *   <li>JPEG 数据块里单块长度是 BE16，最大 65535，所以要切块（这里 60KB 一块）。</li>
 * </ol>
 *
 * <p>9100 端口没有回执：写成功 ≠ 打出来了。调用方要自己确认（比如查 SNMP 页计数）。
 */
public final class EscprClient {

    public static final int PORT = 9100;
    /** A4 @ 360dpi（这个模式的原生分辨率） */
    public static final int A4_W = 2977;
    public static final int A4_H = 4208;
    private static final int CHUNK = 60000;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int WRITE_TIMEOUT_MS = 60000;

    private EscprClient() {
    }

    /** 发一页 JPEG；成功返回发出去的字节数，网络层失败抛 IOException。 */
    public static int printJpeg(String ip, byte[] jpeg) throws IOException {
        if (jpeg == null || jpeg.length < 4) throw new IOException("没有图像数据");
        byte[] job = buildJob(jpeg);
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(WRITE_TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            out.write(job);
            out.flush();
        } catch (IOException e) {
            throw new IOException("连不上 " + ip + ":" + PORT + "（" + e.getMessage()
                    + "）。请确认手机和打印机在同一个 Wi-Fi", e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        return job.length;
    }

    /** 整个作业：退包模式 → 初始化 → 远程作业头 → 进 ESCPRJ → 页 → 分块 → 收尾。 */
    static byte[] buildJob(byte[] jpeg) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(jpeg.length + 512);
        b.write(EXIT_PACKET_MODE, 0, EXIT_PACKET_MODE.length);
        b.write(INIT, 0, INIT.length);
        b.write(ENTER_REMOTE, 0, ENTER_REMOTE.length);
        b.write(REMOTE_JS, 0, REMOTE_JS.length);
        b.write(REMOTE_JH, 0, REMOTE_JH.length);
        b.write(REMOTE_HD, 0, REMOTE_HD.length);
        b.write(REMOTE_PP, 0, REMOTE_PP.length);
        b.write(ESCPR_MODE_JPG, 0, ESCPR_MODE_JPG.length);
        b.write(START_PAGE, 0, START_PAGE.length);
        for (int off = 0; off < jpeg.length; off += CHUNK) {
            int n = Math.min(CHUNK, jpeg.length - off);
            b.write(jpegChunk(jpeg, off, n), 0, n + 12);
        }
        b.write(END_PAGE, 0, END_PAGE.length);
        b.write(END_JOB, 0, END_JOB.length);
        b.write(INIT, 0, INIT.length);
        b.write(ENTER_REMOTE, 0, ENTER_REMOTE.length);
        b.write(REMOTE_JE, 0, REMOTE_JE.length);
        b.write(EXIT_REMOTE, 0, EXIT_REMOTE.length);
        return b.toByteArray();
    }

    /** 一个 JPEG 数据块：ESC d(1) + LE32(2+size)(4) + "jsnd"(4) + BE16(size)(2) + 数据 = 12 字节头。 */
    private static byte[] jpegChunk(byte[] data, int off, int len) {
        byte[] out = new byte[len + 12];
        out[0] = 0x1B;
        out[1] = 'd';
        int paramLen = 2 + len;
        out[2] = (byte) (paramLen & 0xFF);
        out[3] = (byte) ((paramLen >>> 8) & 0xFF);
        out[4] = (byte) ((paramLen >>> 16) & 0xFF);
        out[5] = (byte) ((paramLen >>> 24) & 0xFF);
        out[6] = 'j';
        out[7] = 's';
        out[8] = 'n';
        out[9] = 'd';
        out[10] = (byte) ((len >>> 8) & 0xFF);
        out[11] = (byte) (len & 0xFF);
        System.arraycopy(data, off, out, 12, len);
        return out;
    }
    // ---- 命令常量（字节来自 epson-escpr-api.c） ----

    private static final byte[] EXIT_PACKET_MODE = concat(
            new byte[]{0x00, 0x00, 0x00, 0x1B, 0x01},
            ascii("ESCJL 1284.4\n"), ascii("@ESCJL     \n"));

    private static final byte[] INIT = {0x1B, 0x40};

    /** ESC ( R + LE16(8) + 0x00 + "REMOTE1"（长度含前导 0x00） */
    private static final byte[] ENTER_REMOTE = escR(ascii("REMOTE1"));

    /** ESC ( R + LE16(7) + 0x00 + "ESCPRJ" */
    private static final byte[] ESCPR_MODE_JPG = escR(ascii("ESCPRJ"));

    private static final byte[] REMOTE_JS = remote(ascii("JS"), new byte[]{0, 0, 0});

    private static final byte[] REMOTE_JH = remote(ascii("JH"),
            concat(new byte[]{0, 0, 0, 0, 0}, ascii("ESCPRLib")));

    private static final byte[] REMOTE_HD = remote(ascii("HD"), new byte[]{0x03, (byte) 0xFF});

    private static final byte[] REMOTE_PP = remote(ascii("PP"), new byte[]{0, 0});

    private static final byte[] REMOTE_JE = remote(ascii("JE"), new byte[0]);

    private static final byte[] START_PAGE = concat(new byte[]{0x1B, 'p', 0, 0, 0, 0}, ascii("sttp"));

    private static final byte[] END_PAGE =
            concat(new byte[]{0x1B, 'p', 1, 0, 0, 0}, ascii("endp"), new byte[]{0});

    private static final byte[] END_JOB = concat(new byte[]{0x1B, 'j', 0, 0, 0, 0}, ascii("endj"));

    private static final byte[] EXIT_REMOTE = {0x1B, 0x00, 0x00, 0x00};

    private static byte[] escR(byte[] name) {
        byte[] payload = concat(new byte[]{0}, name);
        return concat(new byte[]{0x1B, '(', 'R', (byte) (payload.length & 0xFF),
                (byte) ((payload.length >>> 8) & 0xFF)}, payload);
    }

    private static byte[] remote(byte[] name, byte[] params) {
        byte[] payload = concat(new byte[]{0}, params);
        return concat(name, new byte[]{(byte) (payload.length & 0xFF),
                (byte) ((payload.length >>> 8) & 0xFF)}, payload);
    }

    private static byte[] ascii(String s) {
        try {
            return s.getBytes("US-ASCII");
        } catch (java.io.UnsupportedEncodingException e) {
            return s.getBytes();
        }
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
