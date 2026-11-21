package com.shi.remoteprinter;

import android.graphics.Bitmap;
import android.util.Log;

/**
 * 证件照自动换底（真抠图，不是"从四边扩散颜色"）。
 *
 * <p>算法（2026-11-21 在用户真实人像照片上调通，Python 原型见
 * `experiments/idphoto/seg_proto.py`）：
 * <ol>
 *   <li>缩到 ≤520px 做分割，最后把 alpha 放大回原图再合成，省内存也更快；</li>
 *   <li>采样两类颜色：背景取上/左/右三边（底部是肩膀，不采），前景取中央区域的
 *       暗部（头发/衣服）与肤色；各自用 k-means（k=5）建颜色模型；</li>
 *   <li>每个像素算"到背景模型的距离 − 到前景模型的距离"，再叠加一个"离边缘越远越像人"
 *       的空间先验，得到 score；</li>
 *   <li>先按 score 阈值取前景，再**从四边做背景洪水**：凡是能从边缘连通过去、且
 *       score 偏背景的像素一律归背景——这一步专治"被阴影压暗的墙"，
 *       而衣服里的白色包边因为被包住、连不到边缘，会被保住；</li>
 *   <li>形态学闭/开运算去毛刺补洞，只留最大连通块（人），最后羽化边缘并合成新底色。</li>
 * </ol>
 *
 * <p>局限（诚实版）：纯色/浅色墙、光线均匀时效果最好；复杂背景（窗帘、书柜、强阴影）
 * 仍会留残边，那时退回手动「手指涂背景」补。
 */
public final class PortraitCut {

    private static final String TAG = "PortraitCut";
    private static final int WORK_MAX = 520;
    private static final int K = 5;
    private static final int KMEANS_ITERS = 8;

    private PortraitCut() {
    }

