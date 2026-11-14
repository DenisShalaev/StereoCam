package com.example.stereocam;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Bildaufbau-Hilfen der Aufnahme: Raster (aus / Drittel / goldener Schnitt), Horizont-Wasserwaage (Beschleunigungssensor)
 * und eine Entfernungsangabe aus dem Fokus. Die Entfernung ist die der Schaerfeebene: Dinge davor treten bei der Betrachtung vor den Bildschirm,
 * wenn das Hauptmotiv auf die Bildebene gelegt wird; es gibt keine Tiefenkarte in der Live-Vorschau, also keine Linien pro Bildstelle.
 */
final class ComposeOverlay {
    private final Activity act; private final TextureView tv; private final Canvas0 canvas; private final TextView info;
    private int grid; private boolean level; private double baselineMm = Double.NaN;
    private float rollDeg = Float.NaN; private float focusM = -1; private long lastUi;

    ComposeOverlay(Activity a, FrameLayout root, TextureView tv) {
        act = a; this.tv = tv;
        grid = Prefs.getInt(a, "grid_mode", 0); level = Prefs.getInt(a, "level_on", 0) == 1;
        canvas = new Canvas0(a); root.addView(canvas, new FrameLayout.LayoutParams(-1, -1));
        info = Ui.text(a, "", 12, Ui.TEXT); info.setBackground(Ui.round(a, 14, Ui.alpha(Ui.BG, 170), Ui.EDGE)); info.setPadding(Ui.dp(a, 10), Ui.dp(a, 5), Ui.dp(a, 10), Ui.dp(a, 5)); info.setVisibility(View.GONE);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL); ip.topMargin = Ui.dp(a, 14); root.addView(info, ip);
        final ImageView g = btn(root, R.drawable.ic_grid, R.string.cd_grid, 62), l = btn(root, R.drawable.ic_level, R.string.cd_level, 114);
        g.setOnClickListener(v -> {
            grid = (grid + 1) % 3; Prefs.setInt(act, "grid_mode", grid); canvas.invalidate(); state(g, grid != 0);
            Toast.makeText(act, grid == 0 ? R.string.grid_off : grid == 1 ? R.string.grid_thirds : R.string.grid_golden, Toast.LENGTH_SHORT).show();
        });
        l.setOnClickListener(v -> { level = !level; Prefs.setInt(act, "level_on", level ? 1 : 0); canvas.invalidate(); state(l, level); listen(); });
        state(g, grid != 0); state(l, level);
    }

    private ImageView btn(FrameLayout root, int icon, int desc, int top) {
        ImageView g = new ImageView(act); g.setImageResource(icon); int p = Ui.dp(act, 10); g.setPadding(p, p, p, p); g.setContentDescription(act.getString(desc));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(act, 44), Ui.dp(act, 44), Gravity.TOP | Gravity.END); lp.topMargin = Ui.dp(act, top); lp.rightMargin = Ui.dp(act, 12);
        root.addView(g, lp); return g;
    }
    private void state(ImageView g, boolean on) { g.setBackground(Ui.round(act, 22, on ? Ui.alpha(Ui.ACCENT, 90) : Ui.alpha(Ui.BG, 170), on ? Ui.ACCENT : Ui.EDGE)); }

    void setBaselineMm(double mm) { baselineMm = mm; }

    // ------------------------------------------------------------------ Sensor

    private SensorManager sm;
    private final SensorEventListener sl = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            float gx = e.values[0], gy = e.values[1], sx, sy;
            switch (act.getWindowManager().getDefaultDisplay().getRotation()) {
                case 1: sx = -gy; sy = gx; break; case 2: sx = -gx; sy = -gy; break; case 3: sx = gy; sy = -gx; break; default: sx = gx; sy = gy;
            }
            if (Math.hypot(sx, sy) < 3f) { rollDeg = Float.NaN; return; }            // Telefon liegt flach: kein Horizont
            float r = (float) Math.toDegrees(Math.atan2(sx, sy)); rollDeg = Float.isNaN(rollDeg) ? r : rollDeg + 0.25f * (r - rollDeg);
            canvas.invalidate();
        }
        @Override public void onAccuracyChanged(Sensor s, int a) {}
    };
    void listen() {
        if (sm == null) sm = (SensorManager) act.getSystemService(Context.SENSOR_SERVICE);
        sm.unregisterListener(sl);
        if (!level) return;
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY); if (s == null) s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (s != null) sm.registerListener(sl, s, SensorManager.SENSOR_DELAY_UI);
    }
    void stop() { if (sm != null) sm.unregisterListener(sl); }

    // ------------------------------------------------------------------ Fokus-Entfernung aus den Aufnahmeergebnissen

    CameraCaptureSession.CaptureCallback callback() {
        return new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) { result(res); }
        };
    }
    void result(CaptureResult res) {
        long now = android.os.SystemClock.uptimeMillis(); if (now - lastUi < 300) return; lastUi = now;
        Float d = res.get(CaptureResult.LENS_FOCUS_DISTANCE); if (d == null || d <= 0.01f) { focusM = -1; } else focusM = 1f / d;
        act.runOnUiThread(this::showInfo);
    }
    private void showInfo() {
        if (focusM <= 0) { info.setVisibility(View.GONE); return; }
        double minM = (Double.isNaN(baselineMm) ? 20 : baselineMm) * 15 / 1000.0;
        String dist = focusM < 10 ? String.format(Locale.US, "%.2f", focusM) : String.format(Locale.US, "%.0f", focusM);
        info.setText(focusM < minM ? act.getString(R.string.compose_near_fmt, dist, (int) Math.round(minM * 100)) : act.getString(R.string.compose_focus_fmt, dist));
        info.setTextColor(focusM < minM ? 0xFFFFB74D : Ui.TEXT); info.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ Zeichnen

    private final class Canvas0 extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        Canvas0(Context c) { super(c); setClickable(false); setFocusable(false); }
        @Override protected void onDraw(Canvas c) {
            int[] a = new int[2], b = new int[2]; tv.getLocationInWindow(a); getLocationInWindow(b);
            float x0 = a[0] - b[0], y0 = a[1] - b[1], w = tv.getWidth(), h = tv.getHeight(); if (w <= 0 || h <= 0) return;
            float lw = Ui.dp(act, 1);
            sh.setColor(0x66000000); sh.setStrokeWidth(lw * 2.2f); p.setColor(0xCCFFFFFF); p.setStrokeWidth(lw);
            if (grid != 0) {
                float f = grid == 1 ? 1f / 3f : 0.381966f;
                for (float t : new float[] {f, 1 - f}) {
                    c.drawLine(x0 + w * t, y0, x0 + w * t, y0 + h, sh); c.drawLine(x0 + w * t, y0, x0 + w * t, y0 + h, p);
                    c.drawLine(x0, y0 + h * t, x0 + w, y0 + h * t, sh); c.drawLine(x0, y0 + h * t, x0 + w, y0 + h * t, p);
                }
            }
            if (level && !Float.isNaN(rollDeg)) {
                boolean ok = Math.abs(rollDeg) < 1f; int col = ok ? 0xFF66BB6A : (Math.abs(rollDeg) < 5f ? 0xFFFFEE58 : Ui.ACCENT);
                float cx = x0 + w / 2, cy = y0 + h / 2, len = Math.min(w, h) * 0.28f, gap = Ui.dp(act, 26);
                sh.setStrokeWidth(lw * 4f); p.setColor(col); p.setStrokeWidth(lw * 2f);
                // feste Mittenmarke (Soll) und drehende Linie (Ist)
                c.drawLine(cx - len * 1.25f, cy, cx - len * 1.25f + gap * 0.6f, cy, sh); c.drawLine(cx - len * 1.25f, cy, cx - len * 1.25f + gap * 0.6f, cy, p);
                c.drawLine(cx + len * 1.25f, cy, cx + len * 1.25f - gap * 0.6f, cy, sh); c.drawLine(cx + len * 1.25f, cy, cx + len * 1.25f - gap * 0.6f, cy, p);
                c.save(); c.rotate(rollDeg, cx, cy);
                c.drawLine(cx - len, cy, cx - gap, cy, sh); c.drawLine(cx - len, cy, cx - gap, cy, p);
                c.drawLine(cx + gap, cy, cx + len, cy, sh); c.drawLine(cx + gap, cy, cx + len, cy, p);
                c.restore();
                p.setTextSize(Ui.dp(act, 12)); p.setTextAlign(Paint.Align.CENTER); p.setStyle(Paint.Style.FILL);
                c.drawText(String.format(Locale.US, "%+.1f°", rollDeg), cx, cy - Ui.dp(act, 8), p);
                p.setStyle(Paint.Style.STROKE);
            }
        }
    }
}
