package com.example.stereocam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Startbildschirm: findet Kamerapaare, zeigt den Bericht, Einstellungen, Start der Aufnahme. */
public class ProbeActivity extends Activity {

    private DualProbe.Result res;
    private String selected = "";
    private LinearLayout pairBox;
    private TextView reportView;
    private LinearLayout modeBox;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(Ui.BG); sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16); root.setPadding(p, p, p, p); sv.addView(root);
        setContentView(sv);
        sv.setOnApplyWindowInsetsListener((v, in) -> { v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom()); return in; });

        LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView back = new android.widget.ImageView(this); back.setImageResource(R.drawable.ic_back); int bp = Ui.dp(this, 10); back.setPadding(bp, bp, bp, bp);
        back.setBackground(Ui.round(this, 22, Ui.CARD, Ui.EDGE)); back.setContentDescription(getString(R.string.back)); back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)); bl.rightMargin = Ui.dp(this, 12); head.addView(back, bl);
        head.addView(Ui.text(this, getString(R.string.set_title), 22, Ui.TEXT)); root.addView(head); root.addView(Ui.space(this, 6));
        root.addView(Ui.text(this, getString(R.string.probe_intro), 13, Ui.GREY));
        root.addView(Ui.space(this, 12));

        pairBox = Ui.card(this); root.addView(pairBox); root.addView(Ui.space(this, 10));

        modeBox = Ui.card(this); root.addView(modeBox); root.addView(Ui.space(this, 12));
        buildMode();

        LinearLayout fmt = Ui.card(this);
        fmt.addView(Ui.text(this, getString(R.string.fmt_title), 16, Ui.ACCENT));
        fmt.addView(row(Prefs.MPO, R.string.fmt_mpo_t, R.string.fmt_mpo_d));
        fmt.addView(row(Prefs.SBS, R.string.fmt_sbs_t, R.string.fmt_sbs_d));
        fmt.addView(row(Prefs.VRJPG, R.string.fmt_vr_t, R.string.fmt_vr_d));
        fmt.addView(row(Prefs.ANAGLYPH, R.string.fmt_ana_t, R.string.fmt_ana_d));
        fmt.addView(row(Prefs.PAIR, R.string.fmt_pair_t, 0));
        fmt.addView(Ui.space(this, 6));
        fmt.addView(row(Prefs.ALIGN, R.string.align_t, R.string.align_d));
        fmt.addView(row(Prefs.COLOR, R.string.color_t, R.string.color_d));
        root.addView(fmt); root.addView(Ui.space(this, 12));

        LinearLayout rep = Ui.card(this);
        rep.addView(Ui.text(this, getString(R.string.report_title), 16, Ui.ACCENT));
        reportView = Ui.text(this, "…", 11, Ui.GREY); reportView.setTypeface(android.graphics.Typeface.MONOSPACE); reportView.setTextIsSelectable(true);
        rep.addView(reportView);
        Button share = Ui.pill(this, R.string.share_report, false); share.setOnClickListener(v -> shareReport());
        rep.addView(Ui.space(this, 8)); rep.addView(share);
        root.addView(rep);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(perms(), 1); else scan();
    }

    private String[] perms() { return Build.VERSION.SDK_INT <= 28 ? new String[] {Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE} : new String[] {Manifest.permission.CAMERA}; }

    @Override public void onRequestPermissionsResult(int rc, String[] ps, int[] gr) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan();
        else reportView.setText(R.string.permission_needed);
    }

    private View row(final String key, int t, int d) { return Ui.toggleRow(this, t, d, Prefs.get(this, key), v -> Prefs.set(this, key, v)); }

    private void scan() {
        res = DualProbe.scan(this);
        reportView.setText(res.report.toString());
        pairBox.removeAllViews();
        if (res.pairs.isEmpty()) { pairBox.addView(Ui.text(this, getString(R.string.probe_no_pairs), 14, Ui.TEXT)); selected = ""; return; }
        RadioGroup g = new RadioGroup(this);
        String saved = Prefs.str(this, Prefs.PAIR_KEY); selected = res.pairs.get(0).key();
        for (DualProbe.Pair q : res.pairs) if (q.key().equals(saved)) selected = saved;
        Prefs.setStr(this, Prefs.PAIR_KEY, selected);
        for (final DualProbe.Pair q : res.pairs) {
            RadioButton rb = new RadioButton(this); rb.setTextColor(Ui.TEXT); rb.setTextSize(14);
            String base = Double.isNaN(q.baselineMm) ? getString(R.string.baseline_unknown) : String.format(Locale.ROOT, "%.0f mm", q.baselineMm);
            rb.setText(getString(R.string.pair_fmt, q.a.id, q.b.id, String.format(Locale.ROOT, "%.0f", q.a.equiv35), String.format(Locale.ROOT, "%.0f", q.b.equiv35), base));
            rb.setId(View.generateViewId()); g.addView(rb); rb.setChecked(q.key().equals(selected));
            rb.setOnClickListener(v -> { selected = q.key(); Prefs.setStr(this, Prefs.PAIR_KEY, selected); });
        }
        pairBox.addView(g);
    }

    private void buildMode() {
        modeBox.removeAllViews();
        modeBox.addView(Ui.text(this, getString(R.string.mode_title), 16, Ui.ACCENT));
        RadioGroup g = new RadioGroup(this);
        String[] keys = {"auto", "dual", "lens", "shift"}; int[] names = {R.string.mode_auto, R.string.mode_dual, R.string.mode_lens, R.string.mode_shift};
        String cur = Prefs.str(this, Prefs.MODE); if (cur.isEmpty()) cur = "auto";
        for (int i = 0; i < keys.length; i++) {
            final String k = keys[i]; RadioButton rb = new RadioButton(this); rb.setTextColor(Ui.TEXT); rb.setTextSize(14); rb.setText(names[i]);
            rb.setId(View.generateViewId()); g.addView(rb); rb.setChecked(k.equals(cur)); rb.setOnClickListener(v -> Prefs.setStr(this, Prefs.MODE, k));
        }
        modeBox.addView(g);
        modeBox.addView(Ui.text(this, getString(R.string.switch_intro), 12, Ui.GREY)); modeBox.addView(Ui.space(this, 4));
        modeBox.addView(Ui.text(this, getString(R.string.shift_intro), 12, Ui.GREY));
    }

    private void shareReport() {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, reportView.getText().toString()).putExtra(Intent.EXTRA_SUBJECT, "StereoCam report");
        startActivity(Intent.createChooser(i, null));
    }
}
