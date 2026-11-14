package com.example.stereocam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Zwei JPEGs der beiden Kameras -> entzerrtes Stereopaar -> Dateien (MPO, SBS, .vr.jpg, Anaglyphe, L/R). */
final class Processor {

    static final class Input {
        byte[] jpegA, jpegB;             // Rohaufnahmen der Kameras A und B
        int rotA, rotB;                  // noetige Drehung im Uhrzeigersinn (Grad), damit das Bild aufrecht steht
        float equivA, equivB;            // kleinbildaequivalente Brennweiten
        boolean aIsLeft = true;          // aus Pose und Bildschirmdrehung
    }

    static final class Output {
        String summary = "";
        Uri preview;                     // Uri des ersten gespeicherten Bildes
        String align = "";               // Kurzbericht der Entzerrung (fuer die Diagnose)
    }

    static final int MAX_EDGE = 3600;    // Speicher: pro Auge hoechstens so viele Pixel an der langen Kante

    private Processor() {}

    static Output run(Context ctx, Input in) throws Exception {
        Output out = new Output();
        Bitmap ba = rotate(decode(in.jpegA), in.rotA), bb = rotate(decode(in.jpegB), in.rotB);
        int wa = ba.getWidth(), ha = ba.getHeight(), wb = bb.getWidth(), hb = bb.getHeight();
        int[] pa = new int[wa * ha], pb = new int[wb * hb];
        ba.getPixels(pa, 0, wa, 0, 0, wa, ha); ba.recycle();
        bb.getPixels(pb, 0, wb, 0, 0, wb, hb); bb.recycle();

        // 1) Bildwinkel angleichen: die weitwinkligere Kamera wird mittig auf den Bildwinkel der anderen zugeschnitten
        double fa = in.equivA > 0 ? in.equivA : 1, fb = in.equivB > 0 ? in.equivB : 1;
        double cropA = fa < fb ? fa / fb : 1, cropB = fb < fa ? fb / fa : 1;           // Anteil des Bildes, der bleibt
        StringBuilder note = new StringBuilder();
        if (Math.abs(fa / fb - 1) > 0.03 && in.equivA > 0 && in.equivB > 0) {         // Brennweiten-Verhaeltnis aus den Bildern selbst nachpruefen
            try {
                boolean aWide = fa < fb; double nom = aWide ? cropA : cropB;
                double[] r = aWide ? StereoAligner.refineCrop(pa, wa, ha, pb, wb, hb, nom) : StereoAligner.refineCrop(pb, wb, hb, pa, wa, ha, nom);
                if (r[1] > r[2] + 0.02 && r[1] > 0.3) { if (aWide) cropA = r[0]; else cropB = r[0]; note.append(String.format(Locale.ROOT, "crop refined %.3f -> %.3f (ncc %.2f -> %.2f)\n", nom, r[0], r[2], r[1])); }
                else note.append(String.format(Locale.ROOT, "crop nominal %.3f kept (ncc %.2f)\n", nom, r[2]));
            } catch (Throwable e) { note.append("crop refine failed: ").append(e).append('\n'); }
        }
        double aw = wa * cropA, ah = ha * cropA, bw = wb * cropB, bh = hb * cropB;
        // Seitenverhaeltnis angleichen (falls Sensoren verschieden)
        double ra = aw / ah, rb = bw / bh;
        if (Math.abs(ra / rb - 1) > 0.02) {
            if (ra > rb) aw = ah * rb; else ah = aw / rb;
            if (rb > ra) bw = bh * ra; else bh = bw / ra;
        }
        int tw = (int) Math.floor(Math.min(aw, bw)), th = (int) Math.floor(tw / (aw / ah));
        if (Math.max(tw, th) > MAX_EDGE) { double s = (double) MAX_EDGE / Math.max(tw, th); tw = (int) (tw * s); th = (int) (th * s); }
        tw &= ~1; th &= ~1;
        int[] la = StereoAligner.resample(pa, wa, ha, (wa - aw) / 2, (ha - ah) / 2, aw, ah, tw, th); pa = null;
        int[] lb = StereoAligner.resample(pb, wb, hb, (wb - bw) / 2, (hb - bh) / 2, bw, bh, tw, th); pb = null;
        int[] left = in.aIsLeft ? la : lb, right = in.aIsLeft ? lb : la;

        out.align = note.toString();
        // 2) Restfehler schaetzen und rechtes Bild entzerren
        int W = tw, H = th;
        if (Prefs.get(ctx, Prefs.ALIGN)) {
            int ww = Math.min(640, W), wh = Math.max(8, Math.round((float) H * ww / W)); double k = (double) W / ww;
            int[] l2 = StereoAligner.resample(left, W, H, 0, 0, W, H, ww, wh), r2 = StereoAligner.resample(right, W, H, 0, 0, W, H, ww, wh);
            StereoAligner.Fit f = StereoAligner.estimate(StereoAligner.gray(l2, ww * wh), StereoAligner.gray(r2, ww * wh), ww, wh);
            out.align = note + f.toString();
            if (f.ok) {
                // Konvergenz: optional ueber Einstellung - 0 = Hauptmotiv (Median), sonst der naechste Punkt ("Fenster hinter den Bildrand")
                int[] rect = StereoAligner.validRect(f, k, W, H);
                right = StereoAligner.warpRight(right, W, H, f, k, rect); left = StereoAligner.crop(left, W, H, rect);
                W = rect[2] - rect[0]; H = rect[3] - rect[1]; W &= ~1; H &= ~1;
                if (W != rect[2] - rect[0] || H != rect[3] - rect[1]) { int[] r3 = {0, 0, W, H}; left = cropTL(left, rect[2] - rect[0], W, H); right = cropTL(right, rect[2] - rect[0], W, H); }
            }
        }
        write(ctx, left, right, W, H, out);
        return out;
    }

