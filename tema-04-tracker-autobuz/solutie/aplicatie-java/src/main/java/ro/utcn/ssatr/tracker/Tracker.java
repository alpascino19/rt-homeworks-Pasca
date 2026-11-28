package ro.utcn.ssatr.tracker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ro.utcn.ssatr.tracker.MasinaStari.Stare;

/**
 * Starea vehiculelor liniei alese, alegerea autobuzului urmărit, ETA și starea LED.
 * Nu e thread-safe: e folosit de un singur thread (cel care golește coada din Main).
 * Timpul vine din Clock, ca replay-ul și testele să aibă propriul "acum".
 */
public final class Tracker {

    /** Setări de calcul; vezi config.example.properties. */
    public record Setari(MasinaStari.Praguri praguri, double vitezaImplicita) {
        public static final Setari IMPLICITE = new Setari(MasinaStari.Praguri.IMPLICITE, 5.0);
    }

    /** Ce se afișează și se trimite plăcii. eta/rest/vehicul sunt null fără autobuz urmărit. */
    public record Rezultat(Stare stare, Long etaS, Long restM, String vehicul, int activi, int candidati) { }

    static final long VARSTA_MAX_S = 90;            // poziție mai veche la citirea serverului: ignorată
    static final long POZITIE_VECHE_S = 120;        // ultima poziție a candidatului mai veche de atât: nu mai e candidat
    static final long INACTIV_S = 180;              // fără mesaje de atât: vehiculul e scos
    static final long FARA_CANDIDAT_S = 60;         // fără candidat atâta timp: FARA_DATE
    static final double TRECUT_M = -30;             // rest sub atât: a trecut de stație
    static final double SCHIMBARE_S = 30;           // alt autobuz devine urmărit doar dacă e mai bun cu atât
    static final double V_MIN = 1, V_MAX = 15;      // m/s, viteze plauzibile
    static final double TAU_EMA_S = 120;            // constanta de timp a netezirii (~ ultimele 2 minute)
    static final double MISCARE_MIN_M = 10;         // sub atât vehiculul e considerat pe loc
    static final double PAUZA_MAX_S = 180;          // interval prea lung între poziții: nu se calculează viteza

    private static final class StareVehicul {
        final long id;
        String label;
        String tripId;
        double lat, lon;           // ultima poziție GPS acceptată (pentru hartă)
        Instant timestamp;         // ultima poziție acceptată
        Instant vazutLa;           // ultimul mesaj primit (ceasul nostru)
        Double s;                  // poziția pe traseu; null dacă necunoscută sau în afara traseului
        Double sRef;               // ancora pentru viteză: ultima poziție în care s-a mișcat
        Instant tRef;
        Double viteza;             // EMA, m/s; null până la prima măsurare
        final Deque<Double> ultimeleS = new ArrayDeque<>();  // pentru sensul dedus când trip_id e null

        StareVehicul(long id) {
            this.id = id;
        }
    }

    private final Traseu traseu;
    private final String tripAles;
    private final double sStatie;
    private double mersS;                 // se poate actualiza din locația utilizatorului
    private final Setari setari;
    private final Clock ceas;
    private final MasinaStari masina;
    private final Map<Long, StareVehicul> vehicule = new HashMap<>();

    private Long urmarit;                 // id-ul autobuzului urmărit
    private Instant ultimulCandidat;      // ultima dată când a existat un candidat
    private Rezultat ultimul;
    private Double etaPrins;              // ETA-ul primului autobuz care poate fi prins (la ultima evaluare)

    public Tracker(Traseu traseu, String tripAles, int indexStatie, int mersMin, Setari setari, Clock ceas) {
        this.traseu = traseu;
        this.tripAles = tripAles;
        this.sStatie = traseu.sStatie(indexStatie);
        this.mersS = mersMin * 60.0;
        this.setari = setari;
        this.ceas = ceas;
        this.masina = new MasinaStari(setari.praguri());
        this.ultimulCandidat = ceas.instant();  // dăm sursei timp să adune flota
        this.ultimul = new Rezultat(Stare.FARA_DATE, null, null, null, 0, 0);
    }

