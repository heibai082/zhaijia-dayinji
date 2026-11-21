package com.shi.remoteprinter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 SMTP 发信客户端：只为把打印文件发到打印机邮箱（Epson Connect Email Print）。
 *
 * <p>为什么要它：邮件打印本来是"跳到一个邮箱 App 里点发送"，家里人的手机不一定有邮箱
 * App，硬装一个也不合理。这里自己连 SMTP 把信发出去，用户就只点一下"打印"。
 *
 * <p>支持 465 隐式 TLS（QQ / 163 / 126 都是这种）与 587 STARTTLS；认证用 AUTH LOGIN，
 * 失败再试 AUTH PLAIN。不依赖任何第三方库，也不碰 Gmail / Google 服务。
 */
public class MailSender {

    public static class Config {
        public String host;
        public int port = 465;
        public boolean ssl = true;
        public String user;      // 登录账号，通常就是发件邮箱
        public String pass;      // QQ/163 这里填"授权码"，不是登录密码
        public String from;      // 发件人地址
        public String to;        // 打印机邮箱
        /** 明文连接时是否强制升级 TLS。默认 true（不允许明文传授权码）。 */
        public boolean requireTls = true;
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Socket socket;
    private BufferedReader in;
    private OutputStream out;

    /** 连上并完成 EHLO / STARTTLS / AUTH。 */
    private static MailSender open(Config c) throws IOException {
        String host = c.host.trim();
        int port = c.port <= 0 ? (c.ssl ? 465 : 587) : c.port;
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), 20000);
        raw.setSoTimeout(60000);

