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
import android.view.KeyEvent;
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
    private ImageView exportBtn, modeBtn, playBtn, tuneBtn, gyroBtn, halfBtn, lensBtn;
    private boolean sysShown; private float planeAdj; private LinearLayout planeCard; private TextView planeLabel; private android.widget.SeekBar planeBar;
    private WiggleRenderer.Scene scene; private Bitmap frame, left, right; private int[] buf;
    private volatile boolean playing = true, alive = true, busy, paused;
    private volatile int depthPct = 100;
    private volatile int ampIdx = 1, perIdx = 1, shape = WigglePath.LINE;
    private int mode = StereoView.MOTION, lastStereo = StereoView.CARDBOARD;
    private boolean half, lens = true, gyro;
    private long t0; private double pausedAt;
    private SensorManager sm; private final float[] rm = new float[9], ori = new float[3]; private float baseYaw = Float.NaN, basePitch;
    private float lx, ly;
    private final List<Uri> uris = new ArrayList<>(); private final List<String> names = new ArrayList<>(); private int index; private int gen;
    private final List<TextView> ampChips = new ArrayList<>(), perChips = new ArrayList<>(), shapeChips = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        ampIdx = Prefs.getInt(this, "wig_amp2", 1); depthPct = Prefs.getInt(this, "wig_depth", 100); perIdx = Prefs.getInt(this, "wig_per", 1); shape = Prefs.getInt(this, "wig_shape", 0);
        mode = Prefs.getInt(this, "view_mode", StereoView.MOTION); lastStereo = Prefs.getInt(this, "view_last", StereoView.CARDBOARD);
        half = Prefs.getInt(this, "view_half2", 1) == 1; lens = !Prefs.str(this, "view_lens_off").equals("1"); gyro = false;

        Intent in = getIntent();
        ArrayList<String> us = in.getStringArrayListExtra("uris"), ns = in.getStringArrayListExtra("names");
        if (us != null && !us.isEmpty()) { for (String x : us) uris.add(Uri.parse(x)); if (ns != null) names.addAll(ns); index = Math.max(0, Math.min(uris.size() - 1, in.getIntExtra("index", 0))); }
        else if (in.getData() != null) { uris.add(in.getData()); names.add(in.getStringExtra("name")); }
        while (names.size() < uris.size()) names.add("");

        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xFF000000);
        view = new StereoView(this); root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        status = Ui.text(this, getString(R.string.wig_loading), 14, Ui.GREY); status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { boolean on = bar.getVisibility() == View.VISIBLE; bar.setVisibility(on ? View.GONE : View.VISIBLE); planeCard.setVisibility(on || isStereo() ? View.GONE : View.VISIBLE); showSystemBars(!on); if (on) panel.setVisibility(View.GONE); return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b2, float vx, float vy) {
                if (a == null || b2 == null || view.getZoom() > 1.02f) return false;
                float dy = b2.getY() - a.getY(), dx = b2.getX() - a.getX();
                if (Math.abs(dy) > Ui.dp(WiggleActivity.this, 80) && Math.abs(dy) > 1.5f * Math.abs(dx)) { step(dy < 0 ? 1 : -1); return true; }
                if (isStereo() && Math.abs(dx) > Ui.dp(WiggleActivity.this, 80) && Math.abs(dx) > 1.5f * Math.abs(dy)) {
                    float na = Math.max(-0.06f, Math.min(0.06f, planeAdj + (dx > 0 ? 0.005f : -0.005f))); planeBar.setProgress(Math.round(na / 0.06f * 50f + 50f));
                    view.flash(getString(R.string.plane_fmt, na * 100f)); return true;
                }
                return false;
            }
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
        bar.addView(btn(R.drawable.ic_back, R.string.back, v -> finish()));
        modeBtn = btn(MODE_ICON[mode], R.string.cd_mode, v -> setMode(mode == StereoView.SBS ? StereoView.MOTION : mode + 1)); bar.addView(modeBtn);
        playBtn = btn(R.drawable.ic_pause, R.string.cd_pause, v -> toggle()); bar.addView(playBtn);
        exportBtn = btn(R.drawable.ic_movie, R.string.cd_export, v -> exportDialog()); bar.addView(exportBtn);
        bar.addView(btn(R.drawable.ic_print, R.string.lenti_menu, v -> { if (!uris.isEmpty()) startActivity(new Intent(this, LentiActivity.class).setData(uris.get(index)).putExtra("name", names.get(index))); }));
        tuneBtn = btn(R.drawable.ic_tune, R.string.cd_tune, v -> panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)); bar.addView(tuneBtn);
        gyroBtn = btn(R.drawable.ic_gyro, R.string.cd_gyro, v -> { gyro = !gyro; baseYaw = Float.NaN; refresh(); listen(); say(gyro ? R.string.gyro_on : R.string.gyro_off); }); bar.addView(gyroBtn);
        halfBtn = btn(R.drawable.ic_half, R.string.cd_half, v -> { half = !half; Prefs.setInt(this, "view_half2", half ? 1 : 0); view.setHalf(half); refresh(); say(half ? R.string.half_on : R.string.half_off); }); bar.addView(halfBtn);
        lensBtn = btn(R.drawable.ic_lens, R.string.cd_lens, v -> { lens = !lens; Prefs.setStr(this, "view_lens_off", lens ? "" : "1"); view.setLens(lens); refresh(); say(lens ? R.string.lens_on : R.string.lens_off); }); bar.addView(lensBtn);
        bar.addView(btn(R.drawable.ic_center, R.string.cd_center, v -> { view.reset(); baseYaw = Float.NaN; }));
        bar.addView(btn(R.drawable.ic_settings, R.string.set_title, v -> startActivity(new Intent(this, ProbeActivity.class))));
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol) fitBar(); });

        panel = Ui.card(this); panel.setVisibility(View.GONE);
        panel.addView(label(R.string.wig_amp)); panel.addView(chips(ampChips, new String[] {"×1", "×2", "×3", "×4", "×6"}, ampIdx, i -> { ampIdx = i; Prefs.setInt(this, "wig_amp2", i); }));
        panel.addView(label(R.string.wig_speed)); panel.addView(chips(perChips, new String[] {getString(R.string.wig_fast), getString(R.string.wig_mid), getString(R.string.wig_slow)}, perIdx, i -> { retime(PERIODS[i]); perIdx = i; Prefs.setInt(this, "wig_per", i); }));
        panel.addView(label(R.string.wig_path)); panel.addView(chips(shapeChips, new String[] {getString(R.string.wig_line), getString(R.string.wig_circle)}, shape, i -> { shape = i; Prefs.setInt(this, "wig_shape", i); }));
        final TextView dl = label(R.string.wig_depth); dl.setText(getString(R.string.wig_depth_fmt, depthPct)); panel.addView(dl);
        android.widget.SeekBar sb = new android.widget.SeekBar(this); sb.setMax(150); sb.setProgress(depthPct);
        sb.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT)); sb.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) { depthPct = p; dl.setText(getString(R.string.wig_depth_fmt, p)); if (user) Prefs.setInt(WiggleActivity.this, "wig_depth", p); }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        panel.addView(sb);
        panel.addView(Ui.space(this, 6)); panel.addView(Ui.text(this, getString(R.string.wig_hint), 12, Ui.GREY));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); pl.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        root.addView(panel, pl);
        planeCard = Ui.card(this);
        planeLabel = Ui.text(this, "", 13, Ui.ACCENT); planeLabel.setPadding(0, 0, 0, Ui.dp(this, 2));
        planeLabel.setOnClickListener(x -> { planeBar.setProgress(50); });
        planeBar = new android.widget.SeekBar(this); planeBar.setMax(100); planeBar.setProgress(50);
        planeBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT)); planeBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        planeBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) { setPlane((p - 50) / 50f * 0.06f); }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        planeCard.addView(planeLabel); planeCard.addView(planeBar);
        FrameLayout.LayoutParams ql = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); ql.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        root.addView(planeCard, ql); setPlane(0f);
        pl.bottomMargin = Ui.dp(this, 96); panel.setLayoutParams(pl);
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        android.view.WindowManager.LayoutParams wlp = getWindow().getAttributes(); wlp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES; getWindow().setAttributes(wlp);
        setContentView(root);
        bar.setOnApplyWindowInsetsListener((v, in) -> {
            int l = Math.max(in.getStableInsetLeft(), in.getSystemWindowInsetLeft()), t = Math.max(in.getStableInsetTop(), in.getSystemWindowInsetTop()), r = Math.max(in.getStableInsetRight(), in.getSystemWindowInsetRight());
            android.view.DisplayCutout dc = in.getDisplayCutout(); if (dc != null) { l = Math.max(l, dc.getSafeInsetLeft()); t = Math.max(t, dc.getSafeInsetTop()); r = Math.max(r, dc.getSafeInsetRight()); }
            v.setPadding(l + Ui.dp(this, 8), t + Ui.dp(this, 8), r + Ui.dp(this, 8), Ui.dp(this, 8)); return in; });
        showSystemBars(false);
        view.setHalf(half); view.setLens(lens); applyMode(false);
        load();
    }

    /** Naechste (+1) oder vorherige (-1) Datei der Galerie. */
    private void step(int d) {
        int n = index + d; if (n < 0 || n >= uris.size()) { say(d > 0 ? R.string.nav_last : R.string.nav_first); return; }
        index = n; gen++; scene = null; if (planeBar != null) planeBar.setProgress(50); busy = false; view.reset(); view.setSingle(null); view.setPair(null, null);
        status.setText(R.string.wig_loading); status.setVisibility(View.VISIBLE);
        say((index + 1) + "/" + uris.size() + "  " + names.get(index));
        load();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) { step(1); return true; }
        if (code == KeyEvent.KEYCODE_VOLUME_UP) { step(-1); return true; }
        return super.onKeyDown(code, e);
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

    /** Systemleisten (Uhr, Akku, WLAN, Navigation) ausblenden; nur per Antippen erscheinen sie zusammen mit der Knopfleiste. */
    private void showSystemBars(boolean show) {
        sysShown = show;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController wc = getWindow().getInsetsController(); if (wc == null) return;
            wc.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (show) wc.show(android.view.WindowInsets.Type.systemBars()); else wc.hide(android.view.WindowInsets.Type.systemBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(show ? View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    : View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) showSystemBars(sysShown); }

    private boolean isStereo() { return mode == StereoView.CARDBOARD || mode == StereoView.SBS; }
    /** Kurzmeldung: in den Stereo-Modi in beiden Augen, sonst als Toast. */
    private void say(CharSequence s) { if (isStereo()) view.flash(String.valueOf(s)); else Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void say(int res) { say(getString(res)); }

    private void applyMode(boolean toast) {
        boolean stereo = mode == StereoView.CARDBOARD || mode == StereoView.SBS;
        setRequestedOrientation(stereo ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        view.setMode(mode); modeBtn.setImageResource(MODE_ICON[mode]); if (planeCard != null) planeCard.setVisibility(stereo || bar.getVisibility() != View.VISIBLE ? View.GONE : View.VISIBLE);
        if (mode == StereoView.MOTION) { if (playing && scene != null) tick(); }
        refresh();
        if (toast) say(getString(R.string.v_mode_fmt, getString(MODE_NAME[mode])));
    }

    private void refresh() {
        boolean motion = mode == StereoView.MOTION;
        playBtn.setVisibility(motion ? View.VISIBLE : View.GONE); tuneBtn.setVisibility(motion ? View.VISIBLE : View.GONE); exportBtn.setVisibility(motion ? View.VISIBLE : View.GONE);
        if (!motion) panel.setVisibility(View.GONE);
        halfBtn.setVisibility(mode == StereoView.SBS ? View.VISIBLE : View.GONE); lensBtn.setVisibility(mode == StereoView.CARDBOARD ? View.VISIBLE : View.GONE);
        Ui.state(gyroBtn, gyro ? Ui.ST_ON : Ui.ST_OFF); Ui.state(halfBtn, half ? Ui.ST_ON : Ui.ST_OFF); Ui.state(lensBtn, lens ? Ui.ST_ON : Ui.ST_OFF);
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        fitBar();
    }

    /** Knopfgroesse an die Breite anpassen, damit alle sichtbaren Knoepfe in die Leiste passen (Hochformat). */
    private void fitBar() {
        int n = 0; for (int i = 0; i < bar.getChildCount(); i++) if (bar.getChildAt(i).getVisibility() == View.VISIBLE) n++;
        int avail = (bar.getWidth() > 0 ? bar.getWidth() : getResources().getDisplayMetrics().widthPixels) - bar.getPaddingLeft() - bar.getPaddingRight();
        if (n == 0 || avail <= 0) return;
        int margin = Ui.dp(this, 3), size = Math.max(Ui.dp(this, 30), Math.min(Ui.dp(this, 42), avail / n - 2 * margin));
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i); ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp != null && (lp.width != size || lp.height != size)) { lp.width = size; lp.height = size; c.setLayoutParams(lp); }
        }
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

    // ------------------------------------------------------------------ Export

    private void exportDialog() {
        if (uris.isEmpty()) return;
        new Ui.Dlg(this).setTitle(getString(R.string.cd_export)).setItems(new String[] {getString(R.string.exp_gif), getString(R.string.exp_mp4)}, (d, w) -> {
            final Uri u = uris.get(index); final String nm = names.get(index); final int kind = w == 0 ? StereoExport.GIF : StereoExport.MP4;
            say(R.string.gal_converting);
            new Thread(() -> {
                String m; try { StereoExport.run(this, u, nm, kind); m = getString(R.string.exp_done); }
                catch (Throwable e) { m = getString(R.string.gal_conv_failed, String.valueOf(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())); }
                final String msg = m; runOnUiThread(() -> say(msg));
            }).start();
        }).show();
    }

    // ------------------------------------------------------------------ Bewegung

    private double period() { return PERIODS[perIdx]; }
    private void retime(double np) { double ph = (now() - t0) / 1000.0 / period(); t0 = (long) (now() - ph * np * 1000); }
    private long now() { return android.os.SystemClock.uptimeMillis(); }
    private void toggle() { playing = !playing; if (playing) { t0 = now() - (long) (pausedAt * 1000); tick(); } else pausedAt = (now() - t0) / 1000.0; refresh(); }

    private void load() {
        if (uris.isEmpty()) { status.setText(R.string.wig_not_stereo); return; }
        final Uri u = uris.get(index); final String name = names.get(index); final int myGen = gen;
        worker.execute(() -> {
            String err = null;
            try {
                byte[] data = StereoFile.readAll(this, u);
                StereoFile.Pair p = StereoFile.load(data, name, 2048);
                if (myGen != gen) return;
                if (p == null) err = getString(R.string.wig_not_stereo);
                else {
                    left = p.left; right = p.right;
                    int w = left.getWidth(), h = left.getHeight(); int[] l = new int[w * h], r = new int[w * h];
                    left.getPixels(l, 0, w, 0, 0, w, h); right.getPixels(r, 0, w, 0, 0, w, h);
                    WiggleRenderer.Scene sc = WiggleRenderer.build(l, r, w, h, 600);
                    if (myGen != gen) return;
                    buf = new int[sc.w * sc.h]; frame = Bitmap.createBitmap(sc.w, sc.h, Bitmap.Config.ARGB_8888); scene = sc;
                }
            } catch (Throwable e) { err = getString(R.string.cap_error, String.valueOf(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())); }
            final String fe = err;
            ui.post(() -> {
                if (myGen != gen) return;
                if (fe != null) { status.setText(fe); return; }
                status.setVisibility(View.GONE); view.setPair(left, right); applyPlane(); view.setSingle(frame); t0 = now(); if (mode == StereoView.MOTION) tick();
            });
        });
    }

    /** Bildebene: adj > 0 = Ebene naeher zum Betrachter (Szene rueckt hinter den Bildschirm), adj < 0 = Szene tritt vor den Bildschirm. Anteil der Bildbreite. */
    private void setPlane(float adj) {
        planeAdj = adj; Prefs.setInt(this, "plane_adj_pm", Math.round(adj * 10000));
        planeLabel.setText(getString(R.string.plane_fmt, adj * 100f));
        applyPlane();
    }
    private void applyPlane() {
        if (scene != null) { scene.d0 = scene.d0auto - planeAdj * scene.w; if (mode == StereoView.MOTION && !playing) { /* naechster Frame beim Abspielen */ } }
        float base = scene != null ? -scene.d0auto / scene.w : 0f;
        view.setShift(base + planeAdj);
        if (mode == StereoView.MOTION && scene != null && !playing && !busy) { playing = true; tick(); playing = false; }
    }

    private void tick() {
        if (!alive || !playing || scene == null || busy || mode != StereoView.MOTION) return;
        busy = true;
        final double sec = (now() - t0) / 1000.0; final double amp = AMPS[ampIdx]; final int sh = shape; final double per = period(); final float dk = depthPct / 100f;
        worker.execute(() -> {
            double[] tv = WigglePath.at(sec, per, amp, sh);
            WiggleRenderer.render(scene, tv[0], tv[1], buf, dk);
            frame.setPixels(buf, 0, scene.w, 0, 0, scene.w, scene.h);
            ui.post(() -> { view.setSingle(frame); busy = false; tick(); });
        });
    }

    // ------------------------------------------------------------------ Gyro: Bildausschnitt mit dem Telefon verschieben (bei Zoom)

    private void listen() {
        sm.unregisterListener(this);
        if (!gyro) return;
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR); if (s == null) s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (s == null) { gyro = false; refresh(); say(R.string.gyro_none); return; }
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
    @Override protected void onDestroy() { super.onDestroy(); Prefs.setInt(this, "plane_adj_pm", 0); alive = false; worker.shutdownNow(); }
}
