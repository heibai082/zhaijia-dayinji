package com.shi.remoteprinter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Epson ESC/I 扫描客户端（爱普生私有协议，Epson Scan 2 / Smart Panel / iPrint 用的就是它）。
 *
 * 为什么需要它：Epson L3150/L3151 根本不支持 eSCL（GET /eSCL/ScannerStatus 返回 404，
 * POST /eSCL/ScanJobs 也 404），eSCL 这条路已死；但同一台打印机一定支持 ESC/I，
 * 因为官方 Smart Panel 就是用它扫描的。
 *
 * 协议资料出处（2026-11-21 查证）：
 * - Epson 官方 ESC/I-2 协议规范：IS 帧头 12 字节（"IS" + 类型 + 偏移 + 长度 + 填充）、
 *   控制流 FS Y / FS Z / INFO / CAPA / FIN / FS X / STAT / PARA / TRDT / IMG，
 *   以及 BUSY/WUP/#pen/0xa000 空包等语义。
 * - epson2paperless（GitHub mtheuma/epson2paperless）对 ET-2750 等机型的实测抓包：
 *   docs/PROTOCOL-REFERENCE.md、src/esci2/*.ts、src/protocol.ts、src/commands-fs.ts。
 *   本实现复用它的会话流程和 ET-2750 的 PARA 参数模板（L3150 与 ET-2750 同为 2019 年
 *   EcoTank 平板机型，固件行为最接近；L3150 自己的 PARA 抓包目前没有）。
 * - SANE epsonds 后端（sane-backends backend/epsonds*.c）印证了同一套命令：
 *   INFO/CAPA/RESA/STAT/FIN/TRDT/IMG + FS 命令 + 12 字节 PARA 头。
 *
 * 传输：纯 TCP 直连打印机 1865 端口，不走代理、不走云端（手机和打印机须在同一 Wi-Fi）。
 * 图像：协议返回 JPEG 数据块（#FMTJPG），直接拼接就是一张 JPEG；黑白模式协议层面
 * 没有灰度输出，做法是先按彩色扫，再在本地转灰度压回 JPEG（与 epson2paperless
 * "线上传输永远是彩色，灰度在主机端转换" 的结论一致）。
 * 幅面：平板 Platen，A4 全幅（300dpi 时 2477x3500，600dpi 时 4954x7000）。
 *
 * 未经验证（云端连不上用户家内网打印机 192.168.5.4，没法真机测试）：
 * PARA 参数是 ET-2750 的实测值，L3150 可能拒绝（#parNG）或需要不同的
 * #ACQ 扫描窗口；欢迎包也可能不是 0x04。所有失败都会抛 IOException，
 * 信息里写明走到哪一步、打印机回了什么，方便按报错原文定位。
 *
 * 2026-11-21 真机反馈：彩色/300dpi 扫描报“ESC/I 锁定失败：打印机关闭了连接”。
 * 发包字节已与 epson2paperless（src/protocol.ts、src/esci2/*.ts）逐字节核对一致，
 * 问题不在包格式上，最可能是打印机睡眠/被占用或旧锁定未释放、直接断了连接。
 * 本版应对：锁定失败自动重试 1 次（整套 connect → 欢迎包 → 锁定重来）；
 * 欢迎包载荷记成十六进制存下来，失败报错末尾附上，方便定位型号差异还是状态问题；
 * 扫描页加“诊断扫描连接”按钮，只做 connect → 欢迎包 → 锁定 → 解锁。
 * 下一步（未做）：如 plain 锁定持续失败，可尝试 TLS 1865（参考实现里 ET-4950
 * 用 TLS 1865，但 L3150 的 SNI/destId 未知，盲目加没用，等诊断结果再定）。
 */
public class EsciClient {
    private static final String TAG = "EsciClient";

    public static final int PORT = 1865;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int WELCOME_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 90000;

    /** IS 包类型 */
    private static final int T_WELCOME = 0x8000;
    private static final int T_ASYNC = 0x9000;
    private static final int T_PASSTHRU_REPLY = 0xa000;
    private static final int T_LOCK_ACK = 0xa100;
    private static final int T_UNLOCK_ACK = 0xa101;
    private static final int T_PASSTHRU = 0x2000;
    private static final int T_LOCK = 0x2100;
    private static final int T_UNLOCK = 0x2101;

    private static final byte ACK = 0x06;
    private static final int INIT_POLL_ITERATIONS = 2; // ET-2750 实测值
    /** 欢迎包允许的最大长度：真实欢迎包固定 5 字节，留一点余量，超过说明数据流不对。 */
    private static final int WELCOME_MAX_LEN = 8;
    private static final int MAX_ZERO_IMG_RETRIES = 5000;
    private static final long STAT_BUSY_DEADLINE_MS = 60000;

    /** FS 命令（2 字节，回复 1 字节 ACK 0x06） */
    private static final byte[] FS_Y = {0x1C, 0x59};
    private static final byte[] FS_Z = {0x1C, 0x5A};
    private static final byte[] FS_X = {0x1C, 0x58};

