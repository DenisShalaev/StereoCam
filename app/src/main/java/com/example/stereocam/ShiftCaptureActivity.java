package com.example.stereocam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
import android.widget.SeekBar;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/**
 * Stereoaufnahme mit EINER Kamera und Verschiebung: erstes Bild, Telefon seitlich verschieben, zweites Bild.
 * Hilfen: empfohlene Verschiebung (1/30 des Motivabstands), halbtransparentes erstes Bild ("Geist") zum Deckungsgleich-Machen,
 * Drehwinkel zwischen den Aufnahmen (Rotationsvektor-Sensor), Belichtung/Weissabgleich/Fokus nach dem ersten Bild gesperrt.
 * Die Restfehler (Hoehenversatz, Neigung, Massstab) korrigiert danach derselbe Aligner wie bei zwei Kameras.
 * Hinweis: Die Verschiebung selbst laesst sich mit Beschleunigungssensoren nicht genau messen; sie bleibt Augenmass.
 */
public class ShiftCaptureActivity extends Activity implements SensorEventListener {

    private TextureView tv; private FrameLayout root; private ImageView ghost, thumb;
    private TextView status, angles, dist; private View shutter; private TextView reset;
    private HandlerThread ht; private Handler bg, ui;
    private CameraDevice cam; private CameraCaptureSession sess; private ImageReader reader;
    private String camId; private int orient = 90; private Size previewSize = new Size(1280, 960);
    private float minFocusDioptre = 0; private boolean aeLockOk = false;
    private final MediaActionSound sound = new MediaActionSound();
    private boolean busy = false, closed = false;
    private android.net.Uri lastUri; private String cfgAtStart = "";