    /** O poziție nouă a unui vehicul de pe linia aleasă. */
    public void actualizeaza(Vehicul v) {
        StareVehicul st = vehicule.computeIfAbsent(v.id(), StareVehicul::new);
        st.vazutLa = ceas.instant();
        st.label = v.label();
        if (v.age() != null && v.age() > VARSTA_MAX_S) return;                     // poziție veche
        if (st.timestamp != null && !v.timestamp().isAfter(st.timestamp)) return;  // duplicat sau mai veche
        st.timestamp = v.timestamp();
        st.tripId = v.tripId();
        st.lat = v.lat();
        st.lon = v.lon();

        Traseu.Proiectie p = st.s == null ? traseu.proiecteaza(v.lat(), v.lon())
                                          : traseu.proiecteaza(v.lat(), v.lon(), st.s);
        if (p.lateral() > Traseu.LATERAL_MAX_M) {  // depou, GPS eronat: uităm poziția pe traseu
            st.s = null;
            st.sRef = null;
            st.ultimeleS.clear();
            return;
        }
        st.s = p.s();
        st.ultimeleS.addLast(p.s());
        while (st.ultimeleS.size() > 3) st.ultimeleS.removeFirst();
        actualizeazaViteza(st);
    }

    /**
     * Viteza pe traseu din progresul lui s, pe timestamp-urile vehiculului. Ancora se mută doar
     * când vehiculul s-a mișcat, deci opririle (semafor, stație) intră în media următoare în loc
     * să pună viteza la 0.
     */
    private void actualizeazaViteza(StareVehicul st) {
        if (st.sRef == null) {
            st.sRef = st.s;
            st.tRef = st.timestamp;
            return;
        }
        double dt = Duration.between(st.tRef, st.timestamp).toMillis() / 1000.0;
        double ds = st.s - st.sRef;
        if (dt > PAUZA_MAX_S || ds < -Traseu.FEREASTRA_INAPOI_M) {  // pauză lungă sau salt înapoi: reluăm
            st.sRef = st.s;
            st.tRef = st.timestamp;
            return;
        }
        if (ds < MISCARE_MIN_M || dt <= 0) return;  // pe loc: păstrăm EMA și ancora

        double vMasurat = clamp(ds / dt);
        double alfa = 1 - Math.exp(-dt / TAU_EMA_S);
        // prima măsurare pornește de la viteza implicită, nu o înlocuiește (o singură măsurare e zgomotoasă)
        double anterior = st.viteza != null ? st.viteza : setari.vitezaImplicita();
        st.viteza = clamp(anterior + alfa * (vMasurat - anterior));
        st.sRef = st.s;
        st.tRef = st.timestamp;
    }

    /** Timpul de mers pe jos până la stație s-a schimbat (utilizatorul s-a mișcat). */
    public void seteazaMers(double secunde) {
        mersS = secunde;
    }

    public double mersS() {
        return mersS;
    }

    /**
     * ETA-ul (s) primului autobuz care ajunge în stație după utilizator (ETA >= timpul de mers),
     * la ultima evaluare; null dacă nu e niciunul. Pentru compararea variantelor de drum.
     */
    public Double etaPrimulPrins() {
        return etaPrins;
    }

    /** Un vehicul al liniei, pentru hartă. candidat = vine spre stație pe sensul ales; urmarit = cel de pe LED. */
    public record VehiculHarta(String label, double lat, double lon, boolean candidat, boolean urmarit, long vechimeS) { }

    /** Vehiculele liniei cu o poziție acceptată, la momentul curent. */
    public List<VehiculHarta> vehiculeHarta() {
        Instant acum = ceas.instant();
        List<VehiculHarta> l = new ArrayList<>();
        for (StareVehicul st : vehicule.values()) {
            if (st.timestamp == null) continue;
            l.add(new VehiculHarta(st.label, st.lat, st.lon, esteCandidat(st, acum),
                    urmarit != null && st.id == urmarit, Duration.between(st.timestamp, acum).toSeconds()));
        }
        return l;
    }

