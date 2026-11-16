package com.example.stereocam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Aufnahme mit zwei physischen Kameras gleichzeitig (Camera2: logische Mehrfachkamera + OutputConfiguration.setPhysicalCameraId).
 * Ob das Geraet beide Kameras fuer Apps freigibt, haengt vom Hersteller ab. Darum gibt es eine Rueckfallleiter der Aufloesung
 * und klare Fehlermeldungen; der Bericht des Startbildschirms hilft bei der Fehlersuche.
 */
public class DualCaptureActivity extends Activity {

    private static final int[] CAPS = {Integer.MAX_VALUE, 3264, 2048};      // Rueckfallstufen: lange Kante der Fotos

    private DualProbe.Pair pair; private double baseMm = Double.NaN;
    private TextureView tv;
    private FrameLayout root;
    private TextView status;
    private ImageView thumb;
    private View shutter;
    private HandlerThread ht; private Handler bg, ui;
    private CameraDevice cam; private CameraCaptureSession sess;
    private ImageReader rdA, rdB; private Size previewSize = new Size(1280, 960);
    private int level = 0; private boolean busy = false, closed = false;
    private byte[] gotA, gotB; private Size usedA, usedB;
    private final MediaActionSound sound = new MediaActionSound();
    private FocusControl focus; private ComposeOverlay compose; private Surface pvS;
    private android.net.Uri lastUri; private String cfgAtStart = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Handler(getMainLooper());
        pair = findPair(getIntent().getStringExtra("pair"));
        if (pair == null) { finish(); return; }
        // Die Basis zwischen den Linsen soll beim Halten waagerecht liegen
        double dx = 0, dy = 1;
        if (!Double.isNaN(pair.baselineMm)) { final double bm0 = pair.baselineMm; baseMm = bm0; }
        if (pair.a.pose != null && pair.b.pose != null) { dx = Math.abs(pair.a.pose[0] - pair.b.pose[0]); dy = Math.abs(pair.a.pose[1] - pair.b.pose[1]); }
        setRequestedOrientation(dx > dy ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        root = new FrameLayout(this); root.setBackgroundColor(0xFF000000); setContentView(root);
        tv = new TextureView(this); root.addView(tv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fit());

        status = Ui.text(this, getString(R.string.cap_hint), 13, Ui.TEXT); status.setShadowLayer(4, 0, 1, 0xFF000000); status.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); sp.topMargin = Ui.dp(this, 12); sp.leftMargin = sp.rightMargin = Ui.dp(this, 64); root.addView(status, sp);