    // ---- PARA 参数模板：ET-2750 实测（epson2paperless，936 字节 @300dpi/质量 90）----
    // 伽马 LUT（et4950-stock，804 字节 = 3 段 × 268 字节）：
    // GRN 恒等；RED 跳过 0x14、重复 0xc6；BLU 重复 0x24、跳过 0xa6。
    // 直接用抓包的原始字节，不"修正"这些异常点。
    private static final String GAMMA_HEX =
            // #GMTGRN h100 + 恒等 LUT
            "23474d5447524e2068313030"
            + "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
            + "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
            + "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"
            + "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"
            + "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"
            + "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"
            + "c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf"
            + "e0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
            // #GMTRED h100 + LUT（跳过 0x14、重复 0xc6）
            + "23474d545245442068313030"
            + "000102030405060708090a0b0c0d0e0f1011121315161718191a1b1c1d1e1f"
            + "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
            + "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"
            + "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"
            + "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"
            + "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"
            + "c0c1c2c3c4c5c6c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf"
            + "e0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
            // #GMTBLU h100 + LUT（重复 0x24、跳过 0xa6）
            + "23474d54424c552068313030"
            + "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
            + "20212223242425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
            + "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"
            + "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"
            + "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"
            + "a0a1a2a3a4a5a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"
            + "c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf"
            + "e0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff";

    /** CMX 颜色矩阵（et2750-um08，单位矩阵）：#CMXUM08h009 + 12 字节 */
    private static final String CMX_HEX = "23434d58554d303868303039200000002000000020000000";

    /** 一个 IS 包 */
    private static class Packet {
        final int type;
        final byte[] payload;

        Packet(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    /** ESC/I-2 12 字节帧头：<NAME>x<7 位十六进制长度> */
    private static class Esci2Header {
        final String name;
        final int length;

        Esci2Header(String name, int length) {
            this.name = name;
            this.length = length;
        }
    }

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private boolean locked;
    private String infoBody;
    /** 欢迎包载荷的十六进制（readWelcome 里记下，失败报错末尾附上，方便定位）。 */
    private String welcomeHex;
    /** 欢迎包载荷长度。标准 ESC/I-2 是 5 字节（01 04 00 00 00），明显更长说明数据流不对。 */
    private int welcomeLen = -1;
    /** 欢迎包第 2 字节：0x04 = ESC/I-2 plain，0x02 = 旧版 ESC/I，-1 = 未收到。 */
    private int welcomeClass = -1;

    /**
     * 扫一页平板。返回 JPEG 字节；失败抛 IOException（信息带 "ESC/I" 前缀，
     * 写明走到哪一步、打印机回了什么，原样显示给用户）。
     * 用法与 EsclClient.scanPage(ip, dpi, color) 一致。
     */
    public static byte[] scanPage(String ip, int dpi, boolean color) throws IOException {
        EsciClient c = new EsciClient();
        try {
            return c.run(ip, dpi, color);
        } finally {
            c.closeQuietly();
        }
    }

    /**
     * 扫描连接诊断：只做 connect → 欢迎包 → 锁定 → 解锁，不扫描。
     * 返回每一步的结果（逐行），供扫描页状态栏显示，例如：
     * 已连接 192.168.5.4:1865
     * 欢迎包：ESC/I-2（0x04）：8000…
     * 锁定成功
     * 已解锁
     * 锁定分两次尝试：先试 300 秒，失败再试 30 秒（作业控制流程里锁定用的
     * 就是 30 秒），例如：
     * 锁定（300 秒）失败：打印机关闭了连接
     * 锁定（30 秒）成功
     * 已解锁
     * 某一步失败时返回已走到的步骤 + 失败原文（含欢迎包十六进制）。
     */
    public static String diagnose(String ip) {
        EsciClient c = new EsciClient();
        StringBuilder sb = new StringBuilder();
        try {
            c.connect(ip);
            sb.append("已连接 ").append(ip).append(":1865\n");
            c.readWelcome();
            sb.append("欢迎包：").append(c.welcomeClassText()).append("\n");
            // 先试 300 秒锁定，失败再试 30 秒锁定
            c.pauseBeforeLock(); // 和正常扫描同一节奏：锁定前停 300ms
            try {
                c.sendLock(300);
                sb.append("锁定（300 秒）成功\n");
            } catch (IOException e300) {
                sb.append("锁定（300 秒）失败：")
                        .append(stripStepPrefix(e300.getMessage())).append("\n");
                c.closeQuietly();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return sb.toString() + "失败：等待被打断";
                }
                c.connect(ip);
                c.readWelcome();
                c.pauseBeforeLock();
                try {
                    c.sendLock(30);
                    sb.append("锁定（30 秒）成功\n");
                } catch (IOException e30) {
                    return sb.toString() + "失败：锁定（30 秒）失败："
                            + stripStepPrefix(e30.getMessage())
                            + "（欢迎包：" + c.welcomeHexOrUnknown() + "）";
                }
            }
            // 顺手读一次机器信息（FS Y/INFO/CAPA/FIN 都是只读查询，不改变扫描状态）：
            // 型号 PID、固件版本、支持的分辨率，是判断"打印机被识别成什么机型"的关键证据。
            try {
                c.sendFsAndAck("INIT1 FS Y", FS_Y);
                String info = c.sendEsci2("INIT1 INFO", "INFO");
                String capa = c.sendEsci2("INIT1 CAPA", "CAPA");
                c.sendEsci2("INIT1 FIN", "FIN");
                sb.append("INFO：").append(describeInfo(info, capa)).append("\n");
            } catch (IOException ei) {
                sb.append("INFO 读取失败（不影响锁定结论）：")
                        .append(stripStepPrefix(ei.getMessage())).append("\n");
            }
            c.closeQuietly(); // 解锁并关连接
            sb.append("已解锁");
            return sb.toString();
        } catch (IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (c.welcomeHex != null && !m.contains("欢迎包：")) {
                m += "（欢迎包：" + c.welcomeHex + "）";
            }
            return sb.toString() + "失败：" + m;
        } finally {
            c.closeQuietly();
        }
    }

