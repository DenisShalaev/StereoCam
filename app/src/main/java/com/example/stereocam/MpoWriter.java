package com.example.stereocam;

import java.io.ByteArrayOutputStream;

/**
 * Schreibt eine MPO-Datei (Multi-Picture Format, CIPA DC-007): zwei komplette JPEGs hintereinander,
 * das erste enthaelt einen APP2-"MPF"-Index mit Groesse und Offset beider Bilder.
 * Das ist das Standardformat fuer Stereo-Fotos (Fujifilm W3, Nintendo 3DS, 3D-Fernseher).
 */
final class MpoWriter {
    private MpoWriter() {}

    static byte[] build(byte[] left, byte[] right) {
        // Einfuegestelle: hinter SOI und evtl. vorhandenen APP0/APP1 (JFIF/Exif)
        int pos = 2;
        while (pos + 4 <= left.length && (left[pos] & 255) == 0xFF && ((left[pos + 1] & 255) == 0xE0 || (left[pos + 1] & 255) == 0xE1)) {
            pos += 2 + (((left[pos + 2] & 255) << 8) | (left[pos + 3] & 255));
        }
        final int payload = 4 + 8 + 2 + 3 * 12 + 4 + 32;                 // "MPF\0" + Kopf + IFD + Eintraege
        final int segTotal = 2 + 2 + payload;                           // Marker + Laenge + Nutzdaten
        int size1 = left.length + segTotal, size2 = right.length;
        int mpHeaderPos = pos + 2 + 2 + 4;                               // Dateioffset des "II" (nach "MPF\0")
        int off2 = size1 - mpHeaderPos;                                  // Offset des 2. Bildes relativ zum MP-Kopf

        ByteArrayOutputStream o = new ByteArrayOutputStream(size1 + size2);
        o.write(left, 0, pos);
        o.write(0xFF); o.write(0xE2); o.write((payload + 2) >> 8); o.write((payload + 2) & 255);
        o.write('M'); o.write('P'); o.write('F'); o.write(0);
        o.write('I'); o.write('I'); o.write(0x2A); o.write(0);
        le32(o, 8);                                                     // Offset der ersten IFD
        le16(o, 3);                                                     // 3 Eintraege
        ifd(o, 0xB000, 7, 4, 0x30313030);                               // MPFVersion "0100"
        ifd(o, 0xB001, 4, 1, 2);                                        // NumberOfImages
        ifd(o, 0xB002, 7, 32, 8 + 2 + 36 + 4);                          // MPEntry-Tabelle (Offset ab MP-Kopf)
        le32(o, 0);                                                     // keine weitere IFD
        le32(o, 0x20020002); le32(o, size1); le32(o, 0); le16(o, 0); le16(o, 0);        // Bild 1: Stereo/Disparity, Hauptbild
        le32(o, 0x00020002); le32(o, size2); le32(o, off2); le16(o, 0); le16(o, 0);     // Bild 2
        o.write(left, pos, left.length - pos);
        o.write(right, 0, right.length);
        return o.toByteArray();
    }

    private static void ifd(ByteArrayOutputStream o, int tag, int type, int count, int value) { le16(o, tag); le16(o, type); le32(o, count); le32(o, value); }
    private static void le16(ByteArrayOutputStream o, int v) { o.write(v & 255); o.write((v >> 8) & 255); }
    private static void le32(ByteArrayOutputStream o, int v) { le16(o, v & 0xFFFF); le16(o, (v >>> 16) & 0xFFFF); }
}
