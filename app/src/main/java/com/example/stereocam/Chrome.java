package com.example.stereocam;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.List;

/** Gemeinsame Bedienelemente der Aufnahme-Bildschirme: Zahnrad (oben rechts) und Galerie-Vorschaubild (unten links). */
final class Chrome {
    private Chrome() {}

    static String cfg(Context c) { return Prefs.str(c, Prefs.MODE) + "|" + Prefs.str(c, Prefs.PAIR_KEY); }

    static ImageView gear(final Activity a, FrameLayout root) {
        ImageView g = new ImageView(a); g.setImageResource(R.drawable.ic_settings); int p = Ui.dp(a, 10); g.setPadding(p, p, p, p);
        g.setBackground(Ui.round(a, 22, Ui.alpha(Ui.BG, 170), Ui.EDGE)); g.setContentDescription(a.getString(R.string.set_title));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44), Gravity.TOP | Gravity.END); lp.topMargin = Ui.dp(a, 10); lp.rightMargin = Ui.dp(a, 12);
        root.addView(g, lp); g.setOnClickListener(v -> a.startActivity(new Intent(a, ProbeActivity.class)));
        return g;
    }

    /** Vorschaubild des neuesten Fotos; Antippen oeffnet die Galerie. */
    static ImageView gallery(final Activity a, FrameLayout root) {
        ImageView t = new ImageView(a); t.setScaleType(ImageView.ScaleType.CENTER_CROP); t.setBackground(Ui.round(a, 12, Ui.CARD, Ui.EDGE));
        t.setClipToOutline(true); t.setContentDescription(a.getString(R.string.gal_title));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(a, 60), Ui.dp(a, 60), Gravity.BOTTOM | Gravity.START); lp.leftMargin = Ui.dp(a, 24); lp.bottomMargin = Ui.dp(a, 30);
        root.addView(t, lp); t.setOnClickListener(v -> a.startActivity(new Intent(a, StereoGalleryActivity.class)));
        return t;
    }

    static void loadLatest(final Activity a, final ImageView thumb) {
        if (thumb == null) return;
        new Thread(() -> {
            try {
                List<StereoItems.Item> l = StereoItems.query(a); if (l.isEmpty()) return;
                Bitmap bm = null; android.net.Uri u = l.get(0).uri;
                if (Build.VERSION.SDK_INT >= 29) bm = a.getContentResolver().loadThumbnail(u, new Size(240, 240), null);
                else { try (java.io.InputStream in = a.getContentResolver().openInputStream(u)) { BitmapFactory.Options o = new BitmapFactory.Options(); o.inSampleSize = 8; bm = BitmapFactory.decodeStream(in, null, o); } }
                final Bitmap fb = bm; if (fb != null) a.runOnUiThread(() -> thumb.setImageBitmap(fb));
            } catch (Throwable ignored) {}
        }).start();
    }

    /** Haben sich Modus oder Kamerapaar in den Einstellungen geaendert, wird der Startablauf neu durchlaufen. */
    static boolean restartIfChanged(Activity a, String cfgAtStart) {
        if (cfg(a).equals(cfgAtStart)) return false;
        a.startActivity(new Intent(a, LaunchActivity.class)); a.finish(); return true;
    }
}
