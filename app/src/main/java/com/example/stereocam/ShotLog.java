package com.example.stereocam;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Kleines Aufnahmeprotokoll (eine Zeile je Aufnahme, die letzten 300): Entfernung, Brennweiten, gefundener Zuschnitt - zum Auswerten und Teilen. */
final class ShotLog {
    private ShotLog() {}

    private static File file(Context c) { return new File(c.getFilesDir(), "shots.log"); }

    static synchronized void add(Context c, String line) {
        try {
            List<String> all = new ArrayList<>();
            File f = file(c);
            if (f.exists()) try (BufferedReader r = new BufferedReader(new FileReader(f))) { String s; while ((s = r.readLine()) != null) all.add(s); }
            all.add(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " | " + line.replace('\n', ' ').trim());
            while (all.size() > 300) all.remove(0);
            try (FileWriter w = new FileWriter(f, false)) { for (String s : all) w.write(s + "\n"); }
        } catch (Exception ignored) {}
    }

    static synchronized String read(Context c) {
        File f = file(c); if (!f.exists()) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) { String s; while ((s = r.readLine()) != null) sb.append(s).append('\n'); } catch (Exception ignored) {}
        return sb.toString();
    }
}
