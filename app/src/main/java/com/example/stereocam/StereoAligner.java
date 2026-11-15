package com.example.stereocam;

import java.util.Arrays;

/**
 * Reiner Java-Kern (ohne Android-Klassen, auf dem Desktop-JVM testbar):
 * schaetzt aus einem Stereopaar die Restfehler zwischen den beiden Kameras und entzerrt das rechte Bild.
 *
 * Modell (kleine Aehnlichkeitstransformation + Vertikalversatz), R(x+dx, y+dy) ~ L(x, y):
 *   dy = a + b*xn + c*yn          xn = x - w/2, yn = y - h/2
 *   dx = hx + c*xn - b*yn         (hx = Konvergenz: Verschiebung der Schaerfeebene auf "0 Disparitaet")
 * b = Drehung (rad), c = Massstabsunterschied, a = Vertikalversatz.
 * Die Tiefe (echte Parallaxe) steckt nur in dx; a, b, c werden ausschliesslich aus dy bestimmt.
 */
final class StereoAligner {

    static final class Fit {
        boolean ok;
        double a, b, c, hx;          // in Pixeln der Arbeitsaufloesung (b, c dimensionslos)
        int used, total;             // verwendete / gemessene Messfelder
        double rms;                  // Rest-dy der Inlier (Pixel)
        double dispMin, dispMax;     // Disparitaetsbereich (10 % / 90 % Perzentil, nach Abzug von b, c), Pixel
        int workW, workH;
        String note = "";
        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "ok=%b used=%d/%d a=%.2f b=%.5f c=%.5f hx=%.2f rms=%.2f disp=[%.1f..%.1f] %s",
                    ok, used, total, a, b, c, hx, rms, dispMin, dispMax, note);
        }
    }

    private StereoAligner() {}

    // ------------------------------------------------------------------ Hilfsfunktionen fuer Bilder (ARGB int[])

    static float[] gray(int[] argb, int n) {
        float[] g = new float[n];
        for (int i = 0; i < n; i++) {
            int p = argb[i];
            g[i] = 0.299f * ((p >> 16) & 255) + 0.587f * ((p >> 8) & 255) + 0.114f * (p & 255);
        }
        return g;
    }

    /** Bilinear verkleinern/vergroessern eines Ausschnitts (sx0, sy0, sw, sh sind reell) auf dw x dh. */
    static int[] resample(int[] src, int w, int h, double sx0, double sy0, double sw, double sh, int dw, int dh) {
        int[] out = new int[dw * dh];
        double kx = sw / dw, ky = sh / dh;
        for (int y = 0; y < dh; y++) {
            double fy = sy0 + (y + 0.5) * ky - 0.5;
            for (int x = 0; x < dw; x++) {
                double fx = sx0 + (x + 0.5) * kx - 0.5;
                out[y * dw + x] = bilinear(src, w, h, fx, fy);
            }
        }
        return out;
    }

    static int bilinear(int[] s, int w, int h, double fx, double fy) {
        if (fx < 0) fx = 0; else if (fx > w - 1) fx = w - 1;
        if (fy < 0) fy = 0; else if (fy > h - 1) fy = h - 1;
        int x0 = (int) fx, y0 = (int) fy, x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        double tx = fx - x0, ty = fy - y0;
        int p00 = s[y0 * w + x0], p10 = s[y0 * w + x1], p01 = s[y1 * w + x0], p11 = s[y1 * w + x1];
        int r = 0;
        for (int sh = 16, k = 0; k < 3; k++, sh -= 8) {
            double v = ((p00 >> sh) & 255) * (1 - tx) * (1 - ty) + ((p10 >> sh) & 255) * tx * (1 - ty)
                     + ((p01 >> sh) & 255) * (1 - tx) * ty + ((p11 >> sh) & 255) * tx * ty;
            r |= ((int) Math.round(v) & 255) << sh;
        }
        return 0xFF000000 | r;
    }

    // ------------------------------------------------------------------ Schaetzung

    /** gl, gr: Graubilder 0..255 gleicher Groesse (Arbeitsaufloesung, z. B. 640 Pixel Breite). */
    static Fit estimate(float[] gl, float[] gr, int w, int h) {
        Fit f = new Fit(); f.workW = w; f.workH = h;
        final int P = 32, half = P / 2;
        final int sxr = Math.max(8, w / 8), syr = Math.max(4, h / 40);
        final int NX = 10, NY = 7;
        if (w < 4 * (sxr + half) || h < 4 * (syr + half)) { f.note = "Bild zu klein"; return f; }

        // Integralbilder von R fuer Fenstersummen
        double[] iS = new double[(w + 1) * (h + 1)], iQ = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            double rs = 0, rq = 0;
            for (int x = 0; x < w; x++) {
                double v = gr[y * w + x]; rs += v; rq += v * v;
                iS[(y + 1) * (w + 1) + x + 1] = iS[y * (w + 1) + x + 1] + rs;
                iQ[(y + 1) * (w + 1) + x + 1] = iQ[y * (w + 1) + x + 1] + rq;
            }
        }
        double[] cx = new double[NX * NY], cy = new double[NX * NY], mdx = new double[NX * NY], mdy = new double[NX * NY], mc = new double[NX * NY];
        int n = 0;
        float[] t = new float[P * P];
        double xLo = sxr + half, xHi = w - sxr - half - 1, yLo = syr + half, yHi = h - syr - half - 1;
        for (int gy = 0; gy < NY; gy++) for (int gx = 0; gx < NX; gx++) {
            int px = (int) Math.round(xLo + (xHi - xLo) * gx / (NX - 1)), py = (int) Math.round(yLo + (yHi - yLo) * gy / (NY - 1));
            double tm = 0;
            for (int j = 0; j < P; j++) for (int i = 0; i < P; i++) { float v = gl[(py - half + j) * w + px - half + i]; t[j * P + i] = v; tm += v; }
            tm /= P * P; double tv = 0;
            for (int k = 0; k < P * P; k++) { t[k] -= tm; tv += t[k] * t[k]; }
            if (Math.sqrt(tv / (P * P)) < 6.0) continue;                       // zu wenig Textur
            double tn = Math.sqrt(tv);
            int W2 = 2 * sxr + 1, H2 = 2 * syr + 1;
            double[] sc = new double[W2 * H2]; double best = -2; int bi = 0, bj = 0;
            for (int dj = -syr; dj <= syr; dj++) for (int di = -sxr; di <= sxr; di++) {
                int x0 = px + di - half, y0 = py + dj - half;
                double s = 0;
                for (int j = 0; j < P; j++) { int ro = (y0 + j) * w + x0, to = j * P; for (int i = 0; i < P; i++) s += t[to + i] * gr[ro + i]; }
                double sum = win(iS, w, x0, y0, P), sq = win(iQ, w, x0, y0, P);
                double var = sq - sum * sum / (P * P);
                double v = var > 1e-6 ? s / (tn * Math.sqrt(var)) : -1;        // t hat Mittelwert 0 -> s ist schon die Kovarianz
                sc[(dj + syr) * W2 + di + sxr] = v;
                if (v > best) { best = v; bi = di; bj = dj; }
            }
            if (best < 0.55) continue;
            // Subpixel (Parabel)
            double sdx = bi, sdy = bj;
            if (bi > -sxr && bi < sxr) sdx += parab(sc[(bj + syr) * W2 + bi - 1 + sxr], best, sc[(bj + syr) * W2 + bi + 1 + sxr]);
            if (bj > -syr && bj < syr) sdy += parab(sc[(bj - 1 + syr) * W2 + bi + sxr], best, sc[(bj + 1 + syr) * W2 + bi + sxr]);
            cx[n] = px - w / 2.0; cy[n] = py - h / 2.0; mdx[n] = sdx; mdy[n] = sdy; mc[n] = best; n++;
        }
        f.total = n;
        if (n < 8) { f.note = "zu wenige verwertbare Messfelder (" + n + ")"; return f; }

        // robuste Anpassung dy = a + b*xn + c*yn (IRLS, Tukey)
        double a = 0, b = 0, c = 0; double[] wt = new double[n]; Arrays.fill(wt, 1);
        for (int it = 0; it < 12; it++) {
            double[][] M = new double[3][4];
            for (int i = 0; i < n; i++) {
                double[] r = {1, cx[i], cy[i]};
                for (int p = 0; p < 3; p++) { for (int q = 0; q < 3; q++) M[p][q] += wt[i] * r[p] * r[q]; M[p][3] += wt[i] * r[p] * mdy[i]; }
            }
            double[] sol = solve3(M); if (sol == null) { f.note = "singulaer"; return f; }
            a = sol[0]; b = sol[1]; c = sol[2];
            double[] res = new double[n];
            for (int i = 0; i < n; i++) res[i] = Math.abs(mdy[i] - (a + b * cx[i] + c * cy[i]));
            double[] sorted = res.clone(); Arrays.sort(sorted);
            double sigma = Math.max(0.35, 1.4826 * sorted[n / 2]), cut = 4.685 * sigma;
            for (int i = 0; i < n; i++) { double u = res[i] / cut; wt[i] = u < 1 ? (1 - u * u) * (1 - u * u) : 0; }
        }
        // Inlier + Konvergenz
        double[] z = new double[n]; int m = 0; double ss = 0;
        for (int i = 0; i < n; i++) {
            double r = mdy[i] - (a + b * cx[i] + c * cy[i]);
            if (Math.abs(r) < 1.2) { z[m++] = mdx[i] - c * cx[i] + b * cy[i]; ss += r * r; }
        }
        if (m < 6) { f.note = "zu wenige Inlier (" + m + ")"; return f; }
        double[] zz = Arrays.copyOf(z, m); Arrays.sort(zz);
        f.a = a; f.b = b; f.c = c; f.hx = zz[m / 2]; f.used = m; f.rms = Math.sqrt(ss / m);
        f.dispMin = zz[(int) (0.10 * (m - 1))]; f.dispMax = zz[(int) Math.ceil(0.90 * (m - 1))];
        f.ok = true;
        return f;
    }

    private static double win(double[] I, int w, int x0, int y0, int P) {
        int W = w + 1;
        return I[(y0 + P) * W + x0 + P] - I[y0 * W + x0 + P] - I[(y0 + P) * W + x0] + I[y0 * W + x0];
    }

    private static double parab(double l, double c, double r) {
        double d = l - 2 * c + r; if (Math.abs(d) < 1e-9) return 0;
        double o = 0.5 * (l - r) / d; return Math.max(-0.5, Math.min(0.5, o));
    }

    private static double[] solve3(double[][] M) {
        for (int i = 0; i < 3; i++) {
            int p = i; for (int r = i + 1; r < 3; r++) if (Math.abs(M[r][i]) > Math.abs(M[p][i])) p = r;
            if (Math.abs(M[p][i]) < 1e-12) return null;
            double[] tmp = M[i]; M[i] = M[p]; M[p] = tmp;
            for (int r = i + 1; r < 3; r++) { double k = M[r][i] / M[i][i]; for (int q = i; q < 4; q++) M[r][q] -= k * M[i][q]; }
        }
        double[] s = new double[3];
        for (int i = 2; i >= 0; i--) { double v = M[i][3]; for (int q = i + 1; q < 3; q++) v -= M[i][q] * s[q]; s[i] = v / M[i][i]; }
        return s;
    }

    // ------------------------------------------------------------------ Anwendung

    /** Modellverschiebung am Pixel (x, y) im Vollbild (W x H); k = Vollaufloesung / Arbeitsaufloesung. */
    private static void model(Fit f, double k, int W, int H, double x, double y, double[] out) {
        double xn = x - W / 2.0, yn = y - H / 2.0;
        out[0] = f.hx * k + f.c * xn - f.b * yn;
        out[1] = f.a * k + f.b * xn + f.c * yn;
    }

    /** Gemeinsamer gueltiger Ausschnitt {x0, y0, x1, y1} (x1/y1 exklusiv), in dem das entzerrte R komplett Bilddaten hat. */
    static int[] validRect(Fit f, double k, int W, int H) {
        int x0 = 0, y0 = 0, x1 = W, y1 = H; double[] d = new double[2];
        for (int guard = 0; guard < 20000; guard++) {
            boolean ch = false;
            int[] xs = {x0, x1 - 1}, ys = {y0, y1 - 1};
            for (int xi = 0; xi < 2; xi++) for (int yi = 0; yi < 2; yi++) {
                model(f, k, W, H, xs[xi], ys[yi], d);
                double sx = xs[xi] + d[0], sy = ys[yi] + d[1];
                if (sx < 0) { if (xi == 0) x0++; else x1--; ch = true; } else if (sx > W - 1) { if (xi == 1) x1--; else x0++; ch = true; }
                if (sy < 0) { if (yi == 0) y0++; else y1--; ch = true; } else if (sy > H - 1) { if (yi == 1) y1--; else y0++; ch = true; }
            }
            if (!ch) break;
            if (x1 - x0 < W / 4 || y1 - y0 < H / 4) break;
        }
        return new int[] {x0, y0, x1, y1};
    }

    /** Entzerrtes, zugeschnittenes rechtes Bild. rect aus validRect(). */
    static int[] warpRight(int[] rgb, int W, int H, Fit f, double k, int[] rect) {
        int ow = rect[2] - rect[0], oh = rect[3] - rect[1]; int[] out = new int[ow * oh]; double[] d = new double[2];
        for (int y = 0; y < oh; y++) for (int x = 0; x < ow; x++) {
            int X = x + rect[0], Y = y + rect[1];
            model(f, k, W, H, X, Y, d);
            out[y * ow + x] = bilinear(rgb, W, H, X + d[0], Y + d[1]);
        }
        return out;
    }

    static int[] crop(int[] rgb, int W, int H, int[] rect) {
        int ow = rect[2] - rect[0], oh = rect[3] - rect[1]; int[] out = new int[ow * oh];
        for (int y = 0; y < oh; y++) System.arraycopy(rgb, (y + rect[1]) * W + rect[0], out, y * ow, ow);
        return out;
    }

    /**
     * Verfeinert den Bildausschnitt der weitwinkligeren Kamera: sucht den Anteil frac (um nominal herum, +-10 %), bei dem der mittige Ausschnitt
     * des Weitwinkelbildes dem Bild der anderen Kamera am besten gleicht (NCC, mit kleiner Verschiebungssuche gegen Parallaxe).
     * @return {frac, score, scoreNominal}
     */
    static double[] refineCrop(int[] wide, int ww, int wh, int[] narrow, int nw, int nh, double nominal) {
        final int tw = 160, th = Math.max(16, (int) Math.round(160.0 * nh / nw));
        float[] gn = gray(resample(narrow, nw, nh, 0, 0, nw, nh, tw, th), tw * th);
        double[] best = {nominal, -2, -2};
        double lo = nominal * 0.90, hi = Math.min(1.0, nominal * 1.10);
        for (double step : new double[] {0.01, 0.0025}) {
            double c0 = step == 0.01 ? lo : Math.max(lo, best[0] - 0.012), c1 = step == 0.01 ? hi : Math.min(hi, best[0] + 0.012);
            for (double fr = c0; fr <= c1 + 1e-9; fr += nominal * step) {
                double cw = ww * fr, ch = wh * fr; if (cw < 8 || ch < 8) continue;
                float[] gw = gray(resample(wide, ww, wh, (ww - cw) / 2, (wh - ch) / 2, cw, ch, tw, th), tw * th);
                double sc = bestShiftNcc(gw, gn, tw, th, 8, 3);
                if (sc > best[1]) { best[0] = fr; best[1] = sc; }
            }
        }
        float[] gw = gray(resample(wide, ww, wh, (ww - ww * nominal) / 2, (wh - wh * nominal) / 2, ww * nominal, wh * nominal, tw, th), tw * th);
        best[2] = bestShiftNcc(gw, gn, tw, th, 8, 3);
        return best;
    }

    /**
     * Bildwinkel ohne Vorwissen suchen: welches der beiden Bilder (0 = a, 1 = b) wird mittig auf welchen Anteil zugeschnitten, damit beide
     * gleich aussehen? Grobe Suche ueber 0,25..1,0 in logarithmischen Schritten, dann feiner; NCC mit kleiner Verschiebungssuche.
     * @return {welches, anteil, score, scoreOhneZuschnitt}
     */
    static double[] searchCrop(int[] a, int aw, int ah, int[] b, int bw, int bh) {
        final int tw = 128, th = Math.max(16, (int) Math.round(128.0 * bh / bw));
        float[] ga = gray(resample(a, aw, ah, 0, 0, aw, ah, tw, th), tw * th), gb = gray(resample(b, bw, bh, 0, 0, bw, bh, tw, th), tw * th);
        double s1 = bestShiftNcc(ga, gb, tw, th, 12, 3);
        double[] best = {0, 1, s1, s1};
        for (int which = 0; which < 2; which++) {
            int[] w = which == 0 ? a : b; int ww = which == 0 ? aw : bw, wh = which == 0 ? ah : bh; float[] ref = which == 0 ? gb : ga;
            double bf = 1, bs = -2;
            for (double fr = 0.97; fr >= 0.25; fr *= 0.965) {
                double sc = cropScore(w, ww, wh, ref, tw, th, fr); if (sc > bs) { bs = sc; bf = fr; }
            }
            for (double fr = bf * 0.97; fr <= Math.min(1.0, bf * 1.03) + 1e-9; fr += bf * 0.006) {
                double sc = cropScore(w, ww, wh, ref, tw, th, fr); if (sc > bs) { bs = sc; bf = fr; }
            }
            if (bs > best[2]) { best[0] = which; best[1] = bf; best[2] = bs; }
        }
        return best;
    }

    private static double cropScore(int[] src, int sw, int sh, float[] ref, int tw, int th, double fr) {
        double cw = sw * fr, ch = sh * fr; if (cw < 8 || ch < 8) return -2;
        return bestShiftNcc(gray(resample(src, sw, sh, (sw - cw) / 2, (sh - ch) / 2, cw, ch, tw, th), tw * th), ref, tw, th, 12, 3);
    }

    /**
     * Feinabgleich des Massstabs: der weitwinklige Zuschnitt wird in +-6 % um den aktuellen Wert mit Hochpassbildern (unabhaengig von Helligkeit
     * und Unschaerfe) hoeher aufgeloest verglichen. Grobe Stufe 160 px, feine Stufe 320 px.
     * @return {anteil, score, scoreAktuell, welches (0 = a, 1 = b)}
     */
    static double[] fineCrop(int[] a, int aw, int ah, double ca, int[] b, int bw, int bh, double cb) {
        boolean varyA = ca <= cb; double base = varyA ? ca : cb;
        int[] w = varyA ? a : b, n = varyA ? b : a; int ww = varyA ? aw : bw, wh = varyA ? ah : bh, nw = varyA ? bw : aw, nh = varyA ? bh : ah; double cn = varyA ? cb : ca;
        double[] best = {base, -2, -2, varyA ? 0 : 1};
        double lo = base * 0.94, hi = Math.min(1.0, base * 1.06);
        int[] sizes = {160, 320};
        double centre = base, span = (hi - lo) / 2, step = base * 0.012;
        for (int stage = 0; stage < 2; stage++) {
            int tw = sizes[stage], th = Math.max(16, (int) Math.round((double) tw * nh / nw));
            float[] ref = highPass(gray(resample(n, nw, nh, (nw - nw * cn) / 2, (nh - nh * cn) / 2, nw * cn, nh * cn, tw, th), tw * th), tw, th);
            int mx = stage == 0 ? 12 : 8, my = stage == 0 ? 3 : 2;
            double bs = -2, bf = centre;
            for (double fr = Math.max(0.2, centre - span); fr <= Math.min(1.0, centre + span) + 1e-9; fr += step) {
                double cw = ww * fr, ch = wh * fr;
                float[] g = highPass(gray(resample(w, ww, wh, (ww - cw) / 2, (wh - ch) / 2, cw, ch, tw, th), tw * th), tw, th);
                double sc = bestShiftNcc(g, ref, tw, th, mx, my);
                if (sc > bs) { bs = sc; bf = fr; }
                if (stage == 1 && Math.abs(fr - base) < base * 0.0011) best[2] = sc;
            }
            centre = bf; best[0] = bf; best[1] = bs; span = step * 1.1; step = base * 0.002;
        }
        if (best[2] < -1) {                                                    // aktueller Wert nicht genau auf dem Raster: separat bewerten
            int tw = 320, th = Math.max(16, (int) Math.round((double) tw * nh / nw));
            float[] ref = highPass(gray(resample(n, nw, nh, (nw - nw * cn) / 2, (nh - nh * cn) / 2, nw * cn, nh * cn, tw, th), tw * th), tw, th);
            double cw = ww * base, ch = wh * base;
            best[2] = bestShiftNcc(highPass(gray(resample(w, ww, wh, (ww - cw) / 2, (wh - ch) / 2, cw, ch, tw, th), tw * th), tw, th), ref, tw, th, 8, 2);
        }
        return best;
    }

    /** Hochpass: Bild minus Boxfilter (Radius 4). */
    static float[] highPass(float[] g, int w, int h) {
        float[] t = new float[g.length], o = new float[g.length]; int r = 4;
        for (int y = 0; y < h; y++) { double s = 0; for (int x = -r; x <= r; x++) s += g[y * w + Math.max(0, Math.min(w - 1, x))];
            for (int x = 0; x < w; x++) { t[y * w + x] = (float) (s / (2 * r + 1)); s += g[y * w + Math.min(w - 1, x + r + 1)] - g[y * w + Math.max(0, x - r)]; } }
        for (int x = 0; x < w; x++) { double s = 0; for (int y = -r; y <= r; y++) s += t[Math.max(0, Math.min(h - 1, y)) * w + x];
            for (int y = 0; y < h; y++) { o[y * w + x] = g[y * w + x] - (float) (s / (2 * r + 1)); s += t[Math.min(h - 1, y + r + 1) * w + x] - t[Math.max(0, y - r) * w + x]; } }
        return o;
    }

    /** Aehnlichkeit (NCC) zweier Bilder bei gegebenen Zuschnittanteilen (mittig). */
    static double pairScore(int[] a, int aw, int ah, double ca, int[] b, int bw, int bh, double cb) {
        final int tw = 128, th = Math.max(16, (int) Math.round(128.0 * bh / bw));
        float[] ga = gray(resample(a, aw, ah, (aw - aw * ca) / 2, (ah - ah * ca) / 2, aw * ca, ah * ca, tw, th), tw * th);
        float[] gb = gray(resample(b, bw, bh, (bw - bw * cb) / 2, (bh - bh * cb) / 2, bw * cb, bh * cb, tw, th), tw * th);
        return bestShiftNcc(ga, gb, tw, th, 12, 3);
    }

    private static double bestShiftNcc(float[] a, float[] b, int w, int h, int mx, int my) {
        double best = -2;
        for (int dy = -my; dy <= my; dy++) for (int dx = -mx; dx <= mx; dx++) {
            int x0 = Math.max(0, dx) + 6, x1 = Math.min(w, w + dx) - 6, y0 = Math.max(0, dy) + 6, y1 = Math.min(h, h + dy) - 6; if (x1 - x0 < 16 || y1 - y0 < 16) continue;
            double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0; int n = 0;
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) { double va = a[(y - dy) * w + (x - dx)], vb = b[y * w + x]; sa += va; sb += vb; saa += va * va; sbb += vb * vb; sab += va * vb; n++; }
            double cov = sab - sa * sb / n, va = saa - sa * sa / n, vb = sbb - sb * sb / n; if (va < 1e-6 || vb < 1e-6) continue;
            double r = cov / Math.sqrt(va * vb); if (r > best) best = r;
        }
        return best;
    }
}