    /** Utilizatorul a confirmat că a urcat. */
    public Rezultat confirma() {
        masina.confirma();
        return ultimul = new Rezultat(Stare.CONFIRMAT, ultimul.etaS(), ultimul.restM(), ultimul.vehicul(),
                ultimul.activi(), ultimul.candidati());
    }

    /** Recalculează totul la momentul curent. sursaConectata = RabbitMQ conectat / replay activ. */
    public Rezultat evalueaza(boolean sursaConectata) {
        Instant acum = ceas.instant();
        vehicule.values().removeIf(st -> Duration.between(st.vazutLa, acum).toSeconds() > INACTIV_S);
        int activi = vehicule.size();

        if (masina.stare() == Stare.CONFIRMAT) {
            return ultimul = new Rezultat(Stare.CONFIRMAT, null, null, ultimul.vehicul(), activi, 0);
        }

        StareVehicul cel = null, actual = null;
        double etaCel = Double.MAX_VALUE, etaActual = 0;
        int candidati = 0;
        etaPrins = null;
        for (StareVehicul st : vehicule.values()) {
            if (!esteCandidat(st, acum)) continue;
            candidati++;
            double eta = eta(st, acum);
            if (eta >= mersS && (etaPrins == null || eta < etaPrins)) etaPrins = eta;
            if (eta < etaCel) {
                etaCel = eta;
                cel = st;
            }
            if (urmarit != null && st.id == urmarit) {
                actual = st;
                etaActual = eta;
            }
        }
        // autobuzul urmărit se schimbă doar dacă altul e clar mai bun (nu oscilează între două)
        StareVehicul ales = actual != null && etaActual - etaCel <= SCHIMBARE_S ? actual : cel;
        double etaAles = ales == actual ? etaActual : etaCel;

        if (!sursaConectata) {
            return ultimul = new Rezultat(masina.faraDate(), null, null, null, activi, candidati);
        }
        if (ales == null) {
            urmarit = null;
            if (Duration.between(ultimulCandidat, acum).toSeconds() > FARA_CANDIDAT_S) {
                return ultimul = new Rezultat(masina.faraDate(), null, null, null, activi, 0);
            }
            // pauză scurtă (ex. vehiculul tocmai a trecut de stație): păstrăm starea
            return ultimul = new Rezultat(masina.stare(), ultimul.etaS(), ultimul.restM(), ultimul.vehicul(), activi, 0);
        }

        urmarit = ales.id;
        ultimulCandidat = acum;
        double rest = sStatie - ales.s;
        Stare s = masina.actualizeaza(etaAles, rest, mersS);
        return ultimul = new Rezultat(s, Math.round(etaAles), Math.round(rest), ales.label, activi, candidati);
    }

    private boolean esteCandidat(StareVehicul st, Instant acum) {
        if (st.s == null || st.timestamp == null) return false;
        if (Duration.between(st.timestamp, acum).toSeconds() > POZITIE_VECHE_S) return false;
        if (sStatie - st.s < TRECUT_M) return false;
        return st.tripId != null ? st.tripId.equals(tripAles) : mergeInainte(st);
    }

    /** trip_id lipsă: sensul se deduce din ultimele 3 poziții (s crește pe traseul ales). */
    private static boolean mergeInainte(StareVehicul st) {
        if (st.ultimeleS.size() < 3) return false;
        Double anterior = null;
        for (double s : st.ultimeleS) {
            if (anterior != null && s < anterior - 5) return false;
            anterior = s;
        }
        return st.ultimeleS.peekLast() - st.ultimeleS.peekFirst() >= 2 * MISCARE_MIN_M;
    }

    /** Secunde până în stație, din viteza observată, minus cât e de veche poziția. */
    private double eta(StareVehicul st, Instant acum) {
        double v = st.viteza != null ? st.viteza : setari.vitezaImplicita();
        double vechime = Duration.between(st.timestamp, acum).toMillis() / 1000.0;
        return Math.max(0, Math.max(0, sStatie - st.s) / v - vechime);
    }

    private static double clamp(double v) {
        return Math.max(V_MIN, Math.min(V_MAX, v));
    }
}
