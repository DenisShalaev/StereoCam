package com.example.stereocam;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

/** Liste der mit StereoCam gespeicherten Stereodateien (Pictures/StereoCam, Download/StereoCam), neueste zuerst. */
final class StereoItems {
    static final class Item { Uri uri; String name; long date, size; }
    private StereoItems() {}

    static List<Item> query(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            Uri files = MediaStore.Files.getContentUri("external");
            String sel; String[] args;
            if (Build.VERSION.SDK_INT >= 29) { sel = "(" + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?)"; args = new String[] {"Pictures/StereoCam%", "Download/StereoCam%"}; }
            else { sel = MediaStore.MediaColumns.DATA + " LIKE ?"; args = new String[] {"%/StereoCam/%"}; }
            try (Cursor cu = c.getContentResolver().query(files, new String[] {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE}, sel, args, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                while (cu != null && cu.moveToNext()) {
                    String n = cu.getString(1); String l = n.toLowerCase();
                    if (l.endsWith(".mpo") || l.endsWith(".vr.jpg") || l.contains("_sbs")) {
                        Item it = new Item(); it.uri = ContentUris.withAppendedId(files, cu.getLong(0)); it.name = n; it.date = cu.getLong(2) * 1000; it.size = cu.getLong(3); out.add(it);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }
}
