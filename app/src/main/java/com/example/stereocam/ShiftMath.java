package com.example.stereocam;

/** Reine Rechenhilfen fuer die Aufnahme mit Verschiebung (auf der Desktop-JVM testbar). */
final class ShiftMath {
    private ShiftMath() {}

    /** Abstand zum Motiv in cm aus der Reglerstellung 0..100 (logarithmisch: 5 cm .. ca. 16 m). */
    static double distanceCm(int progress) { return 5 * Math.pow(10, progress / 40.0); }

    /** Empfohlene Basis (Verschiebung) in mm: 1/30 des Motivabstands. */
    static double baselineMm(double distanceCm) { return distanceCm * 10.0 / 30.0; }

    /**
     * Relative Drehung zwischen zwei Aufnahmen im Koordinatensystem des Geraets bei der ersten Aufnahme.
     * r1, r2: 3x3-Rotationsmatrizen (zeilenweise, Geraet -> Welt, wie SensorManager.getRotationMatrixFromVector).
     * Rueckgabe: {um x (Nicken), um y (Rollen), um z (Gieren/Schwenken)} in Grad; fuer kleine Winkel.
     */
    static double[] relAnglesDeg(float[] r1, float[] r2) {
        double[][] rel = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) { double s = 0; for (int k = 0; k < 3; k++) s += r1[k * 3 + i] * r2[k * 3 + j]; rel[i][j] = s; }
        double wx = (rel[2][1] - rel[1][2]) / 2, wy = (rel[0][2] - rel[2][0]) / 2, wz = (rel[1][0] - rel[0][1]) / 2;
        return new double[] {Math.toDegrees(Math.asin(clamp(wx))), Math.toDegrees(Math.asin(clamp(wy))), Math.toDegrees(Math.asin(clamp(wz)))};
    }

    /** Groesste Winkelabweichung in Grad. */
    static double maxAbs(double[] a) { return Math.max(Math.abs(a[0]), Math.max(Math.abs(a[1]), Math.abs(a[2]))); }

    private static double clamp(double v) { return Math.max(-1, Math.min(1, v)); }
}
