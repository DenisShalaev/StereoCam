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

    /** Ergebnis einer SGM-Rechnung (Disparitaet in Pixeln dieser Aufloesung). */
    private static final class Sgm { float[] fL, fR; boolean[] okL, okR; int valid; }

    /** rgbL, rgbR: ARGB, gleiche Groesse w x h. range: groesster |d| in Pixeln. */
    static Result compute(int[] rgbL, int[] rgbR, int w, int h, int range) {
        final int N = w * h;
        float[] gl = norm(StereoAligner.gray(rgbL, N), w, h), gr = norm(StereoAligner.gray(rgbR, N), w, h);
        // 1) grober Durchlauf (1/3 Aufloesung, voller Bereich): liefert die tatsaechlich vorkommenden Disparitaeten (2 %..98 %)
        int cw = Math.max(32, w / 3), ch = Math.max(24, h / 3), cr = Math.max(2, range / 3 + 1);
        int[] l3 = StereoAligner.resample(rgbL, w, h, 0, 0, w, h, cw, ch), r3 = StereoAligner.resample(rgbR, w, h, 0, 0, w, h, cw, ch);
        Sgm c = sgm(norm(StereoAligner.gray(l3, cw * ch), cw, ch), norm(StereoAligner.gray(r3, cw * ch), cw, ch), cw, ch, -cr, cr);
        float[] vals = new float[cw * ch]; int nv = 0; for (int i = 0; i < cw * ch; i++) if (c.okL[i]) vals[nv++] = c.fL[i];
        int lo = -range, hi = range;
        if (nv > 200) { Arrays.sort(vals, 0, nv); float k = (float) w / cw; lo = Math.max(-range, (int) Math.floor(vals[(int) (0.02 * (nv - 1))] * k) - 3); hi = Math.min(range, (int) Math.ceil(vals[(int) (0.98 * (nv - 1))] * k) + 3); if (hi - lo < 8) { int m = (lo + hi) / 2; lo = Math.max(-range, m - 4); hi = Math.min(range, m + 4); } }
        // 2) feiner Durchlauf nur im gefundenen Bereich
        Sgm f = sgm(gl, gr, w, h, lo, hi);
        float[] fL = f.fL, fR = f.fR; boolean[] okL = f.okL, okR = f.okR; int valid = f.valid;
        rejectStrips(fL, okL, w, h); rejectStrips(fR, okR, w, h);
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

    /** Semi-Global Matching: Census(5x5)+Differenz als Kosten, Pfadaggregation in 4 Richtungen, Links-Rechts-Pruefung. d = xR - xL in [dmin, dmax]. */
    private static Sgm sgm(float[] gl, float[] gr, int w, int h, int dmin, int dmax) {
        final int D = dmax - dmin + 1, N = w * h;
        int[] cl = census(gl, w, h), cr = census(gr, w, h);
        byte[] C = new byte[N * D];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = y * w + x, base = p * D;
            for (int k = 0; k < D; k++) {
                int xr = x + dmin + k;
                if (xr < 0 || xr >= w) { C[base + k] = (byte) 255; continue; }
                float bestc = 9f;                                                   // kleiner vertikaler Versatz (Objektivverzeichnung, Restfehler der Ausrichtung) wird toleriert
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy; if (yy < 0 || yy >= h) continue;
                    float ham = Integer.bitCount(cl[p] ^ cr[yy * w + xr]) / 24f, ad = Math.min(1.5f, Math.abs(gl[p] - gr[yy * w + xr])) / 1.5f;
                    float cc = 0.7f * ham + 0.3f * ad + (dy == 0 ? 0f : 0.04f); if (cc < bestc) bestc = cc;
                }
                C[base + k] = (byte) Math.round(255f * Math.min(1f, bestc));
            }
        }
        short[] S = new short[N * D];
        final int P1 = 10, P2 = 60;
        int[] prev = new int[D], cur = new int[D];
        for (int dir = 0; dir < 4; dir++) {
            int lines = dir < 2 ? h : w, len = dir < 2 ? w : h;
            for (int ln = 0; ln < lines; ln++) {
                Arrays.fill(prev, 0); int pPrev = -1;
                for (int i = 0; i < len; i++) {
                    int pos = (dir == 0 || dir == 2) ? i : len - 1 - i;
                    int p = dir < 2 ? ln * w + pos : pos * w + ln, base = p * D;
                    int minPrev = Integer.MAX_VALUE; for (int k = 0; k < D; k++) if (prev[k] < minPrev) minPrev = prev[k];
                    int p2 = P2; if (pPrev >= 0) { float dI = Math.abs(gl[p] - gl[pPrev]); p2 = Math.max(P1 * 2, Math.round(P2 / (1f + 4f * dI))); }
                    int mn = Integer.MAX_VALUE;
                    for (int k = 0; k < D; k++) {
                        int best = prev[k]; if (k > 0 && prev[k - 1] + P1 < best) best = prev[k - 1] + P1; if (k < D - 1 && prev[k + 1] + P1 < best) best = prev[k + 1] + P1; if (minPrev + p2 < best) best = minPrev + p2;
                        int v = (C[base + k] & 255) + best - minPrev; cur[k] = v; if (v < mn) mn = v;
                    }
                    for (int k = 0; k < D; k++) { S[base + k] += (short) cur[k]; }
                    int[] t = prev; prev = cur; cur = t; pPrev = p;
                }
            }
        }
        // Gewinner (links) mit Subpixel; Gewinner (rechts) aus demselben Volumen
        Sgm r = new Sgm(); r.fL = new float[N]; r.fR = new float[N]; r.okL = new boolean[N]; r.okR = new boolean[N];
        int[] kL = new int[N], kR = new int[N]; int[] bestR = new int[N]; Arrays.fill(bestR, Integer.MAX_VALUE);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = y * w + x, base = p * D, bk = 0, bv = Integer.MAX_VALUE;
            for (int k = 0; k < D; k++) { int v = S[base + k]; if (v < bv) { bv = v; bk = k; } int xr = x + dmin + k; if (xr >= 0 && xr < w && v < bestR[y * w + xr]) { bestR[y * w + xr] = v; kR[y * w + xr] = k; } }
            kL[p] = bk; float sub = 0;
            if (bk > 0 && bk < D - 1) { float a = S[base + bk - 1], b = bv, c = S[base + bk + 1], den = a - 2 * b + c; if (den > 1e-3f) sub = Math.max(-0.5f, Math.min(0.5f, 0.5f * (a - c) / den)); }
            r.fL[p] = dmin + bk + sub;
        }
        for (int i = 0; i < N; i++) r.fR[i] = dmin + kR[i];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x, xr = Math.round(x + r.fL[i]);
            if (xr >= 0 && xr < w && Math.abs(r.fR[y * w + xr] - r.fL[i]) <= 1.5f && kL[i] > 0 && kL[i] < D - 1 + (D < 3 ? 2 : 0)) { r.okL[i] = true; r.valid++; }
            int xl = Math.round(x - r.fR[i]);
            if (xl >= 0 && xl < w && Math.abs(r.fL[y * w + xl] - r.fR[i]) <= 1.5f) r.okR[i] = true;
        }
        return r;
    }

    private static int[] census(float[] g, int w, int h) {
        int[] o = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float c = g[y * w + x]; int bits = 0;
            for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) { if (i == 0 && j == 0) continue; int xx = Math.max(0, Math.min(w - 1, x + i)), yy = Math.max(0, Math.min(h - 1, y + j)); bits = (bits << 1) | (g[yy * w + xx] < c ? 1 : 0); }
            o[y * w + x] = bits;
        }
        return o;
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

    /** Verwirft gueltige Werte, die stark vom Median ihrer 11x11-Umgebung abweichen: schmale Streifen mit falscher (z. B. umgekehrter) Tiefe. Echte Objekte (breiter als ~6 px) bleiben. */
    private static void rejectStrips(float[] m, boolean[] ok, int w, int h) {
        boolean[] bad = new boolean[w * h]; float[] t = new float[121];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x; if (!ok[i]) continue; int n = 0;
            for (int j = -5; j <= 5; j++) for (int k = -5; k <= 5; k++) { int xx = Math.max(0, Math.min(w - 1, x + k)), yy = Math.max(0, Math.min(h - 1, y + j)); t[n++] = m[yy * w + xx]; }
            Arrays.sort(t, 0, n); if (Math.abs(m[i] - t[n / 2]) > Math.max(2.5f, 0.02f * w)) bad[i] = true;
        }
        for (int i = 0; i < w * h; i++) if (bad[i]) ok[i] = false;
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