    /** 欢迎包类型的人话描述（诊断显示用），带上长度：5 字节才是干净的 ESC/I-2 欢迎包。 */
    private String welcomeClassText() {
        String head;
        switch (welcomeClass) {
            case 0x04: head = "ESC/I-2（0x04）"; break;
            case 0x02: head = "旧版 ESC/I（0x02）"; break;
            default: head = "未知类型 0x" + hexByte(welcomeClass); break;
        }
        return head + "，" + welcomeLen + " 字节：" + welcomeHex;
    }

    /**
     * 把 INFO 回复翻译成一行人话：PID（机型）、固件版本、支持的分辨率。
     * 原始回复长这样（L3150 实测）：
     * INFOx0000078#---...#FB ALGNLEFT#FB AREAd850i0001170#FB RESOi0001200
     * #FB RSMSLISTd075d150d300d600i0001200…；第二段里是 #PRDh010PID 1143  #VERh008FB  1.08
     */
    private static String describeInfo(String info, String capa) {
        String all = info + capa;
        StringBuilder sb = new StringBuilder();
        int pid = indexOfIgnoreCase(all, "PID ");
        if (pid >= 0) {
            sb.append("PID ").append(nextToken(all, pid + 4));
        }
        int fb = indexOfIgnoreCase(all, "FB  ");
        if (fb >= 0) {
            if (sb.length() > 0) sb.append("，");
            sb.append("固件 FB ").append(nextToken(all, fb + 4));
        }
        // 分辨率列表（在 CAPA 里）：RSMSLISTd075d150d300d600…
        int rs = indexOfIgnoreCase(all, "RSMSLIST");
        if (rs >= 0) {
            String v = all.substring(rs + 8);
            StringBuilder list = new StringBuilder();
            for (int i = 0; i + 4 <= v.length() && v.charAt(i) == 'd'; i += 4) {
                String num = v.substring(i + 1, i + 4);
                boolean digits = true;
                for (int k = 0; k < num.length(); k++) {
                    if (!Character.isDigit(num.charAt(k))) digits = false;
                }
                if (!digits) break;
                if (list.length() > 0) list.append("/");
                list.append(Integer.parseInt(num)).append("dpi");
            }
            if (list.length() > 0) {
                if (sb.length() > 0) sb.append("，");
                sb.append("分辨率 ").append(list);
            }
        }
        if (sb.length() == 0) {
            return "（原始回复）" + truncate(asciiSafe(ascii(all)), 160);
        }
        return sb.toString();
    }

    /** 取 from 开始的一个 token：到空白或 '#' 为止。 */
    private static String nextToken(String s, int from) {
        int end = from;
        while (end < s.length()) {
            char ch = s.charAt(end);
            if (Character.isWhitespace(ch) || ch == '#') break;
            end++;
        }
        return s.substring(from, end).trim();
    }

    private static int indexOfIgnoreCase(String s, String key) {
        if (s == null) return -1;
        for (int i = 0; i + key.length() <= s.length(); i++) {
            if (s.regionMatches(true, i, key, 0, key.length())) return i;
        }
        return -1;
    }

    private byte[] run(String ip, int dpi, boolean color) throws IOException {
        try {
            connect(ip);
            readWelcome(); // 0x04 = ESC/I-2；0x02 旧版直接抛错
            lockWithRetry(ip);
            // INIT1：FS Y -> INFO -> CAPA -> FIN
            sendFsAndAck("INIT1 FS Y", FS_Y);
            infoBody = sendEsci2("INIT1 INFO", "INFO");
            sendEsci2("INIT1 CAPA", "CAPA");
            sendEsci2("INIT1 FIN", "FIN");
            // INIT2：FS Z -> INFO -> CAPA -> RESA -> FIN
            sendFsAndAck("INIT2 FS Z", FS_Z);
            sendEsci2("INIT2 INFO", "INFO");
            sendEsci2("INIT2 CAPA", "CAPA");
            sendEsci2("INIT2 RESA", "RESA");
            sendEsci2("INIT2 FIN", "FIN");
            // INIT_POLL x2：FS Y -> STAT -> FIN
            for (int i = 0; i < INIT_POLL_ITERATIONS; i++) {
                sendFsAndAck("INIT_POLL FS Y", FS_Y);
                sendEsci2("INIT_POLL STAT", "STAT");
                sendEsci2("INIT_POLL FIN", "FIN");
            }
            // MODE_SWITCH：FS X
            sendFsAndAck("MODE_SWITCH FS X", FS_X);
            // POST_MODE_STAT：等就绪（BUSY/WUP 时每 100ms 重问，最多 60 秒）
            waitUntilReady();
            // PARA：两包连发
            sendPara(buildPara(dpi));
            // TRDT
            sendTrdt();
            // IMG 循环取 JPEG 数据
            byte[] jpeg = ensureJpegEoi(readImage());
            // END FIN
            sendEsci2("END FIN", "FIN");
            if (!color) return toGrayscaleJpeg(jpeg);
            return jpeg;
        } catch (IOException e) {
            // 任何一步失败，报错末尾附上欢迎包十六进制（已有时），方便定位
            throw appendWelcome(e);
        }
    }

