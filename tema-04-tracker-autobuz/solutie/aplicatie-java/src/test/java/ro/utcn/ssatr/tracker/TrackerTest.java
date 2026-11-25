package ro.utcn.ssatr.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import ro.utcn.ssatr.tracker.CtpApiClient.Statie;
import ro.utcn.ssatr.tracker.CtpApiClient.TraseuSens;
import ro.utcn.ssatr.tracker.MasinaStari.Stare;
import ro.utcn.ssatr.tracker.Tracker.Rezultat;

/** Traseu drept de 5 km spre est; stația la 4000 m; 5 minute de mers pe jos. */
class TrackerTest {

    /** Ceas de test: timpul îl mutăm noi. */
    static final class CeasTest extends Clock {
        Instant acum = Instant.parse("2026-11-25T08:00:00Z");
        void trece(long secunde) { acum = acum.plusSeconds(secunde); }
        @Override public Instant instant() { return acum; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
    }

    private static final double LAT = 46.77, LON = 23.60;
    private static final double M_LON = 111320 * Math.cos(Math.toRadians(LAT));

    private final CeasTest ceas = new CeasTest();
    private final Tracker t;

    TrackerTest() {
        List<double[]> shape = List.of(new double[] {LAT, LON}, new double[] {LAT, LON + 5000 / M_LON});
        List<Statie> stops = List.of(new Statie(1, "Stația", LAT, LON + 4000 / M_LON));
        Traseu traseu = new Traseu(new TraseuSens("A", 0, "est", shape, stops));
        t = new Tracker(traseu, "A", 0, 5, Tracker.Setari.IMPLICITE, ceas);
    }

    /** Poziție cu timestamp = acum - vechime, la x metri de la capăt, la y metri lateral. */
    private void pozitie(long id, String trip, double x, double y, long vechime) {
        t.actualizeaza(new Vehicul(id, "B" + id, 7, trip, "7", LAT + y / 110540, LON + x / M_LON,
                ceas.acum.minusSeconds(vechime), (int) vechime));
    }

    private void pozitie(long id, String trip, double x) {
        pozitie(id, trip, x, 0, 0);
    }

    @Test
    void vitezaDinIstoricSiEta() {
        // 120 m la fiecare 20 s = 6 m/s; netezirea pornește de la 5 m/s și se apropie de 6
        for (int i = 0; i <= 15; i++) {
            pozitie(1, "A", 120 * i);
            if (i < 15) ceas.trece(20);
        }
        Rezultat r = t.evalueaza(true);

        assertEquals("B1", r.vehicul());
        assertEquals(2200, r.restM());
        assertEquals(2200 / 6.0, r.etaS(), 10);  // ~367 s
        assertEquals(Stare.PLEACA, r.stare());   // rezervă ~70 s <= 180
    }

    @Test
    void etaScadeCuVechimeaPozitiei() {
        pozitie(1, "A", 1000);
        ceas.trece(20);
        pozitie(1, "A", 1100);    // 5 m/s
        ceas.trece(10);           // poziția are acum 10 s

        assertEquals(2900 / 5.0 - 10, t.evalueaza(true).etaS(), 1);
    }

    @Test
    void faraIstoricVitezaImplicita() {
        pozitie(1, "A", 1500);

        assertEquals(500, t.evalueaza(true).etaS(), 1);  // 2500 m / 5 m/s
    }

    @Test
    void vehiculOpritPastreazaViteza() {
        pozitie(1, "A", 1000);
        ceas.trece(20);
        pozitie(1, "A", 1120);    // s-a mișcat: viteza măsurată
        long etaInainte = t.evalueaza(true).etaS();
        for (int i = 0; i < 3; i++) {  // un minut la semafor
            ceas.trece(20);
            pozitie(1, "A", 1122);
        }
        Rezultat r = t.evalueaza(true);

        assertEquals(etaInainte, r.etaS(), 2);  // viteza păstrată: nu scade spre 0, ETA nu explodează
    }