        MailSender s = new MailSender();
        Socket sock = raw;
        if (c.ssl) {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) f.createSocket(raw, host, port, true);
            ssl.startHandshake();
            sock = ssl;
        }
        s.attach(sock);
        s.expect("220");

        String ehlo = s.command("EHLO zhajiadayinji");
        if (!c.ssl) {
            if (!ehlo.toUpperCase(Locale.US).contains("STARTTLS")) {
                // 默认不接受明文传授权码；只有显式关掉 requireTls 才继续（本地测试/内网中继）
                if (c.requireTls) {
                    throw new IOException("服务器不支持 STARTTLS，请改用 465 端口（SSL）");
                }
            } else {
                s.command("STARTTLS");
                SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ssl = (SSLSocket) f.createSocket(s.socket, host, port, true);
                ssl.startHandshake();
                s.attach(ssl);
                s.expect("220");
                ehlo = s.command("EHLO zhajiadayinji");
            }
        }
        if (!ehlo.toUpperCase(Locale.US).contains("AUTH")) {
            throw new IOException("服务器没提供 AUTH 认证：" + ehlo);
        }
        s.auth(c.user, c.pass);
        return s;
    }

    private void attach(Socket sock) throws IOException {
        this.socket = sock;
        this.in = new BufferedReader(new InputStreamReader(sock.getInputStream(), UTF8));
        this.out = sock.getOutputStream();
    }

    /** 发一封带附件的邮件，成功返回服务器最后一句回复。 */
    public static String send(Config c, String subject, String body,
                              String fileName, String mimeType, byte[] data) throws IOException {
        if (c == null || c.host == null || c.host.trim().isEmpty()
                || c.user == null || c.pass == null) {
            throw new IOException("发信设置没填完整（服务器 / 账号 / 授权码）");
        }
        MailSender s = null;
        try {
            s = open(c);
            String from = (c.from == null || c.from.trim().isEmpty()) ? c.user : c.from.trim();
            s.command("MAIL FROM:<" + from + ">");
            s.command("RCPT TO:<" + c.to.trim() + ">");
            s.command("DATA");
            s.writeRaw(buildMessage(from, c.to.trim(), subject, body, fileName, mimeType, data));
            String accepted = s.command(".");
            if (!accepted.startsWith("250")) {
                throw new IOException("服务器没有接收这封信：" + accepted);
            }
            try {
                s.command("QUIT");
            } catch (IOException ignored) {
            }
            return accepted;
        } finally {
            if (s != null) s.close();
        }
    }

    /** 只测连接 + 认证，不发信（不浪费纸）。 */
    public static String testLogin(Config c) throws IOException {
        if (c == null || c.user == null || c.pass == null || c.host == null) {
            throw new IOException("先把服务器、账号、授权码填上");
        }
        MailSender s = null;
        try {
            s = open(c);
            try {
                s.command("QUIT");
            } catch (IOException ignored) {
            }
            return "连接和认证都通过（没有发信，不会出纸）";
        } finally {
            if (s != null) s.close();
        }
    }

    private void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    // ---- SMTP 细节 ----

    private void auth(String user, String pass) throws IOException {
        String r = command("AUTH LOGIN");
        if (r.startsWith("334")) {
            r = command(b64(user.getBytes(UTF8)));
            if (!r.startsWith("334")) throw new IOException("用户名被拒：" + r);
            r = command(b64(pass.getBytes(UTF8)));
            if (!r.startsWith("235")) throw new IOException("授权码被拒：" + r);
            return;
        }
        // 退回 AUTH PLAIN
        byte[] raw = ("\0" + user + "\0" + pass).getBytes(UTF8);
        r = command("AUTH PLAIN " + b64(raw));
        if (!r.startsWith("235")) throw new IOException("认证失败：" + r);
    }

    private String command(String line) throws IOException {
        writeRaw(line + "\r\n");
        return readResponse();
    }

    private void writeRaw(String s) throws IOException {
        out.write(s.getBytes(UTF8));
        out.flush();
    }

    private void expect(String code) throws IOException {
        String r = readResponse();
        if (!r.startsWith(code)) throw new IOException("服务器回了：" + r);
    }

    /** 读一个 SMTP 回复（多行 "250-..." 一直读到 "250 ..."）。 */
    private String readResponse() throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            sb.append(line);
            if (line.length() < 4 || line.charAt(3) != '-') break;
            sb.append(" | ");
        }
        if (line == null) throw new IOException("服务器断开了连接");
        return sb.toString();
    }

    // ---- 报文组装 ----

    private static String buildMessage(String from, String to, String subject, String body,
                                       String fileName, String mimeType, byte[] data) {
        String boundary = "----zhajiadayinji" + System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("From: <").append(from).append(">\r\n");
        sb.append("To: <").append(to).append(">\r\n");
        sb.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
        sb.append("Date: ").append(new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z",
                Locale.US).format(new Date())).append("\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n");
        sb.append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        sb.append(chunk(b64(body.getBytes(UTF8))));
        sb.append("\r\n");
        if (data != null && data.length > 0) {
            String name = asciiName(fileName);
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Type: ").append(mimeType == null
                    ? "application/octet-stream" : mimeType)
                    .append("; name=\"").append(name).append("\"\r\n");
            sb.append("Content-Transfer-Encoding: base64\r\n");
            sb.append("Content-Disposition: attachment; filename=\"").append(name)
                    .append("\"\r\n\r\n");
            sb.append(chunk(b64(data)));
            sb.append("\r\n");
        }
        sb.append("--").append(boundary).append("--\r\n");
        return sb.toString().replace("\r\n.", "\r\n..");   // SMTP 的点转义
    }

    /** 附件名只留 ASCII 安全字符：中文文件名会让部分邮件服务器打回。 */
    private static String asciiName(String name) {
        if (name == null) return "print.jpg";
        String ext = ".jpg";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String e = name.substring(dot + 1);
            if (e.matches("[A-Za-z0-9]{1,5}")) ext = "." + e.toLowerCase(Locale.US);
        }
        StringBuilder sb = new StringBuilder();
        String base = dot > 0 ? name.substring(0, dot) : name;
        for (int i = 0; i < base.length(); i++) {
            char ch = base.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_';
            if (ok) sb.append(ch);
        }
        String s = sb.toString();
        // 纯中文文件名会被清空，用一个固定名，避免出现 ".jpg" 这种怪名字
        if (s.trim().isEmpty()) s = "print-" + (System.currentTimeMillis() / 1000);
        return s + ext;
    }

    private static String encodeHeader(String s) {
        if (s == null) return "";
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return "=?UTF-8?B?" + b64(s.getBytes(UTF8)) + "?=";
            }
        }
        return s;
    }

    private static String chunk(String s) {
        StringBuilder sb = new StringBuilder(s.length() + s.length() / 76 * 2 + 4);
        int i = 0;
        while (i < s.length()) {
            int end = Math.min(i + 76, s.length());
            sb.append(s, i, end).append("\r\n");
            i = end;
        }
        return sb.toString();
    }

    private static String b64(byte[] data) {
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
    }
}
