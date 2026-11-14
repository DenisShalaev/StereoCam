package com.example.stereocam;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Schreibt animierte GIFs (Endlosschleife, globale 256-Farben-Palette per Median-Cut, Bayer-Raster gegen Streifen). Reines Java. */
final class GifWriter {
    private final OutputStream os; private final int w, h, delayCs; private final int[] pal; private final byte[] lut = new byte[32768];

    GifWriter(OutputStream os, int w, int h, int delayCs, int[] palette) throws IOException {
        this.os = os; this.w = w; this.h = h; this.delayCs = delayCs; this.pal = palette;
        for (int i = 0; i < 32768; i++) {                                   // naechste Palettenfarbe je RGB555
            int r = (i >> 10 & 31) * 255 / 31, g = (i >> 5 & 31) * 255 / 31, b = (i & 31) * 255 / 31, best = 0, bd = Integer.MAX_VALUE;
            for (int k = 0; k < 256; k++) { int pr = pal[k] >> 16 & 255, pg = pal[k] >> 8 & 255, pb = pal[k] & 255; int d = 3 * (r - pr) * (r - pr) + 4 * (g - pg) * (g - pg) + 2 * (b - pb) * (b - pb); if (d < bd) { bd = d; best = k; } }
            lut[i] = (byte) best;
        }
        os.write("GIF89a".getBytes("US-ASCII")); le16(w); le16(h); os.write(0xF7); os.write(0); os.write(0);   // globale Palette, 256 Eintraege
        for (int k = 0; k < 256; k++) { os.write(pal[k] >> 16 & 255); os.write(pal[k] >> 8 & 255); os.write(pal[k] & 255); }
        os.write(0x21); os.write(0xFF); os.write(11); os.write("NETSCAPE2.0".getBytes("US-ASCII")); os.write(3); os.write(1); le16(0); os.write(0);   // Endlosschleife
    }

    private void le16(int v) throws IOException { os.write(v & 255); os.write(v >> 8 & 255); }

    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

    void frame(int[] argb) throws IOException {
        byte[] idx = new byte[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int c = argb[y * w + x], o = BAYER[(y & 3) * 4 + (x & 3)] - 8;      // +-8 / 255 ~ eine Stufe der 5-Bit-Quantisierung
            int r = clamp((c >> 16 & 255) + o), g = clamp((c >> 8 & 255) + o), b = clamp((c & 255) + o);
            idx[y * w + x] = lut[(r >> 3) << 10 | (g >> 3) << 5 | (b >> 3)];
        }
        os.write(0x21); os.write(0xF9); os.write(4); os.write(0); le16(delayCs); os.write(0); os.write(0);
        os.write(0x2C); le16(0); le16(0); le16(w); le16(h); os.write(0); os.write(8);
        lzw(idx);
    }