        shutter = new View(this);
        shutter.setBackground(Ui.round(this, 36, Ui.alpha(Ui.TEXT, 235), Ui.ACCENT));
        FrameLayout.LayoutParams sh = new FrameLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); sh.bottomMargin = Ui.dp(this, 24);
        root.addView(shutter, sh); shutter.setOnClickListener(v -> shoot());

        thumb = new ImageView(this); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP); thumb.setBackground(Ui.round(this, 12, Ui.CARD, Ui.EDGE)); thumb.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60), Gravity.BOTTOM | Gravity.START); tp.leftMargin = Ui.dp(this, 24); tp.bottomMargin = Ui.dp(this, 30);
        root.addView(thumb, tp);
        thumb.setOnClickListener(v -> startActivity(new Intent(this, StereoGalleryActivity.class)));
        Chrome.gear(this, root); cfgAtStart = Chrome.cfg(this);
        focus = new FocusControl(this, root, tv, this::refocus); compose = new ComposeOverlay(this, root, tv); compose.setBaselineMm(baseMm); compose.setMinFocusM(Math.max(pair.a.minFocusDioptre > 0 ? 1 / pair.a.minFocusDioptre : 0, pair.b.minFocusDioptre > 0 ? 1 / pair.b.minFocusDioptre : 0));

        TextView swap = Ui.text(this, getString(R.string.swap_eyes), 13, Ui.TEXT); swap.setBackground(Ui.round(this, 18, Ui.alpha(Ui.BG, 200), Ui.EDGE));
        swap.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END); wp.rightMargin = Ui.dp(this, 24); wp.bottomMargin = Ui.dp(this, 42);
        root.addView(swap, wp);
        swap.setOnClickListener(v -> { boolean s = !Prefs.get(this, Prefs.SWAP); Prefs.set(this, Prefs.SWAP, s); say(getString(s ? R.string.swapped_on : R.string.swapped_off)); });
        root.setOnApplyWindowInsetsListener((v, in) -> { v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom()); return in; });
    }

    private DualProbe.Pair findPair(String key) {
        if (key == null) return null;
        for (DualProbe.Pair p : DualProbe.scan(this).pairs) if (p.key().equals(key)) return p;
        return null;
    }

    @Override protected void onResume() {
        super.onResume(); if (compose != null) compose.listen(); if (Chrome.restartIfChanged(this, cfgAtStart)) return; Chrome.loadLatest(this, thumb); closed = false;
        ht = new HandlerThread("cam"); ht.start(); bg = new Handler(ht.getLooper());
        if (tv.isAvailable()) openCamera(); else tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { fit(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        });
    }

    @Override protected void onPause() { if (compose != null) compose.stop(); closeCamera(); if (ht != null) { ht.quitSafely(); ht = null; } super.onPause(); }

    // ------------------------------------------------------------------ Vorschaugeometrie (wie Camera2Basic)

    private void fit() {
        if (root.getWidth() == 0) return;
        int rot = getWindowManager().getDefaultDisplay().getRotation();
        boolean land = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270;
        double ar = land ? (double) previewSize.getWidth() / previewSize.getHeight() : (double) previewSize.getHeight() / previewSize.getWidth();
        int bw = root.getWidth(), bh = root.getHeight(), w, h;
        if ((double) bw / bh > ar) { h = bh; w = (int) Math.round(bh * ar); } else { w = bw; h = (int) Math.round(bw / ar); }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tv.getLayoutParams();
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; tv.setLayoutParams(lp); }
        Matrix m = new Matrix(); RectF view = new RectF(0, 0, w, h), buf = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float cx = view.centerX(), cy = view.centerY();
        if (land) {
            buf.offset(cx - buf.centerX(), cy - buf.centerY());
            m.setRectToRect(view, buf, Matrix.ScaleToFit.FILL);
            float s = Math.max((float) h / previewSize.getHeight(), (float) w / previewSize.getWidth());
            m.postScale(s, s, cx, cy); m.postRotate(90 * (rot - 2), cx, cy);
        } else if (rot == Surface.ROTATION_180) m.postRotate(180, cx, cy);
        tv.setTransform(m);
    }

    // ------------------------------------------------------------------ Kamera oeffnen, Sitzung mit zwei physischen Streams

    private void openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { say(getString(R.string.permission_needed)); return; }
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            CameraCharacteristics cc = cm.getCameraCharacteristics(pair.logicalId);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] ps = map.getOutputSizes(SurfaceTexture.class); Size best = null;
            for (Size s : ps) { if (s.getWidth() * 3 != s.getHeight() * 4 || s.getWidth() > 1600) continue; if (best == null || s.getWidth() > best.getWidth()) best = s; }
            if (best == null) for (Size s : ps) if (s.getWidth() <= 1600 && (best == null || s.getWidth() > best.getWidth())) best = s;
            if (best != null) previewSize = best;
            ui.post(this::fit);
            cm.openCamera(pair.logicalId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) { cam = c; if (closed) { c.close(); return; } level = 0; startSession(); }
                @Override public void onDisconnected(CameraDevice c) { c.close(); cam = null; }
                @Override public void onError(CameraDevice c, int err) { c.close(); cam = null; sayUi(getString(R.string.cap_open_fail, "error " + err)); }
            }, bg);
        } catch (Exception e) { say(getString(R.string.cap_open_fail, e.toString())); }
    }

    /** Groesste JPEG-Groesse der physischen Kamera mit langer Kante <= cap. */
    private Size pick(DualProbe.Phys p, int cap) {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            StreamConfigurationMap map = cm.getCameraCharacteristics(p.id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = null;
            for (Size s : map.getOutputSizes(ImageFormat.JPEG)) { if (Math.max(s.getWidth(), s.getHeight()) > cap) continue; if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s; }
            return best != null ? best : p.best;
        } catch (Exception e) { return p.best; }
    }

    private void startSession() {
        if (cam == null || closed) return;
        try {
            if (sess != null) { sess.close(); sess = null; }
            if (rdA != null) rdA.close(); if (rdB != null) rdB.close();
            usedA = pick(pair.a, CAPS[level]); usedB = pick(pair.b, CAPS[level]);
            rdA = ImageReader.newInstance(usedA.getWidth(), usedA.getHeight(), ImageFormat.JPEG, 2);
            rdB = ImageReader.newInstance(usedB.getWidth(), usedB.getHeight(), ImageFormat.JPEG, 2);
            rdA.setOnImageAvailableListener(r -> got(r, true), bg); rdB.setOnImageAvailableListener(r -> got(r, false), bg);
            SurfaceTexture st = tv.getSurfaceTexture(); st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            final Surface pv = new Surface(st);
            OutputConfiguration oa = new OutputConfiguration(rdA.getSurface()); oa.setPhysicalCameraId(pair.a.id);
            OutputConfiguration ob = new OutputConfiguration(rdB.getSurface()); ob.setPhysicalCameraId(pair.b.id);
            List<OutputConfiguration> outs = new ArrayList<>(); outs.add(new OutputConfiguration(pv)); outs.add(oa); outs.add(ob);
            Executor ex = r -> bg.post(r);
            SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, ex, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (closed || cam == null) return; sess = s; pvS = pv;
                    try {
                        focus.setCharacteristics(((CameraManager) getSystemService(CAMERA_SERVICE)).getCameraCharacteristics(pair.logicalId));
                        CaptureRequest.Builder rb = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); rb.addTarget(pv);
                        rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        rb.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);   // bei einigen Geraeten (z. B. Xiaomi/POCO) bricht die Sitzung mit Stabilisierung auf physischen Kameras ab
                        focus.apply(rb, false);
                        s.setRepeatingRequest(rb.build(), compose.callback(), bg);
                        sayUi(getString(R.string.cap_mode, String.valueOf(level + 1), usedA.getWidth() + "x" + usedA.getHeight(), usedB.getWidth() + "x" + usedB.getHeight()));
                    } catch (Exception e) { sayUi(getString(R.string.cap_error, e.toString())); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    if (level + 1 < CAPS.length) { level++; startSession(); } else switchFallback();
                }
            });
            cam.createCaptureSession(sc);
        } catch (Exception e) {
            if (level + 1 < CAPS.length) { level++; startSession(); } else switchFallback();
        }
    }

    /** Fokus geaendert (Tippen oder Regler): Vorschauanfrage neu setzen, ggf. einmal den Autofokus ausloesen. */
    private void refocus() {
        if (sess == null || cam == null || pvS == null) return;
        try {
            CaptureRequest.Builder rb = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); rb.addTarget(pvS);
            rb.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            boolean trig = focus.takeTrigger(); focus.apply(rb, false); sess.setRepeatingRequest(rb.build(), compose.callback(), bg);
            if (trig) {
                CaptureRequest.Builder t = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); t.addTarget(pvS);
                t.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF); focus.apply(t, true); sess.capture(t.build(), null, bg);
            }
        } catch (Exception e) { sayUi(getString(R.string.cap_error, e.toString())); }
    }

    // ------------------------------------------------------------------ Aufnahme

    private void shoot() {
        if (busy || sess == null || cam == null) return;
        busy = true; gotA = gotB = null; shutter.setAlpha(0.4f);
        try {
            CaptureRequest.Builder b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(rdA.getSurface()); b.addTarget(rdB.getSurface());
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            focus.apply(b, false);
            sound.play(MediaActionSound.SHUTTER_CLICK);
            resLatch = new java.util.concurrent.CountDownLatch(1); capFocalA = capFocalB = capFocusA = capFocusB = -1;
            sess.capture(b.build(), stillCb, bg);
            say(getString(R.string.cap_processing));
            bg.postDelayed(() -> { if (busy && (gotA == null || gotB == null)) { busy = false; sayUi(getString(R.string.cap_missing)); ui.post(() -> shutter.setAlpha(1f)); } }, 9000);
        } catch (Exception e) { busy = false; shutter.setAlpha(1f); say(getString(R.string.cap_error, e.toString())); }
    }

    // Aufnahmedaten der Einzelkameras (Brennweite und Fokusentfernung laut Ergebnis): der Bildwinkel aendert sich mit dem Fokus ("Focus Breathing"),
    // unterhalb der Naheinstellgrenze einer Kamera kann das Telefon auf Zuschnitt der anderen ausweichen.
    private volatile float capFocalA = -1, capFocalB = -1, capFocusA = -1, capFocusB = -1; private volatile java.util.concurrent.CountDownLatch resLatch = new java.util.concurrent.CountDownLatch(1);
    private final android.hardware.camera2.CameraCaptureSession.CaptureCallback stillCb = new android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(android.hardware.camera2.CameraCaptureSession s, CaptureRequest r, android.hardware.camera2.TotalCaptureResult res) {
            try {
                java.util.Map<String, android.hardware.camera2.CaptureResult> m = res.getPhysicalCameraResults();
                android.hardware.camera2.CaptureResult ra = m.get(pair.a.id), rb = m.get(pair.b.id);
                if (ra != null) { Float f = ra.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH), fd = ra.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE); capFocalA = f == null ? -1 : f; capFocusA = fd == null ? -1 : fd; }
                if (rb != null) { Float f = rb.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH), fd = rb.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE); capFocalB = f == null ? -1 : f; capFocusB = fd == null ? -1 : fd; }
            } catch (Throwable ignored) {}
            resLatch.countDown();
        }
    };
    private String capInfo(Processor.Input in) {
        StringBuilder sb = new StringBuilder("capture data:");
        for (int k = 0; k < 2; k++) {
            DualProbe.Phys c = k == 0 ? pair.a : pair.b; float f = k == 0 ? capFocalA : capFocalB, fd = k == 0 ? capFocusA : capFocusB;
            sb.append(String.format(java.util.Locale.ROOT, " %s: f %.2f mm (static %.2f), focus %s, min focus %s;", k == 0 ? "A" : "B", f, c.focalMm, fd > 0.01f ? String.format(java.util.Locale.ROOT, "%.2f m", 1 / fd) : (fd >= 0 ? "inf" : "?"), c.minFocusDioptre > 0 ? String.format(java.util.Locale.ROOT, "%.2f m", 1 / c.minFocusDioptre) : "fixed"));
            if (f > 0 && c.focalMm > 0 && f / c.focalMm > 0.5f && f / c.focalMm < 2f && Math.abs(f / c.focalMm - 1) > 0.01f) { if (k == 0) in.equivA = c.equiv35 * f / c.focalMm; else in.equivB = c.equiv35 * f / c.focalMm; }
        }
        return sb.toString();
    }

    private void got(ImageReader r, boolean isA) {
        Image im = r.acquireLatestImage(); if (im == null) return;
        byte[] data; try { ByteBuffer bb = im.getPlanes()[0].getBuffer(); data = new byte[bb.remaining()]; bb.get(data); } finally { im.close(); }
        if (!busy) return;
        synchronized (this) { if (isA) gotA = data; else gotB = data; if (gotA == null || gotB == null) return; }
        final byte[] ja = gotA, jb = gotB; gotA = gotB = null;
        process(ja, jb);
    }

    private void process(byte[] ja, byte[] jb) {
        final int rot = getWindowManager().getDefaultDisplay().getRotation();
        final int disp = rot * 90;
        Processor.Input in = new Processor.Input();
        in.jpegA = ja; in.jpegB = jb; in.equivA = pair.a.equiv35; in.equivB = pair.b.equiv35;
        in.rotA = (pair.a.orient - disp + 360) % 360; in.rotB = (pair.b.orient - disp + 360) % 360;
        in.aIsLeft = Processor.aIsLeft(pair, rot) ^ Prefs.get(this, Prefs.SWAP);
        new Thread(() -> {
            try {
                try { resLatch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
                in.info = capInfo(in);
                Processor.Output o = Processor.run(this, in);
                ui.post(() -> { say(getString(R.string.cap_saved, o.summary) + (o.align.isEmpty() ? "" : "\n" + o.align)); lastUri = o.preview; showThumb(o.preview); });
            } catch (Throwable t) { ui.post(() -> say(getString(R.string.cap_error, t.toString()))); }
            finally { busy = false; ui.post(() -> shutter.setAlpha(1f)); }
        }, "process").start();
    }

    private void showThumb(android.net.Uri u) {
        if (u == null) return;
        Chrome.loadLatest(this, thumb);
    }

    // ------------------------------------------------------------------ Aufraeumen und Meldungen

    private void closeCamera() {
        closed = true;
        try { if (sess != null) sess.close(); } catch (Exception ignored) {} sess = null;
        try { if (cam != null) cam.close(); } catch (Exception ignored) {} cam = null;
        if (rdA != null) { rdA.close(); rdA = null; } if (rdB != null) { rdB.close(); rdB = null; }
    }

    /** Zwei Kameras gleichzeitig gehen nicht: automatisch auf schnelles Umschalten der Linsen wechseln. */
    private void switchFallback() {
        ui.post(() -> {
            android.widget.Toast.makeText(this, R.string.switch_fallback, android.widget.Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, LensSwitchActivity.class).putExtra("pair", pair.key()));
            finish();
        });
    }

    private void say(String s) { if (status != null) status.setText(s); }
    private void sayUi(String s) { ui.post(() -> say(s)); }
}
