package com.example.stereocam;

import java.util.Arrays;

/**
 * Dichte Disparitaetskarte eines Stereopaares (reines Java, auf dem Desktop-JVM testbar).
 * Definition: d = xR - xL, also steht der Punkt, der links bei xL liegt, rechts bei xL + d.
 * Verfahren: lokal normalisierte Helligkeit (unempfindlich gegen Unterschiede der beiden Objektive), absolute Differenz,
 * Boxfilter (Integralbild) je Disparitaet, Gewinner nimmt alles, Subpixel (Parabel), Links-Rechts-Pruefung, Luecken fuellen
 * (mit dem weiter entfernten Nachbarn), Median.
 * Bei echter Geometrie liegen nahe Objekte bei kleinerem d (xR < xL + ...), ferne bei groesserem d.
 */
final class DisparityMap {

    static final class Result {
        int w, h;
        float[] dL;          // Karte im linken Bild:  d(xL)
        float[] dR;          // Karte im rechten Bild: d(xR) = xR - xL des zugehoerigen Punktes
        float centerMedian;  // Median im mittleren Bildbereich (Ruhe-Ebene)
        float min, max;      // 2 % / 98 % Perzentil
        int validPercent;    // Anteil der Pixel, die die Links-Rechts-Pruefung bestanden haben
    }

    private DisparityMap() {}

