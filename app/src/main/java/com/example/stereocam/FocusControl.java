package com.example.stereocam;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import java.util.Locale;

/**
 * Fokus-Bedienung der Aufnahme-Bildschirme: Tippen auf die Vorschau fokussiert (und misst) auf diese Stelle;
 * der senkrechte Regler stellt die Entfernung von Hand ein (oben unendlich, unten Naheinstellgrenze), "AF" schaltet zurueck auf Autofokus.
 * Gilt fuer die logische Kamera (bei zwei Kameras gleichzeitig wirkt die Einstellung auf beide, soweit das Telefon sie weitergibt).
 */
final class FocusControl {
    interface Callback { void changed(); }

    private final Activity act; private final Callback cb; private final TextureView tv;
    private boolean manual; private float diopter;                         // manuelle Entfernung in 1/m
    private MeteringRectangle region; private boolean trigger;
    private float minFocus; private Rect active; private int sensorOrient = 90, maxAf;
    private final Ring ring; private final Slider slider;

    FocusControl(Activity a, FrameLayout root, TextureView tv, Callback cb) {
        act = a; this.cb = cb; this.tv = tv;
        ring = new Ring(a); root.addView(ring, new FrameLayout.LayoutParams(Ui.dp(a, 72), Ui.dp(a, 72))); ring.setVisibility(View.GONE);
        slider = new Slider(a);                                                // links, zwischen oberem Rand und der Galerie-Vorschau (Klapphandys: Aussendisplay ist schmal, rechts liegen die Knoepfe)
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(a, 76), -1, Gravity.START | Gravity.CENTER_VERTICAL); lp.leftMargin = Ui.dp(a, 6); lp.topMargin = Ui.dp(a, 12); lp.bottomMargin = Ui.dp(a, 104);
        root.addView(slider, lp); slider.setVisibility(View.GONE);
        final float[] down = new float[2];
        tv.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { down[0] = e.getX(); down[1] = e.getY(); }
            else if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.hypot(e.getX() - down[0], e.getY() - down[1]) < Ui.dp(act, 12)) tap(e.getX(), e.getY());
            return true;
        });
    }

    /** Nach dem Oeffnen der Kamera: Naheinstellgrenze, Sensorgroesse und -ausrichtung der logischen Kamera lesen. */
    void setCharacteristics(final CameraCharacteristics cc) {
        Float mf = cc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE); minFocus = mf == null ? 0 : mf;
        active = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION); sensorOrient = so == null ? 90 : so;
        Integer m = cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF); maxAf = m == null ? 0 : m;
        act.runOnUiThread(() -> { slider.setVisibility(minFocus > 0 ? View.VISIBLE : View.GONE); slider.invalidate(); });
    }

    /** Fokus-Einstellungen in eine Anfrage schreiben. @param withTrigger einmalig den Autofokus-Durchlauf starten. */
    void apply(CaptureRequest.Builder b, boolean withTrigger) {
        if (manual && minFocus > 0) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF); b.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopter); return;
        }
        if (region != null && maxAf > 0) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[] {region}); b.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[] {region});
            if (withTrigger) b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        } else b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
    }

    boolean takeTrigger() { boolean t = trigger; trigger = false; return t; }
    boolean isManual() { return manual; }

    private void tap(float x, float y) {
        if (active == null || maxAf <= 0 || tv.getWidth() == 0) return;
        float nx = Math.max(0, Math.min(1, x / tv.getWidth())), ny = Math.max(0, Math.min(1, y / tv.getHeight()));
        int rot = (sensorOrient - act.getWindowManager().getDefaultDisplay().getRotation() * 90 + 360) % 360;
        float sx, sy;
        if (rot == 90) { sx = ny; sy = 1 - nx; } else if (rot == 180) { sx = 1 - nx; sy = 1 - ny; } else if (rot == 270) { sx = 1 - ny; sy = nx; } else { sx = nx; sy = ny; }
        int half = Math.max(8, Math.round(0.07f * Math.max(active.width(), active.height())));
        int cx = active.left + Math.round(sx * active.width()), cy = active.top + Math.round(sy * active.height());
        int l = Math.max(active.left, cx - half), t = Math.max(active.top, cy - half), r = Math.min(active.right - 1, cx + half), bt = Math.min(active.bottom - 1, cy + half);
        region = new MeteringRectangle(l, t, Math.max(1, r - l), Math.max(1, bt - t), 1000); trigger = true; manual = false;
        int[] loc = new int[2]; tv.getLocationInWindow(loc); View p = (View) ring.getParent(); int[] pl = new int[2]; p.getLocationInWindow(pl);
        ring.show(loc[0] - pl[0] + x, loc[1] - pl[1] + y); slider.invalidate();
        cb.changed();
    }

    // ------------------------------------------------------------------ Bildelemente

    private static final class Ring extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Ring(Context c) { super(c); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Ui.dp(c, 2)); p.setColor(Ui.ACCENT); }
        void show(float cx, float cy) {
            setX(cx - getLayoutParams().width / 2f); setY(cy - getLayoutParams().height / 2f); setAlpha(1f); setScaleX(1.4f); setScaleY(1.4f); setVisibility(View.VISIBLE);
            animate().cancel(); animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction(() -> animate().alpha(0f).setStartDelay(1100).setDuration(300).withEndAction(() -> setVisibility(View.GONE)).start()).start();
        }
        @Override protected void onDraw(Canvas c) { float r = Math.min(getWidth(), getHeight()) / 2f - p.getStrokeWidth(); c.drawCircle(getWidth() / 2f, getHeight() / 2f, r, p); }
    }

    private final class Slider extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Slider(Context c) { super(c); }
        @Override protected void onMeasure(int wms, int hms) { int h = Math.min(Ui.dp(getContext(), 270), MeasureSpec.getSize(hms)); super.onMeasure(wms, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)); }
        private float top() { return Ui.dp(getContext(), 44); }
        @Override protected void onDraw(Canvas c) {
            Context ctx = getContext(); int w = getWidth(), h = getHeight(); float cx = Ui.dp(ctx, 22), t = top(), b = h - Ui.dp(ctx, 12);
            p.setStyle(Paint.Style.FILL); p.setColor(manual ? Ui.alpha(Ui.BG, 200) : Ui.ACCENT);
            RectF chip = new RectF(0, 0, Ui.dp(ctx, 44), Ui.dp(ctx, 34)); c.drawRoundRect(chip, Ui.dp(ctx, 17), Ui.dp(ctx, 17), p);
            p.setColor(manual ? Ui.TEXT : 0xFF0B1220); p.setTextSize(Ui.dp(ctx, 13)); p.setTextAlign(Paint.Align.CENTER); c.drawText("AF", chip.centerX(), chip.centerY() + Ui.dp(ctx, 5), p);
            p.setColor(Ui.alpha(Ui.TEXT, 140)); p.setStrokeWidth(Ui.dp(ctx, 3)); c.drawLine(cx, t, cx, b, p);
            if (manual && minFocus > 0) {
                float f = Math.max(0, Math.min(1, diopter / minFocus)), y = t + (b - t) * f;
                p.setColor(Ui.ACCENT); c.drawCircle(cx, y, Ui.dp(ctx, 11), p);
                p.setColor(Ui.TEXT); p.setTextAlign(Paint.Align.LEFT); p.setTextSize(Ui.dp(ctx, 12)); p.setShadowLayer(4, 0, 1, 0xFF000000);
                c.drawText(diopter < 0.05f ? "∞" : String.format(Locale.ROOT, "%.2f m", 1f / diopter), cx + Ui.dp(ctx, 16), y + Ui.dp(ctx, 4), p); p.clearShadowLayer();
            } else { p.setColor(Ui.alpha(Ui.TEXT, 200)); c.drawCircle(cx, t + (b - t) / 2, Ui.dp(ctx, 5), p); }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            float t = top(), b = getHeight() - Ui.dp(getContext(), 12);
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && e.getY() < t) { manual = false; region = null; trigger = false; invalidate(); cb.changed(); return true; }
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float f = Math.max(0, Math.min(1, (e.getY() - t) / (b - t))); diopter = f * minFocus; manual = true; invalidate(); cb.changed(); return true;
            }
            return true;
        }
    }
}
