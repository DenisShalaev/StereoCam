package com.example.stereocam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Startpunkt: prueft die Kameraberechtigung, waehlt Modus und Kamerapaar und oeffnet sofort die Kameravorschau. */
public class LaunchActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) route(); else { askScreen(); requestPermissions(perms(), 1); }
    }

    private String[] perms() { return Build.VERSION.SDK_INT <= 28 ? new String[] {Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE} : new String[] {Manifest.permission.CAMERA}; }

    @Override public void onRequestPermissionsResult(int rc, String[] ps, int[] gr) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) route();
    }

    private void askScreen() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setGravity(Gravity.CENTER); l.setBackgroundColor(Ui.BG); int p = Ui.dp(this, 24); l.setPadding(p, p, p, p);
        TextView t = Ui.text(this, getString(R.string.permission_needed), 16, Ui.TEXT); t.setGravity(Gravity.CENTER); l.addView(t); l.addView(Ui.space(this, 16));
        Button g = Ui.pill(this, R.string.perm_grant, true); g.setOnClickListener(v -> requestPermissions(perms(), 1)); l.addView(g);
        setContentView(l);
    }

    private void route() {
        String mode = Prefs.str(this, Prefs.MODE); if (mode.isEmpty()) mode = "auto";
        DualProbe.Result res = DualProbe.scan(this);
        String key = Prefs.str(this, Prefs.PAIR_KEY); DualProbe.Pair chosen = null;
        for (DualProbe.Pair q : res.pairs) if (q.key().equals(key)) chosen = q;
        if (chosen == null && !res.pairs.isEmpty()) { chosen = res.pairs.get(0); Prefs.setStr(this, Prefs.PAIR_KEY, chosen.key()); }
        Intent i;
        if (mode.equals("shift") || chosen == null) i = new Intent(this, ShiftCaptureActivity.class);
        else if (mode.equals("lens")) i = new Intent(this, LensSwitchActivity.class).putExtra("pair", chosen.key());
        else i = new Intent(this, DualCaptureActivity.class).putExtra("pair", chosen.key());
        startActivity(i); finish();
    }
}
