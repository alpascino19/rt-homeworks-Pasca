package ro.utcn.ssatr.tracker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import ro.utcn.ssatr.tracker.CtpApiClient.DetaliiLinie;
import ro.utcn.ssatr.tracker.CtpApiClient.Linie;
import ro.utcn.ssatr.tracker.CtpApiClient.Statie;
import ro.utcn.ssatr.tracker.CtpApiClient.TraseuSens;

/**
 * Variantele directe (fără schimbare) de la locația utilizatorului la o destinație:
 * o linie și un sens care au o stație de urcare lângă utilizator și, mai încolo pe
 * același sens, o stație de coborâre lângă destinație. Clasă pură, fără rețea.
 */
public final class Planificator {

    /** O variantă: urci la stops[iUrcare], cobori la stops[iCoborare]. Timpii sunt în secunde. */
    public record Optiune(Linie linie, TraseuSens sens, int iUrcare, int iCoborare,
                          double mersPanaS, double mersDupaS, double drumM) {
        public Statie urcare()  { return sens.stops().get(iUrcare); }
        public Statie coborare() { return sens.stops().get(iCoborare); }
        /** Timpul fără așteptare: mers + drum cu viteza implicită + mers. */
        double durataS(double vitezaAutobuz) { return mersPanaS + drumM / vitezaAutobuz + mersDupaS; }
    }

    static final double URCARE_MAX_M = 1000;      // cât de departe (în linie dreaptă) poate fi stația de urcare
    static final double COBORARE_MAX_M = 700;     // cât de departe de destinație poate fi stația de coborâre
    static final double OCOL = 1.3;               // străzile nu merg drept: drumul real ~ 1,3 x linia dreaptă
    static final double PIETON_MPS = 1.4;         // ~5 km/h
    static final int MAX_OPTIUNI = 8;

    private Planificator() { }

    /** Distanța pe suprafața Pământului (haversine), în metri. */
    public static double distantaM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    /** Timpul de mers pe jos pentru o distanță în linie dreaptă. shortcut: fără rutare pe străzi, factor de ocol fix. */
    public static double mersS(double distantaDreaptaM) {
        return distantaDreaptaM * OCOL / PIETON_MPS;
    }

    /**
     * Cele mai bune variante (cel mult MAX_OPTIUNI), câte una pe linie și sens, ordonate după
     * durata fără așteptare. destinatie = stațiile cu numele ales (pot fi mai multe, pe sensuri diferite).
     */
    public static List<Optiune> optiuni(List<Linie> linii, Map<Integer, DetaliiLinie> detalii,
                                        double lat, double lon, List<Statie> destinatie, double vitezaAutobuz) {
        List<Optiune> rezultat = new ArrayList<>();
        for (Linie linie : linii) {
            DetaliiLinie d = detalii.get(linie.routeId());
            if (d == null) continue;
            for (TraseuSens sens : d.directions()) {
                if (sens.shape().size() < 2 || sens.stops().size() < 2) continue;
                Optiune o = ceaMaiBuna(linie, sens, lat, lon, destinatie, vitezaAutobuz);
                if (o != null) rezultat.add(o);
            }
        }
        rezultat.sort(Comparator.comparingDouble(o -> o.durataS(vitezaAutobuz)));
        return rezultat.size() > MAX_OPTIUNI ? rezultat.subList(0, MAX_OPTIUNI) : rezultat;
    }

    private static Optiune ceaMaiBuna(Linie linie, TraseuSens sens, double lat, double lon,
                                      List<Statie> destinatie, double vitezaAutobuz) {
        List<Statie> stops = sens.stops();
        int n = stops.size();
        double[] laDest = new double[n];
        boolean vreoCoborare = false;
        for (int j = 0; j < n; j++) {
            Statie s = stops.get(j);
            laDest[j] = destinatie.stream().mapToDouble(x -> distantaM(s.lat(), s.lon(), x.lat(), x.lon())).min().orElse(Double.MAX_VALUE);
            vreoCoborare |= laDest[j] <= COBORARE_MAX_M;
        }
        if (!vreoCoborare) return null;

        Traseu traseu = null;  // construit doar pentru sensurile care chiar pot fi variante
        Optiune cea = null;
        for (int i = 0; i < n - 1; i++) {
            Statie u = stops.get(i);
            double laUrcare = distantaM(lat, lon, u.lat(), u.lon());
            if (laUrcare > URCARE_MAX_M) continue;
            for (int j = i + 1; j < n; j++) {
                if (laDest[j] > COBORARE_MAX_M) continue;
                if (traseu == null) traseu = new Traseu(sens);
                double drum = traseu.sStatie(j) - traseu.sStatie(i);
                if (drum <= 0) continue;
                Optiune o = new Optiune(linie, sens, i, j, mersS(laUrcare), mersS(laDest[j]), drum);
                if (cea == null || o.durataS(vitezaAutobuz) < cea.durataS(vitezaAutobuz)) cea = o;
            }
        }
        return cea;
    }
}