    private static int[] cropTL(int[] src, int sw, int w, int h) {
        int[] o = new int[w * h]; for (int y = 0; y < h; y++) System.arraycopy(src, y * sw, o, y * w, w); return o;
    }

    private static Bitmap decode(byte[] jpeg) {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        int s = 1; while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= MAX_EDGE * 1.2) s *= 2;
        o = new BitmapFactory.Options(); o.inSampleSize = s; o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
    }

    private static Bitmap rotate(Bitmap b, int deg) {
        if (deg % 360 == 0) return b;
        Matrix m = new Matrix(); m.postRotate(deg);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true); if (r != b) b.recycle(); return r;
    }

    private static byte[] jpeg(int[] px, int w, int h, int q) {
        Bitmap b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream o = new ByteArrayOutputStream(w * h / 3); b.compress(Bitmap.CompressFormat.JPEG, q, o); b.recycle(); return o.toByteArray();
    }

    // ------------------------------------------------------------------ Speichern

    private static void write(Context ctx, int[] L, int[] R, int w, int h, Output out) throws Exception {
        String base = "STEREO_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        byte[] jl = jpeg(L, w, h, 95), jr = jpeg(R, w, h, 95);
        StringBuilder names = new StringBuilder();
        if (Prefs.get(ctx, Prefs.MPO)) { Uri u = save(ctx, base + ".mpo", "image/mpo", MpoWriter.build(jl, jr)); if (out.preview == null) out.preview = u; names.append(base).append(".mpo "); }
        if (Prefs.get(ctx, Prefs.SBS)) {
            int ew = Math.min(w, 3000), eh = Math.round((float) h * ew / w);
            Bitmap bl = Bitmap.createScaledBitmap(Bitmap.createBitmap(L, w, h, Bitmap.Config.ARGB_8888), ew, eh, true), br = Bitmap.createScaledBitmap(Bitmap.createBitmap(R, w, h, Bitmap.Config.ARGB_8888), ew, eh, true);
            Bitmap sbs = Bitmap.createBitmap(ew * 2, eh, Bitmap.Config.ARGB_8888); android.graphics.Canvas c = new android.graphics.Canvas(sbs);
            c.drawBitmap(bl, 0, 0, null); c.drawBitmap(br, ew, 0, null);
            ByteArrayOutputStream o = new ByteArrayOutputStream(); sbs.compress(Bitmap.CompressFormat.JPEG, 93, o); sbs.recycle(); bl.recycle(); br.recycle();
            Uri u = save(ctx, base + "_SBS.jpg", "image/jpeg", o.toByteArray()); if (out.preview == null) out.preview = u; names.append(base).append("_SBS.jpg ");
        }
        if (Prefs.get(ctx, Prefs.VRJPG)) {
            VrJpgWriter.Meta m = new VrJpgWriter.Meta();
            m.fullW = w; m.fullH = Math.max(Math.round(w / 2f), h); m.cropW = w; m.cropH = h; m.cropLeft = 0; m.cropTop = (m.fullH - h) / 2;
            Uri u = save(ctx, base + ".vr.jpg", "image/jpeg", VrJpgWriter.build(jl, jr, null, m)); if (out.preview == null) out.preview = u; names.append(base).append(".vr.jpg ");
        }
        if (Prefs.get(ctx, Prefs.ANAGLYPH)) {
            int[] an = new int[w * h]; for (int i = 0; i < an.length; i++) an[i] = 0xFF000000 | (L[i] & 0xFF0000) | (R[i] & 0x00FFFF);
            Uri u = save(ctx, base + "_anaglyph.jpg", "image/jpeg", jpeg(an, w, h, 93)); if (out.preview == null) out.preview = u; names.append(base).append("_anaglyph.jpg ");
        }
        if (Prefs.get(ctx, Prefs.PAIR)) {
            Uri u = save(ctx, base + "_L.jpg", "image/jpeg", jl); save(ctx, base + "_R.jpg", "image/jpeg", jr); if (out.preview == null) out.preview = u; names.append(base).append("_L/_R.jpg ");
        }
        if (names.length() == 0) { Uri u = save(ctx, base + "_SBS_fallback.jpg", "image/jpeg", jl); out.preview = u; names.append("(no format selected: left eye saved)"); }
        out.summary = w + "x" + h + "  " + names.toString().trim();
    }

    /** Speichert unter Pictures/StereoCam (MediaStore ab Android 10; sonst direkt im Dateisystem). */
    static Uri save(Context ctx, String name, String mime, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name); v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StereoCam");
            Uri u = null;
            try { u = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v); } catch (Exception e) { u = null; }
            if (u == null) {                                                         // unbekannter MIME-Typ fuer Bilder (z. B. mpo): in "Download"
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream"); v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StereoCam");
                u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            }
            if (u == null) throw new java.io.IOException("MediaStore insert failed: " + name);
            try (OutputStream os = cr.openOutputStream(u)) { os.write(data); }
            return u;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "StereoCam"); dir.mkdirs();
        File f = new File(dir, name);
        try (FileOutputStream os = new FileOutputStream(f)) { os.write(data); }
        android.media.MediaScannerConnection.scanFile(ctx, new String[] {f.getAbsolutePath()}, null, null);
        return Uri.fromFile(f);
    }

    /** Welche physische Kamera liegt aus Sicht des Fotografen links? Pose-Vektoren im Geraetekoordinatensystem (x rechts, y oben, natuerliche Lage). */
    static boolean aIsLeft(DualProbe.Pair p, int displayRotation) {
        if (p.a.pose == null || p.b.pose == null) return true;
        double rx, ry;                                                      // "rechts" des Nutzers im Geraetesystem
        switch (displayRotation) { case 1: rx = 0; ry = -1; break; case 2: rx = -1; ry = 0; break; case 3: rx = 0; ry = 1; break; default: rx = 1; ry = 0; }
        double pa = p.a.pose[0] * rx + p.a.pose[1] * ry, pb = p.b.pose[0] * rx + p.b.pose[1] * ry;
        return pa <= pb;
    }
}
