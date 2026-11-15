package com.example.stereocam;

import android.content.Context;
import android.content.SharedPreferences;

/** Einstellungen (SharedPreferences). */
final class Prefs {
    static final String MPO = "fmt_mpo", SBS = "fmt_sbs", VRJPG = "fmt_vrjpg", ANAGLYPH = "fmt_anaglyph", PAIR = "fmt_pair",
            SWAP = "swap_eyes", ALIGN = "auto_align", PAIR_KEY = "pair_key", MODE = "capture_mode", CONV = "convergence", DIST = "shift_dist", COLOR = "color_match", ZOOMMIN = "dual_zoom_min";
    private Prefs() {}
    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("stereocam", Context.MODE_PRIVATE); }
    static boolean get(Context c, String k) {
        boolean def = k.equals(MPO) || k.equals(SBS) || k.equals(ALIGN) || k.equals(COLOR) || k.equals(ZOOMMIN);
        return sp(c).getBoolean(k, def);
    }
    static void set(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }
    static int getInt(Context c, String k, int def) { return sp(c).getInt(k, def); }
    static void setInt(Context c, String k, int v) { sp(c).edit().putInt(k, v).apply(); }
    static String str(Context c, String k) { return sp(c).getString(k, ""); }
    static void setStr(Context c, String k, String v) { sp(c).edit().putString(k, v).apply(); }
}