    /**
     * 锁定失败自动重试 1 次：关闭连接、等 1 秒，connect → 欢迎包 → 锁定全套重来。
     * 第一次锁定用 300 秒超时，重试改用 30 秒超时（作业控制流程里锁定用的就是
     * 30 秒，同一端口同一条锁定命令，有证据表明打印机接受 30 秒）。
     * 两次都失败才报错，写明两次用的超时值，并保留第一次的失败详情。
     * 只重试锁定这一步（sendLock 的失败）；连接/欢迎包失败不重试，直接往上抛。
     */
    private void lockWithRetry(String ip) throws IOException {
        pauseBeforeLock();
        try {
            sendLock(300);
            return;
        } catch (IOException first) {
            String firstDetail = stripStepPrefix(first.getMessage());
            closeQuietly();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw first;
            }
            connect(ip);
            readWelcome();
            pauseBeforeLock();
            try {
                sendLock(30);
            } catch (IOException second) {
                String secondDetail = stripStepPrefix(second.getMessage());
                String detail = firstDetail.equals(secondDetail)
                        ? firstDetail + "（300 秒、30 秒各试 1 次均失败）"
                        : firstDetail + "（300 秒失败）；重试（30 秒）失败：" + secondDetail;
                throw fail("锁定", detail + "。欢迎包：" + welcomeHexOrUnknown()
                        + "。请先按一下打印机上的按键唤醒它，再试一次。");
            }
        }
    }