    @Test
    void duplicateleSiPozitiileMaiVechiSuntIgnorate() {
        pozitie(1, "A", 1000);
        Instant ts = ceas.acum;
        ceas.trece(20);
        t.actualizeaza(new Vehicul(1, "B1", 7, "A", "7", LAT, LON + 3000 / M_LON, ts, 0));  // același timestamp

        assertEquals(3000, t.evalueaza(true).restM());
    }

    @Test
    void sensulOpusSiPozitiaVecheNuSuntCandidati() {
        pozitie(1, "B", 2000);                 // alt trip_id
        pozitie(2, "A", 2000, 0, 95);          // age 95 s > 90
        pozitie(3, "A", 2000, 300, 0);         // 300 m lateral: în afara traseului

        Rezultat r = t.evalueaza(true);
        assertEquals(0, r.candidati());
        assertEquals(3, r.activi());
    }

    @Test
    void tripIdNullDevineCandidatDupaTreiPozitiiCrescatoare() {
        pozitie(1, null, 1000);
        assertEquals(0, t.evalueaza(true).candidati());
        ceas.trece(20);
        pozitie(1, null, 1100);
        assertEquals(0, t.evalueaza(true).candidati());
        ceas.trece(20);
        pozitie(1, null, 1200);
        assertEquals(1, t.evalueaza(true).candidati());
    }

    @Test
    void tripIdNullPeSensulOpusNuDevineCandidat() {
        for (int i = 0; i < 4; i++) {
            pozitie(1, null, 3000 - 150 * i);
            ceas.trece(20);
        }
        assertEquals(0, t.evalueaza(true).candidati());
    }

    @Test
    void dupaStatieUrmatorulAutobuz() {
        pozitie(1, "A", 4020);   // 20 m după stație: încă în toleranța de 30 m
        pozitie(2, "A", 1000);
        assertEquals("B1", t.evalueaza(true).vehicul());

        ceas.trece(20);
        pozitie(1, "A", 4200);   // a trecut
        assertEquals("B2", t.evalueaza(true).vehicul());
    }

    @Test
    void schimbareaAutobuzuluiCuHisterezis() {
        pozitie(1, "A", 2000);   // ETA 400 s (viteză implicită 5)
        assertEquals("B1", t.evalueaza(true).vehicul());

        pozitie(2, "A", 2100);   // ETA 380 s: doar 20 s mai bun, nu schimbăm
        assertEquals("B1", t.evalueaza(true).vehicul());

        pozitie(3, "A", 2300);   // ETA 340 s: 60 s mai bun, schimbăm
        assertEquals("B3", t.evalueaza(true).vehicul());
    }

    @Test
    void faraCandidatDouaMinuteFaraDate() {
        pozitie(1, "A", 3000);
        assertTrue(t.evalueaza(true).stare() != Stare.FARA_DATE);

        ceas.trece(119);
        assertEquals(1, t.evalueaza(true).candidati());
        ceas.trece(11);          // poziția are 130 s > 120: nu mai e candidat
        Rezultat r = t.evalueaza(true);
        assertEquals(0, r.candidati());
        assertTrue(r.stare() != Stare.FARA_DATE);  // doar 11 s fără candidat: păstrăm starea
        ceas.trece(61);
        assertEquals(Stare.FARA_DATE, t.evalueaza(true).stare());
    }

    @Test
    void vehiculInactivEsteScos() {
        pozitie(1, "A", 3000);
        ceas.trece(181);
        assertEquals(0, t.evalueaza(true).activi());
    }

    @Test
    void sursaDeconectataFaraDate() {
        pozitie(1, "A", 3000);
        Rezultat r = t.evalueaza(false);
        assertEquals(Stare.FARA_DATE, r.stare());
        assertNull(r.etaS());
    }

    @Test
    void confirmatOpresteUrmarirea() {
        pozitie(1, "A", 3950);   // 50 m <= 80 m
        assertEquals(Stare.IN_STATIE, t.evalueaza(true).stare());
        t.confirma();
        pozitie(2, "A", 1000);
        assertEquals(Stare.CONFIRMAT, t.evalueaza(true).stare());
    }
}
