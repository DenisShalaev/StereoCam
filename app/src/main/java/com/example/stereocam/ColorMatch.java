package com.example.stereocam;

/**
 * Farbangleichung des zweiten Bildes an das erste (zwei Kameras = zwei Sensoren, Weissabgleich und Farbwiedergabe weichen leicht ab).
 * Je Farbkanal Histogramm-Angleichung auf einer Stichprobe der Bildmitte; die Kennlinie wird geglaettet, monoton gehalten
 * und in der Abweichung begrenzt, damit ungleiche Bildinhalte (Parallaxe) sie nicht verfaelschen.
 */
final class ColorMatch {
    private ColorMatch() {}

    /** Passt dst (ARGB, w x h) farblich an ref an. Gibt die mittlere Abweichung (je Kanal, vor der Korrektur) zurueck: {dR, dG, dB}. */
    static int[] apply(int[] ref, int[] dst, int w, int h) {
        int x0 = w / 20, x1 = w - w / 20, y0 = h / 20, y1 = h - h / 20, step = Math.max(1, Math.min(w, h) / 400);
        long[][] hr = new long[3][256], hd = new long[3][256]; long n = 0;
        for (int y = y0; y < y1; y += step) for (int x = x0; x < x1; x += step) {
            int a = ref[y * w + x], b = dst[y * w + x];
            hr[0][a >> 16 & 255]++; hr[1][a >> 8 & 255]++; hr[2][a & 255]++;
            hd[0][b >> 16 & 255]++; hd[1][b >> 8 & 255]++; hd[2][b & 255]++; n++;
        }
        if (n < 100) return new int[3];
        int[][] lut = new int[3][256]; int[] shift = new int[3];
        for (int c = 0; c < 3; c++) {
            double[] cr = cdf(hr[c], n), cd = cdf(hd[c], n);
            double[] m = new double[256]; int u = 0; double sum = 0;
            for (int v = 0; v < 256; v++) { while (u < 255 && cr[u] < cd[v] - 1e-9) u++; m[v] = u; sum += u - v; }
            shift[c] = (int) Math.round(sum / 256);
            double[] s = new double[256];                                   // glaetten (+-4)
            for (int v = 0; v < 256; v++) { double t = 0; int k = 0; for (int d = -4; d <= 4; d++) { int q = v + d; if (q >= 0 && q < 256) { t += m[q]; k++; } } s[v] = t / k; }
            double prev = 0;
            for (int v = 0; v < 256; v++) {
                double o = Math.max(v - 40, Math.min(v + 40, s[v]));        // Abweichung begrenzen
                if (v == 0) o = Math.max(0, Math.min(8, o)); if (v == 255) o = 255;
                o = Math.max(prev, o); prev = o; lut[c][v] = (int) Math.round(Math.max(0, Math.min(255, o)));
            }
        }
        for (int i = 0; i < dst.length; i++) {
            int p = dst[i];
            dst[i] = (p & 0xFF000000) | lut[0][p >> 16 & 255] << 16 | lut[1][p >> 8 & 255] << 8 | lut[2][p & 255];
        }
        return shift;
    }

    private static double[] cdf(long[] h, long n) { double[] c = new double[256]; long s = 0; for (int i = 0; i < 256; i++) { s += h[i]; c[i] = (double) s / n; } return c; }
}
