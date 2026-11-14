package com.example.stereocam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Wandelt ein gespeichertes Stereopaar in ein anderes Format um (Original bleibt unveraendert, das Ergebnis ist eine neue Datei). */
final class StereoExport {
    static final int SBS = 0, ANAGLYPH = 1, LR = 2, MPO = 3, GIF = 4, MP4 = 5;
    private StereoExport() {}

    /** @return Anzahl geschriebener Dateien. @throws IOException wenn die Datei kein Stereopaar ist. */
    static int run(Context c, Uri src, String name, int kind) throws Exception {
        if (kind == GIF) { AnimExport.run(c, src, name, AnimExport.GIF); return 1; }
        if (kind == MP4) { AnimExport.run(c, src, name, AnimExport.MP4); return 1; }
        byte[] data = StereoFile.readAll(c, src);
        StereoFile.Pair p = StereoFile.load(data, name, 8192);
        if (p == null) throw new IOException(c.getString(R.string.wig_not_stereo));
        String base = name.replaceAll("(?i)\\.vr\\.jpg$|(?i)\\.jpe?g$|(?i)\\.mpo$", "").replaceAll("(?i)_sbs$", "");
        int w = p.left.getWidth(), h = p.left.getHeight();
        if (kind == SBS) {
            Bitmap r = Bitmap.createBitmap(w * 2, h, Bitmap.Config.ARGB_8888); Canvas cv = new Canvas(r);
            cv.drawBitmap(p.left, 0, 0, null); cv.drawBitmap(p.right, w, 0, null);
            Processor.save(c, base + "_SBS.jpg", "image/jpeg", jpeg(r)); r.recycle(); return 1;
        }
        if (kind == ANAGLYPH) {
            Bitmap r = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); int[] a = new int[w], b = new int[w];
            for (int y = 0; y < h; y++) {
                p.left.getPixels(a, 0, w, 0, y, w, 1); p.right.getPixels(b, 0, w, 0, y, w, 1);
                for (int x = 0; x < w; x++) a[x] = 0xFF000000 | (a[x] & 0x00FF0000) | (b[x] & 0x0000FFFF);
                r.setPixels(a, 0, w, 0, y, w, 1);
            }
            Processor.save(c, base + "_anaglyph.jpg", "image/jpeg", jpeg(r)); r.recycle(); return 1;
        }
        if (kind == LR) {
            Processor.save(c, base + "_L.jpg", "image/jpeg", jpeg(p.left)); Processor.save(c, base + "_R.jpg", "image/jpeg", jpeg(p.right)); return 2;
        }
        Processor.save(c, base + ".mpo", "image/mpo", MpoWriter.build(jpeg(p.left), jpeg(p.right))); return 1;
    }

    private static byte[] jpeg(Bitmap b) { ByteArrayOutputStream o = new ByteArrayOutputStream(4_000_000); b.compress(Bitmap.CompressFormat.JPEG, 95, o); return o.toByteArray(); }
}
