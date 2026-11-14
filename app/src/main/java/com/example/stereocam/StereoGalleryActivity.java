package com.example.stereocam;

import android.app.Activity;
import android.content.Intent;
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
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Galerie der StereoCam-Fotos: Antippen = Bewegungsansicht, langes Druecken = Auswahl (teilen, umwandeln, loeschen). */
public class StereoGalleryActivity extends Activity {
    private static final int PICK = 7, DEL = 8;
    private LinearLayout list, bar; private TextView count;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private List<StereoItems.Item> items = new ArrayList<>();
    private final Set<Uri> sel = new HashSet<>(); private boolean selecting;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setBackgroundColor(Ui.BG);
        page.setOnApplyWindowInsetsListener((v, in) -> { v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom()); return in; });
        int p = Ui.dp(this, 16);
        LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.VERTICAL); head.setPadding(p, p, p, Ui.dp(this, 8));
        head.addView(Ui.text(this, getString(R.string.gal_title), 22, Ui.TEXT));
        head.addView(Ui.text(this, getString(R.string.gal_intro), 13, Ui.GREY));
        head.addView(Ui.space(this, 8));
        Button open = Ui.pill(this, R.string.gal_open, false);
        open.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK));
        head.addView(open, new LinearLayout.LayoutParams(-1, -2));
        page.addView(head);

        bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setVisibility(View.GONE);
        bar.setBackgroundColor(Ui.CARD); bar.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        count = Ui.text(this, "", 14, Ui.TEXT); bar.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(barBtn(R.string.gal_all, v -> { sel.clear(); for (StereoItems.Item it : items) sel.add(it.uri); refreshSel(); }));
        bar.addView(barBtn(R.string.gal_share, v -> shareSel()));
        bar.addView(barBtn(R.string.gal_convert, v -> convertDialog()));
        bar.addView(barBtn(R.string.gal_delete, v -> confirmDelete()));
        bar.addView(barBtn(R.string.gal_cancel, v -> endSelect()));
        page.addView(bar);

        ScrollView sv = new ScrollView(this); list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(p, Ui.dp(this, 8), p, p);
        sv.addView(list); page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }

    private TextView barBtn(int res, View.OnClickListener l) {
        TextView t = Ui.chip(this, getString(res), false); t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = Ui.dp(this, 6); t.setLayoutParams(lp); return t;
    }

    @Override protected void onResume() { super.onResume(); fill(); }
    @Override protected void onDestroy() { super.onDestroy(); pool.shutdownNow(); }
    @Override public void onBackPressed() { if (selecting) endSelect(); else super.onBackPressed(); }

    @Override protected void onActivityResult(int req, int res, Intent d) {
        if (req == PICK && res == RESULT_OK && d != null && d.getData() != null) open(d.getData(), "");
        if (req == DEL) { endSelect(); fill(); }
    }

    private void open(Uri u, String name) { startActivity(new Intent(this, WiggleActivity.class).setData(u).putExtra("name", name)); }

    // ------------------------------------------------------------------ Liste

    private void fill() {
        items = StereoItems.query(this);
        Set<Uri> keep = new HashSet<>(); for (StereoItems.Item it : items) if (sel.contains(it.uri)) keep.add(it.uri); sel.retainAll(keep);
        list.removeAllViews();
        if (items.isEmpty()) { list.addView(Ui.text(this, getString(R.string.gal_empty), 14, Ui.GREY)); bar.setVisibility(View.GONE); selecting = false; return; }
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (final StereoItems.Item it : items) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            boolean on = sel.contains(it.uri);
            row.setBackground(Ui.round(this, 14, on ? 0xFF234B63 : Ui.CARD, on ? Ui.ACCENT : Ui.EDGE)); int q = Ui.dp(this, 10); row.setPadding(q, q, q, q);
            if (selecting) {
                TextView mark = Ui.text(this, on ? "✓" : "", 15, 0xFF0B1220); mark.setGravity(Gravity.CENTER);
                mark.setBackground(Ui.round(this, 12, on ? Ui.ACCENT : 0, Ui.GREY));
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)); mp.rightMargin = Ui.dp(this, 10); row.addView(mark, mp);
            }
            final ImageView th = new ImageView(this); th.setScaleType(ImageView.ScaleType.CENTER_CROP); th.setBackground(Ui.round(this, 8, Ui.BG, 0)); th.setClipToOutline(true);
            row.addView(th, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 54)));
            LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(Ui.dp(this, 12), 0, 0, 0);
            tx.addView(Ui.text(this, it.name, 14, Ui.TEXT)); tx.addView(Ui.text(this, df.format(new Date(it.date)), 12, Ui.GREY));
            row.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.setOnClickListener(v -> { if (selecting) toggle(it.uri); else open(it.uri, it.name); });
            row.setOnLongClickListener(v -> { if (!selecting) { selecting = true; sel.clear(); } toggle(it.uri); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = Ui.dp(this, 8); list.addView(row, lp);
            pool.execute(() -> {
                try {
                    Bitmap bm = Build.VERSION.SDK_INT >= 29 ? getContentResolver().loadThumbnail(it.uri, new Size(216, 162), null) : null;
                    if (bm != null) runOnUiThread(() -> th.setImageBitmap(bm));
                } catch (Throwable ignored) {}
            });
        }
        updateBar();
    }

    private void toggle(Uri u) { if (!sel.remove(u)) sel.add(u); if (sel.isEmpty()) { endSelect(); return; } refreshSel(); }
    private void refreshSel() { fill(); }
    private void endSelect() { selecting = false; sel.clear(); fill(); }
    private void updateBar() { bar.setVisibility(selecting ? View.VISIBLE : View.GONE); count.setText(getString(R.string.gal_selected, sel.size())); }

    private List<StereoItems.Item> chosen() { List<StereoItems.Item> o = new ArrayList<>(); for (StereoItems.Item it : items) if (sel.contains(it.uri)) o.add(it); return o; }

    // ------------------------------------------------------------------ Aktionen

    private void shareSel() {
        List<StereoItems.Item> c = chosen(); if (c.isEmpty()) return;
        ArrayList<Uri> uris = new ArrayList<>(); for (StereoItems.Item it : c) uris.add(it.uri);
        Intent s = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE).setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (uris.size() == 1) s.putExtra(Intent.EXTRA_STREAM, uris.get(0)); else s.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        startActivity(Intent.createChooser(s, null));
    }

    private void convertDialog() {
        if (sel.isEmpty()) return;
        String[] names = {getString(R.string.fmt_sbs_t), getString(R.string.fmt_ana_t), getString(R.string.fmt_pair_t), getString(R.string.fmt_mpo_t)};
        new Ui.Dlg(this).setTitle(getString(R.string.gal_convert)).setItems(names, (d, w) -> convert(w)).show();
    }

    private void convert(final int kind) {
        final List<StereoItems.Item> c = chosen(); Toast.makeText(this, R.string.gal_converting, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int ok = 0; String err = null;
            for (StereoItems.Item it : c) {
                try { StereoExport.run(this, it.uri, it.name, kind); ok++; }
                catch (Throwable e) { err = getString(R.string.gal_conv_failed, String.valueOf(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())); }
            }
            final String m = err == null ? getString(R.string.gal_converted, ok) : err + " (" + ok + "/" + c.size() + ")";
            runOnUiThread(() -> { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); endSelect(); });
        }).start();
    }

    private void confirmDelete() {
        final List<StereoItems.Item> c = chosen(); if (c.isEmpty()) return;
        new Ui.Dlg(this).setMessage(getString(R.string.gal_delete_confirm, c.size())).setNegativeButton(R.string.gal_cancel, null)
                .setPositiveButton(R.string.gal_delete, (d, w) -> {
                    final List<Uri> failed = new ArrayList<>();
                    for (StereoItems.Item it : c) { try { if (getContentResolver().delete(it.uri, null, null) <= 0) failed.add(it.uri); } catch (Throwable e) { failed.add(it.uri); } }
                    if (!failed.isEmpty() && Build.VERSION.SDK_INT >= 30) {              // Dateien anderer Herkunft: Systemdialog zum Loeschen
                        try { startIntentSenderForResult(MediaStore.createDeleteRequest(getContentResolver(), failed).getIntentSender(), DEL, null, 0, 0, 0); return; } catch (Throwable ignored) {}
                    }
                    if (!failed.isEmpty()) Toast.makeText(this, R.string.gal_delete_failed, Toast.LENGTH_LONG).show();
                    endSelect();
                }).show();
    }
}