    /** 锁定前停 300ms：个别机型欢迎包发完还要一拍才能接受锁定，发太快会被直接断开。被打断就抛错。 */
    private void pauseBeforeLock() throws IOException {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw fail("锁定", "等待被打断");
        }
    }

    /** 任何一步失败时，报错末尾附上欢迎包十六进制（已有且未附过时），方便定位。 */
    private IOException appendWelcome(IOException e) {
        String m = e.getMessage();
        if (m == null || welcomeHex == null || m.contains("欢迎包：")) return e;
        return new IOException(m + "（欢迎包：" + welcomeHex + "）");
    }

    private String welcomeHexOrUnknown() {
        return welcomeHex == null ? "未收到" : welcomeHex;
    }

    /** 去掉报错开头的 "ESC/I xxx失败：" 前缀，剩下的才是失败详情（拼重试报错时用）。 */
    private static String stripStepPrefix(String msg) {
        if (msg == null) return "";
        if (msg.startsWith("ESC/I ")) {
            int i = msg.indexOf("失败：");
            if (i >= 0) return msg.substring(i + 3);
        }
        return msg;
    }

    // ---- 连接与握手 ----

    private void connect(String ip) throws IOException {
        socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(WELCOME_TIMEOUT_MS);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        } catch (IOException e) {
            throw fail("连接", "连不上 " + ip + ":" + PORT + "（" + e.getMessage()
                    + "）。请确认手机和打印机在同一 Wi-Fi，且打印机开机");
        }
    }

    /** 读欢迎包（打印机连上后立即发来）。0x04 = ESC/I-2 plain，0x02 = 旧版 ESC/I。 */
    private void readWelcome() throws IOException {
        Packet p;
        try {
            p = readPacketRaw();
        } catch (java.net.SocketTimeoutException e) {
            throw fail("握手", "连接后 10 秒内没收到打印机欢迎包。可能是这台打印机用 TLS 加密连接"
                    + "（较新的 ET 系列），或 1865 端口未响应");
        } catch (EOFException e) {
            throw fail("握手", "打印机关闭了连接（没发欢迎包就断开）");
        }
        // 记下欢迎包原文（十六进制），之后任何一步失败都附在报错末尾，方便定位。
        welcomeHex = hexDump(p.payload);
        welcomeLen = p.payload.length;
        if (p.payload.length >= 2) welcomeClass = p.payload[1] & 0xFF;
        if (p.type != T_WELCOME) {
            throw fail("握手", "第一个包不是欢迎包（类型 0x" + hex(p.type)
                    + "），不是 ESC/I 打印机");
        }
        if (p.payload.length < 2) {
            throw fail("握手", "欢迎包太短（" + p.payload.length + " 字节）");
        }
        // 长度校验：真实 ESC/I-2 欢迎包固定 5 字节（01 04 00 00 00），旧版 5 字节（01 02 00 00 00）。
        // 明显更长说明本次读取到的不是干净的首包（旧连接残留、异步事件、中间设备插话），
        // 继续往下发锁定只会被打印机断连，所以直接报清楚，别再猜超时值。
        if (p.payload.length > WELCOME_MAX_LEN) {
            throw fail("握手", "欢迎包异常：收到 " + p.payload.length + " 字节（正常 5 字节），内容 "
                    + welcomeHex + "。这条连接上收到的不是打印机的标准欢迎包，"
                    + "通常是手机到打印机之间还有代理/VPN/热点共享在改写连接，"
                    + "或上一次连接没断干净。请关掉代理和 VPN、切到打印机所在的同一个 Wi-Fi 再试");
        }
        int cls = p.payload[1] & 0xFF;
        if (cls == 0x04) {
            try {
                socket.setSoTimeout(READ_TIMEOUT_MS);
            } catch (java.net.SocketException e) {
                throw fail("握手", "设置读超时失败（" + e.getMessage() + "）");
            }
            Log.i(TAG, "欢迎包：ESC/I-2 plain");
            return;
        }
        if (cls == 0x02) {
            throw fail("握手", "打印机是旧版 ESC/I（欢迎包 0x02，如 WF-3620/XP-620/ET-2550 那一代），"
                    + "本版本暂不支持旧版协议，请用 Epson Smart Panel 扫描后导入");
        }
        throw fail("握手", "未知的欢迎包类型 0x" + hexByte(cls)
                + "（已知：0x02 旧版 ESC/I，0x04 ESC/I-2）");
    }

    /** 锁定打印机（IS 0x2100，载荷 01 a0 04 + 超时秒数），等 0xa100 确认。 */
    private void sendLock(int timeoutSeconds) throws IOException {
        byte[] payload = new byte[7];
        payload[0] = 0x01;
        payload[1] = (byte) 0xA0;
        payload[2] = 0x04;
        putBe32(payload, 3, timeoutSeconds);
        sendIs(T_LOCK, payload);
        Packet ack = readReply("锁定", T_LOCK_ACK);
        if (ack.payload.length < 1 || ack.payload[0] != ACK) {
            throw fail("锁定", "打印机没有确认锁定，返回：" + describePayload(ack.payload));
        }
        locked = true;
    }

    /** 解锁并关连接（尽力而为，失败只记日志，不掩盖扫描结果）。 */
    private void closeQuietly() {
        if (socket != null) {
            try {
                if (locked) {
                    try {
                        socket.setSoTimeout(5000);
                        sendIs(T_UNLOCK, null);
                        Packet ack = readPacketRaw();
                        if (ack.type != T_UNLOCK_ACK) {
                            Log.w(TAG, "解锁回应不是 0xa101: 0x" + hex(ack.type));
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "解锁失败（忽略）", e);
                    }
                }
            } finally {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
                socket = null;
            }
        }
    }

    // ---- ESC/I-2 命令 ----

    /** 发 FS 命令，等 1 字节 ACK 0x06。 */
    private void sendFsAndAck(String step, byte[] fs) throws IOException {
        sendPassthru(fs, 1);
        Packet r = readReply(step);
        if (r.payload.length != 1 || r.payload[0] != ACK) {
            throw fail(step, "FS 命令未收到 ACK 0x06，收到 " + describePayload(r.payload));
        }
    }

    /**
     * 发一条 12 字节 ESC/I-2 命令，收 64 字节 META 回复；帧头声明正文长度时
     * 用纯读取回正文。返回回复帧全文（META 帧 + 正文，ASCII）。
     */
    private String sendEsci2(String step, String cmdName) throws IOException {
        sendPassthru(ascii(padCmd(cmdName)), 64);
        Packet r = readReply(step);
        Esci2Header h = parseEsci2Header(r.payload, step);
        if (!cmdName.equals(h.name)) {
            throw fail(step, "回复的命令名是 " + h.name + "，期望 " + cmdName);
        }
        String frame = new String(r.payload, StandardCharsets.US_ASCII);
        checkStatErrors(frame, step);
        if (h.length > 0) {
            byte[] body = drainBody(step, h.length);
            return frame + new String(body, StandardCharsets.US_ASCII);
        }
        return frame;
    }

    /** POST_MODE_STAT：等打印机就绪。BUSY/WUP 时每 100ms 重问 STAT，最多等 60 秒。 */
    private void waitUntilReady() throws IOException {
        long deadline = System.currentTimeMillis() + STAT_BUSY_DEADLINE_MS;
        for (;;) {
            String reply = sendEsci2("STAT（等就绪）", "STAT");
            String nrd = findToken(reply, "#nrd");
            if (nrd == null) return; // 没有 #nrd = 就绪
            if (nrd.startsWith("BUSY") || nrd.startsWith("WUP")) {
                if (System.currentTimeMillis() > deadline) {
                    throw fail("STAT（等就绪）", "打印机 60 秒一直显示 " + nrd
                            + "（忙/预热），请重启打印机再试");
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw fail("STAT（等就绪）", "等待被打断");
                }
                continue;
            }
            if (nrd.startsWith("RSVD") || nrd.startsWith("AFEN")) {
                throw fail("STAT（等就绪）", "打印机报告 " + nrd
                        + "（被占用/自动进纸器已启用），无法平板扫描");
            }
            return; // 其他 #nrd 值：当作就绪继续，不硬拦
        }
    }

    /** 检查回复里的错误令牌：小写 #err = 帧头错误，大写 #ERR = 状态错误。 */
    private void checkStatErrors(String frame, String step) throws IOException {
        String err = findToken(frame, "#err");
        if (err != null) {
            throw fail(step, "打印机返回帧头错误 #err" + err);
        }
        String bigErr = findToken(frame, "#ERR");
        if (bigErr != null) {
            throw fail(step, "打印机报告状态错误 #ERR" + bigErr
                    + "（如 #ERRADF PE 表示自动进纸器没纸；平板扫描请确认盖好盖板）");
        }
    }

    /**
     * 发 PARA 参数：phase 1 只发 12 字节 PARA 头（reply_size=0），phase 2 发参数体
     * （reply_size=64）。两包连发，中间不等确认（协议如此）。等 #parOK 回复。
     */
    private void sendPara(byte[] para) throws IOException {
        String header = "PARAx" + String.format(java.util.Locale.US, "%07X", para.length);
        sendPassthru(ascii(header), 0);
        sendPassthru(para, 64);
        Packet r = readReply("PARA");
        Esci2Header h = parseEsci2Header(r.payload, "PARA");
        if (!"PARA".equals(h.name)) {
            throw fail("PARA", "回复的命令名是 " + h.name + "，期望 PARA");
        }
        String frame = new String(r.payload, StandardCharsets.US_ASCII);
        if (frame.contains("#parNG")) {
            throw fail("PARA", "打印机拒绝了扫描参数（#parNG）。"
                    + "可能原因：这台 L3150 不接受 ET-2750 的参数模板（分辨率/扫描窗口/伽马值）。"
                    + "请把这条报错原文发给开发者。打印机 INFO：" + shortInfo());
        }
        if (!frame.contains("#parOK")) {
            throw fail("PARA", "打印机未确认参数（既无 #parOK 也无 #parNG），回复原文："
                    + truncate(frame, 200));
        }
        Log.i(TAG, "PARA 已确认（#parOK），" + para.length + " 字节");
    }

    /** 发 TRDT（开始传输），收 64 字节回复（不同机型格式略有差异，不硬校验命令名）。 */
    private void sendTrdt() throws IOException {
        sendPassthru(ascii("TRDTx0000000"), 64);
        Packet r = readReply("TRDT");
        if (r.payload.length >= 12) {
            try {
                Esci2Header h = parseEsci2Header(r.payload, "TRDT");
                if (!"TRDT".equals(h.name)) {
                    Log.w(TAG, "TRDT 回复命令名是 " + h.name);
                }
            } catch (IOException ignored) {
                // TRDT 回复格式不标准也不硬拦，继续等 IMG
            }
        }
    }

    /**
     * IMG 循环：发 IMG 收 64 字节元数据，帧头声明的长度就是下一数据块大小，
     * 纯读取出数据块，拼起来就是一张 JPEG。#pen = 本页结束（平板单页）。
     * 零长度块且无 #pen 时重发 IMG，最多 5000 次。
     */
    private byte[] readImage() throws IOException {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(1024 * 1024);
        int zeroRetries = 0;
        for (;;) {
            sendPassthru(ascii("IMG x0000000"), 64);
            Packet meta = readReply("IMG");
            Esci2Header h = parseEsci2Header(meta.payload, "IMG");
            if (!"IMG".equals(h.name)) {
                throw fail("IMG", "回复的命令名是 " + h.name + "，期望 IMG");
            }
            String frame = new String(meta.payload, StandardCharsets.US_ASCII);
            checkStatErrors(frame, "IMG");
            String nrd = findToken(frame, "#nrd");
            if (nrd != null && (nrd.startsWith("RSVD") || nrd.startsWith("AFEN"))) {
                throw fail("IMG", "打印机报告 " + nrd + "，扫描中止");
            }
            boolean pen = frame.contains("#pen");
            int chunk = h.length;
            if (chunk == 0) {
                if (pen) break; // 零长度 + #pen = 本页结束
                if (++zeroRetries > MAX_ZERO_IMG_RETRIES) {
                    throw fail("IMG", "打印机连续 " + MAX_ZERO_IMG_RETRIES
                            + " 次返回空数据块且没有 #pen（扫描卡住了），请重启打印机再试");
                }
                continue; // 重新发 IMG
            }
            zeroRetries = 0;
            // 关键一步（2026-11-21 内网真机验证）：元数据声明了 chunk 字节之后，必须先发一个
            // pure-read（cmd_size=0，reply_size=chunk）把读请求告诉打印机，它才开始发数据。
            // 少了这一步，打印机会一直等，客户端只会等到 90 秒超时——这正是 v1.6 扫不出图的原因。
            sendPureRead(chunk);
            byte[] data = readChunk(chunk);
            jpeg.write(data, 0, data.length);
            if (pen) break;
        }
        if (jpeg.size() == 0) {
            throw fail("IMG", "扫描结束但一字节图像数据都没收到（打印机可能没放纸，或盖板没盖好）");
        }
        Log.i(TAG, "IMG 完成，收到 JPEG " + jpeg.size() + " 字节");
        return jpeg.toByteArray();
    }

    /**
     * 读一个数据块（chunk 字节）。pure-read 已经由调用方发过，这里只负责把数据收够。
     * 兼容 XP-7100 式"先回显 64 字节 IMG 头再给数据"：第一个包若正好是 64 字节且
     * 解析为声明长度与本块一致的 IMG 头，就跳过它继续读（不再重复发 pure-read）。
     */
    private byte[] readChunk(int chunk) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(chunk);
        boolean first = true;
        while (buf.size() < chunk) {
            Packet p = readReply("IMG");
            if (first && p.payload.length == 64 && buf.size() == 0) {
                try {
                    Esci2Header h = parseEsci2Header(p.payload, "IMG");
                    if ("IMG".equals(h.name) && h.length == chunk) {
                        first = false;
                        continue; // 只是重复回显的 IMG 头，跳过；pure-read 已经发过了
                    }
                } catch (IOException ignored) {
                    // 不是 IMG 头，按数据处理
                }
            }
            first = false;
            int need = chunk - buf.size();
            int take = Math.min(need, p.payload.length);
            buf.write(p.payload, 0, take);
        }
        return buf.toByteArray();
    }

    // ---- IS 帧读写 ----

    /**
     * 等一个回复：跳过 0xa000 空包（打印机"稍等"信号），处理 0x9000 异步事件，
     * 收到期望类型即返回，其他类型报错。
     */
    private Packet readReply(String step) throws IOException {
        return readReply(step, T_PASSTHRU_REPLY);
    }

    private Packet readReply(String step, int expectType) throws IOException {
        for (;;) {
            Packet p;
            try {
                p = readPacketRaw();
            } catch (java.net.SocketTimeoutException e) {
                throw fail(step, "等待打印机回应超时（90 秒无数据）。打印机可能卡住了，重启打印机再试");
            } catch (EOFException e) {
                throw fail(step, "打印机关闭了连接");
            }
            if (p.type == T_ASYNC) {
                handleAsync(p, step);
                continue;
            }
            if (p.type == T_PASSTHRU_REPLY && p.payload.length == 0) {
                continue; // 空包 = 打印机还没准备好，继续等
            }
            if (p.type == expectType) return p;
            throw fail(step, "等到意外的包类型 0x" + hex(p.type)
                    + "（期望 0x" + hex(expectType) + "）");
        }
    }

    /** 0x9000 异步事件：0x02 断开、0x80 超时、0xa0 服务器错误、0x03 取消都是致命的。 */
    private void handleAsync(Packet p, String step) throws IOException {
        int code = p.payload.length > 0 ? (p.payload[0] & 0xFF) : -1;
        switch (code) {
            case 0x02:
                throw fail(step, "打印机主动断开连接（异步事件 0x02 Disconnect）");
            case 0x80:
                throw fail(step, "打印机超时断开（异步事件 0x80 Timeout）");
            case 0xa0:
                throw fail(step, "打印机报告服务器错误（异步事件 0xa0 ServerError）");
            case 0x03:
                throw fail(step, "扫描被取消（异步事件 0x03 ScanCancel）");
            default:
                break; // 0x01 ScanStart、0x04 Stop 等忽略，继续等回复
        }
    }

    /** 读一个 IS 包（12 字节头 + 载荷）。 */
    private Packet readPacketRaw() throws IOException {
        byte[] head = new byte[12];
        readFully(head, 0, 12);
        if (head[0] != 'I' || head[1] != 'S') {
            throw fail("读数据", "收到非 IS 帧（头字节 0x" + hexByte(head[0])
                    + " 0x" + hexByte(head[1]) + "），数据流不同步");
        }
        int type = ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
        long size = ((long) (head[6] & 0xFF) << 24) | ((long) (head[7] & 0xFF) << 16)
                | ((long) (head[8] & 0xFF) << 8) | (head[9] & 0xFF);
        if (size < 0 || size > 64 * 1024 * 1024) {
            throw fail("读数据", "IS 包声明长度异常（" + size + " 字节）");
        }
        byte[] payload = new byte[(int) size];
        readFully(payload, 0, payload.length);
        return new Packet(type, payload);
    }

    private void readFully(byte[] b, int off, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int r = in.read(b, off + n, len - n);
            if (r == -1) throw new EOFException("打印机关闭了连接（读到一半断开）");
            n += r;
        }
    }

    /** 发一个 IS 包：12 字节头（"IS" + 类型 + 偏移 0x000C + 长度 + 2 字节填充）+ 载荷。 */
    private void sendIs(int type, byte[] payload) throws IOException {
        byte[] head = new byte[12];
        head[0] = 'I';
        head[1] = 'S';
        head[2] = (byte) ((type >> 8) & 0xFF);
        head[3] = (byte) (type & 0xFF);
        head[4] = 0x00;
        head[5] = 0x0C;
        int n = payload == null ? 0 : payload.length;
        head[6] = (byte) ((n >> 24) & 0xFF);
        head[7] = (byte) ((n >> 16) & 0xFF);
        head[8] = (byte) ((n >> 8) & 0xFF);
        head[9] = (byte) (n & 0xFF);
        head[10] = 0;
        head[11] = 0;
        try {
            out.write(head);
            if (n > 0) out.write(payload);
            out.flush();
        } catch (IOException e) {
            throw fail("发送", "写数据失败（" + e.getMessage() + "）");
        }
    }

    /** 发 passthru 包：8 字节头（命令长度 + 期望回复长度）+ 命令字节。 */
    private void sendPassthru(byte[] cmd, int replySize) throws IOException {
        byte[] body = new byte[8 + cmd.length];
        putBe32(body, 0, cmd.length);
        putBe32(body, 4, replySize);
        System.arraycopy(cmd, 0, body, 8, cmd.length);
        sendIs(T_PASSTHRU, body);
    }

    /** 纯读取回：不发命令，只取打印机已排队的 length 字节。 */
    private void sendPureRead(int length) throws IOException {
        byte[] body = new byte[8];
        putBe32(body, 0, 0);
        putBe32(body, 4, length);
        sendIs(T_PASSTHRU, body);
    }

    /** META 回复声明了正文长度时，用纯读取回正文（长度 0 时返回空数组）。 */
    private byte[] drainBody(String step, int length) throws IOException {
        if (length == 0) return new byte[0];
        if (length < 0 || length > 16 * 1024 * 1024) {
            throw fail(step, "META 回复声明的正文长度异常（" + length + " 字节）");
        }
        sendPureRead(length);
        return readData(step, length);
    }

    /** 读够 length 字节数据（跳过空包和异步事件；数据可能分多个 IS 包到达）。 */
    private byte[] readData(String step, int length) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(length);
        while (buf.size() < length) {
            Packet p = readReply(step);
            int need = length - buf.size();
            int take = Math.min(need, p.payload.length);
            buf.write(p.payload, 0, take);
        }
        return buf.toByteArray();
    }

    // ---- 帧头与令牌解析 ----

    private static Esci2Header parseEsci2Header(byte[] payload, String step) throws IOException {
        if (payload == null || payload.length < 12) {
            throw fail(step, "ESC/I-2 回复太短（"
                    + (payload == null ? 0 : payload.length) + " 字节，期望 64 字节帧）");
        }
        for (int i = 0; i < 4; i++) {
            int c = payload[i] & 0xFF;
            if (c < 0x20 || c > 0x7E) {
                throw fail(step, "ESC/I-2 回复格式不对（命令名含不可打印字符），原文："
                        + truncate(asciiSafe(payload), 80));
            }
        }
        String name = new String(payload, 0, 4, StandardCharsets.US_ASCII).trim();
        if (payload[4] != 'x') {
            throw fail(step, "ESC/I-2 回复格式不对（第 5 字节不是 'x'），原文："
                    + truncate(asciiSafe(payload), 80));
        }
        int len;
        try {
            len = Integer.parseInt(new String(payload, 5, 7, StandardCharsets.US_ASCII), 16);
        } catch (NumberFormatException e) {
            throw fail(step, "ESC/I-2 回复的长度字段不是十六进制，原文："
                    + truncate(asciiSafe(payload), 80));
        }
        return new Esci2Header(name, len);
    }

    /** 在帧文本里找 #KEY 令牌，返回其后到下一个 '#' 之前的内容（去空格），找不到返回 null。 */
    private static String findToken(String frame, String key) {
        if (frame == null) return null;
        int i = frame.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = frame.indexOf('#', start);
        if (end < 0) end = frame.length();
        String v = frame.substring(start, end).trim();
        return v;
    }

    // ---- PARA 参数 ----

    /**
     * 拼 PARA 参数体（ET-2750 实测模板，平板 A4，彩色 JPEG 质量 90）。
     * 布局：#FB(4) + 分辨率/颜色/格式(48) + #GMMUG18(8) + 伽马三元组(804)
     * + CMX(24) + #ACQ 扫描窗口(36) + #BSZ(12) = 936 字节。
     */
    private static byte[] buildPara(int dpi) throws IOException {
        int w = dpi >= 600 ? 4954 : 2477;
        int h = dpi >= 600 ? 7000 : 3500;
        String res = String.format(java.util.Locale.US, "i%07d", dpi);
        ByteArrayOutputStream b = new ByteArrayOutputStream(936);
        b.write(ascii("#FB "));
        b.write(ascii("#RSM" + res + "#RSS" + res + "#COLC024#FMTJPG #JPGd090"));
        b.write(ascii("#GMMUG18"));
        b.write(hexToBytes(GAMMA_HEX)); // 804 字节
        b.write(hexToBytes(CMX_HEX));   // 24 字节
        b.write(ascii(String.format(java.util.Locale.US,
                "#ACQi0000000i0000000i%07di%07d", w, h)));
        b.write(ascii("#BSZi1048576"));
        byte[] para = b.toByteArray();
        if (para.length != 936) {
            throw new IOException("ESC/I PARA 参数体长度异常（" + para.length
                    + " 字节，期望 936），内部错误");
        }
        return para;
    }

    /**
     * 打印机流式发来的 JPEG 没有结尾标记 EOI（FFD9），实测 300dpi 那张少了最后 2 字节。
     * 像素数据是完整的（用 Pillow 解码整页可读，最后几十行有内容），但标准解码器遇到
     * 缺 EOI 的流有概率直接报错，所以这里补上——补一个结束标记，不动任何像素。
     */
    private static byte[] ensureJpegEoi(byte[] jpeg) {
        if (jpeg.length >= 2 && (jpeg[jpeg.length - 2] & 0xFF) == 0xFF
                && (jpeg[jpeg.length - 1] & 0xFF) == 0xD9) {
            return jpeg;
        }
        byte[] out = new byte[jpeg.length + 2];
        System.arraycopy(jpeg, 0, out, 0, jpeg.length);
        out[jpeg.length] = (byte) 0xFF;
        out[jpeg.length + 1] = (byte) 0xD9;
        Log.i(TAG, "JPEG 缺 EOI，已补齐（" + jpeg.length + " -> " + out.length + " 字节）");
        return out;
    }

    /** 黑白模式：协议层面没有灰度输出，先按彩色扫，再本地转灰度压回 JPEG。 */
    private static byte[] toGrayscaleJpeg(byte[] jpeg) throws IOException {
        Bitmap src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (src == null) {
            throw new IOException("ESC/I 扫描返回的 JPEG 无法解码");
        }
        Bitmap gray = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(gray);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);
        src.recycle();
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length);
        if (!gray.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
            gray.recycle();
            throw new IOException("ESC/I 灰度转换失败（JPEG 压缩失败）");
        }
        gray.recycle();
        return out.toByteArray();
    }

    // ---- 小工具 ----

    /** ESC/I-2 12 字节命令头：名字空格补齐 4 字节 + "x0000000"。 */
    private static String padCmd(String name) {
        while (name.length() < 4) name += " ";
        return name + "x0000000";
    }

    private static IOException fail(String step, String detail) {
        return new IOException("ESC/I " + step + "失败：" + detail);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static void putBe32(byte[] b, int off, int v) {
        b[off] = (byte) ((v >> 24) & 0xFF);
        b[off + 1] = (byte) ((v >> 16) & 0xFF);
        b[off + 2] = (byte) ((v >> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    private static byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 字节数组转大写十六进制字符串（欢迎包记录用）。 */
    private static String hexDump(byte[] p) {
        if (p == null || p.length == 0) return "";
        StringBuilder sb = new StringBuilder(p.length * 2);
        for (byte b : p) {
            sb.append(String.format(java.util.Locale.US, "%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private static String hex(int v) {
        return String.format(java.util.Locale.US, "%04X", v & 0xFFFF);
    }

    private static String hexByte(int b) {
        return String.format(java.util.Locale.US, "%02X", b & 0xFF);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String asciiSafe(byte[] p) {
        StringBuilder sb = new StringBuilder();
        for (byte b : p) {
            int c = b & 0xFF;
            sb.append((c >= 0x20 && c < 0x7F) ? (char) c : '.');
        }
        return sb.toString();
    }

    private static String describePayload(byte[] p) {
        if (p == null || p.length == 0) return "空";
        if (p.length == 1) return "0x" + hexByte(p[0]);
        return "长度 " + p.length + " 字节，原文：" + truncate(asciiSafe(p), 120);
    }

    /** 报错时附带的打印机 INFO 摘要（可打印字符，截断到 160 字）。 */
    private String shortInfo() {
        if (infoBody == null || infoBody.isEmpty()) return "（未取到 INFO）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < infoBody.length() && sb.length() < 160; i++) {
            char c = infoBody.charAt(i);
            if (c >= 0x20 && c < 0x7F) sb.append(c);
        }
        return "（打印机 INFO：" + sb.toString() + "）";
    }
}
