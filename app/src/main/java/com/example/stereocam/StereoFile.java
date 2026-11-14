package com.example.stereocam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Liest ein Stereopaar aus MPO, .vr.jpg oder einem Side-by-Side-JPEG und liefert zwei Bitmaps (links, rechts). */
final class StereoFile {
    static final class Pair { Bitmap left, right; String kind; }

    private StereoFile() {}

    static byte[] readAll(Context c, Uri u) throws Exception {
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    /** @param name Dateiname (nur als Hinweis fuer SBS), @param maxEdge Zielgroesse der langen Kante beim Dekodieren. @return null, wenn kein Paar erkannt. */
    static Pair load(byte[] data, String name, int maxEdge) {
        Pair p = new Pair();
        byte[] second = mpoSecond(data);
        if (second != null) { p.kind = "MPO"; p.left = decode(data, 0, firstLen(data, second), maxEdge); p.right = decode(second, 0, second.length, maxEdge); }
        if (p.left == null || p.right == null) {
            second = null;
            try { VrJpgReader.Result r = VrJpgReader.parse(data); if (r != null && r.rightJpeg != null) second = r.rightJpeg; } catch (Throwable ignored) {}
            if (second != null) { p.kind = "VR.JPG"; p.left = decode(data, 0, data.length, maxEdge); p.right = decode(second, 0, second.length, maxEdge); }
        }
        if (p.left == null || p.right == null) {
            Bitmap whole = decode(data, 0, data.length, maxEdge * 2);
            boolean sbs = name != null && name.toLowerCase().contains("_sbs");
            if (whole != null && (sbs || whole.getWidth() > 2.4 * whole.getHeight())) {
                int hw = whole.getWidth() / 2; p.kind = "SBS";
                p.left = Bitmap.createBitmap(whole, 0, 0, hw, whole.getHeight()); p.right = Bitmap.createBitmap(whole, hw, 0, hw, whole.getHeight());
            } else return null;
        }
        if (p.left == null || p.right == null) return null;
        if (p.left.getWidth() != p.right.getWidth() || p.left.getHeight() != p.right.getHeight())
            p.right = Bitmap.createScaledBitmap(p.right, p.left.getWidth(), p.left.getHeight(), true);
        return p;
    }

    private static int firstLen(byte[] d, byte[] second) { return d.length - second.length > 0 ? d.length - second.length : d.length; }

    static Bitmap decode(byte[] d, int off, int len, int maxEdge) {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(d, off, len, o);
        int s = 1; while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxEdge) s *= 2;
        o = new BitmapFactory.Options(); o.inSampleSize = s; o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(d, off, len, o);
    }

    /** Zweites Bild eines MPO: ueber die MP-Eintraege (MPF), sonst ueber die zweite JPEG-Kennung. */
    static byte[] mpoSecond(byte[] d) {
        try {
            int p = 2;
            while (p + 4 < d.length && (d[p] & 255) == 0xFF) {
                int m = d[p + 1] & 255, len = ((d[p + 2] & 255) << 8) | (d[p + 3] & 255);
                if (m == 0xDA) break;
                if (m == 0xE2 && d[p + 4] == 'M' && d[p + 5] == 'P' && d[p + 6] == 'F' && d[p + 7] == 0) {
                    int base = p + 8; boolean le = d[base] == 'I';
                    int ifd = base + i32(d, base + 4, le), cnt = i16(d, ifd, le);
                    for (int i = 0; i < cnt; i++) {
                        int e = ifd + 2 + i * 12;
                        if (i16(d, e, le) == 0xB002) {
                            int ent = base + i32(d, e + 8, le), n = i32(d, e + 4, le) / 16;
                            if (n >= 2) {
                                int size = i32(d, ent + 16 + 4, le), off = base + i32(d, ent + 16 + 8, le);
                                if (off > 0 && off + 4 < d.length && (d[off] & 255) == 0xFF && (d[off + 1] & 255) == 0xD8) {
                                    int end = size > 0 && off + size <= d.length ? off + size : d.length;
                                    byte[] r = new byte[end - off]; System.arraycopy(d, off, r, 0, r.length); return r;
                                }
                            }
                        }
                    }
                }
                p += 2 + len;
            }
        } catch (Throwable ignored) {}
        // Rueckfall: zweite JPEG-Kennung hinter dem ersten Bild suchen (nicht in Vorschaubildern: erst ab 20 % der Datei)
        for (int i = d.length / 5; i + 4 < d.length; i++)
            if ((d[i] & 255) == 0xFF && (d[i + 1] & 255) == 0xD8 && (d[i + 2] & 255) == 0xFF && ((d[i + 3] & 255) == 0xE0 || (d[i + 3] & 255) == 0xE1)) {
                byte[] r = new byte[d.length - i]; System.arraycopy(d, i, r, 0, r.length); return r;
            }
        return null;
    }

    private static int i16(byte[] d, int p, boolean le) { return le ? (d[p] & 255) | ((d[p + 1] & 255) << 8) : ((d[p] & 255) << 8) | (d[p + 1] & 255); }
    private static int i32(byte[] d, int p, boolean le) {
        return le ? (d[p] & 255) | ((d[p + 1] & 255) << 8) | ((d[p + 2] & 255) << 16) | ((d[p + 3] & 255) << 24)
                : ((d[p] & 255) << 24) | ((d[p + 1] & 255) << 16) | ((d[p + 2] & 255) << 8) | (d[p + 3] & 255);
    }
}
