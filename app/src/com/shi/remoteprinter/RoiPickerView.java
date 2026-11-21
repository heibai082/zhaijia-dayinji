package com.shi.remoteprinter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 框选头部/主体：在一张图上拖一个矩形。
 *
 * <p>为什么要它：全自动抠图只对"背景干净、主体居中"的照片成立（人像白墙可以，
 * 猫躺在花枕头上就不行——实测会把枕头留下来、猫吃掉）。先框住头，再把"框边缘"当成背景，
 * 抠图就成立了，对手、猫、玩具都通用。
 */
public class RoiPickerView extends View {

    public interface Callback {
        /** @param rect 选中区域（原图坐标） */
        void onRect(Rect rect);
    }

    private final Bitmap bmp;
    private final RectF dst = new RectF();
    private final RectF sel = new RectF();
    private final Callback cb;
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint();
    private float downX, downY;
    private boolean dragging;

    public RoiPickerView(Context c, Bitmap bmp, Callback cb) {
        super(c);
        this.bmp = bmp;
        this.cb = cb;
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(4f);
        border.setColor(Color.parseColor("#FF2F80ED"));
        dim.setColor(0x99000000);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || bmp == null) return;
        float s = Math.min((float) w / bmp.getWidth(), (float) h / bmp.getHeight());
        float dw = bmp.getWidth() * s, dh = bmp.getHeight() * s;
        float ox = (w - dw) / 2f, oy = (h - dh) / 2f;
        dst.set(ox, oy, ox + dw, oy + dh);

        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(bmp, null, dst, p);

        if (sel.width() < 4 || sel.height() < 4) {
            // 初始给一个居中的框，选中部 60%
            sel.set(dst.left + dst.width() * 0.2f, dst.top + dst.height() * 0.15f,
                    dst.right - dst.width() * 0.2f, dst.bottom - dst.height() * 0.35f);
        }

        // 遮罩：框外压暗
        canvas.drawRect(dst.left, dst.top, dst.right, sel.top, dim);
        canvas.drawRect(dst.left, sel.bottom, dst.right, dst.bottom, dim);
        canvas.drawRect(dst.left, sel.top, sel.left, sel.bottom, dim);
        canvas.drawRect(sel.right, sel.top, dst.right, sel.bottom, dim);
        canvas.drawRect(sel, border);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = Math.max(dst.left, Math.min(dst.right, e.getX()));
                downY = Math.max(dst.top, Math.min(dst.bottom, e.getY()));
                dragging = true;
                sel.set(downX, downY, downX, downY);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return true;
                float x = Math.max(dst.left, Math.min(dst.right, e.getX()));
                float y = Math.max(dst.top, Math.min(dst.bottom, e.getY()));
                sel.set(Math.min(downX, x), Math.min(downY, y),
                        Math.max(downX, x), Math.max(downY, y));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                if (cb != null && sel.width() > 20 && sel.height() > 20) {
                    cb.onRect(toBitmapRect());
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    /** 当前框（原图坐标）；还没有框时返回 null。 */
    public Rect getRect() {
        if (sel.width() < 4 || sel.height() < 4) return null;
        return toBitmapRect();
    }

    /** 把屏幕上的框换算回原图坐标。 */
    private Rect toBitmapRect() {
        float s = dst.width() / bmp.getWidth();
        int left = Math.max(0, (int) ((sel.left - dst.left) / s));
        int top = Math.max(0, (int) ((sel.top - dst.top) / s));
        int right = Math.min(bmp.getWidth(), (int) ((sel.right - dst.left) / s));
        int bottom = Math.min(bmp.getHeight(), (int) ((sel.bottom - dst.top) / s));
        if (right - left < 8) right = left + 8;
        if (bottom - top < 8) bottom = top + 8;
        return new Rect(left, top, right, bottom);
    }
}
