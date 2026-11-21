package com.shi.remoteprinter;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * 极简 SNMP v1 客户端：只为查打印机的累计页数，用来确认"刚才那一页到底打没打出来"。
 *
 * <p>9100 端口没有回执，ESC/P-R 作业发出去之后没有任何回话，所以只能靠打印机的
 * 页计数（`1.3.6.1.2.1.43.10.2.1.4.1.1`，Counter32）来判：发之前记一次、发之后等它 +1。
 */
public final class SnmpClient {

    /** 打印机累计打印页数 OID */
    public static final String OID_PAGE_COUNT = "1.3.6.1.2.1.43.10.2.1.4.1.1";
    /** hrPrinterStatus: 2=idle 3=printing 4=warmup 5=stopped */
    public static final String OID_PRINTER_STATUS = "1.3.6.1.2.1.25.3.5.1.1.1";

    private SnmpClient() {
    }

    /**
     * 读一个整数值；失败返回 -1。
     * 实现取巧但可靠：把整包里最后一个 INTEGER/Counter32/Gauge32 的值当成结果
     * （SNMP GET 回复里最后那个值就是被请求的值），不做完整 BER 树解析。
     */
    public static long getInt(String ip, String oid) {
        try {
            byte[] resp = request(ip, oid);
            if (resp == null) return -1;
            long value = -1;
            for (int i = 0; i + 2 < resp.length; i++) {
                int tag = resp[i] & 0xFF;
                if (tag != 0x41 && tag != 0x42 && tag != 0x02) continue;
                int len = resp[i + 1] & 0xFF;
                if (len == 0 || len > 4) continue;
                int p = i + 2;
                if (p + len > resp.length) continue;
                long v = 0;
                for (int k = 0; k < len; k++) v = (v << 8) | (resp[p + k] & 0xFF);
                value = v;
            }
            return value;
        } catch (Exception e) {
            return -1;
        }
    }
    /** 累计页数；失败返回 -1。 */
    public static long pageCount(String ip) {
        return getInt(ip, OID_PAGE_COUNT);
    }

    private static byte[] request(String ip, String oid) throws IOException {
        byte[] pkt = buildGet(oid);
        DatagramSocket s = new DatagramSocket();
        try {
            s.setSoTimeout(2500);
            s.send(new DatagramPacket(pkt, pkt.length,
                    InetAddress.getByName(ip), 161));
            byte[] buf = new byte[1500];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            s.receive(in);
            byte[] out = new byte[in.getLength()];
            System.arraycopy(in.getData(), 0, out, 0, in.getLength());
            return out;
        } finally {
            s.close();
        }
    }

    private static byte[] buildGet(String oid) {
        byte[] oidBytes = encodeOid(oid);
        byte[] varbind = tlv(0x30, concat(tlv(0x06, oidBytes), tlv(0x05, new byte[0])));
        byte[] varbinds = tlv(0x30, varbind);
        byte[] pdu = tlv(0xA0, concat(intBytes(1), intBytes(0), intBytes(0), varbinds));
        return tlv(0x30, concat(intBytes(0), tlv(0x04, "public".getBytes()), pdu));
    }

    private static byte[] encodeOid(String oid) {
        String[] parts = oid.split("\\.");
        int[] v = new int[parts.length];
        for (int i = 0; i < parts.length; i++) v[i] = Integer.parseInt(parts[i]);
        byte[] tmp = new byte[64];
        int n = 0;
        tmp[n++] = (byte) (v[0] * 40 + v[1]);
        for (int i = 2; i < v.length; i++) {
            int val = v[i];
            if (val < 0x80) {
                tmp[n++] = (byte) val;
            } else {
                int[] stack = new int[5];
                int sp = 0;
                stack[sp++] = val & 0x7F;
                val >>>= 7;
                while (val > 0) {
                    stack[sp++] = (val & 0x7F) | 0x80;
                    val >>>= 7;
                }
                while (sp > 0) tmp[n++] = (byte) stack[--sp];
            }
        }
        byte[] out = new byte[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    private static byte[] intBytes(int v) {
        return tlv(0x02, new byte[]{(byte) v});
    }

    private static byte[] tlv(int tag, byte[] payload) {
        byte[] len;
        int n = payload.length;
        if (n < 0x80) {
            len = new byte[]{(byte) n};
        } else if (n < 0x100) {
            len = new byte[]{(byte) 0x81, (byte) n};
        } else {
            len = new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
        }
        return concat(new byte[]{(byte) tag}, len, payload);
    }

    private static void skip(byte[] b, int[] pos) {
        if (pos[0] >= b.length) return;
        pos[0]++;                        // tag
        readLength(b, pos);
    }

    private static int readLength(byte[] b, int[] pos) {
        int l = b[pos[0]] & 0xFF;
        pos[0]++;
        if ((l & 0x80) != 0) {
            int count = l & 0x7F;
            l = 0;
            for (int i = 0; i < count && pos[0] < b.length; i++) {
                l = (l << 8) | (b[pos[0]] & 0xFF);
                pos[0]++;
            }
        }
        return l;
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
