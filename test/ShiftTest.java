package com.example.stereocam;

public class ShiftTest {
    static float[] rot(char ax, double deg) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        switch (ax) {
            case 'x': return new float[] {1, 0, 0, 0, (float) c, (float) -s, 0, (float) s, (float) c};
            case 'y': return new float[] {(float) c, 0, (float) s, 0, 1, 0, (float) -s, 0, (float) c};
            default: return new float[] {(float) c, (float) -s, 0, (float) s, (float) c, 0, 0, 0, 1};
        }
    }
    static float[] mul(float[] a, float[] b) { float[] o = new float[9]; for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) { float s = 0; for (int k = 0; k < 3; k++) s += a[i * 3 + k] * b[k * 3 + j]; o[i * 3 + j] = s; } return o; }
    static boolean bad;
    static void check(boolean c, String m) { System.out.println((c ? "OK   " : "FAIL ") + m); if (!c) bad = true; }
    public static void main(String[] a) {
        float[] world = mul(rot('z', 40), rot('x', 70));                 // beliebige Ausgangslage
        double[] r0 = ShiftMath.relAnglesDeg(world, world);
        check(ShiftMath.maxAbs(r0) < 1e-4, "gleiche Lage -> 0 Grad");
        for (char ax : new char[] {'x', 'y', 'z'}) {
            // zweite Aufnahme: zusaetzlich um die GERAETEachse ax um 2 Grad gedreht (rechts multiplizieren = im Geraetesystem)
            float[] r2 = mul(world, rot(ax, 2.0));
            double[] d = ShiftMath.relAnglesDeg(world, r2); int i = ax - 'x';
            check(Math.abs(d[i] - 2.0) < 0.02 && Math.abs(d[(i + 1) % 3]) < 0.02 && Math.abs(d[(i + 2) % 3]) < 0.02, "Drehung um " + ax + ": " + java.util.Arrays.toString(d));
        }
        check(Math.abs(ShiftMath.baselineMm(300) - 100) < 1e-9, "Basis bei 3 m = 100 mm");
        check(Math.abs(ShiftMath.distanceCm(0) - 5) < 1e-9 && ShiftMath.distanceCm(100) > 1000, "Abstandsregler 5 cm .. " + ShiftMath.distanceCm(100) + " cm");
        System.out.println(bad ? "FEHLER" : "ALLE TESTS OK"); System.exit(bad ? 1 : 0);
    }
}
