package com.example.stereocam;
import java.io.*; import javax.imageio.ImageIO; import java.awt.image.BufferedImage;
public class LentiTest {
    public static void main(String[] a) throws Exception {
        int W = 800, H = 600; int[] t1 = AlignTest.tex(W + 100, H, 4); int[] l = new int[W * H], r = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) { boolean fg = x > 300 && x < 500 && y > 200 && y < 400; l[y * W + x] = fg ? 0xFF000000 | (200 << 16) | (80 << 8) : t1[y * (W + 100) + x + 50]; r[y * W + x] = fg ? l[y * W + x] : t1[y * (W + 100) + x + 50 + 0]; }
        LentiCore.Params p = new LentiCore.Params(); p.outW = 1000; p.outH = 750; p.dpi = 600; p.lpi = 75; p.views = 8; p.reverse = true; p.depth = 1; p.marginX = 40; p.marginY = 20; p.gap = 6;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); LentiCore.render(l, r, W, H, 48, p, bo, f -> true);
        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bo.toByteArray()));
        System.out.println("PNG " + bi.getWidth() + "x" + bi.getHeight() + " " + bo.size() + " Bytes");
        boolean ok = bi.getWidth() == 1000 && bi.getHeight() == 750;
        ByteArrayOutputStream bt = new ByteArrayOutputStream(); LentiCore.testSheet(1000, 750, 600, 75, 8, true, bt); BufferedImage tb = ImageIO.read(new ByteArrayInputStream(bt.toByteArray())); ok &= tb.getWidth() == 1000;
        System.out.println(ok ? "ALLE TESTS OK" : "FEHLER"); System.exit(ok ? 0 : 1);
    }
}
