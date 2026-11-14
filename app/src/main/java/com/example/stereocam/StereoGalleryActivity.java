package com.example.stereocam;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Galerie der mit StereoCam gemachten Stereofotos (Pictures/StereoCam und Download/StereoCam) + beliebige Datei oeffnen. */
public class StereoGalleryActivity extends Activity {
    private static final int PICK = 7;
    private LinearLayout list;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); int p = Ui.dp(this, 16); root.setPadding(p, p, p, p);
        sv.addView(root); setContentView(sv);
        sv.setOnApplyWindowInsetsListener((v, in) -> { v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom()); return in; });
        root.addView(Ui.text(this, getString(R.string.gal_title), 22, Ui.TEXT));
        root.addView(Ui.text(this, getString(R.string.gal_intro), 13, Ui.GREY));
        root.addView(Ui.space(this, 10));
        Button open = Ui.pill(this, R.string.gal_open, false);
        open.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"); startActivityForResult(i, PICK); });
        root.addView(open, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.space(this, 10));
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); root.addView(list);
    }

    @Override protected void onResume() { super.onResume(); fill(); }
    @Override protected void onDestroy() { super.onDestroy(); pool.shutdownNow(); }

    @Override protected void onActivityResult(int req, int res, Intent d) {
        if (req == PICK && res == RESULT_OK && d != null && d.getData() != null) show(d.getData(), "");
    }

    private void show(Uri u, String name) { startActivity(new Intent(this, WiggleActivity.class).setData(u).putExtra("name", name)); }

    private void fill() {
        list.removeAllViews();
        final List<Object[]> items = new ArrayList<>();
        try {
            Uri files = MediaStore.Files.getContentUri("external");
            String sel; String[] args;
            if (Build.VERSION.SDK_INT >= 29) { sel = "(" + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?)"; args = new String[] {"Pictures/StereoCam%", "Download/StereoCam%"}; }
            else { sel = MediaStore.MediaColumns.DATA + " LIKE ?"; args = new String[] {"%/StereoCam/%"}; }
            try (Cursor c = getContentResolver().query(files, new String[] {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED}, sel, args, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                while (c != null && c.moveToNext()) {
                    String n = c.getString(1); String l = n.toLowerCase();
                    if (l.endsWith(".mpo") || l.endsWith(".vr.jpg") || l.contains("_sbs")) items.add(new Object[] {ContentUris.withAppendedId(files, c.getLong(0)), n, c.getLong(2) * 1000});
                }
            }
        } catch (Throwable ignored) {}
        if (items.isEmpty()) { list.addView(Ui.text(this, getString(R.string.gal_empty), 14, Ui.GREY)); return; }
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (Object[] it : items) {
            final Uri u = (Uri) it[0]; final String n = (String) it[1];
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.round(this, 14, Ui.CARD, Ui.EDGE)); int q = Ui.dp(this, 10); row.setPadding(q, q, q, q);
            final ImageView th = new ImageView(this); th.setScaleType(ImageView.ScaleType.CENTER_CROP); th.setBackground(Ui.round(this, 8, Ui.BG, 0));
            row.addView(th, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 54)));
            LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(Ui.dp(this, 12), 0, 0, 0);
            tx.addView(Ui.text(this, n, 14, Ui.TEXT)); tx.addView(Ui.text(this, df.format(new Date((Long) it[2])), 12, Ui.GREY));
            row.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.setOnClickListener(v -> show(u, n));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.bottomMargin = Ui.dp(this, 8);
            list.addView(row, lp);
            pool.execute(() -> {
                try {
                    Bitmap bm = Build.VERSION.SDK_INT >= 29 ? getContentResolver().loadThumbnail(u, new Size(216, 162), null) : null;
                    if (bm != null) runOnUiThread(() -> th.setImageBitmap(bm));
                } catch (Throwable ignored) {}
            });
        }
    }
}
