package ro.utcn.ssatr.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import ro.utcn.ssatr.tracker.MasinaStari.Stare;

/** Praguri implicite: PLEACA la rezervă <= 180 s, IN_STATIE la <= 80 m, marje 30 s / 30 m. */
class MasinaStariTest {

    private static final double MERS = 300;   // 5 minute de mers pe jos
    private static final double DEPARTE_M = 5000;

    private final MasinaStari m = new MasinaStari(MasinaStari.Praguri.IMPLICITE);

    /** ETA ales ca rezerva (ETA - mers) să fie cea dată. */
    private Stare rezerva(double rezervaS) {
        return m.actualizeaza(rezervaS + MERS, DEPARTE_M, MERS);
    }

    @Test
    void pornesteFaraDate() {
        assertEquals(Stare.FARA_DATE, m.stare());
    }

    @Test
    void tranzitiiSpreMaiUrgent() {
        assertEquals(Stare.DEPARTE, rezerva(600));
        assertEquals(Stare.PLEACA, rezerva(180));
        assertEquals(Stare.GRABESTE_TE, rezerva(0));
        assertEquals(Stare.IN_STATIE, m.actualizeaza(10, 80, MERS));
    }

    @Test
    void histerezisPleacaDeparte() {
        assertEquals(Stare.PLEACA, rezerva(170));
        assertEquals(Stare.PLEACA, rezerva(200));   // peste prag, dar în marjă: nu pâlpâie
        assertEquals(Stare.PLEACA, rezerva(210));
        assertEquals(Stare.DEPARTE, rezerva(211));  // peste prag + marjă
        assertEquals(Stare.DEPARTE, rezerva(185));  // DEPARTE rămâne până la prag
        assertEquals(Stare.PLEACA, rezerva(180));
    }

    @Test
    void histerezisGrabesteTePleaca() {
        assertEquals(Stare.GRABESTE_TE, rezerva(-10));
        assertEquals(Stare.GRABESTE_TE, rezerva(20));
        assertEquals(Stare.PLEACA, rezerva(31));
    }

    @Test
    void histerezisInStatie() {
        assertEquals(Stare.IN_STATIE, m.actualizeaza(0, 70, MERS));
        assertEquals(Stare.IN_STATIE, m.actualizeaza(0, 110, MERS));     // 80 + 30
        assertEquals(Stare.GRABESTE_TE, m.actualizeaza(0, 111, MERS));
    }

    @Test
    void dinFaraDateDirectInStareaCorecta() {
        rezerva(-100);
        m.faraDate();
        assertEquals(Stare.DEPARTE, rezerva(1000));
    }

    @Test
    void confirmatRamane() {
        rezerva(100);
        m.confirma();
        assertEquals(Stare.CONFIRMAT, rezerva(-50));
        assertEquals(Stare.CONFIRMAT, m.faraDate());
    }
}