    void finish() throws IOException { os.write(0x3B); os.flush(); }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(255, v); }

    // ------------------------------------------------------------------ LZW

    private void lzw(byte[] data) throws IOException {
        final int CLEAR = 256, EOI = 257, HS = 9973;
        int[] hk = new int[HS], hv = new int[HS]; Arrays.fill(hk, -1);
        BitOut out = new BitOut(os);
        int next = 258, bits = 9; out.put(CLEAR, bits);
        int prefix = data[0] & 255;
        for (int i = 1; i < data.length; i++) {
            int k = data[i] & 255, key = prefix << 8 | k, hh = (key * 2654435761L >>> 8 & 0x7fffffff) % HS > 0 ? (int) ((key * 2654435761L >>> 8 & 0x7fffffff) % HS) : 0, found = -1;
            while (hk[hh] != -1) { if (hk[hh] == key) { found = hv[hh]; break; } hh = (hh + 1) % HS; }
            if (found >= 0) { prefix = found; continue; }
            out.put(prefix, bits);
            if (next < 4096) { hk[hh] = key; hv[hh] = next++; if (next > (1 << bits) && bits < 12) bits++; }
            else { out.put(CLEAR, bits); Arrays.fill(hk, -1); next = 258; bits = 9; }
            prefix = k;
        }
        out.put(prefix, bits); out.put(EOI, bits); out.flush(); os.write(0);
    }

    private static final class BitOut {
        private final OutputStream os; private int cur, n; private final byte[] blk = new byte[255]; private int bl;
        BitOut(OutputStream os) { this.os = os; }
        void put(int code, int bits) throws IOException { cur |= code << n; n += bits; while (n >= 8) { add(cur & 255); cur >>>= 8; n -= 8; } }
        private void add(int b) throws IOException { blk[bl++] = (byte) b; if (bl == 255) emit(); }
        private void emit() throws IOException { if (bl == 0) return; os.write(bl); os.write(blk, 0, bl); bl = 0; }
        void flush() throws IOException { if (n > 0) { add(cur & 255); cur = 0; n = 0; } emit(); }
    }

    // ------------------------------------------------------------------ Palette (Median-Cut auf 5-Bit-Histogramm)

    static int[] palette(List<int[]> frames) {
        int[] hist = new int[32768];
        for (int[] f : frames) for (int i = 0; i < f.length; i += 3) { int c = f[i]; hist[(c >> 19 & 31) << 10 | (c >> 11 & 31) << 5 | (c >> 3 & 31)]++; }
        List<int[]> boxes = new ArrayList<>(); List<Integer> all = new ArrayList<>(); for (int i = 0; i < 32768; i++) if (hist[i] > 0) all.add(i);
        int[] first = new int[all.size()]; for (int i = 0; i < first.length; i++) first[i] = all.get(i); boxes.add(first);
        while (boxes.size() < 256) {
            int bi = -1; double bs = 0;
            for (int i = 0; i < boxes.size(); i++) { int[] bx = boxes.get(i); if (bx.length < 2) continue; double sc = 0; for (int c : bx) sc += hist[c]; sc *= range(bx)[3]; if (sc > bs) { bs = sc; bi = i; } }
            if (bi < 0) break;
            final int[] bx = boxes.remove(bi); final int ch = range(bx)[4];
            int[] sorted = Arrays.stream(bx).boxed().sorted((a, b) -> Integer.compare(comp(a, ch), comp(b, ch))).mapToInt(Integer::intValue).toArray();
            long tot = 0; for (int c : sorted) tot += hist[c]; long acc = 0; int cut = 1;
            for (int i = 0; i < sorted.length - 1; i++) { acc += hist[sorted[i]]; cut = i + 1; if (acc * 2 >= tot) break; }
            boxes.add(Arrays.copyOfRange(sorted, 0, cut)); boxes.add(Arrays.copyOfRange(sorted, cut, sorted.length));
        }
        int[] pal = new int[256];
        for (int i = 0; i < boxes.size() && i < 256; i++) {
            long r = 0, g = 0, b = 0, n = 0; for (int c : boxes.get(i)) { int cnt = hist[c]; r += (long) (c >> 10 & 31) * cnt; g += (long) (c >> 5 & 31) * cnt; b += (long) (c & 31) * cnt; n += cnt; }
            n = Math.max(1, n); pal[i] = ((int) (r * 255 / 31 / n)) << 16 | ((int) (g * 255 / 31 / n)) << 8 | (int) (b * 255 / 31 / n);
        }
        return pal;
    }
    private static int comp(int c, int ch) { return ch == 0 ? c >> 10 & 31 : ch == 1 ? c >> 5 & 31 : c & 31; }
    private static int[] range(int[] bx) {
        int[] mn = {31, 31, 31}, mx = {0, 0, 0};
        for (int c : bx) for (int ch = 0; ch < 3; ch++) { int v = comp(c, ch); if (v < mn[ch]) mn[ch] = v; if (v > mx[ch]) mx[ch] = v; }
        int best = 0; for (int ch = 1; ch < 3; ch++) if (mx[ch] - mn[ch] > mx[best] - mn[best]) best = ch;
        return new int[] {mn[0], mn[1], mn[2], Math.max(1, mx[best] - mn[best]), best};
    }
}
