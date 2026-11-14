package com.example.stereocam;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Betrachter wie in StereoPano: Modi Bewegung / 2D / Cardboard / SBS-Brille, Leiste mit runden Symbolknoepfen
 * (Galerie, Modus, Pause, Feineinstellung, Gyro, Halb-SBS, Linsen, Zentrieren, Zahnrad).
 */
public class WiggleActivity extends Activity implements SensorEventListener {
    private static final double[] AMPS = {1, 2, 3, 4, 6};
    private static final double[] PERIODS = {1.5, 2.5, 4};
    private static final int[] MODE_NAME = {R.string.v_mode_motion, R.string.v_mode_flat, R.string.v_mode_cardboard, R.string.v_mode_sbs};
    private static final int[] MODE_ICON = {R.drawable.ic_mode_motion, R.drawable.ic_mode_flat, R.drawable.ic_mode_cardboard, R.drawable.ic_mode_sbs};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private StereoView view; private TextView status; private LinearLayout bar, panel;
    private ImageView modeBtn, playBtn, tuneBtn, gyroBtn, halfBtn, lensBtn;
    private WiggleRenderer.Scene scene; private Bitmap frame, left, right; private int[] buf;
    private volatile boolean playing = true, alive = true, busy, paused;
    private volatile int ampIdx = 1, perIdx = 1, shape = WigglePath.LINE;
    private int mode = StereoView.MOTION, lastStereo = StereoView.CARDBOARD;
    private boolean half, lens = true, gyro;
    private long t0; private double pausedAt;
    private SensorManager sm; private final float[] rm = new float[9], ori = new float[3]; private float baseYaw = Float.NaN, basePitch;
    private float lx, ly;
    private final List<TextView> ampChips = new ArrayList<>(), perChips = new ArrayList<>(), shapeChips = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        ampIdx = Prefs.getInt(this, "wig_amp2", 1); perIdx = Prefs.getInt(this, "wig_per", 1); shape = Prefs.getInt(this, "wig_shape", 0);
        mode = Prefs.getInt(this, "view_mode", StereoView.MOTION); lastStereo = Prefs.getInt(this, "view_last", StereoView.CARDBOARD);
        half = Prefs.get(this, "view_half"); lens = !Prefs.str(this, "view_lens_off").equals("1"); gyro = false;

        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xFF000000);
        view = new StereoView(this); root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        status = Ui.text(this, getString(R.string.wig_loading), 14, Ui.GREY); status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { boolean on = bar.getVisibility() == View.VISIBLE; bar.setVisibility(on ? View.GONE : View.VISIBLE); if (on) panel.setVisibility(View.GONE); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { setMode(mode == StereoView.FLAT ? lastStereo : StereoView.FLAT); return true; }
        });
        final ScaleGestureDetector sd = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { view.setZoom(view.getZoom() * d.getScaleFactor()); return true; }
        });
        view.setOnTouchListener((v, ev) -> {
            sd.onTouchEvent(ev); gd.onTouchEvent(ev);
            if (sd.isInProgress() || ev.getPointerCount() > 1) { lx = ev.getX(); ly = ev.getY(); return true; }
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) { lx = ev.getX(); ly = ev.getY(); }
            else if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) { view.drag((ev.getX() - lx) / Math.max(1, v.getWidth()), (ev.getY() - ly) / Math.max(1, v.getHeight())); lx = ev.getX(); ly = ev.getY(); }
            return true;
        });

        bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_HORIZONTAL); bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        bar.addView(btn(R.drawable.ic_gallery, R.string.gal_title, v -> finish()));
        modeBtn = btn(MODE_ICON[mode], R.string.cd_mode, v -> setMode(mode == StereoView.SBS ? StereoView.MOTION : mode + 1)); bar.addView(modeBtn);
        playBtn = btn(R.drawable.ic_pause, R.string.cd_pause, v -> toggle()); bar.addView(playBtn);
        tuneBtn = btn(R.drawable.ic_tune, R.string.cd_tune, v -> panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)); bar.addView(tuneBtn);
        gyroBtn = btn(R.drawable.ic_gyro, R.string.cd_gyro, v -> { gyro = !gyro; baseYaw = Float.NaN; refresh(); listen(); Toast.makeText(this, gyro ? R.string.gyro_on : R.string.gyro_off, Toast.LENGTH_SHORT).show(); }); bar.addView(gyroBtn);
        halfBtn = btn(R.drawable.ic_half, R.string.cd_half, v -> { half = !half; Prefs.set(this, "view_half", half); view.setHalf(half); refresh(); Toast.makeText(this, half ? R.string.half_on : R.string.half_off, Toast.LENGTH_SHORT).show(); }); bar.addView(halfBtn);
        lensBtn = btn(R.drawable.ic_lens, R.string.cd_lens, v -> { lens = !lens; Prefs.setStr(this, "view_lens_off", lens ? "" : "1"); view.setLens(lens); refresh(); Toast.makeText(this, lens ? R.string.lens_on : R.string.lens_off, Toast.LENGTH_SHORT).show(); }); bar.addView(lensBtn);
        bar.addView(btn(R.drawable.ic_center, R.string.cd_center, v -> { view.reset(); baseYaw = Float.NaN; }));
        bar.addView(btn(R.drawable.ic_settings, R.string.set_title, v -> startActivity(new Intent(this, ProbeActivity.class))));
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        panel = Ui.card(this); panel.setVisibility(View.GONE);
        panel.addView(label(R.string.wig_amp)); panel.addView(chips(ampChips, new String[] {"×1", "×2", "×3", "×4", "×6"}, ampIdx, i -> { ampIdx = i; Prefs.setInt(this, "wig_amp2", i); }));
        panel.addView(label(R.string.wig_speed)); panel.addView(chips(perChips, new String[] {getString(R.string.wig_fast), getString(R.string.wig_mid), getString(R.string.wig_slow)}, perIdx, i -> { retime(PERIODS[i]); perIdx = i; Prefs.setInt(this, "wig_per", i); }));
        panel.addView(label(R.string.wig_path)); panel.addView(chips(shapeChips, new String[] {getString(R.string.wig_line), getString(R.string.wig_circle)}, shape, i -> { shape = i; Prefs.setInt(this, "wig_shape", i); }));
        panel.addView(Ui.space(this, 6)); panel.addView(Ui.text(this, getString(R.string.wig_hint), 12, Ui.GREY));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); pl.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        root.addView(panel, pl);
        setContentView(root);
        bar.setOnApplyWindowInsetsListener((v, in) -> { v.setPadding(in.getSystemWindowInsetLeft() + Ui.dp(this, 8), in.getSystemWindowInsetTop() + Ui.dp(this, 8), in.getSystemWindowInsetRight() + Ui.dp(this, 8), Ui.dp(this, 8)); return in; });
        view.setHalf(half); view.setLens(lens); applyMode(false);
        load();
    }

    private ImageView btn(int icon, int desc, View.OnClickListener l) {
        ImageView k = Ui.iconBtn(this, icon, desc); k.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)); lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3)); k.setLayoutParams(lp); return k;
    }

    private void setMode(int m) {
        if (mode != StereoView.MOTION && mode != StereoView.FLAT) lastStereo = mode;
        mode = m; if (m == StereoView.CARDBOARD || m == StereoView.SBS) lastStereo = m;
        Prefs.setInt(this, "view_mode", mode); Prefs.setInt(this, "view_last", lastStereo);
        applyMode(true);
    }

    private void applyMode(boolean toast) {
        boolean stereo = mode == StereoView.CARDBOARD || mode == StereoView.SBS;
        setRequestedOrientation(stereo ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        view.setMode(mode); modeBtn.setImageResource(MODE_ICON[mode]);
        if (mode == StereoView.MOTION) { if (playing && scene != null) tick(); }
        refresh();
        if (toast) Toast.makeText(this, getString(R.string.v_mode_fmt, getString(MODE_NAME[mode])), Toast.LENGTH_SHORT).show();
    }

    private void refresh() {
        boolean motion = mode == StereoView.MOTION;
        playBtn.setVisibility(motion ? View.VISIBLE : View.GONE); tuneBtn.setVisibility(motion ? View.VISIBLE : View.GONE);
        if (!motion) panel.setVisibility(View.GONE);
        halfBtn.setVisibility(mode == StereoView.SBS ? View.VISIBLE : View.GONE); lensBtn.setVisibility(mode == StereoView.CARDBOARD ? View.VISIBLE : View.GONE);
        Ui.state(gyroBtn, gyro ? Ui.ST_ON : Ui.ST_OFF); Ui.state(halfBtn, half ? Ui.ST_ON : Ui.ST_OFF); Ui.state(lensBtn, lens ? Ui.ST_ON : Ui.ST_OFF);
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private TextView label(int res) { TextView t = Ui.text(this, getString(res), 13, Ui.ACCENT); t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4)); return t; }

    private interface Pick { void on(int i); }
    private View chips(final List<TextView> store, String[] names, int sel, final Pick pick) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < names.length; i++) {
            final int k = i; TextView c = Ui.text(this, names[i], 14, Ui.TEXT); c.setGravity(Gravity.CENTER);
            c.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8)); store.add(c);
            c.setOnClickListener(v -> { pick.on(k); paint(store, k); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.rightMargin = Ui.dp(this, 6); row.addView(c, lp);
        }
        paint(store, sel); return row;
    }
    private void paint(List<TextView> store, int sel) {
        for (int i = 0; i < store.size(); i++) { TextView c = store.get(i); boolean on = i == sel; c.setTextColor(on ? 0xFF0B1220 : Ui.TEXT); c.setBackground(Ui.round(this, 18, on ? Ui.ACCENT : Ui.BG, on ? 0 : Ui.EDGE)); }
    }

    // ------------------------------------------------------------------ Bewegung

    private double period() { return PERIODS[perIdx]; }
    private void retime(double np) { double ph = (now() - t0) / 1000.0 / period(); t0 = (long) (now() - ph * np * 1000); }
    private long now() { return android.os.SystemClock.uptimeMillis(); }
    private void toggle() { playing = !playing; if (playing) { t0 = now() - (long) (pausedAt * 1000); tick(); } else pausedAt = (now() - t0) / 1000.0; refresh(); }

    private void load() {
        final Uri u = getIntent().getData(); final String name = getIntent().getStringExtra("name");
        worker.execute(() -> {
            String err = null;
            try {
                byte[] data = StereoFile.readAll(this, u);
                StereoFile.Pair p = StereoFile.load(data, name, 2048);
                if (p == null) err = getString(R.string.wig_not_stereo);
                else {
                    left = p.left; right = p.right;
                    int w = left.getWidth(), h = left.getHeight(); int[] l = new int[w * h], r = new int[w * h];
                    left.getPixels(l, 0, w, 0, 0, w, h); right.getPixels(r, 0, w, 0, 0, w, h);
                    scene = WiggleRenderer.build(l, r, w, h, 600);
                    buf = new int[scene.w * scene.h]; frame = Bitmap.createBitmap(scene.w, scene.h, Bitmap.Config.ARGB_8888);
                }
            } catch (Throwable e) { err = getString(R.string.cap_error, String.valueOf(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())); }
            final String fe = err;
            ui.post(() -> {
                if (fe != null) { status.setText(fe); return; }
                status.setVisibility(View.GONE); view.setPair(left, right); view.setSingle(frame); t0 = now(); if (mode == StereoView.MOTION) tick();
            });
        });
    }

    private void tick() {
        if (!alive || !playing || scene == null || busy || mode != StereoView.MOTION) return;
        busy = true;
        final double sec = (now() - t0) / 1000.0; final double amp = AMPS[ampIdx]; final int sh = shape; final double per = period();
        worker.execute(() -> {
            double[] tv = WigglePath.at(sec, per, amp, sh);
            WiggleRenderer.render(scene, tv[0], tv[1], buf);
            frame.setPixels(buf, 0, scene.w, 0, 0, scene.w, scene.h);
            ui.post(() -> { view.setSingle(frame); busy = false; tick(); });
        });
    }

    // ------------------------------------------------------------------ Gyro: Bildausschnitt mit dem Telefon verschieben (bei Zoom)

    private void listen() {
        sm.unregisterListener(this);
        if (!gyro) return;
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR); if (s == null) s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (s == null) { gyro = false; refresh(); Toast.makeText(this, R.string.gyro_none, Toast.LENGTH_SHORT).show(); return; }
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
    }
    @Override public void onSensorChanged(SensorEvent e) {
        SensorManager.getRotationMatrixFromVector(rm, e.values); SensorManager.getOrientation(rm, ori);
        float yaw = (float) Math.toDegrees(ori[0]), pitch = (float) Math.toDegrees(ori[1]);
        if (Float.isNaN(baseYaw)) { baseYaw = yaw; basePitch = pitch; }
        float dy = yaw - baseYaw; if (dy > 180) dy -= 360; if (dy < -180) dy += 360;
        view.setPan(-dy / 60f, (pitch - basePitch) / 60f);                   // 60 Grad ~ eine Bildbreite (grobe Annahme)
    }
    @Override public void onAccuracyChanged(Sensor s, int a) {}

    @Override protected void onPause() { super.onPause(); sm.unregisterListener(this); if (playing) { pausedAt = (now() - t0) / 1000.0; playing = false; paused = true; } }
    @Override protected void onResume() { super.onResume(); listen(); if (paused) { paused = false; playing = true; t0 = now() - (long) (pausedAt * 1000); tick(); refresh(); } }
    @Override protected void onDestroy() { super.onDestroy(); alive = false; worker.shutdownNow(); }
}
