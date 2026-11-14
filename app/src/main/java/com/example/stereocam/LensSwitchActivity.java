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
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Locale;

/**
 * Schnelles Umschalten der Linsen: EINE Sitzung der logischen Kamera (Vorschau + ein JPEG-Strom - eine garantierte Kombination).
 * Das Objektiv wechselt ueber den Zoomfaktor (so macht es auch die Kamera-App; ab Android 11), ACTIVE_PHYSICAL_ID bestaetigt,
 * welche Linse das Bild geliefert hat. Ablauf: Foto mit Linse A, sofort Zoom auf Linse B, warten bis B aktiv ist, Foto mit Linse B.
 * Der zeitliche Abstand der beiden Bilder (im Status angezeigt) ist die Schwaeche: Motiv und Telefon sollten ruhen.
 */
public class LensSwitchActivity extends Activity {

    private DualProbe.Pair pair;
    private TextureView tv; private FrameLayout root; private TextView status; private ImageView thumb; private View shutter;
    private HandlerThread ht; private Handler bg, ui;
    private CameraDevice cam; private CameraCaptureSession sess; private ImageReader reader; private Surface pv;
    private Size previewSize = new Size(1280, 960); private int logicalOrient = 90;
    private final MediaActionSound sound = new MediaActionSound();
    private boolean busy = false, closed = false; private android.net.Uri lastUri; private String cfgAtStart = "";

    private Range<Float> zoomRange; private String refId; private float equivRef = 0;
    private volatile String lastActive; private volatile String waitFor; private volatile int okCount;
    private float zA = 1, zB = 1;                      // Zoomfaktoren der beiden Linsen
    private float zNow = 1;
    private byte[] jpegA, jpegB; private float usedZA, usedZB; private String actA, actB; private long tA, tB; private int rotShot;
    private Runnable onReady;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Handler(getMainLooper());
        pair = findPair(getIntent().getStringExtra("pair"));
        if (pair == null) { finish(); return; }
        double dx = 0, dy = 1;
        if (pair.a.pose != null && pair.b.pose != null) { dx = Math.abs(pair.a.pose[0] - pair.b.pose[0]); dy = Math.abs(pair.a.pose[1] - pair.b.pose[1]); }
        setRequestedOrientation(dx > dy ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        root = new FrameLayout(this); root.setBackgroundColor(0xFF000000); setContentView(root);
        tv = new TextureView(this); root.addView(tv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fit());
        status = Ui.text(this, getString(R.string.switch_hold), 13, Ui.TEXT); status.setShadowLayer(4, 0, 1, 0xFF000000); status.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); sp.topMargin = Ui.dp(this, 12); sp.leftMargin = sp.rightMargin = Ui.dp(this, 64); root.addView(status, sp);

