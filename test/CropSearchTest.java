package com.example.stereocam;
public class CropSearchTest {
    static int[] scene(int w, int h) {
        int[] p = new int[w * h]; java.util.Random r = new java.util.Random(5);
        double[][] blobs = new double[60][5];
        for (double[] b : blobs) { b[0] = r.nextDouble(); b[1] = r.nextDouble(); b[2] = 0.01 + r.nextDouble() * 0.06; b[3] = r.nextDouble(); b[4] = r.nextDouble(); }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double v = 0.4 + 0.2 * Math.sin(x * 0.03) * Math.cos(y * 0.02);
            for (double[] b : blobs) { double dx = x / (double) w - b[0], dy = y / (double) h - b[1]; v += (b[3] - 0.5) * Math.exp(-(dx * dx + dy * dy) / (b[2] * b[2])); }
            int g = (int) Math.max(0, Math.min(255, v * 255)); p[y * w + x] = 0xFF000000 | g << 16 | (int) (g * 0.9) << 8 | (int) (g * 0.8);
        }
        return p;
    }
    public static void main(String[] a) {
        int W = 1600, H = 1200; int[] full = scene(W, H);
        // Kamera B = Teleansicht: mittiger Ausschnitt 55 % des Weitwinkels, kleiner horizontaler Versatz (Parallaxe)
        double f = 0.55; int bw = 800, bh = 600; int[] tele = StereoAligner.resample(full, W, H, (W - W * f) / 2 + 6, (H - H * f) / 2, W * f, H * f, bw, bh);
        int[] wide = StereoAligner.resample(full, W, H, 0, 0, W, H, 800, 600);
        double[] r = StereoAligner.searchCrop(wide, 800, 600, tele, bw, bh);
        System.out.printf("welches=%d anteil=%.3f score=%.2f ohne=%.2f%n", (int) r[0], r[1], r[2], r[3]);
        boolean ok = (int) r[0] == 0 && Math.abs(r[1] - f) < 0.02 && r[2] > r[3] + 0.2;
        double[] r2 = StereoAligner.searchCrop(tele, bw, bh, wide, 800, 600);
        System.out.printf("vertauscht: welches=%d anteil=%.3f score=%.2f%n", (int) r2[0], r2[1], r2[2]);
        ok &= (int) r2[0] == 1 && Math.abs(r2[1] - f) < 0.02;
        double[] r3 = StereoAligner.searchCrop(wide, 800, 600, wide.clone(), 800, 600);
        System.out.printf("gleich: anteil=%.3f score=%.2f ohne=%.2f%n", r3[1], r3[2], r3[3]);
        System.out.println(ok ? "ALLE TESTS OK" : "FEHLER");
    }
}
