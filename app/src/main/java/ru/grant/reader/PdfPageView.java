package ru.grant.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** A single PDF page with pinch zoom and panning; no browser or network is involved. */
public class PdfPageView extends View {
    public interface Navigation { void turn(int direction); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final GestureDetector gestures;
    private final ScaleGestureDetector scales;
    private Bitmap bitmap;
    private float zoom = 1f, fit = 1f, x, y;
    private boolean multiplePointers;
    private Navigation navigation;

    public PdfPageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setClickable(true);
        scales = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                setZoom(zoom * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                float portion = e.getX() / Math.max(1, getWidth());
                if (portion >= 0.25f && portion <= 0.75f) performClick();
                else if (zoom <= 1.01f && navigation != null) navigation.turn(portion < 0.25f ? -1 : 1);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                setZoom(zoom > 1.1f ? 1f : 2.2f, e.getX(), e.getY());
                return true;
            }
            @Override public boolean onScroll(MotionEvent first, MotionEvent current, float dx, float dy) {
                if (zoom > 1.01f && !scales.isInProgress()) { x -= dx; y -= dy; constrain(); invalidate(); }
                return true;
            }
            @Override public boolean onFling(MotionEvent first, MotionEvent last, float vx, float vy) {
                if (!multiplePointers && zoom <= 1.01f && first != null && Math.abs(last.getX() - first.getX()) > 70
                        && Math.abs(vx) > Math.abs(vy) && navigation != null) {
                    navigation.turn(vx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
    }

    public void setNavigation(Navigation navigation) { this.navigation = navigation; }
    public void setBitmap(Bitmap next) {
        Bitmap previous = bitmap;
        bitmap = next;
        reset();
        if (previous != null && previous != next && !previous.isRecycled()) previous.recycle();
    }
    public void clear() { setBitmap(null); }

    private void reset() {
        zoom = 1;
        if (bitmap != null && getWidth() > 0 && getHeight() > 0) {
            fit = Math.min((float)getWidth() / bitmap.getWidth(), (float)getHeight() / bitmap.getHeight());
            x = (getWidth() - bitmap.getWidth() * fit) / 2;
            y = (getHeight() - bitmap.getHeight() * fit) / 2;
        }
        invalidate();
    }
    private void setZoom(float requested, float focusX, float focusY) {
        float next = Math.max(1, Math.min(4, requested));
        float ratio = next / zoom;
        x = focusX - (focusX - x) * ratio;
        y = focusY - (focusY - y) * ratio;
        zoom = next;
        constrain();
        invalidate();
    }
    private void constrain() {
        if (bitmap == null) return;
        float w = bitmap.getWidth() * fit * zoom, h = bitmap.getHeight() * fit * zoom;
        x = w < getWidth() ? (getWidth() - w) / 2 : Math.min(0, Math.max(getWidth() - w, x));
        y = h < getHeight() ? (getHeight() - h) / 2 : Math.min(0, Math.max(getHeight() - h, y));
    }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { reset(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || bitmap.isRecycled()) return;
        matrix.reset(); matrix.postScale(fit * zoom, fit * zoom); matrix.postTranslate(x, y);
        canvas.drawBitmap(bitmap, matrix, paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) multiplePointers = false;
        if (event.getPointerCount() > 1) multiplePointers = true;
        scales.onTouchEvent(event);
        gestures.onTouchEvent(event);
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
