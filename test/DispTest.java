package com.example.stereocam;
/** Haertere Szene: schraege Bodenebene (d 2..12 ueber die Bildhoehe), Streifenmuster-Kasten, glatter Kasten, anderes Objektiv (Gain/Gamma/Rauschen). */
public class DispTest {
    static final int W = 900, H = 600, TW = W + 200;
    static int[] t1, t2;
    static double dTrue(int x, int y, boolean right) { return 0; }
    public static void main(String[] a) throws Exception {
        t1 = AlignTest.tex(TW, H + 100, 11); t2 = AlignTest.tex(TW, H + 100, 77);
        int[] L = new int[W * H], R = new int[W * H]; float[] tl = new float[W * H]; java.util.Random rnd = new java.util.Random(9);
        // Wahrheit linkes Bild: Boden d(y) = 2 + 10*y/H ; Kasten A (x 150..330, y 150..400) mit Streifen, d=-9 ; Kasten B (x 560..760, y 120..330) glatt, d=-4
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double d = 2 + 10.0 * y / H; int c = t1[y * TW + x + 100];
            if (x >= 150 && x < 330 && y >= 150 && y < 400) { d = -9; int s = ((x / 6) % 2 == 0) ? 40 : 215; c = 0xFF000000 | s << 16 | s << 8 | s; }
            else if (x >= 560 && x < 760 && y >= 120 && y < 330) { d = -4; c = 0xFF000000 | 120 << 16 | 125 << 8 | 130; }
            L[y * W + x] = c; tl[y * W + x] = (float) d;
        }
        // Rechtes Bild durch Vorwaerts-Abbildung (naeheres, d kleiner, gewinnt)
        float[] zb = new float[W * H]; java.util.Arrays.fill(zb, Float.MAX_VALUE);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) { int xr = (int) Math.round(x + tl[y * W + x]); if (xr < 0 || xr >= W) continue; if (tl[y * W + x] < zb[y * W + xr]) { zb[y * W + xr] = tl[y * W + x]; R[y * W + xr] = L[y * W + x]; } }
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (zb[y * W + x] == Float.MAX_VALUE) R[y * W + x] = t2[y * TW + x + 100];
        for (int i = 0; i < W * H; i++) { int c = R[i], o = 0xFF000000; for (int sh = 0; sh < 24; sh += 8) { double v = 255 * Math.pow(((c >> sh) & 255) / 255.0, 0.8) * 1.15 + rnd.nextGaussian() * 5; o |= Math.max(0, Math.min(255, (int) v)) << sh; } R[i] = o;
            int cl = L[i], ol = 0xFF000000; for (int sh = 0; sh < 24; sh += 8) { double v = ((cl >> sh) & 255) + rnd.nextGaussian() * 5; ol |= Math.max(0, Math.min(255, (int) v)) << sh; } L[i] = ol; }
        int hw = 300, hh = 200; int[] l2 = StereoAligner.resample(L, W, H, 0, 0, W, H, hw, hh), r2 = StereoAligner.resample(R, W, H, 0, 0, W, H, hw, hh);
        long t0 = System.nanoTime(); DisparityMap.Result nw = DisparityMap.compute(l2, r2, hw, hh, Math.round(0.07f * hw)); long ms = (System.nanoTime() - t0) / 1000000;
        DisparityMapOld.Result ol = DisparityMapOld.compute(l2, r2, hw, hh, Math.round(0.07f * hw));
        System.out.println("neu: " + ms + " ms");
        for (int v = 0; v < 2; v++) {
            float[] m = v == 0 ? ol.dL : nw.dL; int bad = 0, wrongSign = 0, n = 0; double err = 0;
            for (int y = 8; y < hh - 8; y++) for (int x = 8; x < hw - 8; x++) {
                int X = x * W / hw, Y = y * H / hh; boolean edge = false;
                for (int dy = -12; dy <= 12 && !edge; dy += 6) for (int dx = -30; dx <= 30; dx += 10) { int xx = Math.max(0, Math.min(W - 1, X + dx)), yy = Math.max(0, Math.min(H - 1, Y + dy)); if (Math.abs(tl[yy * W + xx] - tl[Y * W + X]) > 3) { edge = true; break; } }
                if (edge) continue;
                double tr = tl[Y * W + X] * hw / (double) W, e = Math.abs(m[y * hw + x] - tr); err += e; n++;
                if (e > 1.5) bad++; if (e > 1.5 && Math.signum(m[y * hw + x] - tr) != 0 && Math.abs(m[y * hw + x] - tr) > 2.5) wrongSign++;
            }
            System.out.println(String.format("%s: mittlerer Fehler %.2f px, falsch (>1.5 px) %.1f %%, grob falsch (>2.5 px) %.1f %%", v == 0 ? "alt" : "neu", err / n, 100.0 * bad / n, 100.0 * wrongSign / n));
        }
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(hw * 2, hh, 1);
        for (int v = 0; v < 2; v++) { float[] m = v == 0 ? ol.dL : nw.dL; for (int y = 0; y < hh; y++) for (int x = 0; x < hw; x++) { int g = (int) Math.max(0, Math.min(255, (m[y * hw + x] + 14) * 9)); bi.setRGB(v * hw + x, y, g << 16 | g << 8 | g); } }
        javax.imageio.ImageIO.write(bi, "png", new java.io.File("/tmp/rp/disp_cmp.png"));
    }
}
