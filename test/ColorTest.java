package com.example.stereocam;
public class ColorTest {
    public static void main(String[] a) {
        int w = 400, h = 300; int[] L = new int[w * h], R = new int[w * h]; java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < w * h; i++) {
            int r = 40 + rnd.nextInt(180), g = 40 + rnd.nextInt(180), b = 40 + rnd.nextInt(180); L[i] = 0xFF000000 | r << 16 | g << 8 | b;
            int r2 = Math.min(255, (int) (r * 1.08)), g2 = g, b2 = (int) (b * 0.9); R[i] = 0xFF000000 | r2 << 16 | g2 << 8 | b2;
        }
        double[] before = diff(L, R), ok;
        ColorMatch.apply(L, R, w, h);
        double[] after = diff(L, R);
        System.out.printf("mittlere Abweichung vorher R%.1f G%.1f B%.1f, nachher R%.1f G%.1f B%.1f%n", before[0], before[1], before[2], after[0], after[1], after[2]);
        boolean good = Math.abs(after[0]) < 1.5 && Math.abs(after[1]) < 1.5 && Math.abs(after[2]) < 1.5;
        System.out.println(good ? "ALLE TESTS OK" : "FEHLER");
        // Identisch: bleibt (nahezu) unveraendert
        int[] R2 = L.clone(); ColorMatch.apply(L, R2, w, h); double[] same = diff(L, R2); System.out.printf("identisch: %.2f %.2f %.2f%n", same[0], same[1], same[2]);
    }
    static double[] diff(int[] a, int[] b) { double[] s = new double[3]; for (int i = 0; i < a.length; i++) { s[0] += (b[i] >> 16 & 255) - (a[i] >> 16 & 255); s[1] += (b[i] >> 8 & 255) - (a[i] >> 8 & 255); s[2] += (b[i] & 255) - (a[i] & 255); } for (int c = 0; c < 3; c++) s[c] /= a.length; return s; }
}
