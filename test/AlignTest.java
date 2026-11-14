package com.example.stereocam;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Random;

public class AlignTest {
    static int[] tex(int w, int h, long seed) {            // verrauschte Textur mit Strukturen auf mehreren Skalen
        Random r = new Random(seed); double[] acc = new double[w * h];
        for (int oct = 0; oct < 5; oct++) {
            int cw = Math.max(2, w >> (6 - oct)), ch = Math.max(2, h >> (6 - oct)); double[] g = new double[(cw + 1) * (ch + 1)];
            for (int i = 0; i < g.length; i++) g[i] = r.nextDouble();
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                double fx = (double) x * cw / w, fy = (double) y * ch / h; int x0 = (int) fx, y0 = (int) fy; double tx = fx - x0, ty = fy - y0;
                double v = g[y0 * (cw + 1) + x0] * (1 - tx) * (1 - ty) + g[y0 * (cw + 1) + x0 + 1] * tx * (1 - ty) + g[(y0 + 1) * (cw + 1) + x0] * (1 - tx) * ty + g[(y0 + 1) * (cw + 1) + x0 + 1] * tx * ty;
                acc[y * w + x] += v / (1 << oct) ;
            }
        }
        int[] o = new int[w * h];
        for (int i = 0; i < o.length; i++) { int v = (int) Math.max(0, Math.min(255, acc[i] * 120)); o[i] = 0xFF000000 | v << 16 | (v * 9 / 10) << 8 | (v * 8 / 10); }
        return o;
    }
    static void check(boolean c, String m) { System.out.println((c ? "OK   " : "FAIL ") + m); if (!c) failed = true; }
    static boolean failed;

    public static void main(String[] a) throws Exception {
        int W = 1280, H = 960;
        int[] base = tex(W + 200, H + 200, 7);                       // grosse Szene, L und R sind Ausschnitte
        int BW = W + 200;
        // L = Mittelausschnitt; R = aehnlichkeitstransformiert + Parallaxe (Vordergrundkasten verschoben)
        double th = 0.5 * Math.PI / 180, sc = 1.012, dy0 = 7, dx0 = 22;
        int[] L = new int[W * H], R = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) L[y * W + x] = base[(y + 100) * BW + x + 100];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double xn = x - W / 2.0, yn = y - H / 2.0;
            // R(x,y) zeigt dort, wo L bei (x - dx, y - dy) lag: Gegenrichtung zum Modell
            double sx = x + 100 - dx0 - (sc - 1) * xn + th * yn, sy = y + 100 - dy0 - th * xn - (sc - 1) * yn;
            boolean fg = x > 450 && x < 800 && y > 300 && y < 700;
            if (fg) sx -= 26;                                             // Vordergrund hat mehr Disparitaet (naeher)
            R[y * W + x] = StereoAligner.bilinear(base, BW, H + 200, sx, sy);
        }
        // Arbeitsaufloesung 640
        int ww = 640, wh = 480, k = 2;
        int[] l2 = StereoAligner.resample(L, W, H, 0, 0, W, H, ww, wh), r2 = StereoAligner.resample(R, W, H, 0, 0, W, H, ww, wh);
        long t0 = System.nanoTime();
        StereoAligner.Fit f = StereoAligner.estimate(StereoAligner.gray(l2, ww * wh), StereoAligner.gray(r2, ww * wh), ww, wh);
        System.out.println(f + String.format("  [%d ms]", (System.nanoTime() - t0) / 1000000));
        check(f.ok, "Schaetzung ok");
        // erwartet (im Arbeitsmassstab): R(x+dx, y+dy) = L(x,y): dy = a + b*xn + c*yn mit a = dy0/2 (k=2), b = th, c = sc-1 (im Vorzeichen des Modells)
        check(Math.abs(f.a * k - dy0) < 1.0, "a*k = " + f.a * k + " erwartet ~" + dy0);
        check(Math.abs(f.b - th) < 0.002, "b = " + f.b + " erwartet ~" + th);
        check(Math.abs(f.c - (sc - 1)) < 0.003, "c = " + f.c + " erwartet ~" + (sc - 1));
        // Entzerren und Restfehler messen
        int[] rect = StereoAligner.validRect(f, k, W, H);
        int[] Rw = StereoAligner.warpRight(R, W, H, f, k, rect), Lc = StereoAligner.crop(L, W, H, rect);
        int ow = rect[2] - rect[0], oh = rect[3] - rect[1];
        System.out.println("Ausschnitt " + java.util.Arrays.toString(rect) + " -> " + ow + "x" + oh);
        int[] a2 = StereoAligner.resample(Lc, ow, oh, 0, 0, ow, oh, ow / 2, oh / 2), b2 = StereoAligner.resample(Rw, ow, oh, 0, 0, ow, oh, ow / 2, oh / 2);
        StereoAligner.Fit g = StereoAligner.estimate(StereoAligner.gray(a2, (ow / 2) * (oh / 2)), StereoAligner.gray(b2, (ow / 2) * (oh / 2)), ow / 2, oh / 2);
        System.out.println("nach Entzerrung: " + g);
        check(g.ok && Math.abs(g.a) < 0.3 && Math.abs(g.b) < 0.0008 && Math.abs(g.c) < 0.0015, "Restfehler klein (a, b, c)");
        check(g.ok && Math.abs(g.hx) < 2.0, "Konvergenz ~0 nach Entzerrung: hx = " + g.hx);
        check(g.ok && g.dispMax - g.dispMin > 5, "Disparitaetsbereich erhalten: " + (g.dispMax - g.dispMin));

        // MPO
        ByteArrayOutputStream b1 = new ByteArrayOutputStream(), b3 = new ByteArrayOutputStream();
        BufferedImage i1 = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB), i2 = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
        i1.setRGB(0, 0, ow, oh, Lc, 0, ow); i2.setRGB(0, 0, ow, oh, Rw, 0, ow);
        ImageIO.write(i1, "jpg", b1); ImageIO.write(i2, "jpg", b3);
        byte[] mpo = MpoWriter.build(b1.toByteArray(), b3.toByteArray());
        try (FileOutputStream fo = new FileOutputStream("/tmp/rp/sc_test.mpo")) { fo.write(mpo); }
        // zuruecklesen: MP-Index auswerten, beide Teile mit ImageIO dekodieren
        int p = 2; int mp = -1; while ((mpo[p] & 255) == 0xFF) { int mk = mpo[p + 1] & 255, len = ((mpo[p + 2] & 255) << 8) | (mpo[p + 3] & 255); if (mk == 0xE2 && mpo[p + 4] == 'M') { mp = p + 8; break; } p += 2 + len; }
        check(mp > 0, "APP2/MPF gefunden");
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(mpo).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int n = bb.getShort(mp + 8); int entryOff = -1; int num = -1;
        for (int e = 0; e < n; e++) { int q = mp + 10 + 12 * e, tag = bb.getShort(q) & 0xFFFF; if (tag == 0xB001) num = bb.getInt(q + 8); if (tag == 0xB002) entryOff = bb.getInt(q + 8); }
        check(num == 2 && entryOff > 0, "NumberOfImages=2, Eintragsoffset=" + entryOff);
        int s1 = bb.getInt(mp + entryOff + 4), o1 = bb.getInt(mp + entryOff + 8), s2 = bb.getInt(mp + entryOff + 20), o2 = bb.getInt(mp + entryOff + 24);
        check(o1 == 0 && s1 + s2 == mpo.length, "Groessen summieren sich: " + s1 + "+" + s2 + "=" + mpo.length);
        BufferedImage d1 = ImageIO.read(new ByteArrayInputStream(mpo, 0, s1)), d2 = ImageIO.read(new ByteArrayInputStream(mpo, mp + o2, s2));
        check(d1 != null && d2 != null && d1.getWidth() == ow && d2.getWidth() == ow, "beide Bilder lesbar (" + (d1 != null ? d1.getWidth() : -1) + "x" + (d1 != null ? d1.getHeight() : -1) + ")");
        check((mpo[mp + o2] & 255) == 0xFF && (mpo[mp + o2 + 1] & 255) == 0xD8, "Bild 2 beginnt mit SOI am berechneten Offset");
        // Vorschau-Anaglyph zur Sichtpruefung
        BufferedImage an = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < oh; y++) for (int x = 0; x < ow; x++) an.setRGB(x, y, (Lc[y * ow + x] & 0xFF0000) | (Rw[y * ow + x] & 0x00FFFF));
        ImageIO.write(an, "png", new File("/tmp/rp/sc_anaglyph.png"));
        System.out.println(failed ? "FEHLER" : "ALLE TESTS OK");
        System.exit(failed ? 1 : 0);
    }
}
