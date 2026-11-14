package com.example.stereocam;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Exportiert die Bewegungsansicht (aktuelle Einstellungen: Ausschlag, Tempo, Bahn, Tiefenstaerke) als animiertes GIF oder MP4-Video. */
final class AnimExport {
    static final int GIF = 0, MP4 = 1;
    private AnimExport() {}

    static void run(Context c, Uri src, String name, int kind) throws Exception {
        byte[] data = StereoFile.readAll(c, src);
        StereoFile.Pair p = StereoFile.load(data, name, 1600);
        if (p == null) throw new IOException(c.getString(R.string.wig_not_stereo));
        int w = p.left.getWidth(), h = p.left.getHeight(); int[] l = new int[w * h], r = new int[w * h];
        p.left.getPixels(l, 0, w, 0, 0, w, h); p.right.getPixels(r, 0, w, 0, 0, w, h); p.left.recycle(); p.right.recycle();
        final double[] AMPS = {1, 2, 3, 4, 6}, PERIODS = {1.5, 2.5, 4};
        double amp = AMPS[Math.max(0, Math.min(4, Prefs.getInt(c, "wig_amp2", 1)))], period = PERIODS[Math.max(0, Math.min(2, Prefs.getInt(c, "wig_per", 1)))];
        int shape = Prefs.getInt(c, "wig_shape", 0); float k = Prefs.getInt(c, "wig_depth", 100) / 100f;
        String base = name.replaceAll("(?i)\\.vr\\.jpg$|(?i)\\.jpe?g$|(?i)\\.mpo$", "").replaceAll("(?i)_sbs$", "");
        if (kind == GIF) {
            WiggleRenderer.Scene s = WiggleRenderer.build(l, r, w, h, 480); l = r = null;
            int fps = 14, n = (int) Math.round(period * fps); List<int[]> samples = new ArrayList<>(); int[] first = null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream(8_000_000); List<int[]> all = new ArrayList<>();
            for (int i = 0; i < n; i++) { int[] f = new int[s.w * s.h]; double[] tv = WigglePath.at(period * i / n, period, amp, shape); WiggleRenderer.render(s, tv[0], tv[1], f, k); all.add(f); }
            for (int i = 0; i < n; i += Math.max(1, n / 4)) samples.add(all.get(i));
            GifWriter g = new GifWriter(bo, s.w, s.h, Math.max(2, 100 / fps), GifWriter.palette(samples));
            for (int[] f : all) g.frame(f); g.finish();
            save(c, base + "_motion.gif", "image/gif", bo.toByteArray(), false);
        } else {
            WiggleRenderer.Scene s = WiggleRenderer.build(l, r, w, h, 720); l = r = null;
            int vw = Math.max(160, s.w & ~15), vh = Math.max(160, s.h & ~15), fps = 24, n = (int) Math.round(period * fps);
            File tmp = new File(c.getCacheDir(), "motion_" + System.nanoTime() + ".mp4");
            String stage = "encoder start";
            VideoWriter vwr;
            try { vwr = new VideoWriter(tmp.getAbsolutePath(), vw, vh, fps); } catch (Throwable e) { throw new IOException("MP4 (" + stage + ", " + vw + "x" + vh + "): " + e, e); }
            int[] f = new int[s.w * s.h], crop = new int[vw * vh];
            try {
                stage = "frames";
                for (int loop = 0; loop < 2; loop++) for (int i = 0; i < n; i++) {
                    double[] tv = WigglePath.at(period * i / n, period, amp, shape); WiggleRenderer.render(s, tv[0], tv[1], f, k);
                    int ox = (s.w - vw) / 2, oy = (s.h - vh) / 2; for (int y = 0; y < vh; y++) System.arraycopy(f, (y + oy) * s.w + ox, crop, y * vw, vw);
                    vwr.frame(crop);
                }
            } catch (Throwable e) { tmp.delete(); throw new IOException("MP4 (" + stage + "): " + e, e);
            } finally { vwr.finish(); }
            if (!vwr.hasOutput() || tmp.length() < 1000) { tmp.delete(); throw new IOException("MP4: encoder produced no video data"); }
            try { byte[] bytes = new byte[(int) tmp.length()]; try (FileInputStream in = new FileInputStream(tmp)) { int off = 0, nn; while (off < bytes.length && (nn = in.read(bytes, off, bytes.length - off)) > 0) off += nn; }
                save(c, base + "_motion.mp4", "video/mp4", bytes, true); } finally { tmp.delete(); }
        }
    }

    private static void save(Context c, String name, String mime, byte[] data, boolean video) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues(); v.put(MediaStore.MediaColumns.DISPLAY_NAME, name); v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/StereoCam");
            Uri u = c.getContentResolver().insert(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new IOException("MediaStore insert failed: " + name);
            try (OutputStream os = c.getContentResolver().openOutputStream(u)) { os.write(data); }
            return;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), "StereoCam"); dir.mkdirs();
        File f = new File(dir, name); try (FileOutputStream os = new FileOutputStream(f)) { os.write(data); }
        android.media.MediaScannerConnection.scanFile(c, new String[] {f.getAbsolutePath()}, null, null);
    }
}
