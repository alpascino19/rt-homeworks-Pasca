package ro.utcn.ssatr.tracker;

import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;

import ro.utcn.ssatr.tracker.CtpApiClient.Statie;
import ro.utcn.ssatr.tracker.CtpApiClient.TraseuSens;

/**
 * Geometria unui sens: distanța măsurată pe drum (curbele contează).
 *
 * Punctele [lat, lon] se proiectează într-un plan local în metri (echirectangular,
 * centrat pe traseu; eroarea e neglijabilă pe câțiva km), apoi JTS dă poziția
 * oricărui punct ca distanță de la începutul traseului ("s", în metri).
 */
public final class Traseu {

    /** Un vehicul mai departe de atât de traseu nu e pe traseu (depou, GPS eronat). */
    public static final double LATERAL_MAX_M = 100;

    /** Fereastra de căutare în jurul poziției anterioare: puțin înapoi, mult înainte. */
    static final double FEREASTRA_INAPOI_M = 200;
    static final double FEREASTRA_INAINTE_M = 2000;

    /** s = distanța pe traseu de la început; lateral = distanța punctului până la traseu. */
    public record Proiectie(double s, double lateral) { }

    private static final double M_PER_GRAD_LAT = 110540;
    private static final double M_PER_GRAD_LON_ECUATOR = 111320;

    private final double lat0;
    private final double lon0;
    private final double mPerGradLon;
    private final LengthIndexedLine index;
    private final double lungime;
    private final double[] sStatii;

    public Traseu(TraseuSens sens) {
        List<double[]> shape = sens.shape();
        if (shape.size() < 2) throw new IllegalArgumentException("traseul are mai puțin de 2 puncte");

        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (double[] p : shape) {
            minLat = Math.min(minLat, p[0]);
            maxLat = Math.max(maxLat, p[0]);
            minLon = Math.min(minLon, p[1]);
            maxLon = Math.max(maxLon, p[1]);
        }
        lat0 = (minLat + maxLat) / 2;
        lon0 = (minLon + maxLon) / 2;
        mPerGradLon = M_PER_GRAD_LON_ECUATOR * Math.cos(Math.toRadians(lat0));

        Coordinate[] pts = shape.stream().map(p -> local(p[0], p[1])).toArray(Coordinate[]::new);
        LineString linie = new GeometryFactory().createLineString(pts);
        index = new LengthIndexedLine(linie);
        lungime = linie.getLength();

        // Stațiile sunt în ordinea parcurgerii: fiecare se caută după cea dinainte, ca o
        // stație de pe brațul de întoarcere să nu fie proiectată pe brațul de dus.
        List<Statie> stops = sens.stops();
        sStatii = new double[stops.size()];
        double dupa = 0;
        for (int i = 0; i < stops.size(); i++) {
            sStatii[i] = index.indexOfAfter(local(stops.get(i).lat(), stops.get(i).lon()), dupa);
            dupa = sStatii[i];
        }
    }

    /** Lungimea traseului, în metri. */
    public double lungime() {
        return lungime;
    }

    /** s-ul stației cu indexul dat din lista stops a sensului. */
    public double sStatie(int indexStatie) {
        return sStatii[indexStatie];
    }

    /** Proiecție globală: pentru un vehicul nou, despre care nu știm nimic. */
    public Proiectie proiecteaza(double lat, double lon) {
        Coordinate p = local(lat, lon);
        double s = index.project(p);
        return new Proiectie(s, p.distance(index.extractPoint(s)));
    }

    /**
     * Proiecție cu continuitate: caută doar în [sAnterior - 200 m, sAnterior + 2000 m],
     * ca vehiculul să nu sară pe alt braț în bucle sau pe tronsoane dus-întors.
     * În fereastră se preferă poziția din fața celei anterioare (autobuzul nu merge
     * înapoi; la capăt de linie, același punct e și înainte, și după întoarcere).
     * Înapoi se acceptă doar dacă e clar mai aproape (eroare GPS mare).
     * Dacă în fereastră nu e niciun punct apropiat (vehiculul a lipsit mult, a fost
     * mutat), revine la proiecția globală.
     */
    public Proiectie proiecteaza(double lat, double lon, double sAnterior) {
        Coordinate p = local(lat, lon);
        double ant = Math.max(0, Math.min(lungime, sAnterior));
        Proiectie inainte = proiectiePe(p, ant, Math.min(lungime, ant + FEREASTRA_INAINTE_M));
        Proiectie inapoi = proiectiePe(p, Math.max(0, ant - FEREASTRA_INAPOI_M), ant);
        Proiectie aleasa = inainte.lateral() <= inapoi.lateral() + PREFERINTA_INAINTE_M ? inainte : inapoi;
        return aleasa.lateral() <= LATERAL_MAX_M ? aleasa : proiecteaza(lat, lon);
    }

    /** Cu cât poate fi mai departe de traseu poziția din față ca să fie totuși preferată. */
    static final double PREFERINTA_INAINTE_M = 20;

    /** Proiecția lui p doar pe porțiunea [a, b] a traseului. */
    private Proiectie proiectiePe(Coordinate p, double a, double b) {
        double s = b > a ? a + new LengthIndexedLine(index.extractLine(a, b)).project(p) : a;
        return new Proiectie(s, p.distance(index.extractPoint(s)));
    }

    private Coordinate local(double lat, double lon) {
        return new Coordinate((lon - lon0) * mPerGradLon, (lat - lat0) * M_PER_GRAD_LAT);
    }
}
