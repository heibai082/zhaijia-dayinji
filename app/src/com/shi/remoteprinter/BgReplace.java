package com.shi.remoteprinter;

/**
 * 证件照换底的核心算法：不依赖任何 android 类，只吃 int[] 像素，
 * 所以能在桌面 JDK 上直接测（desktop-test/BgTest.java）。
 *
 * <p>做法：取四条边的平均色 → 从边缘向内做 4 邻域广度优先扩散 →
 * 遇到"每个通道都和边缘色相差不超过 tolerance"的像素就填成目标色。
 * 背景干净（白墙、纯色布）效果好；复杂背景会留残边，这是近似处理的固有代价。
 */
public final class BgReplace {

    private BgReplace() {
    }

    /**
     * 原地修改 px（ARGB_8888，行优先）。
     *
     * @return 被填掉的像素数量
     */
    public static int fillFromEdges(int[] px, int w, int h, int fillColor, int tolerance) {
        if (px == null || w <= 0 || h <= 0 || px.length < w * h) return 0;

        // 1) 四边平均色
        long sr = 0, sg = 0, sb = 0;
        long n = 0;
        for (int x = 0; x < w; x++) {
            int top = px[x];
            int bot = px[(h - 1) * w + x];
            sr += ((top >> 16) & 0xff) + ((bot >> 16) & 0xff);
            sg += ((top >> 8) & 0xff) + ((bot >> 8) & 0xff);
            sb += (top & 0xff) + (bot & 0xff);
            n += 2;
        }
        for (int y = 0; y < h; y++) {
            int lef = px[y * w];
            int rig = px[y * w + (w - 1)];
            sr += ((lef >> 16) & 0xff) + ((rig >> 16) & 0xff);
            sg += ((lef >> 8) & 0xff) + ((rig >> 8) & 0xff);
            sb += (lef & 0xff) + (rig & 0xff);
            n += 2;
        }
        final int br = (int) (sr / n), bg = (int) (sg / n), bb = (int) (sb / n);

        // 2) 从四边向内扩散
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        for (int x = 0; x < w; x++) {
            tail = push(px, seen, queue, tail, w, x, br, bg, bb, tolerance, fillColor);
            tail = push(px, seen, queue, tail, w, (h - 1) * w + x, br, bg, bb, tolerance, fillColor);
        }
        for (int y = 0; y < h; y++) {
            tail = push(px, seen, queue, tail, w, y * w, br, bg, bb, tolerance, fillColor);
            tail = push(px, seen, queue, tail, w, y * w + (w - 1), br, bg, bb, tolerance, fillColor);
        }
        int filled = tail;
        while (head < tail) {
            int idx = queue[head++];
            int x = idx % w;
            int y = idx / w;
            if (x > 0) {
                tail = push(px, seen, queue, tail, w, idx - 1, br, bg, bb, tolerance, fillColor);
            }
            if (x < w - 1) {
                tail = push(px, seen, queue, tail, w, idx + 1, br, bg, bb, tolerance, fillColor);
            }
            if (y > 0) {
                tail = push(px, seen, queue, tail, w, idx - w, br, bg, bb, tolerance, fillColor);
            }
            if (y < h - 1) {
                tail = push(px, seen, queue, tail, w, idx + w, br, bg, bb, tolerance, fillColor);
            }
        }
        return tail;
    }

    /**
     * 从某个点向四周扩散填色（"手指涂背景"用）。
     *
     * <p>和 {@link #fillFromEdges} 的区别：种子是手指点的位置，容差以**那个点的颜色**为基准，
     * 不是全图边缘的平均色。所以背景有明暗变化时也能一点一点涂干净，
     * 而且不会像自动模式那样从脖子/衣领漏到脸上——漏了也能撤销。
     *
     * @return 被填掉的像素数量
     */
    public static int fillFromPoint(int[] px, int w, int h, int sx, int sy,
                                    int fillColor, int tolerance) {
        if (px == null || w <= 0 || h <= 0 || sx < 0 || sy < 0 || sx >= w || sy >= h) return 0;
        int idx0 = sy * w + sx;
        int seed = px[idx0];
        if (seed == fillColor) return 0;
        final int sr = (seed >> 16) & 0xff, sg = (seed >> 8) & 0xff, sb = seed & 0xff;

        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        seen[idx0] = true;
        px[idx0] = fillColor;
        queue[tail++] = idx0;
        while (head < tail) {
            int idx = queue[head++];
            int x = idx % w;
            int y = idx / w;
            if (x > 0) tail = pushSeed(px, seen, queue, tail, idx - 1, sr, sg, sb, tolerance, fillColor);
            if (x < w - 1) tail = pushSeed(px, seen, queue, tail, idx + 1, sr, sg, sb, tolerance, fillColor);
            if (y > 0) tail = pushSeed(px, seen, queue, tail, idx - w, sr, sg, sb, tolerance, fillColor);
            if (y < h - 1) tail = pushSeed(px, seen, queue, tail, idx + w, sr, sg, sb, tolerance, fillColor);
        }
        return tail;
    }

    private static int pushSeed(int[] px, boolean[] seen, int[] queue, int tail, int idx,
                                int sr, int sg, int sb, int tol, int fill) {
        if (idx < 0 || idx >= px.length || seen[idx]) return tail;
        seen[idx] = true;
        int p = px[idx];
        if (Math.abs(((p >> 16) & 0xff) - sr) > tol) return tail;
        if (Math.abs(((p >> 8) & 0xff) - sg) > tol) return tail;
        if (Math.abs((p & 0xff) - sb) > tol) return tail;
        px[idx] = fill;
        queue[tail++] = idx;
        return tail;
    }
    /** 邻居命中"接近边缘色"就填色并入队，返回新的队尾。 */
    private static int push(int[] px, boolean[] seen, int[] queue, int tail, int w, int idx,
                            int br, int bg, int bb, int tol, int fill) {
        if (idx < 0 || idx >= px.length || seen[idx]) return tail;
        seen[idx] = true;
        int p = px[idx];
        if (Math.abs(((p >> 16) & 0xff) - br) > tol) return tail;
        if (Math.abs(((p >> 8) & 0xff) - bg) > tol) return tail;
        if (Math.abs((p & 0xff) - bb) > tol) return tail;
        px[idx] = fill;
        queue[tail++] = idx;
        return tail;
    }
}
