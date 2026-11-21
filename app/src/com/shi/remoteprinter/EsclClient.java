package com.shi.remoteprinter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * eSCL / AirScan 客户端（Epson L3150/L3151 内网扫描）。
 * 全部用原生 HttpURLConnection 直连打印机，不走代理、不走云端：
 * 手机和打印机在同一 Wi-Fi 下才能用，这正是网页版做不到的事
 * （网页版的探测跑在云服务器上，连不到 192.168.x.x 内网地址）。
 */
public class EsclClient {
    private static final String TAG = "EsclClient";

    /** 探测：GET /eSCL/ScannerStatus，200 且含 ScannerStatus 即确认 */
    public static boolean probeEscl(String host, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + host + "/eSCL/ScannerStatus")
                    .openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code != 200) return false;
            String body = readString(c.getInputStream(), 8192);
            String lower = body.toLowerCase(java.util.Locale.US);
            return lower.contains("scannerstatus") || lower.contains("epson");
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 探测：GET / 打印机网页，含 EPSON 字样即确认是 Epson 打印机 */
    public static boolean probeWebPage(String host, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + host + "/")
                    .openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code != 200) return false;
            String body = readString(c.getInputStream(), 16384);
            return body.toLowerCase(java.util.Locale.US).contains("epson");
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 探测：TCP 端口是否打开（用于 631 IPP 等辅助判断） */
    public static boolean probePort(String host, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 查扫描仪状态，返回 Idle / Processing 等；连不上抛 IOException */
    public static String getStatus(String ip) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + ip + "/eSCL/ScannerStatus")
                    .openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("状态查询返回 HTTP " + code);
            String body = readString(c.getInputStream(), 8192);
            String state = extractTag(body, "State");
            return state == null ? "未知" : state;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 扫一页。返回 JPEG 字节；失败抛 IOException（信息会原样显示给用户，不假装成功）。
     * 流程：POST /eSCL/ScanJobs 创建任务（201 + Location 头）-> GET 任务/NextDocument 取图。
     */
    public static byte[] scanPage(String ip, int dpi, boolean color) throws IOException {
        String xml = buildScanJobXml(dpi, color);
        HttpURLConnection post = null;
        String location;
        try {
            post = (HttpURLConnection) new URL("http://" + ip + "/eSCL/ScanJobs")
                    .openConnection(Proxy.NO_PROXY);
            post.setConnectTimeout(10000);
            post.setReadTimeout(30000);
            post.setRequestMethod("POST");
            post.setDoOutput(true);
            post.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
            byte[] body = xml.getBytes(StandardCharsets.UTF_8);
            post.setRequestProperty("Content-Length", Integer.toString(body.length));
            OutputStream os = post.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
            int code = post.getResponseCode();
            if (code != 201 && code != 200) {
                String err = "";
                try {
                    InputStream es = post.getErrorStream();
                    if (es != null) err = readString(es, 2048);
                } catch (Exception ignored) {
                }
                throw new IOException("创建扫描任务失败，HTTP " + code + " " + err);
            }
            location = post.getHeaderField("Location");
            if (location == null || location.isEmpty()) {
                throw new IOException("打印机未返回扫描任务地址（Location 为空）");
            }
        } finally {
            if (post != null) post.disconnect();
        }

        String nextDoc;
        if (location.startsWith("http://") || location.startsWith("https://")) {
            nextDoc = location + "/NextDocument";
        } else {
            if (!location.startsWith("/")) location = "/" + location;
            nextDoc = "http://" + ip + location + "/NextDocument";
        }
        Log.i(TAG, "扫描任务: " + nextDoc);
        HttpURLConnection get = null;
        try {
            get = (HttpURLConnection) new URL(nextDoc).openConnection(Proxy.NO_PROXY);
            get.setConnectTimeout(10000);
            get.setReadTimeout(90000);
            get.setRequestMethod("GET");
            int code = get.getResponseCode();
            if (code == 404) {
                throw new IOException("扫描仪里没有纸，或本页无内容（HTTP 404）");
            }
            if (code != 200) {
                throw new IOException("取扫描结果失败，HTTP " + code);
            }
            return readBytes(get.getInputStream());
        } finally {
            if (get != null) get.disconnect();
        }
    }

    /** 把 JPEG 字节解成 Bitmap，失败返回 null */
    public static Bitmap decodeJpeg(byte[] data) {
        if (data == null || data.length == 0) return null;
        return BitmapFactory.decodeByteArray(data, 0, data.length);
    }

    /** A4 幅面（1/300 英寸单位：2480 x 3508），彩色 RGB24 / 黑白 Grayscale8 */
    private static String buildScanJobXml(int dpi, boolean color) {
        String colorMode = color ? "RGB24" : "Grayscale8";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<scan:ScanSettings"
                + " xmlns:scan=\"http://schemas.hp.com/imaging/escl/2011/05\""
                + " xmlns:escl=\"http://schemas.hp.com/imaging/escl/2011/05\""
                + " xmlns:pwg=\"http://www.pwg.org/schemas/2010/12/sm\">"
                + "<pwg:Version>2.6</pwg:Version>"
                + "<scan:Intent>Document</scan:Intent>"
                + "<scan:InputSource>Platen</scan:InputSource>"
                + "<scan:DocumentFormat>image/jpeg</scan:DocumentFormat>"
                + "<scan:ColorMode>" + colorMode + "</scan:ColorMode>"
                + "<scan:XResolution>" + dpi + "</scan:XResolution>"
                + "<scan:YResolution>" + dpi + "</scan:YResolution>"
                + "<pwg:ScanRegions><pwg:ScanRegion>"
                + "<pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>"
                + "<pwg:Height>3508</pwg:Height>"
                + "<pwg:Width>2480</pwg:Width>"
                + "<pwg:XOffset>0</pwg:XOffset>"
                + "<pwg:YOffset>0</pwg:YOffset>"
                + "</pwg:ScanRegion></pwg:ScanRegions>"
                + "</scan:ScanSettings>";
    }

    private static String extractTag(String xml, String tag) {
        String open = "<pwg:" + tag + ">";
        String close = "</pwg:" + tag + ">";
        int s = xml.indexOf(open);
        if (s < 0) {
            open = "<" + tag + ">";
            close = "</" + tag + ">";
            s = xml.indexOf(open);
            if (s < 0) return null;
        }
        int e = xml.indexOf(close, s + open.length());
        if (e < 0) return null;
        return xml.substring(s + open.length(), e).trim();
    }

    private static String readString(InputStream in, int maxBytes) throws IOException {
        byte[] data = readBytes(in, maxBytes);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream in) throws IOException {
        return readBytes(in, Integer.MAX_VALUE);
    }

    private static byte[] readBytes(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            if (total + n > maxBytes) {
                out.write(buf, 0, maxBytes - total);
                break;
            }
            out.write(buf, 0, n);
            total += n;
        }
        return out.toByteArray();
    }
}