    /** rgbL, rgbR: ARGB, gleiche Groesse w x h. range: groesster |d| in Pixeln. */
    static Result compute(int[] rgbL, int[] rgbR, int w, int h, int range) {
        float[] gl = norm(StereoAligner.gray(rgbL, w * h), w, h), gr = norm(StereoAligner.gray(rgbR, w * h), w, h);
        final int N = w * h, R = 4;                                     // Fenster 9x9
        float[] bestL = new float[N], bestR = new float[N], cPrev = new float[N], cCur = new float[N], cm1 = new float[N], cp1 = new float[N];
        short[] dL = new short[N], dR = new short[N];
        Arrays.fill(bestL, Float.MAX_VALUE); Arrays.fill(bestR, Float.MAX_VALUE);
        double[] sat = new double[(w + 1) * (h + 1)]; float[] ad = new float[N];
        Arrays.fill(cPrev, Float.MAX_VALUE);
        for (int d = -range; d <= range; d++) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int xr = x + d; ad[y * w + x] = (xr < 0 || xr >= w) ? 1.5f : Math.min(1.5f, Math.abs(gl[y * w + x] - gr[y * w + xr]));
            }
            for (int y = 0; y < h; y++) { double rs = 0; for (int x = 0; x < w; x++) { rs += ad[y * w + x]; sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + rs; } }
            for (int y = 0; y < h; y++) {
                int y0 = Math.max(0, y - R), y1 = Math.min(h - 1, y + R);
                for (int x = 0; x < w; x++) {
                    int x0 = Math.max(0, x - R), x1 = Math.min(w - 1, x + R);
                    double s = sat[(y1 + 1) * (w + 1) + x1 + 1] - sat[y0 * (w + 1) + x1 + 1] - sat[(y1 + 1) * (w + 1) + x0] + sat[y0 * (w + 1) + x0];
                    float c = (float) (s / ((x1 - x0 + 1) * (y1 - y0 + 1))); int i = y * w + x; cCur[i] = c;
                    if (dL[i] == d - 1 && bestL[i] != Float.MAX_VALUE) cp1[i] = c;
                    if (c < bestL[i]) { bestL[i] = c; dL[i] = (short) d; cm1[i] = cPrev[i]; cp1[i] = Float.MAX_VALUE; }
                    int xr = x + d; if (xr >= 0 && xr < w) { int j = y * w + xr; if (c < bestR[j]) { bestR[j] = c; dR[j] = (short) d; } }
                }
            }
            float[] t = cPrev; cPrev = cCur; cCur = t;
        }
        float[] fL = new float[N], fR = new float[N];
        for (int i = 0; i < N; i++) {
            float sub = 0, a = cm1[i], b = bestL[i], c = cp1[i];
            if (a != Float.MAX_VALUE && c != Float.MAX_VALUE) { float den = a - 2 * b + c; if (den > 1e-6f) sub = Math.max(-0.5f, Math.min(0.5f, 0.5f * (a - c) / den)); }
            fL[i] = dL[i] + sub; fR[i] = dR[i];
        }
        // Links-Rechts-Pruefung
        boolean[] okL = new boolean[N], okR = new boolean[N]; int valid = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x, xr = Math.round(x + fL[i]);
            if (xr >= 0 && xr < w && Math.abs(fR[y * w + xr] - fL[i]) <= 1.5f && bestL[i] < 0.9f) { okL[i] = true; valid++; }
            int xl = Math.round(x - fR[i]);
            if (xl >= 0 && xl < w && Math.abs(fL[y * w + xl] - fR[i]) <= 1.5f && bestR[i] < 0.9f) okR[i] = true;
        }
        fill(fL, okL, w, h); fill(fR, okR, w, h);
        fL = median(fL, w, h); fR = median(fR, w, h);
        // Kantenerhaltende Glaettung (gewichteter Guided Filter, Leitbild = jeweiliges Bild): beseitigt Streifen und Ausreisser
        float[] wl = new float[N], wr = new float[N]; for (int i = 0; i < N; i++) { wl[i] = okL[i] ? 1f : 0.2f; wr[i] = okR[i] ? 1f : 0.2f; }
        float[] gL = scale01(StereoAligner.gray(rgbL, N)), gR = scale01(StereoAligner.gray(rgbR, N));
        int rad = Math.max(3, w / 60);
        fL = guided(fL, wl, gL, w, h, rad, 0.0025f); fL = guided(fL, null, gL, w, h, rad, 0.0025f);
        fR = guided(fR, wr, gR, w, h, rad, 0.0025f); fR = guided(fR, null, gR, w, h, rad, 0.0025f);
        Result r = new Result(); r.w = w; r.h = h; r.dL = fL; r.dR = fR; r.validPercent = (int) (100L * valid / N);
        float[] cen = new float[(w / 2) * (h / 2)]; int k = 0;
        for (int y = h / 4; y < h / 4 + h / 2; y++) for (int x = w / 4; x < w / 4 + w / 2; x++) cen[k++] = fL[y * w + x];
        Arrays.sort(cen); r.centerMedian = cen[cen.length / 2];
        float[] all = fL.clone(); Arrays.sort(all); r.min = all[(int) (0.02 * (N - 1))]; r.max = all[(int) (0.98 * (N - 1))];
        return r;
    }

    /** Bildweise skalieren (bilinear) - fuer die Darstellung in hoeherer Aufloesung; Disparitaetswerte werden mitskaliert. */
    static float[] upsample(float[] m, int w, int h, int nw, int nh) {
        float[] o = new float[nw * nh]; float k = (float) nw / w;
        for (int y = 0; y < nh; y++) {
            float fy = Math.max(0, Math.min(h - 1, (y + 0.5f) * h / nh - 0.5f)); int y0 = (int) fy, y1 = Math.min(h - 1, y0 + 1); float ty = fy - y0;
            for (int x = 0; x < nw; x++) {
                float fx = Math.max(0, Math.min(w - 1, (x + 0.5f) * w / nw - 0.5f)); int x0 = (int) fx, x1 = Math.min(w - 1, x0 + 1); float tx = fx - x0;
                o[y * nw + x] = k * ((m[y0 * w + x0] * (1 - tx) + m[y0 * w + x1] * tx) * (1 - ty) + (m[y1 * w + x0] * (1 - tx) + m[y1 * w + x1] * tx) * ty);
            }
        }
        return o;
    }

    private static float[] norm(float[] g, int w, int h) {
        final int R = 7; double[] s = new double[(w + 1) * (h + 1)], q = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) { double rs = 0, rq = 0; for (int x = 0; x < w; x++) { double v = g[y * w + x]; rs += v; rq += v * v; s[(y + 1) * (w + 1) + x + 1] = s[y * (w + 1) + x + 1] + rs; q[(y + 1) * (w + 1) + x + 1] = q[y * (w + 1) + x + 1] + rq; } }
        float[] o = new float[w * h];
        for (int y = 0; y < h; y++) { int y0 = Math.max(0, y - R), y1 = Math.min(h - 1, y + R);
            for (int x = 0; x < w; x++) { int x0 = Math.max(0, x - R), x1 = Math.min(w - 1, x + R); double n = (x1 - x0 + 1) * (y1 - y0 + 1);
                double a = (s[(y1 + 1) * (w + 1) + x1 + 1] - s[y0 * (w + 1) + x1 + 1] - s[(y1 + 1) * (w + 1) + x0] + s[y0 * (w + 1) + x0]) / n;
                double b = (q[(y1 + 1) * (w + 1) + x1 + 1] - q[y0 * (w + 1) + x1 + 1] - q[(y1 + 1) * (w + 1) + x0] + q[y0 * (w + 1) + x0]) / n;
                o[y * w + x] = (float) ((g[y * w + x] - a) / (Math.sqrt(Math.max(0, b - a * a)) + 6.0)); } }
        return o;
    }

    /** Ungueltige Werte je Zeile mit dem groesseren (weiter entfernten) der beiden gueltigen Nachbarn fuellen. */
    private static void fill(float[] m, boolean[] ok, int w, int h) {
        for (int y = 0; y < h; y++) {
            int[] left = new int[w], right = new int[w]; int last = -1;
            for (int x = 0; x < w; x++) { if (ok[y * w + x]) last = x; left[x] = last; }
            last = -1; for (int x = w - 1; x >= 0; x--) { if (ok[y * w + x]) last = x; right[x] = last; }
            for (int x = 0; x < w; x++) if (!ok[y * w + x]) {
                float a = left[x] >= 0 ? m[y * w + left[x]] : Float.NaN, b = right[x] >= 0 ? m[y * w + right[x]] : Float.NaN;
                m[y * w + x] = Float.isNaN(a) ? (Float.isNaN(b) ? 0 : b) : Float.isNaN(b) ? a : Math.max(a, b);
            }
        }
    }

    private static float[] scale01(float[] g) { float[] o = new float[g.length]; for (int i = 0; i < g.length; i++) o[i] = g[i] / 255f; return o; }

    private static float[] box(float[] a, int w, int h, int r) {
        double[] s = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) { double rs = 0; for (int x = 0; x < w; x++) { rs += a[y * w + x]; s[(y + 1) * (w + 1) + x + 1] = s[y * (w + 1) + x + 1] + rs; } }
        float[] o = new float[w * h];
        for (int y = 0; y < h; y++) { int y0 = Math.max(0, y - r), y1 = Math.min(h - 1, y + r);
            for (int x = 0; x < w; x++) { int x0 = Math.max(0, x - r), x1 = Math.min(w - 1, x + r);
                o[y * w + x] = (float) ((s[(y1 + 1) * (w + 1) + x1 + 1] - s[y0 * (w + 1) + x1 + 1] - s[(y1 + 1) * (w + 1) + x0] + s[y0 * (w + 1) + x0]) / ((x1 - x0 + 1) * (y1 - y0 + 1))); } }
        return o;
    }

    /** Gewichteter Guided Filter (He et al.): p Eingabe, wgt Gewichte (null = 1), I Leitbild 0..1. */
    static float[] guided(float[] p, float[] wgt, float[] I, int w, int h, int r, float eps) {
        int N = w * h; float[] ww = new float[N], wi = new float[N], wp = new float[N], wii = new float[N], wip = new float[N];
        for (int i = 0; i < N; i++) { float k = wgt == null ? 1f : wgt[i]; ww[i] = k; wi[i] = k * I[i]; wp[i] = k * p[i]; wii[i] = k * I[i] * I[i]; wip[i] = k * I[i] * p[i]; }
        float[] mw = box(ww, w, h, r), mi = box(wi, w, h, r), mp = box(wp, w, h, r), mii = box(wii, w, h, r), mip = box(wip, w, h, r);
        float[] a = new float[N], b = new float[N];
        for (int i = 0; i < N; i++) {
            float W = Math.max(mw[i], 1e-6f), mI = mi[i] / W, mP = mp[i] / W, var = mii[i] / W - mI * mI, cov = mip[i] / W - mI * mP;
            a[i] = cov / (var + eps); b[i] = mP - a[i] * mI;
        }
        float[] ma = box(a, w, h, r), mb = box(b, w, h, r), o = new float[N];
        for (int i = 0; i < N; i++) o[i] = ma[i] * I[i] + mb[i];
        return o;
    }

    private static float[] median(float[] m, int w, int h) {
        float[] o = new float[w * h], t = new float[25];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int n = 0; for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) { int xx = Math.max(0, Math.min(w - 1, x + i)), yy = Math.max(0, Math.min(h - 1, y + j)); t[n++] = m[yy * w + xx]; }
            Arrays.sort(t, 0, n); o[y * w + x] = t[n / 2];
        }
        return o;
    }
}
