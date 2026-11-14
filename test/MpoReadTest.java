package com.example.stereocam;
import java.io.*; import java.awt.image.BufferedImage; import javax.imageio.ImageIO;
public class MpoReadTest {
    static byte[] mpoSecond(byte[] d) {
        try {
            int p = 2;
            while (p + 4 < d.length && (d[p] & 255) == 0xFF) {
                int m = d[p + 1] & 255, len = ((d[p + 2] & 255) << 8) | (d[p + 3] & 255);
                if (m == 0xDA) break;
                if (m == 0xE2 && d[p + 4] == 'M' && d[p + 5] == 'P' && d[p + 6] == 'F' && d[p + 7] == 0) {
                    int base = p + 8; boolean le = d[base] == 'I';
                    int ifd = base + i32(d, base + 4, le), cnt = i16(d, ifd, le);
                    for (int i = 0; i < cnt; i++) {
                        int e = ifd + 2 + i * 12;
                        if (i16(d, e, le) == 0xB002) {
                            int ent = base + i32(d, e + 8, le), n = i32(d, e + 4, le) / 16;
                            if (n >= 2) {
                                int size = i32(d, ent + 16 + 4, le), off = base + i32(d, ent + 16 + 8, le);
                                if (off > 0 && off + 4 < d.length && (d[off] & 255) == 0xFF && (d[off + 1] & 255) == 0xD8) {
                                    int end = size > 0 && off + size <= d.length ? off + size : d.length;
                                    byte[] r = new byte[end - off]; System.arraycopy(d, off, r, 0, r.length); return r;
                                }
                            }
                        }
                    }
                }
                p += 2 + len;
            }
        } catch (Throwable ignored) {}
        // Rueckfall: zweite JPEG-Kennung hinter dem ersten Bild suchen (nicht in Vorschaubildern: erst ab 20 % der Datei)
        for (int i = d.length / 5; i + 4 < d.length; i++)
            if ((d[i] & 255) == 0xFF && (d[i + 1] & 255) == 0xD8 && (d[i + 2] & 255) == 0xFF && ((d[i + 3] & 255) == 0xE0 || (d[i + 3] & 255) == 0xE1)) {
                byte[] r = new byte[d.length - i]; System.arraycopy(d, i, r, 0, r.length); return r;
            }
        return null;
    }

    private static int i16(byte[] d, int p, boolean le) { return le ? (d[p] & 255) | ((d[p + 1] & 255) << 8) : ((d[p] & 255) << 8) | (d[p + 1] & 255); }
    private static int i32(byte[] d, int p, boolean le) {
        return le ? (d[p] & 255) | ((d[p + 1] & 255) << 8) | ((d[p + 2] & 255) << 16) | ((d[p + 3] & 255) << 24)
                : ((d[p] & 255) << 24) | ((d[p + 1] & 255) << 16) | ((d[p + 2] & 255) << 8) | (d[p + 3] & 255);
    }

    static byte[] jpg(int c) throws Exception { BufferedImage b=new BufferedImage(64,48,1); for(int y=0;y<48;y++)for(int x=0;x<64;x++)b.setRGB(x,y,c); ByteArrayOutputStream o=new ByteArrayOutputStream(); ImageIO.write(b,"jpg",o); return o.toByteArray(); }
    public static void main(String[] a) throws Exception {
        byte[] l=jpg(0xFF0000), r=jpg(0x0000FF); byte[] m=MpoWriter.build(l,r);
        byte[] s=mpoSecond(m); boolean ok=s!=null&&java.util.Arrays.equals(s,r);
        System.out.println(ok?"OK mpoSecond == right":"FAIL "+(s==null?"null":s.length+" vs "+r.length));
        System.exit(ok?0:1);
    }
}
