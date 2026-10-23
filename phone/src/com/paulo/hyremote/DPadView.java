package com.paulo.hyremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** D-pad circular: quatro setores e o OK no centro. Segurar repete a tecla. */
final class DPadView extends View {
    interface OnKey { void key(String name); }

    private static final int TILE = 0xFF1B2029, PRESS = 0xFF3A4660, ACCENT = 0xFF4FA3FF, ACCENT_PRESS = 0xFF2F7FD6,
            BG = 0xFF0E1116, DIM = 0xFF8A93A3;
    private static final String[] NAMES = {"right", "down", "left", "up"};    // setores a partir das 3 h, no sentido horário
    private static final String[] GLYPHS = {"▶", "▼", "◀", "▲"};

    private final OnKey listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private String pressed;
    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (pressed != null) {
                listener.key(pressed);
                handler.postDelayed(this, 130);
            }
        }
    };

    DPadView(Context c, OnKey listener) {
        super(c);
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = Math.min(MeasureSpec.getSize(w), (int) (getResources().getDisplayMetrics().density * 260));
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float s = Math.min(getWidth(), getHeight());
        float c = s / 2f, inset = 3f;
        box.set(inset, inset, s - inset, s - inset);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            paint.setColor(NAMES[i].equals(pressed) ? PRESS : TILE);
            canvas.drawArc(box, -45 + 90 * i, 90, true, paint);
        }
        paint.setColor(BG);                                   // divisórias entre setores
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6f);
        for (int i = 0; i < 4; i++) canvas.drawArc(box, -45 + 90 * i, 90, true, paint);
        paint.setStyle(Paint.Style.FILL);
        float k = c * 0.36f;
        paint.setColor("ok".equals(pressed) ? ACCENT_PRESS : ACCENT);
        canvas.drawCircle(c, c, k, paint);
        paint.setColor(BG);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTextSize(c * 0.2f);
        canvas.drawText("OK", c, c + c * 0.07f, paint);
        paint.setFakeBoldText(false);
        paint.setColor(DIM);
        paint.setTextSize(c * 0.16f);
        float r = c * 0.78f;
        float[][] off = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int i = 0; i < 4; i++) canvas.drawText(GLYPHS[i], c + off[i][0] * r, c + off[i][1] * r + c * 0.055f, paint);
    }

    private String hit(float x, float y) {
        float c = Math.min(getWidth(), getHeight()) / 2f;
        float dx = x - c, dy = y - c;
        double dist = Math.hypot(dx, dy);
        if (dist > c) return null;
        if (dist < c * 0.36) return "ok";
        double a = Math.toDegrees(Math.atan2(dy, dx));       // 0 = direita, 90 = baixo (eixo y para baixo)
        if (a >= -45 && a < 45) return "right";
        if (a >= 45 && a < 135) return "down";
        if (a >= 135 || a < -135) return "left";
        return "up";
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                String h = hit(e.getX(), e.getY());
                if (h != null && !h.equals(pressed)) {
                    handler.removeCallbacks(repeat);
                    pressed = h;
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    listener.key(h);
                    handler.postDelayed(repeat, 400);
                    invalidate();
                } else if (h == null && pressed != null) {
                    release();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void release() {
        handler.removeCallbacks(repeat);
        pressed = null;
        invalidate();
    }
}
