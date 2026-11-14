package com.example.stereocam;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Gemeinsamer Stil: dunkelblau, Karten, Akzent #4FC3F7, runde Ecken, keine Standard-Widgets. */
final class Ui {
    static final int BG = 0xFF0F172A, CARD = 0xFF1E293B, EDGE = 0xFF334155, TEXT = 0xFFFFFFFF, GREY = 0xFF94A3B8, ACCENT = 0xFF4FC3F7;
    private Ui() {}

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable round(Context c, float r, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(dp(c, r)); if (stroke != 0) g.setStroke(dp(c, 1), stroke); return g;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); l.setBackground(round(c, 16, CARD, EDGE));
        int p = dp(c, 14); l.setPadding(p, p, p, p); return l;
    }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t;
    }

    static Button pill(Context c, int textRes, boolean primary) {
        Button b = new Button(c); b.setText(textRes); b.setAllCaps(false); b.setTextSize(15);
        b.setTextColor(primary ? 0xFF0B1220 : TEXT); b.setBackground(round(c, 24, primary ? ACCENT : CARD, primary ? 0 : EDGE));
        b.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10)); b.setMinHeight(dp(c, 46)); b.setMinimumHeight(dp(c, 46)); return b;
    }

    /** Zeile mit Titel, Beschreibung und Schalter. */
    static LinearLayout toggleRow(Context c, int titleRes, int descRes, boolean on, final java.util.function.Consumer<Boolean> cb) {
        LinearLayout row = new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 6), 0, dp(c, 6));
        LinearLayout tx = new LinearLayout(c); tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(c, c.getString(titleRes), 15, TEXT));
        if (descRes != 0) tx.addView(text(c, c.getString(descRes), 12, GREY));
        row.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(c); sw.setChecked(on); sw.setOnCheckedChangeListener((b, v) -> cb.accept(v)); row.addView(sw);
        return row;
    }

    static View space(Context c, int h) { View v = new View(c); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, h))); return v; }
    static int alpha(int color, int a) { return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color)); }
}
