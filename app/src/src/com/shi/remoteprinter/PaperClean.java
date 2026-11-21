package com.shi.remoteprinter;

/**
 * 试卷清洗核心算法：纯 int[] 进出，不依赖任何 android 类（可在桌面 JDK 上直接测）。
 *
 * <p>三步：
 * <ol>
 *   <li>**背景归一化**：分块估纸张亮度/颜色场，再按"像素÷背景"归一化。
 *       纸偏黄、有阴影、灯光不均时，阈值才不会整体失效（原来固定阈值就是这个毛病）；</li>
 *   <li>**多色去墨**：按"与背景的色差 + 饱和度"判断彩笔（红/蓝/绿/荧光笔都能盖），
 *       黑色印刷字低饱和、比背景暗很多，保留；</li>
 *   <li>**去孤立点 + 提白**：吃掉噪点，纸面推到纯白。</li>
 * </ol>
 *
 * <p>诚实边界：铅笔灰笔迹和印刷字在亮度/饱和度上几乎一致，纯图像处理分不开，
 * 只能把强度调高（连浅色印刷一起抹淡）。真正区分手写/印刷要靠模型。
 */
public final class PaperClean {

    private PaperClean() {
    }

    /** @return 新的像素数组（ARGB_8888，灰度写回三通道） */
    public static int[] clean(int[] px, int w, int h, int strength) {
        int[] out = new int[w * h];

        // ---- 1) 背景场：32×32 分块均值 ----
        int bw = Math.max(1, w / 32), bh = Math.max(1, h / 32);
        int cols = (w + bw - 1) / bw, rows = (h + bh - 1) / bh;
        float[] bgR = new float[cols * rows];
        float[] bgG = new float[cols * rows];
        float[] bgB = new float[cols * rows];
        float[] bgY = new float[cols * rows];
        for (int cy = 0; cy < rows; cy++) {
            for (int cx = 0; cx < cols; cx++) {
                long sr = 0, sg = 0, sb = 0, sy = 0;
                int n = 0;
                for (int y = cy * bh; y < Math.min(h, (cy + 1) * bh); y += 2) {
                    for (int x = cx * bw; x < Math.min(w, (cx + 1) * bw); x += 2) {
                        int p = px[y * w + x];
                        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                        sr += r;
                        sg += g;
                        sb += b;
                        sy += (r * 299 + g * 587 + b * 114) / 1000;
                        n++;
                    }
                }
                if (n == 0) n = 1;
                bgR[cy * cols + cx] = sr / (float) n;
                bgG[cy * cols + cx] = sg / (float) n;
                bgB[cy * cols + cx] = sb / (float) n;
                bgY[cy * cols + cx] = sy / (float) n;
            }
        }

        int satCut = strength >= 2 ? 24 : (strength == 1 ? 32 : 40);
        int darkCut = strength >= 2 ? 74 : (strength == 1 ? 60 : 50);
        int whiteCut = strength >= 2 ? 216 : (strength == 1 ? 206 : 198);

        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) * rows / h - 0.5f;
            int y0 = Math.max(0, Math.min(rows - 1, (int) Math.floor(fy)));
            int y1 = Math.min(rows - 1, y0 + 1);
            float wy = clamp(fy - y0);
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) * cols / w - 0.5f;
                int x0 = Math.max(0, Math.min(cols - 1, (int) Math.floor(fx)));
                int x1 = Math.min(cols - 1, x0 + 1);
                float wx = clamp(fx - x0);
                int i00 = y0 * cols + x0, i01 = y0 * cols + x1;
                int i10 = y1 * cols + x0, i11 = y1 * cols + x1;
                float bR = bl(bgR[i00], bgR[i01], bgR[i10], bgR[i11], wx, wy);
                float bG = bl(bgG[i00], bgG[i01], bgG[i10], bgG[i11], wx, wy);
                float bB = bl(bgB[i00], bgB[i01], bgB[i10], bgB[i11], wx, wy);
                float bY = bl(bgY[i00], bgY[i01], bgY[i10], bgY[i11], wx, wy);

                int p = px[y * w + x];
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                float v = (r * 299 + g * 587 + b * 114) / 1000f;

                // 2) 彩笔：饱和度高 + 与背景色差大 → 抹白
                int maxC = Math.max(r, Math.max(g, b));
                int minC = Math.min(r, Math.min(g, b));
                int sat = maxC - minC;
                float diff = Math.max(Math.abs(r - bR), Math.max(Math.abs(g - bG),
                        Math.abs(b - bB)));
                if (sat > satCut && diff > satCut * 1.2f && v < bY - 12) {
                    out[y * w + x] = 0xFFFFFFFF;
                    continue;
                }

                // 3) 归一化 + 提白
                float norm = bY > 1f ? v / bY : 1f;
                int nv = (int) (norm * 255f);
                if (nv > 255) nv = 255;
                if (bY - v < darkCut) nv = 255;
                if (nv >= whiteCut) nv = 255;
                out[y * w + x] = 0xFF000000 | (nv << 16) | (nv << 8) | nv;
            }
        }

        // 4) 去孤立点
        int[] res = out.clone();
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                if ((out[y * w + x] & 0xff) > 128) continue;
                int dark = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        if ((out[(y + dy) * w + (x + dx)] & 0xff) < 128) dark++;
                    }
                }
                if (dark < 2) res[y * w + x] = 0xFFFFFFFF;
            }
        }
        return res;
    }

    private static float clamp(float t) {
        return t < 0 ? 0 : (t > 1 ? 1 : t);
    }

    private static float bl(float a, float b, float c, float d, float wx, float wy) {
        float top = a + (b - a) * wx;
        float bot = c + (d - c) * wx;
        return top + (bot - top) * wy;
    }
}
