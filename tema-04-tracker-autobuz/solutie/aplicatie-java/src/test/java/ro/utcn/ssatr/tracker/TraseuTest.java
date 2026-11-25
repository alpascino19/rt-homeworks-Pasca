package ro.utcn.ssatr.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ro.utcn.ssatr.tracker.CtpApiClient.Statie;
import ro.utcn.ssatr.tracker.CtpApiClient.TraseuSens;
import ro.utcn.ssatr.tracker.Traseu.Proiectie;

/** Traseele de test sunt desenate în metri (x spre est, y spre nord) lângă Cluj. */
class TraseuTest {

    private static final double LAT = 46.77;
    private static final double LON = 23.60;
    private static final double TOL = 1.0;  // metri

    private static double[] ll(double x, double y) {
        return new double[] {LAT + y / 110540, LON + x / (111320 * Math.cos(Math.toRadians(LAT)))};
    }

    private static Traseu traseu(double[][] puncteXY, double[]... statiiXY) {
        List<double[]> shape = new ArrayList<>();
        for (double[] p : puncteXY) shape.add(ll(p[0], p[1]));
        List<Statie> stops = new ArrayList<>();
        for (int i = 0; i < statiiXY.length; i++) {
            double[] q = ll(statiiXY[i][0], statiiXY[i][1]);
            stops.add(new Statie(i, "S" + i, q[0], q[1]));
        }
        return new Traseu(new TraseuSens("t", 0, "test", shape, stops));
    }

    private static Proiectie proiecteaza(Traseu t, double x, double y) {
        double[] q = ll(x, y);
        return t.proiecteaza(q[0], q[1]);
    }

    private static Proiectie proiecteaza(Traseu t, double x, double y, double sAnterior) {
        double[] q = ll(x, y);
        return t.proiecteaza(q[0], q[1], sAnterior);
    }

    /** L: 1000 m spre est, apoi 1000 m spre nord. Distanța pe drum, nu în linie dreaptă. */
    @Test
    void curbaInLDistantaPeDrum() {
        Traseu t = traseu(new double[][] {{0, 0}, {1000, 0}, {1000, 1000}}, new double[] {1000, 500});

        assertEquals(2000, t.lungime(), TOL);
        assertEquals(1500, t.sStatie(0), TOL);

        Proiectie v = proiecteaza(t, 500, 10);
        assertEquals(500, v.s(), TOL);
        assertEquals(10, v.lateral(), TOL);
        // în linie dreaptă ar fi ~707 m; pe drum sunt 1000 m
        assertEquals(1000, t.sStatie(0) - v.s(), TOL);

        assertEquals(1800, proiecteaza(t, 1010, 800).s(), TOL);
    }

    @Test
    void vehiculDepartedeTraseuAreDistantaLateralaMare() {
        Traseu t = traseu(new double[][] {{0, 0}, {1000, 0}, {1000, 1000}});

        assertTrue(proiecteaza(t, 500, 300).lateral() > Traseu.LATERAL_MAX_M);
    }

    /** Dus-întors pe aceeași stradă: 1000 m spre est, apoi înapoi pe același drum. */
    private static final double[][] DUS_INTORS = {{0, 0}, {1000, 0}, {0, 0}};

    @Test
    void buclaDusIntorsContinuitate() {
        Traseu t = traseu(DUS_INTORS);

        // vehicul nou: proiecția globală alege primul braț (nu avem de unde ști altceva)
        assertEquals(300, proiecteaza(t, 300, 0).s(), TOL);

        // vehicul cunoscut, pe brațul de întoarcere (x = 400 => s = 1600): rămâne pe el
        assertEquals(1700, proiecteaza(t, 300, 0, 1600).s(), TOL);
        // și la capăt de linie trece corect de pe dus pe întors
        assertEquals(1100, proiecteaza(t, 900, 0, 950).s(), TOL);
    }

    @Test
    void statiileSeProiecteazaInOrdineaParcurgerii() {
        // aceeași stradă la dus și la întors: a treia stație e pe brațul de întoarcere
        Traseu t = traseu(DUS_INTORS, new double[] {500, 0}, new double[] {1000, 0}, new double[] {500, 0});

        assertEquals(500, t.sStatie(0), TOL);
        assertEquals(1000, t.sStatie(1), TOL);
        assertEquals(1500, t.sStatie(2), TOL);
    }

    @Test
    void zgomotGpsInapoi() {
        Traseu t = traseu(new double[][] {{0, 0}, {5000, 0}});

        // 10 m în spate (vehicul oprit, GPS care tremură): rămâne pe loc, nu dă înapoi
        assertEquals(500, proiecteaza(t, 490, 0, 500).s(), TOL);
        // 100 m în spate: e clar în spate, se acceptă
        assertEquals(400, proiecteaza(t, 400, 0, 500).s(), TOL);
    }

    @Test
    void inAfaraFerestreiRevineLaProiectiaGlobala() {
        Traseu t = traseu(new double[][] {{0, 0}, {5000, 0}});

        // ultima poziție știută la început, acum vehiculul e la 4 km (mesaje pierdute)
        assertEquals(4000, proiecteaza(t, 4000, 0, 0).s(), TOL);
    }
}
