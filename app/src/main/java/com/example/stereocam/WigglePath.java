package com.example.stereocam;

/**
 * Bahn der virtuellen Kamera. t = 0 ist das linke, t = 1 das rechte Auge; ausserhalb von [0, 1] wird extrapoliert
 * (die Parallaxe wird dann ueber die echte Basis hinaus vergroessert). Die Sinusform bremst an den Enden sanft ab
 * und faehrt zurueck; die Kreisbahn fuegt eine senkrechte Bewegung hinzu (Umrundung des Motivs).
 */
final class WigglePath {
    static final int LINE = 0, CIRCLE = 1;
    private WigglePath() {}

    /** @param sec Zeit, @param period Dauer eines Hin- und Rueckwegs, @param amp Amplitude in Vielfachen der echten Basis (1 = links..rechts),
     *  @param shape LINE oder CIRCLE. @return {t, v}: t waagerecht (in Basen), v senkrecht (in Basen). */
    static double[] at(double sec, double period, double amp, int shape) {
        double th = 2 * Math.PI * sec / period;
        double t = 0.5 + 0.5 * amp * Math.sin(th);
        double v = shape == CIRCLE ? 0.5 * amp * Math.cos(th) : 0;
        return new double[] {t, v};
    }
}
