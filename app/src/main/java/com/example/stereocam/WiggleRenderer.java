package com.example.stereocam;

import java.util.stream.IntStream;

/**
 * Berechnet Zwischenansichten eines Stereopaares aus den Disparitaetskarten (reines Java, auf dem Desktop-JVM testbar).
 * Ansicht t: 0 = linkes Auge, 1 = rechtes Auge; ausserhalb extrapoliert. v = senkrechter Versatz der Kamera (in Basen).
 * Rueckwaerts-Abbildung von beiden Seiten: Punkte mit Disparitaet d (xR = xL + d) stehen in Ansicht t bei xL + t*d.
 * Wo nur eine Seite eine stimmige Zuordnung hat (Verdeckung), liefert diese die Farbe; sonst wird nach t gemischt.
 */
final class WiggleRenderer {

    static final class Scene {
        int w, h;
        int[] left, right;       // ARGB
        float[] dL, dR;          // Disparitaet im linken / rechten Bild (Pixel dieser Aufloesung)
        float d0;                // Ruheebene (Median der Bildmitte)
        int validPercent;
    }

    private WiggleRenderer() {}

    /** Bereitet ein Paar auf: auf hoechstens maxEdge Pixel an der langen Kante verkleinern, Disparitaet auf halber Aufloesung rechnen. */
    static Scene build(int[] rgbL, int[] rgbR, int w, int h, int maxEdge) {
        double k = Math.min(1.0, (double) maxEdge / Math.max(w, h));
        int sw = Math.max(16, (int) Math.round(w * k)) & ~1, sh = Math.max(16, (int) Math.round(h * k)) & ~1;
        int[] l = k < 1 ? StereoAligner.resample(rgbL, w, h, 0, 0, w, h, sw, sh) : rgbL, r = k < 1 ? StereoAligner.resample(rgbR, w, h, 0, 0, w, h, sw, sh) : rgbR;
        int hw = sw / 2, hh = sh / 2;
        int[] l2 = StereoAligner.resample(l, sw, sh, 0, 0, sw, sh, hw, hh), r2 = StereoAligner.resample(r, sw, sh, 0, 0, sw, sh, hw, hh);
        DisparityMap.Result d = DisparityMap.compute(l2, r2, hw, hh, Math.max(8, Math.round(0.07f * hw)));
        Scene s = new Scene(); s.w = sw; s.h = sh; s.left = l; s.right = r; s.validPercent = d.validPercent;
        s.dL = DisparityMap.upsample(d.dL, hw, hh, sw, sh); s.dR = DisparityMap.upsample(d.dR, hw, hh, sw, sh); s.d0 = d.centerMedian * ((float) sw / hw);
        return s;
    }

    /** Ein Bild der Ansicht (t, v) in out (Groesse s.w x s.h). */
    static void render(final Scene s, final double t, final double v, final int[] out) {
        final int w = s.w, h = s.h; final double tc = Math.max(0, Math.min(1, t));
        final double offX = t * s.d0, offY = v * s.d0;
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                double X = x + offX, Y = y + offY;
                // linke Seite
                double xl = X, yl = Y;
                for (int it = 0; it < 4; it++) { float d = samp(s.dL, w, h, xl, yl); xl = X - t * d; yl = Y - v * d; }
                float dl = samp(s.dL, w, h, xl, yl);
                double errL = Math.max(Math.abs(xl + t * dl - X), Math.abs(yl + v * dl - Y)) + out(xl, yl, w, h);
                // rechte Seite
                double xr = X, yr = Y;
                for (int it = 0; it < 4; it++) { float d = samp(s.dR, w, h, xr, yr); xr = X + (1 - t) * d; yr = Y - v * d; }
                float dr = samp(s.dR, w, h, xr, yr);
                double errR = Math.max(Math.abs(xr - (1 - t) * dr - X), Math.abs(yr + v * dr - Y)) + out(xr, yr, w, h);
                int cl = StereoAligner.bilinear(s.left, w, h, xl, yl), cr = StereoAligner.bilinear(s.right, w, h, xr, yr);
                double eL = errL / 0.8, eR = errR / 0.8, wL = (1 - tc) * Math.exp(-eL * eL) + 1e-4, wR = tc * Math.exp(-eR * eR) + 1e-4;
                int c = mix(cl, cr, wR / (wL + wR));
                out[y * w + x] = c;
            }
        });
    }

    /** Strafe fuer Positionen ausserhalb des Bildes (gilt als unstimmig). */
    private static double out(double x, double y, int w, int h) { return (x < 0 || y < 0 || x > w - 1 || y > h - 1) ? 100 : 0; }

    private static float samp(float[] m, int w, int h, double x, double y) {
        if (x < 0) x = 0; else if (x > w - 1) x = w - 1; if (y < 0) y = 0; else if (y > h - 1) y = h - 1;
        int x0 = (int) x, y0 = (int) y, x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1); float tx = (float) (x - x0), ty = (float) (y - y0);
        return (m[y0 * w + x0] * (1 - tx) + m[y0 * w + x1] * tx) * (1 - ty) + (m[y1 * w + x0] * (1 - tx) + m[y1 * w + x1] * tx) * ty;
    }

    private static int mix(int a, int b, double t) {
        int r = 0; for (int sh = 16, k = 0; k < 3; k++, sh -= 8) { double v = ((a >> sh) & 255) * (1 - t) + ((b >> sh) & 255) * t; r |= ((int) Math.round(v) & 255) << sh; }
        return 0xFF000000 | r;
    }
}
