package com.shi.remoteprinter;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** 图片处理 / PDF 导出 / 保存与分享。全部原生实现，不依赖任何第三方库。 */
public class ImageUtils {
    private static final String TAG = "ImageUtils";

    /** A4 @72dpi 的点数：595 x 842 */
    public static final int A4_W_PT = 595;
    public static final int A4_H_PT = 842;

    /** 从 Uri 读图，最长边限制在 maxDim 内（防大图 OOM） */
    public static Bitmap loadBitmapFromUri(Context ctx, Uri uri, int maxDim) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in1 = cr.openInputStream(uri);
            BitmapFactory.decodeStream(in1, null, o);
            if (in1 != null) in1.close();
            int w = Math.max(1, o.outWidth);
            int h = Math.max(1, o.outHeight);
            int sample = 1;
            while (Math.max(w, h) / sample > maxDim) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
            InputStream in2 = cr.openInputStream(uri);
            Bitmap bm = BitmapFactory.decodeStream(in2, null, o2);
            if (in2 != null) in2.close();
            return bm;
        } catch (Exception e) {
            Log.w(TAG, "读图失败", e);
            return null;
        }
    }

    /**
     * 本地近似换底：从图片四条边取一个平均色，然后从边缘向里做一次"颜色相近"的扩散，
     * 把连成一片、且和边缘色接近的像素整体填成 fillColor。
     *
     * <p>这不是 AI 抠图，实话说明：背景干净（白墙、纯色背景布、单色纸）时效果很干净；
     * 背景杂乱、有家具/窗帘或有阴影渐变时，会在人像边缘留下残边。人像边缘不做羽化，
     * 追求的是"能用、好过不能用"。
     *
     * @param src       源图（不会被修改）
     * @param fillColor 目标底色，如 0xFFFFFFFF 白、0xFF438EDB 蓝、0xFFD9001B 红
     * @param tolerance 颜色容差，每个通道允许的差值，建议 28–48
     * @return 换底后的新图；内存不足等情况返回 null
     */
    public static Bitmap replaceBackground(Bitmap src, int fillColor, int tolerance) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;
        try {
            Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
            if (out == null) return null;
            int[] px = new int[w * h];
            out.getPixels(px, 0, w, 0, 0, w, h);
            // 真正的算法在 BgReplace 里（纯 int[]，不依赖 android，可桌面单测）
            int filled = BgReplace.fillFromEdges(px, w, h, fillColor, tolerance);
            out.setPixels(px, 0, w, 0, 0, w, h);
            Log.i(TAG, "换底完成：填掉 " + filled + " 个像素，底色 #"
                    + Integer.toHexString(fillColor));
            return out;
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "换底内存不足", e);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "换底失败", e);
            return null;
        }
    }

    /**
     * 去红笔：红通道明显高于绿、蓝的像素（红笔墨水）置白。
     * 黑色印刷字、黑色手写三角等不动——黑笔标记请用手动橡皮擦。
     */
    public static Bitmap removeRed(Bitmap src) {
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        int w = out.getWidth();
        int h = out.getHeight();
        int[] px = new int[w * h];
        out.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xff;
            int g = (p >> 8) & 0xff;
            int b = p & 0xff;
            if (r > 90 && r - g > 30 && r - b > 30) {
                px[i] = 0xFFFFFFFF;
            }
        }
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    /**
     * 提白 + 锐化：转灰度、纸面（亮像素）提白、对比度拉伸、3x3 锐化。
     * 和之前验证过的空白卷效果一致：黑字保留，纸变白、字更清晰。
     */
    public static Bitmap whitenSharpen(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int[] gray = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xff;
            int g = (p >> 8) & 0xff;
            int b = p & 0xff;
            int v = (r + g + b) / 3;
            // 纸面提白 + 对比度拉伸
            if (v > 195) v = 255;
            else v = (v - 35) * 255 / 170;
            if (v < 0) v = 0;
            if (v > 255) v = 255;
            gray[i] = v;
        }
        // 3x3 锐化：5*中心 - 上 - 下 - 左 - 右
        int[] sharp = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = gray[y * w + x];
                int t = y > 0 ? gray[(y - 1) * w + x] : c;
                int btm = y < h - 1 ? gray[(y + 1) * w + x] : c;
                int l = x > 0 ? gray[y * w + x - 1] : c;
                int r = x < w - 1 ? gray[y * w + x + 1] : c;
                int v = 5 * c - t - btm - l - r;
                if (v < 0) v = 0;
                if (v > 255) v = 255;
                sharp[y * w + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(sharp, 0, w, 0, 0, w, h);
        return out;
    }

    /**
     * 试卷清洗（去彩色笔迹 + 抹平阴影 + 提白 + 去噪点）。
     *
     * <p>比原来的 `removeRed` 强在三点：
     * <ol>
     *   <li>**背景归一化**：先把整张纸的亮度场估出来（分块均值 + 双线性插值），
     *       用"像素/背景"做归一化。纸面偏黄、有阴影、灯光不均时，阈值才不会失效；</li>
     *   <li>**多色去墨**：不只判红，而是按"与背景的色差 + 饱和度"判断彩笔
     *       （红、蓝、绿、荧光笔都能盖掉），黑色印刷字（低饱和、比背景暗很多）保留；</li>
     *   <li>**去噪点**：孤立的小黑点（铅笔屑、噪点）吃掉，避免纸面脏。</li>
     * </ol>
     *
     * <p>诚实说明：铅笔灰笔迹和印刷字在颜色/亮度上几乎一样，纯图像处理分不开，
     * 只能靠强度调高把浅灰抹掉（会伤到浅色印刷）。要真正区分手写/印刷得靠模型。
     *
     * @param strength 0 柔和 / 1 标准 / 2 强力
     */
    public static Bitmap cleanPaper(Bitmap src, int strength) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int[] out = PaperClean.clean(px, w, h, strength);
        Bitmap res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        res.setPixels(out, 0, w, 0, 0, w, h);
        return res;
    }
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
    /** 按宽高比居中裁剪（如 1 寸 25:35、2 寸 35:49） */
    public static Bitmap cropToRatio(Bitmap src, int rw, int rh) {
        return cropToRatioAround(src, rw, rh, 0.5f, 0.5f);
    }

    /**
     * 按宽高比裁剪，裁剪窗以 (cxFrac, cyFrac) 为中心（0-1 归一化），超界自动收回。
     * 供证件照 AI 指导用：按 AI 给的人像位置定裁剪中心。
     */
    public static Bitmap cropToRatioAround(Bitmap src, int rw, int rh,
                                           float cxFrac, float cyFrac) {
        int w = src.getWidth();
        int h = src.getHeight();
        float target = (float) rw / rh;
        float cur = (float) w / h;
        int nw;
        int nh;
        if (cur > target) {
            nh = h;
            nw = Math.round(h * target);
        } else {
            nw = w;
            nh = Math.round(w / target);
        }
        int cx = Math.round(cxFrac * w);
        int cy = Math.round(cyFrac * h);
        int x = Math.max(0, Math.min(w - nw, cx - nw / 2));
        int y = Math.max(0, Math.min(h - nh, cy - nh / 2));
        return Bitmap.createBitmap(src, x, y, nw, nh);
    }

    /**
     * 按 AI 给的手写区域擦除。
     * 红笔区：只擦红墨水像素（红通道明显高于绿、蓝）。
     * 黑笔区：擦深色像素，但保留“整行贯穿的横线”（印刷的填空横线、表格线），
     * 避免把题干的横线也擦掉；印刷字若在黑笔区内可能被一起擦掉，
     * 擦完请用“恢复原图”或橡皮擦旁的手动方式补救。
     */
    public static Bitmap eraseRegions(Bitmap src, List<android.graphics.Rect> redRegions,
                                      List<android.graphics.Rect> blackRegions) {
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        int w = out.getWidth();
        int h = out.getHeight();
        int[] px = new int[w * h];
        out.getPixels(px, 0, w, 0, 0, w, h);
        if (redRegions != null) {
            for (android.graphics.Rect r : redRegions) {
                int x0 = Math.max(0, r.left);
                int y0 = Math.max(0, r.top);
                int x1 = Math.min(w, r.right);
                int y1 = Math.min(h, r.bottom);
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int p = px[y * w + x];
                        int rr = (p >> 16) & 0xff;
                        int gg = (p >> 8) & 0xff;
                        int bb = p & 0xff;
                        if (rr > 90 && rr - gg > 30 && rr - bb > 30) {
                            px[y * w + x] = 0xFFFFFFFF;
                        }
                    }
                }
            }
        }
        if (blackRegions != null) {
            for (android.graphics.Rect r : blackRegions) {
                int x0 = Math.max(0, r.left);
                int y0 = Math.max(0, r.top);
                int x1 = Math.min(w, r.right);
                int y1 = Math.min(h, r.bottom);
                int rw = Math.max(1, x1 - x0);
                for (int y = y0; y < y1; y++) {
                    // 统计该行深色像素占比：整行贯穿就是印刷横线，整行保留
                    int dark = 0;
                    for (int x = x0; x < x1; x++) {
                        int p = px[y * w + x];
                        int v = (((p >> 16) & 0xff) + ((p >> 8) & 0xff)
                                + (p & 0xff)) / 3;
                        if (v < 120) dark++;
                    }
                    if (dark * 2 > rw) continue;
                    for (int x = x0; x < x1; x++) {
                        int p = px[y * w + x];
                        int v = (((p >> 16) & 0xff) + ((p >> 8) & 0xff)
                                + (p & 0xff)) / 3;
                        if (v < 120) px[y * w + x] = 0xFFFFFFFF;
                    }
                }
            }
        }
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    /**
     * 纯文字 A4 PDF：标题 + 正文行，自动换行、自动分页，存到 Download/宅家打印机。
     * 供 AI 组卷用（题目 + 答题空行 + 参考答案）。
     */
    public static Uri saveTextPdfToDownloads(Context ctx, String title,
                                             List<String> lines, String fileName) {
        PdfDocument doc = new PdfDocument();
        try {
            Paint titlePaint = new Paint();
            titlePaint.setColor(Color.BLACK);
            titlePaint.setTextSize(20);
            titlePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titlePaint.setAntiAlias(true);
            Paint bodyPaint = new Paint();
            bodyPaint.setColor(Color.BLACK);
            bodyPaint.setTextSize(13);
            bodyPaint.setAntiAlias(true);
            Paint smallPaint = new Paint();
            smallPaint.setColor(Color.DKGRAY);
            smallPaint.setTextSize(11);
            smallPaint.setAntiAlias(true);
            int margin = 44;
            int maxW = A4_W_PT - margin * 2;
            int pageNo = 1;
            PdfDocument.Page page = null;
            Canvas c = null;
            int y = 0;
            // 换行：按字符切，保证中文不断词
            java.util.ArrayList<String> wrapped = new java.util.ArrayList<String>();
            for (String line : lines) {
                if (line == null) line = "";
                if (line.isEmpty()) {
                    wrapped.add("");
                    continue;
                }
                StringBuilder cur = new StringBuilder();
                for (int i = 0; i < line.length(); i++) {
                    cur.append(line.charAt(i));
                    if (bodyPaint.measureText(cur.toString()) > maxW) {
                        cur.deleteCharAt(cur.length() - 1);
                        wrapped.add(cur.toString());
                        cur = new StringBuilder();
                        cur.append(line.charAt(i));
                    }
                }
                wrapped.add(cur.toString());
            }
            for (int i = 0; i < wrapped.size(); i++) {
                if (page == null || y > A4_H_PT - margin - 20) {
                    if (page != null) doc.finishPage(page);
                    PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                            A4_W_PT, A4_H_PT, pageNo).create();
                    page = doc.startPage(info);
                    c = page.getCanvas();
                    c.drawColor(Color.WHITE);
                    y = margin;
                    if (pageNo == 1) {
                        c.drawText(title, margin, y, titlePaint);
                        y += 34;
                        c.drawText("宅家打印机 · AI 组卷  "
                                        + new java.text.SimpleDateFormat("yyyy-MM-dd",
                                        java.util.Locale.getDefault())
                                        .format(new java.util.Date()),
                                margin, y, smallPaint);
                        y += 28;
                    }
                    pageNo++;
                }
                String line = wrapped.get(i);
                c.drawText(line, margin, y, bodyPaint);
                y += 20;
            }
            if (page != null) doc.finishPage(page);
            Uri uri = insertDownloadFile(ctx, fileName, "application/pdf");
            if (uri == null) return null;
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            doc.writeTo(os);
            if (os != null) os.close();
            return uri;
        } catch (Exception e) {
            Log.w(TAG, "存文字 PDF 失败", e);
            return null;
        } finally {
            doc.close();
        }
    }

    /** 写文本文件（供错题本 AI 分析 JSON） */
    public static boolean saveTextFile(File f, String text) {
        try {
            FileOutputStream os = new FileOutputStream(f);
            os.write(text.getBytes("UTF-8"));
            os.close();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "写文件失败", e);
            return false;
        }
    }

    /** 读文本文件，失败返回 null */
    public static String readTextFile(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
            in.close();
            return new String(os.toByteArray(), "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "读文件失败", e);
            return null;
        }
    }

    /** 等比缩放到不超过 maxW x maxH（不拉伸） */
    public static Bitmap fitBitmap(Bitmap src, int maxW, int maxH) {
        int w = src.getWidth();
        int h = src.getHeight();
        float s = Math.min((float) maxW / w, (float) maxH / h);
        if (s >= 1f) return src;
        return Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
    }

    /** 存 JPG 到系统相册 Pictures/宅家打印机，返回 content Uri */
    public static Uri saveJpegToPictures(Context ctx, Bitmap bm, String fileName) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/宅家打印机");
            Uri uri = ctx.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            bm.compress(Bitmap.CompressFormat.JPEG, 92, os);
            if (os != null) os.close();
            return uri;
        } catch (Exception e) {
            Log.w(TAG, "存 JPG 失败", e);
            return null;
        }
    }

    /**
     * 取一个可写入的“下载” Uri。API 29+ 用 MediaStore.Downloads；
     * 29 以下 MediaStore.Downloads 类不存在，改用 MediaStore.Files，
     * 避免在老手机上崩溃。
     */
    private static Uri insertDownloadFile(Context ctx, String fileName, String mimeType) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        v.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/宅家打印机");
            return ctx.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        }
        return ctx.getContentResolver().insert(
                MediaStore.Files.getContentUri("external"), v);
    }

    /**
     * 多张图拼成多页 A4 PDF，存到 Download/宅家打印机，返回 content Uri。
     * 每页一张图，等比放进 A4、留白边，不拉伸变形。
     */
    public static Uri savePdfToDownloads(Context ctx, List<Bitmap> pages, String fileName) {
        PdfDocument doc = new PdfDocument();
        try {
            int pageNo = 1;
            for (Bitmap bm : pages) {
                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                        A4_W_PT, A4_H_PT, pageNo).create();
                PdfDocument.Page page = doc.startPage(info);
                Canvas c = page.getCanvas();
                c.drawColor(Color.WHITE);
                int margin = 24;
                int availW = A4_W_PT - margin * 2;
                int availH = A4_H_PT - margin * 2;
                float s = Math.min((float) availW / bm.getWidth(),
                        (float) availH / bm.getHeight());
                int dw = Math.round(bm.getWidth() * s);
                int dh = Math.round(bm.getHeight() * s);
                int dx = margin + (availW - dw) / 2;
                int dy = margin + (availH - dh) / 2;
                c.drawBitmap(Bitmap.createScaledBitmap(bm, dw, dh, true), dx, dy, null);
                doc.finishPage(page);
                pageNo++;
            }
            Uri uri = insertDownloadFile(ctx, fileName, "application/pdf");
            if (uri == null) return null;
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            doc.writeTo(os);
            if (os != null) os.close();
            return uri;
        } catch (Exception e) {
            Log.w(TAG, "存 PDF 失败", e);
            return null;
        } finally {
            doc.close();
        }
    }

    /** 证件照 A4 排版：一页排 8 张（2 列 x 4 行），供打印裁剪 */
    public static Uri saveIdPhotoSheetPdf(Context ctx, Bitmap idPhoto, String fileName) {
        PdfDocument doc = new PdfDocument();
        try {
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                    A4_W_PT, A4_H_PT, 1).create();
            PdfDocument.Page page = doc.startPage(info);
            Canvas c = page.getCanvas();
            c.drawColor(Color.WHITE);
            Paint paint = new Paint();
            paint.setColor(Color.LTGRAY);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            int cols = 2;
            int rows = 4;
            int gap = 16;
            int margin = 36;
            int cellW = (A4_W_PT - margin * 2 - gap) / cols;
            int cellH = (A4_H_PT - margin * 2 - gap * (rows - 1)) / rows;
            float s = Math.min((float) cellW / idPhoto.getWidth(),
                    (float) cellH / idPhoto.getHeight());
            int dw = Math.round(idPhoto.getWidth() * s);
            int dh = Math.round(idPhoto.getHeight() * s);
            Bitmap small = Bitmap.createScaledBitmap(idPhoto, dw, dh, true);
            for (int r = 0; r < rows; r++) {
                for (int col = 0; col < cols; col++) {
                    int x = margin + col * (cellW + gap) + (cellW - dw) / 2;
                    int y = margin + r * (cellH + gap) + (cellH - dh) / 2;
                    c.drawBitmap(small, x, y, null);
                    c.drawRect(x, y, x + dw, y + dh, paint);
                }
            }
            doc.finishPage(page);
            Uri uri = insertDownloadFile(ctx, fileName, "application/pdf");
            if (uri == null) return null;
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            doc.writeTo(os);
            if (os != null) os.close();
            return uri;
        } catch (Exception e) {
            Log.w(TAG, "存证件照 PDF 失败", e);
            return null;
        } finally {
            doc.close();
        }
    }

    /**
     * 把一张图渲染成 A4 @360dpi 的 JPEG，供 ESC/P-R 内网直打使用。
     *
     * <p>300dpi 的 A4 位图是 35–50MB，手机上很容易 OOM，所以优先用 RGB_565（省一半），
     * 再退回 ARGB_8888；两次都失败就抛 OutOfMemoryError 让上层提示用户。
     *
     * @param src     源图（不会修改）
     * @param quality JPEG 质量，建议 85–90
     */
    public static byte[] renderA4Jpeg(Bitmap src, int quality) {
        if (src == null) return null;
        Bitmap page = null;
        try {
            page = Bitmap.createBitmap(EscprClient.A4_W, EscprClient.A4_H,
                    Bitmap.Config.RGB_565);
        } catch (OutOfMemoryError e) {
            try {
                page = Bitmap.createBitmap(EscprClient.A4_W, EscprClient.A4_H,
                        Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e2) {
                Log.w(TAG, "A4 画布申请失败", e2);
                return null;
            }
        }
        try {
            Canvas c = new Canvas(page);
            c.drawColor(Color.WHITE);
            float s = Math.min((float) EscprClient.A4_W / src.getWidth(),
                    (float) EscprClient.A4_H / src.getHeight());
            int dw = Math.max(1, Math.round(src.getWidth() * s));
            int dh = Math.max(1, Math.round(src.getHeight() * s));
            int dx = (EscprClient.A4_W - dw) / 2;
            int dy = (EscprClient.A4_H - dh) / 2;
            Paint p = new Paint();
            p.setFilterBitmap(true);
            c.drawBitmap(src, null, new Rect(dx, dy, dx + dw, dy + dh), p);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
            page.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "渲染 A4 失败", t);
            return null;
        } finally {
            if (page != null) page.recycle();
        }
    }
    /** 发邮件到打印机邮箱：系统分享 Intent，收件人预填，不经过 Gmail/Google 服务 */
    public static void shareToPrinter(Context ctx, Uri fileUri, String mimeType,
                                      String subject, String printerEmail) {
        Intent it = new Intent(Intent.ACTION_SEND);
        it.setType(mimeType);
        it.putExtra(Intent.EXTRA_EMAIL, new String[]{printerEmail});
        it.putExtra(Intent.EXTRA_SUBJECT, subject);
        it.putExtra(Intent.EXTRA_TEXT, "宅家打印机 APP 发送，请打印。");
        it.putExtra(Intent.EXTRA_STREAM, fileUri);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // 只有一个邮箱 App 时直接进它（少点一次）；有多个才弹选择器
        String sole = soleEmailPackage(ctx);
        if (sole != null) {
            Intent direct = new Intent(it);
            direct.setPackage(sole);
            try {
                ctx.startActivity(direct);
                return;
            } catch (Exception e) {
                Log.w(TAG, "直接拉起邮箱失败，退回选择器", e);
            }
        }
        ctx.startActivity(Intent.createChooser(it, "发到打印机（选邮箱 App）"));
    }

    /** 只有一个邮箱 App 时返回它的包名，0 个或多个返回 null。 */
    private static String soleEmailPackage(Context ctx) {
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
            List<ResolveInfo> list = ctx.getPackageManager().queryIntentActivities(i, 0);
            if (list != null && list.size() == 1) {
                return list.get(0).activityInfo.packageName;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 通用分享文件（不预填收件人，如发给家人） */
    public static void shareFile(Context ctx, Uri fileUri, String mimeType, String title) {
        Intent it = new Intent(Intent.ACTION_SEND);
        it.setType(mimeType);
        it.putExtra(Intent.EXTRA_STREAM, fileUri);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.startActivity(Intent.createChooser(it, title));
    }

    /**
     * 手机上有没有能收邮件的 App。
     * 邮件打印靠 ACTION_SEND + 预填收件人，如果系统里根本没有邮箱 App，
     * 分享面板只会列出微信/抖音这些，用户就永远打不出来——必须先问清楚。
     */
    public static boolean hasEmailApp(Context ctx) {
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
            List<ResolveInfo> list = ctx.getPackageManager()
                    .queryIntentActivities(i, 0);
            return list != null && !list.isEmpty();
        } catch (Exception e) {
            Log.w(TAG, "查邮箱 App 失败", e);
            return false;
        }
    }

    /** Epson iPrint 的包名（国际版/国内版/旧版都认一遍），没装返回 null。 */
    public static String iprintPackage(Context ctx) {
        String[] pkgs = {"com.epson.iprint", "jp.co.epson.iprint", "com.epson.iprint.cn"};
        for (String p : pkgs) {
            try {
                ctx.getPackageManager().getPackageInfo(p, 0);
                return p;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 把文件交给 Epson iPrint 打印。它用的是爱普生自家 ESC/P-R 编码器，
     * 所以能直接打照片/PDF —— 我们自己在 APP 里没法生成这个格式（试过，会卡住打印机）。
     */
    public static boolean shareToIPrint(Context ctx, Uri fileUri, String mimeType) {
        String pkg = iprintPackage(ctx);
        if (pkg == null) return false;
        Intent it = new Intent(Intent.ACTION_SEND);
        it.setType(mimeType);
        it.putExtra(Intent.EXTRA_STREAM, fileUri);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        it.setPackage(pkg);
        try {
            ctx.startActivity(it);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "拉起 iPrint 失败", e);
            return false;
        }
    }

    /** 错题本目录（应用专属外部存储） */
    public static File notebookDir(Context ctx) {
        File d = new File(ctx.getExternalFilesDir(null), "错题本");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 存一张图进错题本，返回文件 */
    public static File saveToNotebook(Context ctx, Bitmap bm, String fileName) {
        try {
            File f = new File(notebookDir(ctx), fileName);
            FileOutputStream os = new FileOutputStream(f);
            bm.compress(Bitmap.CompressFormat.JPEG, 92, os);
            os.close();
            return f;
        } catch (Exception e) {
            Log.w(TAG, "存错题本失败", e);
            return null;
        }
    }
}
