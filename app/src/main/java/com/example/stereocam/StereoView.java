package com.example.stereocam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Anzeige eines Stereopaares wie im StereoPano-Betrachter: 2D (flach), Cardboard (zwei Augen nebeneinander, optional mit
 * Linsenverzerrung) und SBS-Brille (voll oder "Halb-SBS", d. h. auf die halbe Breite gestaucht erwartet - hier: jedes Auge fuellt seine Haelfte).
 * Der Modus "Bewegung" zeichnet ein einzelnes, von aussen berechnetes Bild wie 2D.
 */
final class StereoView extends View {
    static final int MOTION = 0, FLAT = 1, CARDBOARD = 2, SBS = 3;
    private int mode = MOTION; private boolean half, lens = true;
    private Bitmap single, left, right;
    private float zoom = 1f, panX, panY;                 // panX/panY: Verschiebung in Bruchteilen der Augenflaeche
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final float K1 = 0.09f, K2 = 0.14f;   // sanfte Tonnenverzerrung (Naeherung, keine Kalibrierung einer bestimmten Brille)
    private static final int MESH = 24;
    private final float[] verts = new float[(MESH + 1) * (MESH + 1) * 2];
    private final float[] src = new float[(MESH + 1) * (MESH + 1) * 2];

    StereoView(Context c) { super(c); setBackgroundColor(0xFF000000); }

    void setMode(int m) { mode = m; invalidate(); }
    int getMode() { return mode; }
    private float shift;                                       // Verschiebung des rechten gegen das linke Bild (Anteil der Bildbreite): Bildebene
    void setShift(float f) { shift = f; invalidate(); }
    void setHalf(boolean h) { half = h; invalidate(); }
    void setLens(boolean l) { lens = l; invalidate(); }
    void setSingle(Bitmap b) { single = b; invalidate(); }
    void setPair(Bitmap l, Bitmap r) { left = l; right = r; invalidate(); }
    float getZoom() { return zoom; }
    void setZoom(float z) { boolean st = mode == CARDBOARD || mode == SBS; zoom = Math.max(st ? 0.5f : 1f, Math.min(6f, z)); clampPan(); invalidate(); }
    void drag(float dxFrac, float dyFrac) { panX += dxFrac; panY += dyFrac; clampPan(); invalidate(); }
    void setPan(float x, float y) { panX = x; panY = y; clampPan(); invalidate(); }
    void reset() { zoom = 1f; panX = panY = 0; invalidate(); }
    private void clampPan() { float m = Math.max(0f, (zoom - 1f) / 2f / zoom); panX = Math.max(-m, Math.min(m, panX)); panY = Math.max(-m, Math.min(m, panY)); }

    // Kurzmeldung, in den Stereo-Modi fuer jedes Auge doppelt gezeichnet (Toasts und Leisten wuerden sich auf die zwei Haelften verteilen)
    private String flashText; private long flashUntil; private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG), bp = new Paint(Paint.ANTI_ALIAS_FLAG);
    void flash(String s) { flashText = s; flashUntil = android.os.SystemClock.uptimeMillis() + 2200; invalidate(); postInvalidateDelayed(2300); }
    private void drawFlash(Canvas c, float x0, float x1, float h) {
        if (flashText == null || android.os.SystemClock.uptimeMillis() > flashUntil) return;
        float d = getResources().getDisplayMetrics().density, maxW = (x1 - x0) * 0.86f;
        tp.setColor(0xFFFFFFFF); tp.setTextSize(15 * d); tp.setTextAlign(Paint.Align.CENTER);
        while (tp.measureText(flashText) > maxW && tp.getTextSize() > 9 * d) tp.setTextSize(tp.getTextSize() - d);
        float tw = tp.measureText(flashText), cx = (x0 + x1) / 2f, cy = h * 0.82f, pad = 10 * d;
        bp.setColor(0xCC0F172A); c.drawRoundRect(cx - tw / 2 - pad, cy - tp.getTextSize() - pad * 0.3f, cx + tw / 2 + pad, cy + pad * 0.8f, 12 * d, 12 * d, bp);
        c.drawText(flashText, cx, cy, tp);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (mode == MOTION || mode == FLAT) {
            Bitmap b = mode == MOTION ? single : left; if (b == null) return;
            drawEye(c, b, new RectF(0, 0, w, h), true, false, 0f);
        } else {
            if (left == null || right == null) return;
            boolean fit = true;                     // Halb-SBS: Bild fuellt die Haelfte (wird vom Display wieder entzerrt)
            c.save(); c.clipRect(0, 0, w / 2f, h); drawEye(c, left, new RectF(0, 0, w / 2f, h), fit, mode == CARDBOARD && lens, -shift / 2f); c.restore();
            c.save(); c.clipRect(w / 2f, 0, w, h); drawEye(c, right, new RectF(w / 2f, 0, w, h), fit, mode == CARDBOARD && lens, shift / 2f); c.restore();
            Paint p = new Paint(); p.setColor(0xFF000000); c.drawRect(w / 2f - 1, 0, w / 2f + 1, h, p);
            drawFlash(c, 0, w / 2f, h); drawFlash(c, w / 2f, w, h);
        }
    }

    /** Zeichnet ein Auge in den Bereich area (Seitenverhaeltnis erhalten, wenn fit). */
    private void drawEye(Canvas c, Bitmap b, RectF area, boolean fit, boolean distort, float sh) {
        float aw = area.width(), ah = area.height(), bw = b.getWidth(), bh = b.getHeight();
        float dw = aw, dh = ah;
        float xs = (mode == SBS && half) ? 0.5f : 1f;                  // Halb-SBS: jedes Auge auf halbe Breite gestaucht (die Brille dehnt es wieder auf das Doppelte)
        if (fit) { float s = Math.min(aw / (bw * xs), ah / bh); dw = bw * s * xs; dh = bh * s; }
        float cx = area.centerX() + panX * dw * zoom + sh * dw * zoom, cy = area.centerY() + panY * dh * zoom;
        float zw = dw * zoom, zh = dh * zoom;
        RectF dst = new RectF(cx - zw / 2, cy - zh / 2, cx + zw / 2, cy + zh / 2);
        if (!distort) { c.drawBitmap(b, null, dst, paint); return; }
        // Tonnenverzerrung gegen das Kissen der Linsen: Zielradius = r * (1 + k1 + k2) / (1 + k1 r^2 + k2 r^4), r auf die halbe Diagonale des Auges bezogen
        float ecx = area.centerX(), ecy = area.centerY(), half = (float) Math.hypot(aw, ah) / 2f, norm = 1f + K1 + K2;
        int i = 0;
        for (int y = 0; y <= MESH; y++) for (int x = 0; x <= MESH; x++) {
            float px = dst.left + dst.width() * x / MESH, py = dst.top + dst.height() * y / MESH;
            src[i] = b.getWidth() * x / (float) MESH; src[i + 1] = b.getHeight() * y / (float) MESH;
            float dx = (px - ecx) / half, dy = (py - ecy) / half, r2 = dx * dx + dy * dy, f = norm / (1f + K1 * r2 + K2 * r2 * r2);
            verts[i] = ecx + dx * half * f; verts[i + 1] = ecy + dy * half * f; i += 2;
        }
        c.drawBitmapMesh(b, MESH, MESH, verts, 0, null, 0, paint);
    }
}
