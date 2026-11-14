package com.example.stereocam;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Zeigt ein Stereopaar als Bewegung der Kamera (links -> rechts -> zurueck, sanft gebremst) auf dem flachen Bildschirm. */
public class WiggleActivity extends Activity {
    private static final double[] AMPS = {1, 2, 3, 4, 6};
    private static final double[] PERIODS = {1.5, 2.5, 4};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ImageView image; private TextView status;
    private WiggleRenderer.Scene scene; private Bitmap frame; private int[] buf;
    private volatile boolean playing = true, alive = true, busy;
    private volatile int ampIdx = 1, perIdx = 1, shape = WigglePath.LINE;
    private long t0; private double pausedAt; private double fpsAvg;
    private final List<TextView> ampChips = new ArrayList<>(), perChips = new ArrayList<>(), shapeChips = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ampIdx = Prefs.getInt(this, "wig_amp2", 1); perIdx = Prefs.getInt(this, "wig_per", 1); shape = Prefs.getInt(this, "wig_shape", 0);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v, in) -> { v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom()); return in; });
        FrameLayout box = new FrameLayout(this);
        image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); box.addView(image, new FrameLayout.LayoutParams(-1, -1));
        status = Ui.text(this, getString(R.string.wig_loading), 14, Ui.GREY); status.setGravity(Gravity.CENTER);
        box.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        box.setOnClickListener(v -> toggle());
        root.addView(box, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout card = Ui.card(this);
        card.addView(label(R.string.wig_amp)); card.addView(chips(ampChips, new String[] {"×1", "×2", "×3", "×4", "×6"}, ampIdx, i -> { ampIdx = i; Prefs.setInt(this, "wig_amp2", i); }));
        card.addView(label(R.string.wig_speed)); card.addView(chips(perChips, new String[] {getString(R.string.wig_fast), getString(R.string.wig_mid), getString(R.string.wig_slow)}, perIdx, i -> { retime(PERIODS[i]); perIdx = i; Prefs.setInt(this, "wig_per", i); }));
        card.addView(label(R.string.wig_path)); card.addView(chips(shapeChips, new String[] {getString(R.string.wig_line), getString(R.string.wig_circle)}, shape, i -> { shape = i; Prefs.setInt(this, "wig_shape", i); }));
        card.addView(Ui.space(this, 6)); card.addView(Ui.text(this, getString(R.string.wig_hint), 12, Ui.GREY));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 12));
        root.addView(card, lp);
        setContentView(root);
        load();
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

    private double period() { return PERIODS[perIdx]; }
    /** Periodenwechsel ohne Sprung: Phase beibehalten. */
    private void retime(double np) { double ph = (now() - t0) / period(); t0 = (long) (now() - ph * np); }
    private long now() { return android.os.SystemClock.uptimeMillis(); }

    private void toggle() { playing = !playing; if (playing) { t0 = now() - (long) (pausedAt * 1000); tick(); } else pausedAt = (now() - t0) / 1000.0; }

    private void load() {
        final Uri u = getIntent().getData(); final String name = getIntent().getStringExtra("name");
        worker.execute(() -> {
            String err = null;
            try {
                byte[] data = StereoFile.readAll(this, u);
                StereoFile.Pair p = StereoFile.load(data, name, 900);
                if (p == null) err = getString(R.string.wig_not_stereo);
                else {
                    int w = p.left.getWidth(), h = p.left.getHeight(); int[] l = new int[w * h], r = new int[w * h];
                    p.left.getPixels(l, 0, w, 0, 0, w, h); p.right.getPixels(r, 0, w, 0, 0, w, h);
                    scene = WiggleRenderer.build(l, r, w, h, 600);
                    buf = new int[scene.w * scene.h]; frame = Bitmap.createBitmap(scene.w, scene.h, Bitmap.Config.ARGB_8888);
                }
            } catch (Throwable e) { err = getString(R.string.cap_error, String.valueOf(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())); }
            final String fe = err;
            ui.post(() -> {
                if (fe != null) { status.setText(fe); return; }
                status.setVisibility(View.GONE); t0 = now(); tick();
            });
        });
    }

    private void tick() {
        if (!alive || !playing || scene == null || busy) return;
        busy = true;
        final double sec = (now() - t0) / 1000.0; final double amp = AMPS[ampIdx]; final int sh = shape; final double per = period();
        worker.execute(() -> {
            long a = System.nanoTime();
            double[] tv = WigglePath.at(sec, per, amp, sh);
            WiggleRenderer.render(scene, tv[0], tv[1], buf);
            frame.setPixels(buf, 0, scene.w, 0, 0, scene.w, scene.h);
            double fps = 1e9 / Math.max(1, System.nanoTime() - a); fpsAvg = fpsAvg == 0 ? fps : fpsAvg * 0.9 + fps * 0.1;
            ui.post(() -> { image.setImageBitmap(frame); image.invalidate(); busy = false; tick(); });
        });
    }

    @Override protected void onPause() { super.onPause(); if (playing) { pausedAt = (now() - t0) / 1000.0; playing = false; paused = true; } }
    private boolean paused;
    @Override protected void onResume() { super.onResume(); if (paused) { paused = false; playing = true; t0 = now() - (long) (pausedAt * 1000); tick(); } }
    @Override protected void onDestroy() { super.onDestroy(); alive = false; worker.shutdownNow(); }
}
