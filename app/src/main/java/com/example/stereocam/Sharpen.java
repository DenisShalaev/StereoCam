package com.example.stereocam;

import java.util.Locale;

/**
 * Schaerfe der beiden Bilder angleichen: die weichere Kamera (meist Ultraweitwinkel, bei wenig Licht mit starker Rauschunterdrueckung)
 * wird begrenzt nachgeschaerft (Unscharfmaske mit Rauschschwelle). Messung: mittlerer Gradient des leicht geglaetteten Graubilds
 * (die Glaettung nimmt dem Kornrauschen den Einfluss).
 */
final class Sharpen {
    private Sharpen() {}

    private static float[] gray(int[] p, int w, int h) {
        float[] g = new float[w * h];
        for (int i = 0; i < g.length; i++) { int c = p[i]; g[i] = 0.299f * (c >> 16 & 255) + 0.587f * (c >> 8 & 255) + 0.114f * (c & 255); }
        return g;
    }

    /** Separable Boxglaettung mit Radius r. */
    private static float[] box(float[] s, int w, int h, int r) {
        float[] t = new float[s.length], o = new float[s.length]; float inv = 1f / (2 * r + 1);
        for (int y = 0; y < h; y++) {
            int row = y * w; float acc = 0;
            for (int k = -r; k <= r; k++) acc += s[row + Math.max(0, Math.min(w - 1, k))];
            for (int x = 0; x < w; x++) { t[row + x] = acc * inv; acc += s[row + Math.min(w - 1, x + r + 1)] - s[row + Math.max(0, x - r)]; }
        }
        for (int x = 0; x < w; x++) {
            float acc = 0;
            for (int k = -r; k <= r; k++) acc += t[Math.max(0, Math.min(h - 1, k)) * w + x];
            for (int y = 0; y < h; y++) { o[y * w + x] = acc * inv; acc += t[Math.min(h - 1, y + r + 1) * w + x] - t[Math.max(0, y - r) * w + x]; }
        }
        return o;
    }

    static double measure(int[] p, int w, int h) {
        float[] b = box(gray(p, w, h), w, h, 1); double sum = 0; long n = 0;
        for (int y = 2; y < h - 2; y += 2) for (int x = 2; x < w - 2; x += 2) {
            float gx = b[y * w + x + 1] - b[y * w + x - 1], gy = b[(y + 1) * w + x] - b[(y - 1) * w + x];
            sum += Math.sqrt(gx * gx + gy * gy); n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Gleicht die Schaerfe an. Gibt einen Kurzbericht fuer das Protokoll zurueck. */
    static String equalize(int[] left, int[] right, int w, int h) {
        double sl = measure(left, w, h), sr = measure(right, w, h);
        if (sl <= 0 || sr <= 0) return "sharp n/a";
        boolean leftSoft = sl < sr; double ratio = Math.min(sl, sr) / Math.max(sl, sr);
        String base = String.format(Locale.ROOT, "sharp L %.2f R %.2f", sl, sr);
        if (ratio > 0.88) return base + " ok";
        double amount = Math.min(1.6, (1 / ratio - 1) * 0.6);
        int[] p = leftSoft ? left : right;
        float[][] ch = new float[3][];
        float core = 2.5f;
        for (int c = 0; c < 3; c++) {
            int sh = 16 - 8 * c; float[] s = new float[w * h];
            for (int i = 0; i < s.length; i++) s[i] = p[i] >> sh & 255;
            float[] bl = box(box(s, w, h, 2), w, h, 2);        // ~ gaussartig, Radius ~ 2-3 px
            for (int i = 0; i < s.length; i++) {
                float d = s[i] - bl[i], a = Math.abs(d) - core; if (a < 0) a = 0;
                float v = s[i] + (float) amount * Math.signum(d) * a; s[i] = v < 0 ? 0 : v > 255 ? 255 : v;
            }
            ch[c] = s;
        }
        for (int i = 0; i < p.length; i++) p[i] = (p[i] & 0xFF000000) | (Math.round(ch[0][i]) << 16) | (Math.round(ch[1][i]) << 8) | Math.round(ch[2][i]);
        return base + String.format(Locale.ROOT, " -> %s sharpened x%.2f", leftSoft ? "L" : "R", amount);
    }
}
