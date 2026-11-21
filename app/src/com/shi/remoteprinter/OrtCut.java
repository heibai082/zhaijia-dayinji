package com.shi.remoteprinter;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * 用 ONNX Runtime 跑 U²-Net（u2netp，rembg 同款）做显著目标分割，然后合成新底色。
 *
 * <p>为什么上模型：颜色模型那套在"猫躺在花枕头上"这种图上会把枕头当主体；U²-Net 是
 * 显著目标分割网络，人、猫、狗、玩偶都认，全自动、不用框。用户实测盲选也是模型这版赢。
 *
 * <p>模型：`assets/u2netp.onnx`（4.36MB，MIT），输入 1×3×320×320（ImageNet 归一化），
 * 输出 1×1×320×320 的软蒙版。预处理/后处理与 `experiments/idphoto/seg_model.py` 保持一致。
 *
 * <p>内存与耗时：320×320 推理，中端机约 0.5–1.5 秒；模型只加载一次（懒加载 + 复用 session）。
 */
public final class OrtCut {

    private static final String TAG = "OrtCut";
    private static final String MODEL = "u2netp.onnx";
    private static final int SIZE = 320;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private static OrtEnvironment env;
    private static OrtSession session;
    private static boolean failed;

    private OrtCut() {
    }

    /** 模型是否可用（第一次调用会加载，约 200ms）。 */
    public static synchronized boolean available(Context ctx) {
        if (session != null) return true;
        if (failed) return false;
        try {
            OrtEnvironment e = OrtEnvironment.getEnvironment();
            AssetManager am = ctx.getAssets();
            InputStream is = am.open(MODEL);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(5 * 1024 * 1024);
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(2);
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            session = e.createSession(bos.toByteArray(), opts);
            env = e;
            Log.i(TAG, "模型已加载：" + MODEL);
            return true;
        } catch (Throwable t) {
            failed = true;
            Log.e(TAG, "模型加载失败", t);
            return false;
        }
    }

    /** 自动抠图换底；不可用时返回 null（调用方退回本地算法）。 */
    public static Bitmap replaceBackground(Context ctx, Bitmap src, int color) {
        if (src == null) return null;
        if (!available(ctx)) return null;
        Bitmap scaled = null;
        try {
            int sw = src.getWidth(), sh = src.getHeight();
            // 1) 预处理：缩到 320×320，ImageNet 归一化，CHW
            scaled = Bitmap.createScaledBitmap(src, SIZE, SIZE, true);
            int[] px = new int[SIZE * SIZE];
            scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
            float[] in = new float[3 * SIZE * SIZE];
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                float r = ((p >> 16) & 0xff) / 255f;
                float g = ((p >> 8) & 0xff) / 255f;
                float b = (p & 0xff) / 255f;
                in[i] = (r - MEAN[0]) / STD[0];
                in[SIZE * SIZE + i] = (g - MEAN[1]) / STD[1];
                in[2 * SIZE * SIZE + i] = (b - MEAN[2]) / STD[2];
            }

            // 2) 推理
            float[] mask = new float[SIZE * SIZE];
            try (OnnxTensor tensor = OnnxTensor.createTensor(env,
                    java.nio.FloatBuffer.wrap(in), new long[]{1, 3, SIZE, SIZE})) {
                OrtSession.Result result = session.run(
                        java.util.Collections.singletonMap(
                                session.getInputNames().iterator().next(), tensor));
                Object value = result.get(0).getValue();
                // 输出结构：float[1][1][320][320]
                float[][][][] out = (float[][][][]) value;
                float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        float v = out[0][0][y][x];
                        mask[y * SIZE + x] = v;
                        if (v < min) min = v;
                        if (v > max) max = v;
                    }
                }
                float span = Math.max(1e-6f, max - min);
                for (int i = 0; i < mask.length; i++) {
                    mask[i] = (mask[i] - min) / span;
                }
                result.close();
            }

            // 3) 蒙版放大回原图 + 合成
            int[] out2 = new int[sw * sh];
            src.getPixels(out2, 0, sw, 0, 0, sw, sh);
            int tr = (color >> 16) & 0xff, tg = (color >> 8) & 0xff, tb = color & 0xff;
            for (int y = 0; y < sh; y++) {
                float fy = (y + 0.5f) * SIZE / sh - 0.5f;
                int y0 = Math.max(0, Math.min(SIZE - 1, (int) Math.floor(fy)));
                int y1 = Math.min(SIZE - 1, y0 + 1);
                float wy = Math.max(0f, Math.min(1f, fy - y0));
                for (int x = 0; x < sw; x++) {
                    float fx = (x + 0.5f) * SIZE / sw - 0.5f;
                    int x0 = Math.max(0, Math.min(SIZE - 1, (int) Math.floor(fx)));
                    int x1 = Math.min(SIZE - 1, x0 + 1);
                    float wx = Math.max(0f, Math.min(1f, fx - x0));
                    float a = mask[y0 * SIZE + x0] * (1 - wx) * (1 - wy)
                            + mask[y0 * SIZE + x1] * wx * (1 - wy)
                            + mask[y1 * SIZE + x0] * (1 - wx) * wy
                            + mask[y1 * SIZE + x1] * wx * wy;
                    if (a < 0) a = 0;
                    if (a > 1) a = 1;
                    int p = out2[y * sw + x];
                    int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                    int nr = (int) (r * a + tr * (1 - a));
                    int ng = (int) (g * a + tg * (1 - a));
                    int nb = (int) (b * a + tb * (1 - a));
                    out2[y * sw + x] = 0xFF000000 | (nr << 16) | (ng << 8) | nb;
                }
            }
            Bitmap res = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            res.setPixels(out2, 0, sw, 0, 0, sw, sh);
            return res;
        } catch (Throwable t) {
            Log.e(TAG, "推理失败", t);
            return null;
        } finally {
            if (scaled != null && scaled != src) scaled.recycle();
        }
    }
}
