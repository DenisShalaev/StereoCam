package com.example.stereocam;
public class CropTest {
    public static void main(String[] a) {
        int W = 1200, H = 900; int[] tex = AlignTest.tex(W + 200, H + 200, 21); int TW = W + 200;
        boolean bad = false;
        for (double trueFrac : new double[] {0.58, 0.50, 0.70}) for (double err : new double[] {0.93, 1.0, 1.06}) {
            // Weitwinkel: ganze Szene; Tele: mittiger Ausschnitt trueFrac der Szene, 2 px Parallaxe + Rauschen
            int[] wide = new int[W * H], tele = new int[W * H]; java.util.Random r = new java.util.Random(3);
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) wide[y * W + x] = tex[(y + 100) * TW + x + 100];
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                double sx = W / 2.0 + (x - W / 2.0) * trueFrac + 100 + 4, sy = H / 2.0 + (y - H / 2.0) * trueFrac + 100 + 1;
                int c = StereoAligner.bilinear(tex, TW, H + 200, sx, sy); int g = (int) Math.max(0, Math.min(255, ((c & 255) * 1.1 + r.nextGaussian() * 3))); tele[y * W + x] = 0xFF000000 | g << 16 | g << 8 | g; }
            double nom = trueFrac * err; double[] res = StereoAligner.refineCrop(wide, W, H, tele, W, H, nom);
            boolean ok = Math.abs(res[0] - trueFrac) < 0.006;
            System.out.println(String.format("wahr %.3f, Nennwert %.3f -> %.3f (ncc %.2f, Nennwert %.2f) %s", trueFrac, nom, res[0], res[1], res[2], ok ? "OK" : "FAIL"));
            if (!ok) bad = true;
        }
        System.out.println(bad ? "FEHLER" : "ALLE TESTS OK"); System.exit(bad ? 1 : 0);
    }
}
