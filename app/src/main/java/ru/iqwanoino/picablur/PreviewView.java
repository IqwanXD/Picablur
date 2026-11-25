package ru.iqwanoino.picablur;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Preview gambar utuh (fit, tidak terpotong) dengan penanda pusat yang bisa digeser. */
public class PreviewView extends View {

    public interface CenterListener {
        void onCenterChanged(float cx, float cy);
    }

    private Bitmap image, processed;
    private float cx = 0.5f, cy = 0.5f;
    private boolean centerVisible = true;
    private CenterListener listener;

    private final RectF dst = new RectF();
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    public PreviewView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(1.5f * dp);
        textPaint.setColor(0xFF777777);
        textPaint.setTextSize(14 * dp);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setImage(Bitmap b) {
        image = b;
        processed = null;
        invalidate();
    }

    public void setProcessed(Bitmap b) {
        processed = b;
        invalidate();
    }

    public void setCenter(float x, float y) {
        cx = x;
        cy = y;
        invalidate();
    }

    public void setCenterVisible(boolean v) {
        centerVisible = v;
        invalidate();
    }

    public void setCenterListener(CenterListener l) {
        listener = l;
    }

    private void computeDst() {
        float vw = getWidth(), vh = getHeight();
        float bw = image.getWidth(), bh = image.getHeight();
        float sc = Math.min(vw / bw, vh / bh);
        float dw = bw * sc, dh = bh * sc;
        float l = (vw - dw) / 2f, t = (vh - dh) / 2f;
        dst.set(l, t, l + dw, t + dh);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (image == null) {
            c.drawText("Import gambar untuk mulai", getWidth() / 2f, getHeight() / 2f, textPaint);
            return;
        }
        computeDst();
        Bitmap b = processed != null ? processed : image;
        c.drawBitmap(b, null, dst, bmpPaint);

        if (centerVisible) {
            float px = dst.left + cx * dst.width();
            float py = dst.top + cy * dst.height();
            c.drawCircle(px, py, 12 * dp, linePaint);
            c.drawLine(px - 18 * dp, py, px + 18 * dp, py, linePaint);
            c.drawLine(px, py - 18 * dp, px, py + 18 * dp, linePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (image == null || !centerVisible) return super.onTouchEvent(ev);
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            computeDst();
            if (dst.width() <= 0 || dst.height() <= 0) return true;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            cx = clamp01((ev.getX() - dst.left) / dst.width());
            cy = clamp01((ev.getY() - dst.top) / dst.height());
            invalidate();
            if (listener != null) listener.onCenterChanged(cx, cy);
        }
        return true;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