    /** 自动换底；失败返回 null（调用方退回原图或手动模式）。 */
    public static Bitmap replaceBackground(Bitmap src, int targetColor) {
        if (src == null) return null;
        try {
            int sw = src.getWidth(), sh = src.getHeight();
            if (sw < 40 || sh < 40) return null;

            // 1) 缩小工作图
            float scale = Math.min(1f, (float) WORK_MAX / Math.max(sw, sh));
            int w = Math.max(40, Math.round(sw * scale));
            int h = Math.max(40, Math.round(sh * scale));
            Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
            int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            if (small != src) small.recycle();

            int[] rgb = new int[w * h];
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                rgb[i] = (((p >> 16) & 0xff) << 16) | (((p >> 8) & 0xff) << 8) | (p & 0xff);
            }

            // 2) 采样前景/背景像素
            int bw = Math.max(2, (int) (w * 0.06));
            int bh = Math.max(2, (int) (h * 0.06));
            int[] bgSamples = new int[w * h];
            int bgN = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    boolean top = y < bh;
                    boolean side = (x < bw || x >= w - bw) && y < h * 0.80;
                    if (top || side) bgSamples[bgN++] = rgb[y * w + x];
                }
            }
            int[] fgSamples = new int[w * h];
            int fgN = 0;
            int cy0 = (int) (h * 0.28), cy1 = (int) (h * 0.95);
            int cx0 = (int) (w * 0.18), cx1 = (int) (w * 0.82);
            for (int y = cy0; y < cy1; y++) {
                for (int x = cx0; x < cx1; x++) {
                    int p = rgb[y * w + x];
                    int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                    boolean dark = r + g + b < 330;
                    boolean skin = r > 95 && g > 55 && b > 40 && r > g + 12
                            && g > b - 5 && r < 250;
                    boolean isBorder = y < bh
                            || ((x < bw || x >= w - bw) && y < h * 0.80);
                    if ((dark || skin) && !isBorder) fgSamples[fgN++] = p;
                }
            }
            if (bgN < 50 || fgN < 50) {
                Log.w(TAG, "样本太少 bg=" + bgN + " fg=" + fgN);
                return null;
            }

            float[][] bgC = kmeans(bgSamples, bgN, K);
            float[][] fgC = kmeans(fgSamples, fgN, K);
            if (bgC.length == 0 || fgC.length == 0) return null;

            // 3) score
            float[] score = new float[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    float db = minDist(rgb[i], bgC);
                    float df = minDist(rgb[i], fgC);
                    int edge = Math.min(Math.min(y, h - 1 - y), Math.min(x, w - 1 - x));
                    float prior = Math.min(1.4f, edge / (0.10f * Math.min(w, h)));
                    score[i] = (db - df) / 40f + (prior - 0.7f) * 0.9f;
                }
            }

            // 4) 背景洪水（从四边）→ 吃掉与人相连但明显是墙的区域
            boolean[] flood = new boolean[w * h];
            int[] queue = new int[w * h];
            int head = 0, tail = 0;
            for (int x = 0; x < w; x++) {
                tail = push(flood, queue, tail, x, w, score, 1.20f);
                tail = push(flood, queue, tail, (h - 1) * w + x, w, score, 1.20f);
            }
            for (int y = 0; y < h; y++) {
                tail = push(flood, queue, tail, y * w, w, score, 1.20f);
                tail = push(flood, queue, tail, y * w + w - 1, w, score, 1.20f);
            }
            while (head < tail) {
                int idx = queue[head++];
                int x = idx % w;
                int y = idx / w;
                if (x > 0) tail = push(flood, queue, tail, idx - 1, w, score, 1.20f);
                if (x < w - 1) tail = push(flood, queue, tail, idx + 1, w, score, 1.20f);
                if (y > 0) tail = push(flood, queue, tail, idx - w, w, score, 1.20f);
                if (y < h - 1) tail = push(flood, queue, tail, idx + w, w, score, 1.20f);
            }

            boolean[] mask = new boolean[w * h];
            for (int i = 0; i < mask.length; i++) mask[i] = score[i] > 0.15f && !flood[i];

            // 5) 形态学：闭(4) → 开(2)
            mask = dilate(mask, w, h, 4);
            mask = erode(mask, w, h, 4);
            mask = erode(mask, w, h, 2);
            mask = dilate(mask, w, h, 2);

            mask = largestComponent(mask, w, h);

            // 6) 羽化（两次盒模糊近似高斯）
            float[] alpha = new float[w * h];
            for (int i = 0; i < alpha.length; i++) alpha[i] = mask[i] ? 1f : 0f;
            alpha = boxBlur(alpha, w, h, 2);
            alpha = boxBlur(alpha, w, h, 1);

            return composite(src, alpha, w, h, targetColor);
        } catch (Throwable t) {
            Log.w(TAG, "自动抠图失败", t);
            return null;
        }
    }

    // ---------------- 各步骤实现 ----------------

    private static int push(boolean[] flood, int[] queue, int tail, int idx, int w,
                            float[] score, float th) {
        if (flood[idx] || idx < 0 || idx >= flood.length) return tail;
        if (score[idx] >= th) return tail;
        flood[idx] = true;
        queue[tail++] = idx;
        return tail;
    }

    private static float[][] kmeans(int[] samples, int n, int k) {
        if (n <= 0) return new float[0][];
        int kk = Math.min(k, n);
        float[][] c = new float[kk][3];
        for (int i = 0; i < kk; i++) {
            int p = samples[(int) ((long) i * (n - 1) / Math.max(1, kk - 1))];
            c[i][0] = (p >> 16) & 0xff;
            c[i][1] = (p >> 8) & 0xff;
            c[i][2] = p & 0xff;
        }
        for (int it = 0; it < KMEANS_ITERS; it++) {
            double[][] sum = new double[kk][3];
            int[] cnt = new int[kk];
            for (int i = 0; i < n; i++) {
                int p = samples[i];
                float r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                int best = 0;
                float bestD = Float.MAX_VALUE;
                for (int j = 0; j < kk; j++) {
                    float dr = r - c[j][0], dg = g - c[j][1], db = b - c[j][2];
                    float d = dr * dr + dg * dg + db * db;
                    if (d < bestD) {
                        bestD = d;
                        best = j;
                    }
                }
                sum[best][0] += r;
                sum[best][1] += g;
                sum[best][2] += b;
                cnt[best]++;
            }
            for (int j = 0; j < kk; j++) {
                if (cnt[j] == 0) continue;
                c[j][0] = (float) (sum[j][0] / cnt[j]);
                c[j][1] = (float) (sum[j][1] / cnt[j]);
                c[j][2] = (float) (sum[j][2] / cnt[j]);
            }
        }
        return c;
    }

    private static float minDist(int p, float[][] centers) {
        float r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        float best = Float.MAX_VALUE;
        for (float[] c : centers) {
            float dr = r - c[0], dg = g - c[1], db = b - c[2];
            float d = dr * dr + dg * dg + db * db;
            if (d < best) best = d;
        }
        return (float) Math.sqrt(best);
    }

    private static boolean[] dilate(boolean[] m, int w, int h, int r) {
        boolean[] out = new boolean[m.length];
        // 横向
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!m[y * w + x]) continue;
                int x0 = Math.max(0, x - r), x1 = Math.min(w - 1, x + r);
                for (int xx = x0; xx <= x1; xx++) out[y * w + xx] = true;
            }
        }
        // 纵向
        boolean[] out2 = new boolean[m.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!out[y * w + x]) continue;
                int y0 = Math.max(0, y - r), y1 = Math.min(h - 1, y + r);
                for (int yy = y0; yy <= y1; yy++) out2[yy * w + x] = true;
            }
        }
        return out2;
    }

    private static boolean[] erode(boolean[] m, int w, int h, int r) {
        boolean[] inv = new boolean[m.length];
        for (int i = 0; i < m.length; i++) inv[i] = !m[i];
        boolean[] d = dilate(inv, w, h, r);
        for (int i = 0; i < d.length; i++) d[i] = !d[i];
        return d;
    }

    /** 只保留最大的连通块（人像必然是最大那块）。 */
    private static boolean[] largestComponent(boolean[] m, int w, int h) {
        boolean[] seen = new boolean[m.length];
        int[] stack = new int[m.length];
        boolean[] best = new boolean[m.length];
        int bestSize = 0;
        for (int start = 0; start < m.length; start++) {
            if (!m[start] || seen[start]) continue;
            int sp = 0;
            stack[sp++] = start;
            seen[start] = true;
            int size = 0;
            int[] comp = new int[m.length];
            while (sp > 0) {
                int idx = stack[--sp];
                comp[size++] = idx;
                int x = idx % w, y = idx / w;
                if (x > 0 && m[idx - 1] && !seen[idx - 1]) {
                    seen[idx - 1] = true;
                    stack[sp++] = idx - 1;
                }
                if (x < w - 1 && m[idx + 1] && !seen[idx + 1]) {
                    seen[idx + 1] = true;
                    stack[sp++] = idx + 1;
                }
                if (y > 0 && m[idx - w] && !seen[idx - w]) {
                    seen[idx - w] = true;
                    stack[sp++] = idx - w;
                }
                if (y < h - 1 && m[idx + w] && !seen[idx + w]) {
                    seen[idx + w] = true;
                    stack[sp++] = idx + w;
                }
            }
            if (size > bestSize) {
                bestSize = size;
                best = new boolean[m.length];
                for (int i = 0; i < size; i++) best[comp[i]] = true;
            }
        }
        return bestSize == 0 ? m : best;
    }

    private static float[] boxBlur(float[] a, int w, int h, int r) {
        float[] tmp = new float[a.length];
        float[] out = new float[a.length];
        int win = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int n = 0;
                for (int dx = -r; dx <= r; dx++) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= w) continue;
                    s += a[y * w + xx];
                    n++;
                }
                tmp[y * w + x] = s / Math.max(1, n);
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int n = 0;
                for (int dy = -r; dy <= r; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    s += tmp[yy * w + x];
                    n++;
                }
                out[y * w + x] = s / Math.max(1, n);
            }
        }
        return out;
    }

    /** 把工作尺寸的 alpha 双线性放大到原图尺寸，然后按 alpha 合成新底色。 */
    private static Bitmap composite(Bitmap src, float[] alpha, int aw, int ah, int color) {
        int sw = src.getWidth(), sh = src.getHeight();
        int[] px = new int[sw * sh];
        src.getPixels(px, 0, sw, 0, 0, sw, sh);
        int tr = (color >> 16) & 0xff, tg = (color >> 8) & 0xff, tb = color & 0xff;
        for (int y = 0; y < sh; y++) {
            float fy = (y + 0.5f) * ah / sh - 0.5f;
            int y0 = Math.max(0, Math.min(ah - 1, (int) Math.floor(fy)));
            int y1 = Math.min(ah - 1, y0 + 1);
            float wy = Math.max(0f, Math.min(1f, fy - y0));
            for (int x = 0; x < sw; x++) {
                float fx = (x + 0.5f) * aw / sw - 0.5f;
                int x0 = Math.max(0, Math.min(aw - 1, (int) Math.floor(fx)));
                int x1 = Math.min(aw - 1, x0 + 1);
                float wx = Math.max(0f, Math.min(1f, fx - x0));
                float a = alpha[y0 * aw + x0] * (1 - wx) * (1 - wy)
                        + alpha[y0 * aw + x1] * wx * (1 - wy)
                        + alpha[y1 * aw + x0] * (1 - wx) * wy
                        + alpha[y1 * aw + x1] * wx * wy;
                int p = px[y * sw + x];
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                int nr = (int) (r * a + tr * (1 - a));
                int ng = (int) (g * a + tg * (1 - a));
                int nb = (int) (b * a + tb * (1 - a));
                px[y * sw + x] = 0xFF000000 | (nr << 16) | (ng << 8) | nb;
            }
        }
        Bitmap out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, sw, 0, 0, sw, sh);
        return out;
    }
}
