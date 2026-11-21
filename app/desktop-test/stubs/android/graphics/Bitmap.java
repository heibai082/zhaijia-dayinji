package android.graphics;

/**
 * 桌面测试用桩：带像素数组的 Bitmap，够 PortraitCut / EsciClient 的灰度路径之外的东西跑起来。
 * 真机上当然是 android.graphics.Bitmap；这里只是让同一份源码能在 JVM 上验证算法。
 */
public class Bitmap {

    public enum Config {ARGB_8888, RGB_565}

    public enum CompressFormat {JPEG, PNG}

    private final int width;
    private final int height;
    private final int[] pixels;

    Bitmap(int w, int h) {
        this.width = w;
        this.height = h;
        this.pixels = new int[Math.max(1, w * h)];
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isMutable() {
        return true;
    }

    public void getPixels(int[] out, int offset, int stride, int x, int y, int w, int h) {
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int sx = x + col, sy = y + row;
                int v = (sx >= 0 && sy >= 0 && sx < width && sy < height)
                        ? pixels[sy * width + sx] : 0;
                out[offset + row * stride + col] = v;
            }
        }
    }

    public void setPixels(int[] in, int offset, int stride, int x, int y, int w, int h) {
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int dx = x + col, dy = y + row;
                if (dx >= 0 && dy >= 0 && dx < width && dy < height) {
                    pixels[dy * width + dx] = in[offset + row * stride + col];
                }
            }
        }
    }

    public boolean compress(CompressFormat format, int quality, java.io.OutputStream out) {
        throw new UnsupportedOperationException("桌面桩不支持 compress");
    }

    public void recycle() {
    }

    public static Bitmap createBitmap(int w, int h, Config config) {
        return new Bitmap(w, h);
    }

    /** 双线性缩放（够用来验证算法，不追求和安卓逐像素一致）。 */
    public static Bitmap createScaledBitmap(Bitmap src, int w, int h, boolean filter) {
        Bitmap out = new Bitmap(w, h);
        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) * src.height / h - 0.5f;
            int y0 = Math.max(0, Math.min(src.height - 1, (int) Math.floor(fy)));
            int y1 = Math.min(src.height - 1, y0 + 1);
            float wy = Math.max(0f, Math.min(1f, fy - y0));
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) * src.width / w - 0.5f;
                int x0 = Math.max(0, Math.min(src.width - 1, (int) Math.floor(fx)));
                int x1 = Math.min(src.width - 1, x0 + 1);
                float wx = Math.max(0f, Math.min(1f, fx - x0));
                int p00 = src.pixels[y0 * src.width + x0];
                int p01 = src.pixels[y0 * src.width + x1];
                int p10 = src.pixels[y1 * src.width + x0];
                int p11 = src.pixels[y1 * src.width + x1];
                out.pixels[y * w + x] = blend(blend(p00, p01, wx), blend(p10, p11, wx), wy);
            }
        }
        return out;
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** 桌面测试专用：直接从像素数组造一个 Bitmap。 */
    public static Bitmap fromPixels(int w, int h, int[] px) {
        Bitmap b = new Bitmap(w, h);
        System.arraycopy(px, 0, b.pixels, 0, Math.min(px.length, b.pixels.length));
        return b;
    }

    /** 桌面测试专用：取出像素数组。 */
    public int[] toPixels() {
        return pixels.clone();
    }
}
