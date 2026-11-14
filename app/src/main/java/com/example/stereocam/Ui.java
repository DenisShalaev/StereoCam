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

    /** Dialog im gemeinsamen Stil (dunkle Karte, runde Ecken, Akzent). */
    static final class Dlg extends android.app.AlertDialog.Builder {
        private final Context ctx;
        Dlg(Context c) { super(c, android.R.style.Theme_DeviceDefault_Dialog_Alert); ctx = c; }
        @Override public android.app.AlertDialog show() {
            android.app.AlertDialog dlg = create(); dlg.show();
            if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(round(ctx, 16, CARD, EDGE), dp(ctx, 12)));
            int tid = ctx.getResources().getIdentifier("alertTitle", "id", "android");
            TextView t = tid != 0 ? dlg.findViewById(tid) : null; if (t != null) t.setTextColor(TEXT);
            TextView m = dlg.findViewById(android.R.id.message); if (m != null) m.setTextColor(0xFFCBD5E1);
            for (int w : new int[] {android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEGATIVE, android.app.AlertDialog.BUTTON_NEUTRAL}) {
                Button b = dlg.getButton(w); if (b != null) { b.setTextColor(ACCENT); b.setAllCaps(false); }
            }
            android.widget.ListView lv = dlg.getListView();
            if (lv != null) { lv.setDivider(new android.graphics.drawable.ColorDrawable(EDGE)); lv.setDividerHeight(Math.max(1, dp(ctx, 1))); }
            return dlg;
        }
    }

    static final int ST_OFF = 0, ST_ON = 1, ST_NORMAL = 3;

    /** Runder Symbol-Knopf (immer quadratisch), Beschreibung als Bedienungshilfe/Tooltip. */
    static final class RoundBtn extends android.widget.ImageView {
        RoundBtn(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) { super.onMeasure(w, h); int s = Math.min(getMeasuredWidth(), getMeasuredHeight()); setMeasuredDimension(s, s); }
    }
    static android.widget.ImageView iconBtn(Context c, int icon, int desc) {
        android.widget.ImageView v = new RoundBtn(c); v.setImageResource(icon); v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int p = dp(c, 10); v.setPadding(p, p, p, p); v.setContentDescription(c.getString(desc)); v.setTooltipText(c.getString(desc)); state(v, ST_NORMAL); return v;
    }
    static void state(android.widget.ImageView v, int st) {
        GradientDrawable bg = new GradientDrawable(); bg.setShape(GradientDrawable.OVAL);
        bg.setColor(st == ST_ON ? 0xE61E88E5 : 0x73000000); bg.setStroke(Math.max(1, dp(v.getContext(), 1)), st == ST_ON ? 0xFFFFFFFF : 0x66FFFFFF);
        v.setBackground(bg); v.setImageAlpha(st == ST_OFF ? 150 : 255);
    }

    /** Kleiner Textknopf (Chip) fuer Leisten. */
    static TextView chip(Context c, CharSequence s, boolean primary) {
        TextView t = text(c, s, 13, primary ? 0xFF0B1220 : TEXT); t.setGravity(Gravity.CENTER);
        t.setBackground(round(c, 18, primary ? ACCENT : BG, primary ? 0 : EDGE)); t.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8)); return t;
    }

    static View space(Context c, int h) { View v = new View(c); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, h))); return v; }
    static int alpha(int color, int a) { return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color)); }
}