        shutter = new View(this); shutter.setBackground(Ui.round(this, 36, Ui.alpha(Ui.TEXT, 235), Ui.ACCENT));
        FrameLayout.LayoutParams sh = new FrameLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); sh.bottomMargin = Ui.dp(this, 24);
        root.addView(shutter, sh); shutter.setOnClickListener(v -> shoot());

        thumb = new ImageView(this); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP); thumb.setBackground(Ui.round(this, 12, Ui.CARD, Ui.EDGE)); thumb.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60), Gravity.BOTTOM | Gravity.END); tp.rightMargin = Ui.dp(this, 24); tp.bottomMargin = Ui.dp(this, 30); root.addView(thumb, tp);
        thumb.setOnClickListener(v -> startActivity(new Intent(this, StereoGalleryActivity.class)));
        Chrome.gear(this, root); cfgAtStart = Chrome.cfg(this);

        TextView swap = Ui.text(this, getString(R.string.swap_eyes), 13, Ui.TEXT); swap.setBackground(Ui.round(this, 18, Ui.alpha(Ui.BG, 200), Ui.EDGE));
        swap.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START); wp.leftMargin = Ui.dp(this, 24); wp.bottomMargin = Ui.dp(this, 42); root.addView(swap, wp);
        swap.setOnClickListener(v -> { boolean s = !Prefs.get(this, Prefs.SWAP); Prefs.set(this, Prefs.SWAP, s); say(getString(s ? R.string.swapped_on : R.string.swapped_off)); });
        root.setOnApplyWindowInsetsListener((v, in) -> { v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom()); return in; });
        if (Build.VERSION.SDK_INT < 30) say(getString(R.string.switch_need_api));
    }

    private DualProbe.Pair findPair(String key) {
        if (key == null) return null;
        for (DualProbe.Pair p : DualProbe.scan(this).pairs) if (p.key().equals(key)) return p;
        return null;
    }

    @Override protected void onResume() {
        super.onResume(); if (Chrome.restartIfChanged(this, cfgAtStart)) return; Chrome.loadLatest(this, thumb); closed = false;
        ht = new HandlerThread("cam"); ht.start(); bg = new Handler(ht.getLooper());
        if (tv.isAvailable()) openCamera(); else tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { fit(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        });
    }

    @Override protected void onPause() { closeCamera(); if (ht != null) { ht.quitSafely(); ht = null; } super.onPause(); }

    private void fit() {
        if (root.getWidth() == 0) return;
        int rot = getWindowManager().getDefaultDisplay().getRotation(); boolean land = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270;
        double ar = land ? (double) previewSize.getWidth() / previewSize.getHeight() : (double) previewSize.getHeight() / previewSize.getWidth();
        int bw = root.getWidth(), bh = root.getHeight(), w, h;
        if ((double) bw / bh > ar) { h = bh; w = (int) Math.round(bh * ar); } else { w = bw; h = (int) Math.round(bw / ar); }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tv.getLayoutParams(); if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; tv.setLayoutParams(lp); }
        Matrix m = new Matrix(); RectF view = new RectF(0, 0, w, h), buf = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth()); float cx = view.centerX(), cy = view.centerY();
        if (land) { buf.offset(cx - buf.centerX(), cy - buf.centerY()); m.setRectToRect(view, buf, Matrix.ScaleToFit.FILL);
            float s = Math.max((float) h / previewSize.getHeight(), (float) w / previewSize.getWidth()); m.postScale(s, s, cx, cy); m.postRotate(90 * (rot - 2), cx, cy);
        } else if (rot == Surface.ROTATION_180) m.postRotate(180, cx, cy);
        tv.setTransform(m);
    }

    // ------------------------------------------------------------------ Kamera, Sitzung, Vorschau

    private void openCamera() {
        if (Build.VERSION.SDK_INT < 30) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { say(getString(R.string.permission_needed)); return; }
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            CameraCharacteristics cc = cm.getCameraCharacteristics(pair.logicalId);
            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION); logicalOrient = so == null ? 90 : so;
            zoomRange = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (zoomRange == null) { say(getString(R.string.switch_fail)); return; }
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = null;
            for (Size s : map.getOutputSizes(SurfaceTexture.class)) { if (s.getWidth() * 3 != s.getHeight() * 4 || s.getWidth() > 1600) continue; if (best == null || s.getWidth() > best.getWidth()) best = s; }
            if (best != null) previewSize = best;
            Size jpg = null;
            for (Size s : map.getOutputSizes(ImageFormat.JPEG)) { if (Math.max(s.getWidth(), s.getHeight()) > 4640) continue; if (jpg == null || (long) s.getWidth() * s.getHeight() > (long) jpg.getWidth() * jpg.getHeight()) jpg = s; }
            if (jpg == null) jpg = map.getOutputSizes(ImageFormat.JPEG)[0];
            reader = ImageReader.newInstance(jpg.getWidth(), jpg.getHeight(), ImageFormat.JPEG, 3); reader.setOnImageAvailableListener(this::got, bg);
            ui.post(this::fit);
            cm.openCamera(pair.logicalId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) { cam = c; if (closed) { c.close(); return; } startSession(); }
                @Override public void onDisconnected(CameraDevice c) { c.close(); cam = null; }
                @Override public void onError(CameraDevice c, int err) { c.close(); cam = null; sayUi(getString(R.string.cap_open_fail, "error " + err)); }
            }, bg);
        } catch (Exception e) { say(getString(R.string.cap_open_fail, e.toString())); }
    }

    private void startSession() {
        try {
            SurfaceTexture st = tv.getSurfaceTexture(); st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight()); pv = new Surface(st);
            cam.createCaptureSession(java.util.Arrays.asList(pv, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) { if (closed || cam == null) return; sess = s; setZoomPreview(1f); bg.postDelayed(LensSwitchActivity.this::calibrate, 700); }
                @Override public void onConfigureFailed(CameraCaptureSession s) { sayUi(getString(R.string.cap_cfg_fail)); }
            }, bg);
        } catch (Exception e) { say(getString(R.string.cap_error, e.toString())); }
    }

    private final CameraCaptureSession.CaptureCallback previewCb = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
            String id = Build.VERSION.SDK_INT >= 29 ? res.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) : null;
            if (id != null) lastActive = id;
            String w = waitFor;
            if (w != null) { if (w.equals(id)) okCount++; else okCount = 0; if (okCount >= 3) { waitFor = null; Runnable r2 = onReady; onReady = null; if (r2 != null) r2.run(); } }
        }
    };

    private void setZoomPreview(float z) {
        if (sess == null || cam == null) return;
        try {
            zNow = z;
            CaptureRequest.Builder b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); b.addTarget(pv);
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z); b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            sess.setRepeatingRequest(b.build(), previewCb, bg);
        } catch (Exception e) { sayUi(getString(R.string.cap_error, e.toString())); }
    }

    /** Welche Linse ist bei Zoom 1,0 aktiv? Daraus die Zoomfaktoren der beiden Linsen aus den Brennweiten. */
    private void calibrate() {
        refId = lastActive;
        if (refId == null) { sayUi(getString(R.string.switch_fail)); return; }
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        equivRef = refId.equals(pair.a.id) ? pair.a.equiv35 : refId.equals(pair.b.id) ? pair.b.equiv35 : DualProbe.equiv35(cm, refId);
        if (equivRef <= 0) { sayUi(getString(R.string.switch_fail)); return; }
        zA = clampZ(pair.a.equiv35 / equivRef); zB = clampZ(pair.b.equiv35 / equivRef);
        setZoomPreview(zA);
        sayUi(getString(R.string.switch_ready, pair.a.id + " (" + String.format(Locale.ROOT, "%.2f", zA) + "x)", pair.b.id + " (" + String.format(Locale.ROOT, "%.2f", zB) + "x)"));
    }

    private float clampZ(float z) { return Math.max(zoomRange.getLower(), Math.min(zoomRange.getUpper(), z)); }

    // ------------------------------------------------------------------ Aufnahme: A, umschalten, B

    private void shoot() {
        if (busy || sess == null || cam == null || equivRef <= 0) return;
        busy = true; jpegA = jpegB = null; actA = actB = null; shutter.setAlpha(0.4f); say(getString(R.string.switch_hold));
        sound.play(MediaActionSound.SHUTTER_CLICK);
        stillAt(zA, true);
    }

    private void stillAt(final float z, final boolean first) {
        try {
            CaptureRequest.Builder b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE); b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z); b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            rotShot = (logicalOrient - getWindowManager().getDefaultDisplay().getRotation() * 90 + 360) % 360;
            if (first) { usedZA = z; } else { usedZB = z; }
            sess.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    String id = Build.VERSION.SDK_INT >= 29 ? res.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) : null;
                    if (first) { actA = id; tA = System.nanoTime(); switchTo(pair.b.id, zB, 0); } else { actB = id; tB = System.nanoTime(); }
                }
            }, bg);
        } catch (Exception e) { fail(e.toString()); }
    }

    /** Zoom auf Linse B stellen, warten bis B aktiv ist (ggf. Zoom nachjustieren), dann zweites Foto. */
    private void switchTo(final String target, final float z, final int attempt) {
        okCount = 0; waitFor = target; usedZB = z;
        onReady = () -> stillAt(z, false);
        setZoomPreview(z);
        bg.postDelayed(() -> {
            if (waitFor != null && target.equals(waitFor) && zNow == z) {                      // nicht umgeschaltet
                waitFor = null; onReady = null;
                if (attempt >= 4) { fail(getString(R.string.switch_fail)); return; }
                float nz = clampZ(z < 1f ? z * 0.9f : z * 1.12f);
                if (nz == z) { fail(getString(R.string.switch_fail)); return; }
                switchTo(target, nz, attempt + 1);
            }
        }, 900);
    }

    private void got(ImageReader r) {
        Image im = r.acquireLatestImage(); if (im == null) return;
        byte[] data; try { ByteBuffer bb = im.getPlanes()[0].getBuffer(); data = new byte[bb.remaining()]; bb.get(data); } finally { im.close(); }
        if (!busy) return;
        boolean done;
        synchronized (this) { if (jpegA == null) jpegA = data; else if (jpegB == null) jpegB = data; done = jpegA != null && jpegB != null; }
        if (done) finish2();
    }

    private void finish2() {
        final byte[] a = jpegA, b = jpegB; final int rot = rotShot;
        final long dtMs = Math.max(0, (tB - tA) / 1000000);
        ui.post(() -> say(getString(R.string.cap_processing)));
        Processor.Input in = new Processor.Input();
        in.jpegA = a; in.jpegB = b; in.rotA = rot; in.rotB = rot;
        in.equivA = equivRef * usedZA; in.equivB = equivRef * usedZB;          // wirksame Brennweite des Bildes aus dem logischen Strom
        in.aIsLeft = Processor.aIsLeft(pair, getWindowManager().getDefaultDisplay().getRotation()) ^ Prefs.get(this, Prefs.SWAP);
        final String lens = actA + " → " + actB;
        new Thread(() -> {
            try {
                Processor.Output o = Processor.run(this, in);
                ui.post(() -> { say(getString(R.string.cap_saved, o.summary) + "\n" + getString(R.string.switch_info, String.valueOf(lens), String.valueOf(dtMs)) + (o.align.isEmpty() ? "" : "\n" + o.align)); lastUri = o.preview; showThumb(o.preview); });
            } catch (Throwable t) { ui.post(() -> say(getString(R.string.cap_error, t.toString()))); }
            finally { busy = false; ui.post(() -> shutter.setAlpha(1f)); setZoomPreview(zA); }
        }, "process").start();
    }

    private void fail(String msg) { busy = false; ui.post(() -> { shutter.setAlpha(1f); say(msg); }); setZoomPreview(zA); }

    private void showThumb(android.net.Uri u) { if (u == null) return; Chrome.loadLatest(this, thumb); }

    private void closeCamera() {
        closed = true;
        try { if (sess != null) sess.close(); } catch (Exception ignored) {} sess = null;
        try { if (cam != null) cam.close(); } catch (Exception ignored) {} cam = null;
        if (reader != null) { reader.close(); reader = null; }
    }

    private void say(String s) { if (status != null) status.setText(s); }
    private void sayUi(String s) { ui.post(() -> say(s)); }
}
