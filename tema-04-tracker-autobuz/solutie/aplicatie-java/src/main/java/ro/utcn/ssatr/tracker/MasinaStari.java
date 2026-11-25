package ro.utcn.ssatr.tracker;

/**
 * Starea trimisă plăcii, cu histerezis: o tranziție spre o stare mai urgentă se face
 * la prag, una înapoi cere depășirea pragului cu o marjă, ca LED-ul să nu pâlpâie
 * când autobuzul stă lângă prag. Clasă pură: nu știe de timp, rețea sau MQTT.
 */
public final class MasinaStari {

    public enum Stare { FARA_DATE, DEPARTE, PLEACA, GRABESTE_TE, IN_STATIE, CONFIRMAT }

    /**
     * pleacaS: rezerva (ETA - mers pe jos) sub care e momentul să pleci;
     * inStatieM: distanța pe drum sub care autobuzul e în stație;
     * histerezisS / histerezisM: marja cerută la întoarcerea într-o stare mai puțin urgentă.
     */
    public record Praguri(double pleacaS, double inStatieM, double histerezisS, double histerezisM) {
        public static final Praguri IMPLICITE = new Praguri(180, 80, 30, 30);
    }

    private final Praguri p;
    private Stare stare = Stare.FARA_DATE;

    public MasinaStari(Praguri praguri) {
        this.p = praguri;
    }

    public Stare stare() {
        return stare;
    }

    /** Utilizatorul a urcat: CONFIRMAT rămâne definitiv (urmărirea se oprește). */
    public Stare confirma() {
        return stare = Stare.CONFIRMAT;
    }

    /** Fără candidat valid de prea mult timp sau fără conexiune la sursă. */
    public Stare faraDate() {
        if (stare != Stare.CONFIRMAT) stare = Stare.FARA_DATE;
        return stare;
    }

    /** Autobuzul urmărit e la restM metri pe drum și ajunge în etaS secunde; utilizatorul are nevoie de mersS. */
    public Stare actualizeaza(double etaS, double restM, double mersS) {
        if (stare == Stare.CONFIRMAT) return stare;

        double prag = stare == Stare.IN_STATIE ? p.inStatieM() + p.histerezisM() : p.inStatieM();
        if (restM <= prag) return stare = Stare.IN_STATIE;

        double rezerva = etaS - mersS;
        Stare laPrag = dupaRezerva(rezerva);
        if (!esteNivel(stare) || urgenta(laPrag) >= urgenta(stare)) {
            return stare = laPrag;  // spre mai urgent (sau din FARA_DATE / IN_STATIE): direct
        }
        // spre mai puțin urgent: doar dacă și cu marja scăzută s-ar ieși din starea curentă
        Stare cuMarja = dupaRezerva(rezerva - p.histerezisS());
        if (urgenta(cuMarja) < urgenta(stare)) stare = cuMarja;
        return stare;
    }

    private Stare dupaRezerva(double rezervaS) {
        if (rezervaS > p.pleacaS()) return Stare.DEPARTE;
        if (rezervaS > 0) return Stare.PLEACA;
        return Stare.GRABESTE_TE;
    }

    private static boolean esteNivel(Stare s) {
        return s == Stare.DEPARTE || s == Stare.PLEACA || s == Stare.GRABESTE_TE;
    }

    private static int urgenta(Stare s) {
        return s == Stare.DEPARTE ? 0 : s == Stare.PLEACA ? 1 : 2;
    }
}