    // Zustand der Aufnahme
    private byte[] shot1; private int rot1; private float[] r1;           // erstes Bild, seine Drehung, Sensorlage
    private float[] rNow = new float[9]; private boolean haveRot = false;
    private Float focusDist; private SensorManager sm;
    private long lastAngleUpdate;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Handler(getMainLooper());
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);        // Drehung zwischen den Bildern ausschliessen
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);

        root = new FrameLayout(this); root.setBackgroundColor(0xFF000000); setContentView(root);
        tv = new TextureView(this); root.addView(tv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        ghost = new ImageView(this); ghost.setScaleType(ImageView.ScaleType.FIT_XY); ghost.setAlpha(0.45f); ghost.setVisibility(View.GONE);
        root.addView(ghost, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fit());

        status = Ui.text(this, getString(R.string.shift_step1), 14, Ui.TEXT); status.setShadowLayer(4, 0, 1, 0xFF000000); status.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); sp.topMargin = Ui.dp(this, 12); sp.leftMargin = sp.rightMargin = Ui.dp(this, 64);
        root.addView(status, sp);

        // Bedienfeld unten: Winkel, Abstand/Verschiebung, Regler
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(this, 16, Ui.alpha(Ui.BG, 215), Ui.EDGE)); int pd = Ui.dp(this, 12); panel.setPadding(pd, pd, pd, pd);
        angles = Ui.text(this, "", 13, Ui.GREY); angles.setTypeface(android.graphics.Typeface.MONOSPACE);
        dist = Ui.text(this, "", 14, Ui.TEXT);
        SeekBar sb = new SeekBar(this); sb.setMax(100); sb.setProgress(readDist());
        sb.getProgressDrawable().setTint(Ui.ACCENT); sb.getThumb().setTint(Ui.ACCENT);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { Prefs.setInt(ShiftCaptureActivity.this, Prefs.DIST, p); updateDist(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        panel.addView(angles); panel.addView(dist); panel.addView(sb);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(Ui.dp(this, 340), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); pp.bottomMargin = Ui.dp(this, 110);
        root.addView(panel, pp);

        shutter = new View(this); shutter.setBackground(Ui.round(this, 36, Ui.alpha(Ui.TEXT, 235), Ui.ACCENT));
        FrameLayout.LayoutParams sh = new FrameLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); sh.bottomMargin = Ui.dp(this, 24);
        root.addView(shutter, sh); shutter.setOnClickListener(v -> shoot());

        reset = Ui.text(this, getString(R.string.shift_reset), 13, Ui.TEXT); reset.setBackground(Ui.round(this, 18, Ui.alpha(Ui.BG, 200), Ui.EDGE));
        reset.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8)); reset.setVisibility(View.GONE);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END); rp.rightMargin = Ui.dp(this, 24); rp.bottomMargin = Ui.dp(this, 42);
        root.addView(reset, rp); reset.setOnClickListener(v -> resetShots());

        TextView swap = Ui.text(this, getString(R.string.swap_eyes), 13, Ui.TEXT); swap.setBackground(Ui.round(this, 18, Ui.alpha(Ui.BG, 200), Ui.EDGE));
        swap.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END); wp.rightMargin = Ui.dp(this, 24); wp.bottomMargin = Ui.dp(this, 90);
        root.addView(swap, wp);
        swap.setOnClickListener(v -> { Prefs.set(this, Prefs.SWAP, !Prefs.get(this, Prefs.SWAP)); updateDist(); updateStatus(); });

        thumb = new ImageView(this); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP); thumb.setBackground(Ui.round(this, 12, Ui.CARD, Ui.EDGE)); thumb.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60), Gravity.BOTTOM | Gravity.START); tp.leftMargin = Ui.dp(this, 24); tp.bottomMargin = Ui.dp(this, 30);
        root.addView(thumb, tp);
        thumb.setOnClickListener(v -> startActivity(new Intent(this, StereoGalleryActivity.class)));
        Chrome.gear(this, root); cfgAtStart = Chrome.cfg(this);
        focus = new FocusControl(this, root, tv, this::refocus); compose = new ComposeOverlay(this, root, tv); compose.setBaselineMm(0); compose.setInfoBottomDp(240);
        root.setOnApplyWindowInsetsListener((v, in) -> { v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom()); return in; });
        updateDist(); updateStatus();
    }

    private int readDist() { return Prefs.getInt(this, Prefs.DIST, 50); }

    private boolean secondFirst() { return Prefs.get(this, Prefs.SWAP); }     // false: erstes Bild = linkes Auge, Telefon nach rechts schieben

    private void updateDist() {
        double d = ShiftMath.distanceCm(readDist()), bm = ShiftMath.baselineMm(d);
        String dir = getString(secondFirst() ? R.string.dir_left : R.string.dir_right);
        String dd = d >= 100 ? String.format(Locale.ROOT, "%.1f m", d / 100) : String.format(Locale.ROOT, "%.0f cm", d);
        String bb = bm >= 10 ? String.format(Locale.ROOT, "%.1f cm", bm / 10) : String.format(Locale.ROOT, "%.1f mm", bm);
        dist.setText(getString(R.string.shift_dist, dd, bb, dir));
    }

    private void updateStatus() { status.setText(shot1 == null ? getString(R.string.shift_step1) : getString(R.string.shift_step2)); }

    // ------------------------------------------------------------------ Lebenszyklus

    @Override protected void onResume() {
        super.onResume(); if (compose != null) compose.listen(); if (Chrome.restartIfChanged(this, cfgAtStart)) return; Chrome.loadLatest(this, thumb); closed = false;
        ht = new HandlerThread("cam"); ht.start(); bg = new Handler(ht.getLooper());
        Sensor rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv != null) sm.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME);
        else angles.setText(R.string.shift_no_sensor);
        if (tv.isAvailable()) openCamera(); else tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { fit(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        });
    }

    @Override protected void onPause() {
        sm.unregisterListener(this); if (compose != null) compose.stop(); closeCamera(); if (ht != null) { ht.quitSafely(); ht = null; } super.onPause();
    }

    // ------------------------------------------------------------------ Sensor: Drehung gegenueber dem ersten Bild

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        SensorManager.getRotationMatrixFromVector(rNow, e.values); haveRot = true;
        long t = System.currentTimeMillis(); if (t - lastAngleUpdate < 100) return; lastAngleUpdate = t;
        if (r1 == null) { angles.setText(R.string.shift_angles_wait); angles.setTextColor(Ui.GREY); return; }
        double[] a = ShiftMath.relAnglesDeg(r1, rNow); double m = ShiftMath.maxAbs(a);
        angles.setText(getString(R.string.shift_angles, String.format(Locale.ROOT, "%+.1f", a[0]), String.format(Locale.ROOT, "%+.1f", a[1]), String.format(Locale.ROOT, "%+.1f", a[2])));
        angles.setTextColor(m < 1.0 ? 0xFF66E08A : m < 2.5 ? 0xFFFFD54F : 0xFFFF6B6B);
    }
    @Override public void onAccuracyChanged(Sensor s, int a) {}

    // ------------------------------------------------------------------ Vorschaugeometrie (wie Camera2Basic)

    private void fit() {
        if (root.getWidth() == 0) return;
        int rot = getWindowManager().getDefaultDisplay().getRotation();
        boolean land = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270;
        double ar = land ? (double) previewSize.getWidth() / previewSize.getHeight() : (double) previewSize.getHeight() / previewSize.getWidth();
        int bw = root.getWidth(), bh = root.getHeight(), w, h;
        if ((double) bw / bh > ar) { h = bh; w = (int) Math.round(bh * ar); } else { w = bw; h = (int) Math.round(bw / ar); }
        for (View v : new View[] {tv, ghost}) { FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams(); if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; v.setLayoutParams(lp); } }
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

    // ------------------------------------------------------------------ Kamera

    private void openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { say(getString(R.string.permission_needed)); return; }
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            camId = null;
            for (String id : cm.getCameraIdList()) { Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING); if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) { camId = id; break; } }
            if (camId == null) { say(getString(R.string.cap_open_fail, "no back camera")); return; }
            CameraCharacteristics cc = cm.getCameraCharacteristics(camId);
            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION); orient = so == null ? 90 : so;
            Float mf = cc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE); minFocusDioptre = mf == null ? 0 : mf;
            focus.setCharacteristics(cc);
            Boolean ae = cc.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE); aeLockOk = ae != null && ae;
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = null;
            for (Size s : map.getOutputSizes(SurfaceTexture.class)) { if (s.getWidth() * 3 != s.getHeight() * 4 || s.getWidth() > 1600) continue; if (best == null || s.getWidth() > best.getWidth()) best = s; }
            if (best != null) previewSize = best;
            Size jpg = null;
            for (Size s : map.getOutputSizes(ImageFormat.JPEG)) { if (Math.max(s.getWidth(), s.getHeight()) > 4640) continue; if (jpg == null || (long) s.getWidth() * s.getHeight() > (long) jpg.getWidth() * jpg.getHeight()) jpg = s; }
            if (jpg == null) jpg = map.getOutputSizes(ImageFormat.JPEG)[0];
            reader = ImageReader.newInstance(jpg.getWidth(), jpg.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(this::got, bg);
            ui.post(this::fit);
            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) { cam = c; if (closed) { c.close(); return; } startSession(); }
                @Override public void onDisconnected(CameraDevice c) { c.close(); cam = null; }
                @Override public void onError(CameraDevice c, int err) { c.close(); cam = null; sayUi(getString(R.string.cap_open_fail, "error " + err)); }
            }, bg);
        } catch (Exception e) { say(getString(R.string.cap_open_fail, e.toString())); }
    }

    private CaptureRequest.Builder previewReq; private Surface pvSurface;

    private void startSession() {
        try {
            SurfaceTexture st = tv.getSurfaceTexture(); st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight()); pvSurface = new Surface(st);
            cam.createCaptureSession(Arrays.asList(pvSurface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) { if (closed || cam == null) return; sess = s; startPreview(); }
                @Override public void onConfigureFailed(CameraCaptureSession s) { sayUi(getString(R.string.cap_cfg_fail)); }
            }, bg);
        } catch (Exception e) { say(getString(R.string.cap_error, e.toString())); }
    }

    /** Vorschau; nach dem ersten Bild mit gesperrter Belichtung, Weissabgleich und (wenn moeglich) Fokus. */
    private void startPreview() {
        try {
            previewReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); previewReq.addTarget(pvSurface);
            applyLocks(previewReq, shot1 != null); if (shot1 == null) focus.apply(previewReq, false);
            sess.setRepeatingRequest(previewReq.build(), compose.callback(), bg);
        } catch (Exception e) { sayUi(getString(R.string.cap_error, e.toString())); }
    }

    /** Fokus geaendert: nur vor dem ersten Foto (danach ist der Fokus fuer das zweite Foto gesperrt). */
    private void refocus() {
        if (sess == null || cam == null || shot1 != null) return;
        boolean trig = focus.takeTrigger(); startPreview();
        if (trig && previewReq != null) {
            try { CaptureRequest.Builder t = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); t.addTarget(pvSurface); applyLocks(t, false); focus.apply(t, true); sess.capture(t.build(), null, bg); } catch (Exception e) { sayUi(getString(R.string.cap_error, e.toString())); }
        }
    }

    private void applyLocks(CaptureRequest.Builder b, boolean lock) {
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);   // stabilisiert sonst die Verschiebung weg
        if (lock && focusDist != null && minFocusDioptre > 0) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF); b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDist);
        } else b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        if (aeLockOk) b.set(CaptureRequest.CONTROL_AE_LOCK, lock);
        b.set(CaptureRequest.CONTROL_AWB_LOCK, lock);
    }

    // ------------------------------------------------------------------ Aufnahme

    private void shoot() {
        if (busy || sess == null || cam == null) return;
        busy = true; shutter.setAlpha(0.4f);
        final boolean first = shot1 == null;
        try {
            CaptureRequest.Builder b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE); b.addTarget(reader.getSurface());
            applyLocks(b, !first); if (first) focus.apply(b, false); b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            sound.play(MediaActionSound.SHUTTER_CLICK);
            final float[] rAtShot = haveRot ? rNow.clone() : null;
            sess.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    if (first) { focusDist = res.get(CaptureResult.LENS_FOCUS_DISTANCE); r1 = rAtShot; ui.post(() -> { if (shot1 != null) startPreviewLocked(); }); }
                }
            }, bg);
            pendingFirst = first; pendingRot = (orient - getWindowManager().getDefaultDisplay().getRotation() * 90 + 360) % 360;
        } catch (Exception e) { busy = false; shutter.setAlpha(1f); say(getString(R.string.cap_error, e.toString())); }
    }

    private FocusControl focus; private ComposeOverlay compose;
    private boolean pendingFirst; private int pendingRot;

    private void got(ImageReader r) {
        Image im = r.acquireLatestImage(); if (im == null) return;
        byte[] data; try { ByteBuffer bb = im.getPlanes()[0].getBuffer(); data = new byte[bb.remaining()]; bb.get(data); } finally { im.close(); }
        if (!busy) return;
        if (pendingFirst) {
            shot1 = data; rot1 = pendingRot;
            ui.post(() -> { showGhost(); updateStatus(); reset.setVisibility(View.VISIBLE); busy = false; shutter.setAlpha(1f); startPreviewLocked(); });
        } else {
            final byte[] second = data, first = shot1; final int rot2 = pendingRot;
            ui.post(() -> say(getString(R.string.cap_processing)));
            Processor.Input in = new Processor.Input();
            in.jpegA = first; in.jpegB = second; in.rotA = rot1; in.rotB = rot2; in.equivA = in.equivB = 1;
            in.aIsLeft = !secondFirst();                                // erstes Bild = linkes Auge (Telefon nach rechts), ausser "L/R tauschen"
            new Thread(() -> {
                try {
                    Processor.Output o = Processor.run(this, in);
                    ui.post(() -> { say(getString(R.string.cap_saved, o.summary) + (o.align.isEmpty() ? "" : "\n" + o.align)); lastUri = o.preview; showThumb(o.preview); });
                } catch (Throwable t) { ui.post(() -> say(getString(R.string.cap_error, t.toString()))); }
                finally { ui.post(() -> { resetShots(); busy = false; shutter.setAlpha(1f); }); }
            }, "process").start();
        }
    }

    private void startPreviewLocked() { if (sess != null && cam != null) startPreview(); }

    private void showGhost() {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(shot1, 0, shot1.length, o);
            int s = 1; while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 1400) s *= 2;
            o = new BitmapFactory.Options(); o.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeByteArray(shot1, 0, shot1.length, o);
            if (rot1 != 0) { Matrix m = new Matrix(); m.postRotate(rot1); Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true); if (r != b) b.recycle(); b = r; }
            ghost.setImageBitmap(b); ghost.setVisibility(View.VISIBLE);
        } catch (Exception e) { say(getString(R.string.cap_error, e.toString())); }
    }

    private void resetShots() {
        shot1 = null; r1 = null; focusDist = null; ghost.setVisibility(View.GONE); ghost.setImageBitmap(null); reset.setVisibility(View.GONE);
        angles.setText(R.string.shift_angles_wait); updateStatus(); startPreviewLocked();
    }

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
