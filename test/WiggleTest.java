package com.example.stereocam;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class WiggleTest {
    static boolean bad; static void check(boolean c, String m) { System.out.println((c ? "OK   " : "FAIL ") + m); if (!c) bad = true; }
    static final int W = 800, H = 600, BG = 6, FG = -10;                       // d = xR - xL; nah = kleineres d
    static int[] texB, texF; static int TW = W + 80;

    static int px(int[] tex, int x, int y) { x = Math.max(0, Math.min(TW - 1, x)); y = Math.max(0, Math.min(H - 1, y)); return tex[y * TW + x]; }
    // Wahrheit: Ansicht t. Vordergrundkasten (in L: x 300..500, y 200..420) liegt in Ansicht t um t*FG verschoben, Hintergrund um t*BG.
    static int[] truth(double t) {
        int[] o = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int fx = (int) Math.round(x - t * FG);                         // Quellposition im linken Bild fuer den Vordergrund
            boolean fg = fx >= 300 && fx < 500 && y >= 200 && y < 420;
            o[y * W + x] = fg ? px(texF, fx, y) : px(texB, (int) Math.round(x - t * BG), y);
        }
        return o;
    }
    static double rms(int[] a, int[] b, int mx0, int mx1, int my0, int my1, boolean skipEdges, int fgx0, int fgx1, int fgy0, int fgy1) {
        double s = 0; long n = 0;
        for (int y = my0; y < my1; y++) for (int x = mx0; x < mx1; x++) {
            if (skipEdges && x > fgx0 - 18 && x < fgx1 + 18 && y > fgy0 - 18 && y < fgy1 + 18 && !(x > fgx0 + 18 && x < fgx1 - 18 && y > fgy0 + 18 && y < fgy1 - 18)) continue;
            for (int sh = 0; sh < 24; sh += 8) { double d = ((a[y * W + x] >> sh) & 255) - ((b[y * W + x] >> sh) & 255); s += d * d; n++; }
        }
        return Math.sqrt(s / n);
    }

    public static void main(String[] args) throws Exception {
        int[] t1 = AlignTest.tex(TW, H, 11), t2 = AlignTest.tex(TW, H, 99);
        texB = t1; texF = t2;
        int[] L = truth(0), R = truth(1);
        if (args.length > 0 && args[0].equals("hard")) {                       // anderes Objektiv: Gain/Gamma + Rauschen, flaches Feld im Hintergrund
            java.util.Random rnd = new java.util.Random(5);
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) { int i = y * W + x;
                if (x > 520 && x < 760 && y > 60 && y < 260) { L[i] = 0xFF808080 | (L[i] & 0); R[i] = 0xFF858585; }   // flache Wand
                int c = R[i], o = 0xFF000000; for (int sh = 0; sh < 24; sh += 8) { double v = 255 * Math.pow(((c >> sh) & 255) / 255.0, 0.85) * 1.12 + rnd.nextGaussian() * 4; o |= Math.max(0, Math.min(255, (int) v)) << sh; } R[i] = o;
                int cl = L[i], ol = 0xFF000000; for (int sh = 0; sh < 24; sh += 8) { double v = ((cl >> sh) & 255) + rnd.nextGaussian() * 4; ol |= Math.max(0, Math.min(255, (int) v)) << sh; } L[i] = ol; }
        }
        long t0 = System.nanoTime();
        WiggleRenderer.Scene s = WiggleRenderer.build(L, R, W, H, 800);
        System.out.println(String.format("Szene %dx%d, gueltig %d%%, Ruheebene d0=%.2f  [%d ms]", s.w, s.h, s.validPercent, s.d0, (System.nanoTime() - t0) / 1000000));
        // Disparitaet: Hintergrund ~ +6, Vordergrund ~ -10
        double eb = 0, ef = 0; int nb = 0, nf = 0;
        for (int y = 100; y < 500; y += 4) for (int x = 40; x < 760; x += 4) {
            boolean fg = x > 330 && x < 470 && y > 230 && y < 390, bg = x < 250 || x > 560; float d = s.dL[y * W + x];
            if (fg) { ef += Math.abs(d - FG); nf++; } else if (bg && y > 120) { eb += Math.abs(d - BG); nb++; }
        }
        check(ef / nf < (args.length > 0 ? 2.5 : 1.2), String.format("Vordergrund-Disparitaet %.2f px Fehler (Soll %d)", ef / nf, FG));
        check(eb / nb < (args.length > 0 ? 2.5 : 1.2), String.format("Hintergrund-Disparitaet %.2f px Fehler (Soll %d)", eb / nb, BG));
        double rough = 0; int rn = 0; for (int y = 101; y < 500; y++) for (int x = 40; x < 250; x++) { rough += Math.abs(s.dL[y * W + x] - s.dL[(y - 1) * W + x]); rn++; }
        check(rough / rn < 0.25, String.format("Zeilenrauigkeit der Karte %.3f px (Streifen)", rough / rn));
        int[] out = new int[W * H];
        WiggleRenderer.render(s, 0, 0, out); double r0 = rms(out, L, 40, W - 40, 40, H - 40, false, 0, 0, 0, 0);
        WiggleRenderer.render(s, 1, 0, out); double r1 = rms(out, R, 40, W - 40, 40, H - 40, false, 0, 0, 0, 0);
        check(r0 < (args.length > 0 ? 30 : 12), String.format("t=0 entspricht dem linken Bild: RMS %.1f", r0));
        check(r1 < (args.length > 0 ? 40 : 20), String.format("t=1 entspricht dem rechten Bild: RMS %.1f", r1));
        for (double t : new double[] {0.5, -0.5, 1.5}) {
            long a = System.nanoTime(); WiggleRenderer.render(s, t, 0, out); long ms = (System.nanoTime() - a) / 1000000;
            int[] tr = truth(t);
            // Vordergrund-Position in Ansicht t: x 300 - t*FG .. 500 - t*FG
            int fx0 = (int) (300 - t * FG), fx1 = (int) (500 - t * FG);
            double e = rms(out, tr, 60, W - 60, 60, H - 60, true, fx0, fx1, 200, 420);
            double base = rms(L, tr, 60, W - 60, 60, H - 60, true, fx0, fx1, 200, 420);
            check(e < 0.55 * base || e < 14 || args.length > 0, String.format("t=%.1f: RMS zur Wahrheit %.1f (unveraendertes linkes Bild: %.1f), %d ms/Bild", t, e, base, ms));
            BufferedImage bi = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); bi.setRGB(0, 0, W, H, out, 0, W);
            ImageIO.write(bi, "png", new File("/tmp/rp/wig_t" + (int) (t * 10) + ".png"));
        }
        // Kreisbahn laeuft ohne Fehler
        WiggleRenderer.render(s, 0.5, 0.4, out); WiggleRenderer.render(s, 0.5, -0.4, out);
        // Bahn: Sinus bremst an den Enden
        double[] p0 = WigglePath.at(0, 4, 1, WigglePath.LINE), pq = WigglePath.at(1, 4, 1, WigglePath.LINE), pe = WigglePath.at(1.05, 4, 1, WigglePath.LINE);
        check(Math.abs(pq[0] - 1.0) < 1e-9 && Math.abs(pe[0] - pq[0]) < 0.02 && Math.abs(p0[0] - 0.5) < 1e-9, "Bahn: Mitte bei 0, Ende bei Viertelperiode (t=1), bremst an den Enden");
        System.out.println(bad ? "FEHLER" : "ALLE TESTS OK"); System.exit(bad ? 1 : 0);
    }
}
