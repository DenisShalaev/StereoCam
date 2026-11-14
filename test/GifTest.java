package com.example.stereocam;
import javax.imageio.*; import javax.imageio.stream.*; import java.io.*; import java.util.*; import java.awt.image.BufferedImage;
public class GifTest {
    public static void main(String[] a) throws Exception {
        int W = 200, H = 150, N = 4; List<int[]> fr = new ArrayList<>();
        for (int f = 0; f < N; f++) { int[] p = new int[W * H]; for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) { int r = (x * 255 / W + f * 20) & 255, g = y * 255 / H, b = ((x + y + f * 10) % 64) * 4; if (((x / 20 + y / 20) & 1) == 0 && f % 2 == 0) { r = 200; g = 30; b = 60; } p[y * W + x] = 0xFF000000 | r << 16 | g << 8 | b; } fr.add(p); }
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); GifWriter g = new GifWriter(bo, W, H, 7, GifWriter.palette(fr)); for (int[] p : fr) g.frame(p); g.finish();
        byte[] gif = bo.toByteArray(); System.out.println("GIF " + gif.length + " Bytes");
        ImageReader rd = ImageIO.getImageReadersByFormatName("gif").next(); rd.setInput(new MemoryCacheImageInputStream(new ByteArrayInputStream(gif)));
        int n = rd.getNumImages(true); boolean bad = n != N; System.out.println("Bilder: " + n + (bad ? " FAIL" : " OK"));
        for (int f = 0; f < n; f++) { BufferedImage bi = rd.read(f); double e = 0; for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) { int c = bi.getRGB(x, y), o = fr.get(f)[y * W + x]; for (int sh = 0; sh < 24; sh += 8) e += Math.abs((c >> sh & 255) - (o >> sh & 255)); }
            e /= W * H * 3; System.out.println(String.format("Bild %d: mittlerer Fehler %.1f", f, e)); if (bi.getWidth() != W || e > 12) bad = true; }
        String s = new String(gif, "ISO-8859-1"); if (!s.contains("NETSCAPE2.0")) { bad = true; System.out.println("Schleife fehlt"); }
        System.out.println(bad ? "FEHLER" : "ALLE TESTS OK"); System.exit(bad ? 1 : 0);
    }
}
