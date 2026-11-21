package com.shi.remoteprinter;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * DeepSeek V4.1 Flash 客户端（OpenAI Chat Completions 兼容协议）。
 *
 * - 请求只发到用户在设置里填的 API 地址（默认用户自己的中转地址），不发到别处。
 * - API 密钥只存在手机本地 SharedPreferences，绝不写进代码 / APK。
 * - 所有请求 60 秒超时、强制不走代理（Proxy.NO_PROXY），失败返回人话错误。
 * - 图片先压缩到最长边 1280、JPEG 质量 80 再转 base64，避免请求过大。
 * - 零第三方库：只用 Android 自带的 HttpURLConnection + org.json。
 */
public class AiClient {
    private static final String TAG = "AiClient";

    /** 用户自己的中转地址（默认预填，用户可在设置里改） */
    /** 默认留空：发行版不内置任何人的中转地址，用户自己填自己的 API 地址 */
    public static final String DEFAULT_BASE_URL = "";
    public static final String DEFAULT_MODEL = "deepseek-v4.1-flash";
    private static final int TIMEOUT_MS = 60000;
    private static final int MAX_RESPONSE_CHARS = 400000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onOk(String content);
        void onError(String friendlyError);
    }

    /** 一条对话消息；user 消息可附一张图 */
    public static class Msg {
        public final String role; // system / user / assistant
        public final String text;
        public final Bitmap image;

        public Msg(String role, String text) {
            this(role, text, null);
        }

        public Msg(String role, String text, Bitmap image) {
            this.role = role;
            this.text = text;
            this.image = image;
        }
    }

    /** 非 2xx 响应 */
    public static class AiHttpException extends Exception {
        public final int code;

        public AiHttpException(int code, String body) {
            super("HTTP " + code + ": " + body);
            this.code = code;
        }
    }

    // ---------- 配置（本地 SharedPreferences） ----------
    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("printer", Context.MODE_PRIVATE);
    }

    public static String getBaseUrl(Context ctx) {
        String v = prefs(ctx).getString("aiBaseUrl", DEFAULT_BASE_URL);
        if (v == null || v.trim().isEmpty()) v = DEFAULT_BASE_URL;
        v = v.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    public static String getModel(Context ctx) {
        String v = prefs(ctx).getString("aiModel", DEFAULT_MODEL);
        if (v == null || v.trim().isEmpty()) v = DEFAULT_MODEL;
        return v.trim();
    }

    public static String getKey(Context ctx) {
        String v = prefs(ctx).getString("aiKey", "");
        return v == null ? "" : v.trim();
    }

    public static boolean hasKey(Context ctx) {
        return !getKey(ctx).isEmpty();
    }

    // ---------- 图片压缩 + base64 ----------
    /** 最长边压到 1280、JPEG 质量 80，转 data URL */
    public static String imageToDataUrl(Bitmap bm) {
        int w = bm.getWidth();
        int h = bm.getHeight();
        float s = Math.min(1f, 1280f / Math.max(w, h));
        Bitmap small = s < 1f
                ? Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(w * s)),
                        Math.max(1, Math.round(h * s)), true)
                : bm;
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 80, os);
        String b64 = Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
        return "data:image/jpeg;base64," + b64;
    }

    // ---------- 对外入口 ----------
    public static void chatText(Context ctx, String system, String userText,
                                Callback cb) {
        java.util.ArrayList<Msg> msgs = new java.util.ArrayList<Msg>();
        if (system != null && !system.isEmpty()) msgs.add(new Msg("system", system));
        msgs.add(new Msg("user", userText));
        chat(ctx, msgs, cb);
    }

    public static void chatWithImage(Context ctx, String system, String userText,
                                     Bitmap image, Callback cb) {
        java.util.ArrayList<Msg> msgs = new java.util.ArrayList<Msg>();
        if (system != null && !system.isEmpty()) msgs.add(new Msg("system", system));
        msgs.add(new Msg("user", userText, image));
        chat(ctx, msgs, cb);
    }

    /** 多轮对话：messages 按顺序传，调用方自己维护历史 */
    public static void chat(final Context ctx, final List<Msg> messages,
                            final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String key = getKey(ctx);
                    if (key.isEmpty()) {
                        throw new Exception("NO_KEY");
                    }
                    String body = buildBody(getModel(ctx), messages);
                    String resp = postJson(getBaseUrl(ctx) + "/chat/completions",
                            key, body);
                    final String content = extractContent(resp);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onOk(content);
                        }
                    });
                } catch (final Exception e) {
                    final String msg = "NO_KEY".equals(e.getMessage())
                            ? "还没填 API 密钥，请到“设置 → AI 设置”里填"
                            : friendlyError(e);
                    Log.w(TAG, "AI 请求失败", e);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onError(msg);
                        }
                    });
                }
            }
        }).start();
    }

    /** 测试连接：用最小请求问模型一个字，成功返回带耗时的提示，失败返回人话原因 */
    public static void testConnection(final Context ctx, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long start = System.currentTimeMillis();
                try {
                    String key = getKey(ctx);
                    if (key.isEmpty()) {
                        throw new Exception("NO_KEY");
                    }
                    String body = "{\"model\":\"" + esc(getModel(ctx)) + "\","
                            + "\"messages\":[{\"role\":\"user\",\"content\":\"请只回复一个字：好\"}],"
                            + "\"max_tokens\":5}";
                    // 模型/中转偶发返回空内容，重试一次
                    String content = null;
                    Exception lastErr = null;
                    for (int attempt = 0; attempt < 2 && content == null; attempt++) {
                        try {
                            String resp = postJson(getBaseUrl(ctx) + "/chat/completions",
                                    key, body);
                            content = extractContent(resp);
                        } catch (Exception e) {
                            lastErr = e;
                            if (!"AI 返回了空内容".equals(e.getMessage())) break;
                        }
                    }
                    if (content == null) throw lastErr;
                    final String reply = content;
                    final double secs = (System.currentTimeMillis() - start) / 1000.0;
                    final String ok = "连接成功！模型 " + getModel(ctx) + " 已响应，用时 "
                            + String.format(java.util.Locale.US, "%.1f", secs)
                            + " 秒，回复：" + reply.trim();
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onOk(ok);
                        }
                    });
                } catch (final Exception e) {
                    final String msg = "NO_KEY".equals(e.getMessage())
                            ? "还没填 API 密钥，请先在上方填好密钥再测试"
                            : friendlyError(e);
                    Log.w(TAG, "AI 连接测试失败", e);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onError(msg);
                        }
                    });
                }
            }
        }).start();
    }

    // ---------- 请求体 ----------
    private static String buildBody(String model, List<Msg> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(esc(model)).append("\",\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            Msg m = messages.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"role\":\"").append(esc(m.role)).append("\",\"content\":");
            if (m.image != null) {
                sb.append("[{\"type\":\"text\",\"text\":\"").append(esc(m.text)).append("\"},");
                sb.append("{\"type\":\"image_url\",\"image_url\":{\"url\":\"")
                        .append(imageToDataUrl(m.image)).append("\"}}]");
            } else {
                sb.append("\"").append(esc(m.text)).append("\"");
            }
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------- HTTP ----------
    private static String postJson(String urlStr, String key, String body)
            throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(in);
            if (code < 200 || code >= 300) {
                throw new AiHttpException(code, resp);
            }
            return resp;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) != -1) {
            sb.append(buf, 0, n);
            if (sb.length() > MAX_RESPONSE_CHARS) break;
        }
        r.close();
        return sb.toString();
    }

    // ---------- 响应解析 ----------
    /** 取 choices[0].message.content */
    public static String extractContent(String responseJson) throws Exception {
        JSONObject root = new JSONObject(responseJson);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("AI 没有返回内容");
        }
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        if (msg == null) throw new Exception("AI 返回格式异常");
        String content = msg.optString("content", "");
        if (content == null || content.trim().isEmpty()) {
            // 个别模型把正文放在 reasoning_content 里，退而取之
            content = msg.optString("reasoning_content", "");
        }
        if (content == null || content.trim().isEmpty()) {
            throw new Exception("AI 返回了空内容");
        }
        return content;
    }

    /** 从模型返回的文本里抠出第一个 {...} JSON 对象；找不到返回 null */
    public static String extractJsonObject(String content) {
        if (content == null) return null;
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return content.substring(start, end + 1);
    }

    /** 异常转人话 */
    public static String friendlyError(Exception e) {
        if (e instanceof AiHttpException) {
            int code = ((AiHttpException) e).code;
            if (code == 401) return "API 密钥不对或已过期，请到“设置 → AI 设置”里检查密钥";
            if (code == 404) return "模型或地址不对，请检查“设置 → AI 设置”里的 API 地址和模型名";
            if (code == 429) return "AI 服务说请求太频繁了，等一会儿再试";
            if (code == 400) return "AI 服务说请求有问题（400），可能是图片太大或模型不支持看图";
            if (code >= 500) return "AI 服务那边出了问题（" + code + "），稍后再试";
            return "AI 服务返回错误（HTTP " + code + "）";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "请求超时了（60 秒没回音），请检查网络后重试";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "连不上 AI 服务地址，请检查网络；APP 不走代理，直连你填的地址";
        }
        if (e instanceof java.io.IOException) {
            return "网络出错，连不上 AI 服务：" + e.getMessage();
        }
        String m = e.getMessage();
        return "AI 请求失败：" + (m == null ? e.toString() : m);
    }
}
