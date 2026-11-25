package ro.utcn.ssatr.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ro.utcn.ssatr.tracker.CtpApiClient.DetaliiLinie;
import ro.utcn.ssatr.tracker.CtpApiClient.Linie;
import ro.utcn.ssatr.tracker.CtpApiClient.Statie;
import ro.utcn.ssatr.tracker.CtpApiClient.TraseuSens;
import ro.utcn.ssatr.tracker.Planificator.Optiune;

/** Linii de test desenate în metri (x spre est, y spre nord) lângă Cluj. */
class PlanificatorTest {

    private static final double LAT = 46.77, LON = 23.60;
    private static final double M_LON = 111320 * Math.cos(Math.toRadians(LAT));

    private static double lat(double y) { return LAT + y / 110540; }
    private static double lon(double x) { return LON + x / M_LON; }

    /** Un sens drept de la (x0,y) la (x1,y), cu stații la x-urile date. */
    private static TraseuSens sens(String trip, double y, double x0, double x1, double... statiiX) {
        List<Statie> stops = new ArrayList<>();
        for (int i = 0; i < statiiX.length; i++) stops.add(new Statie(i, trip + "-S" + i, lat(y), lon(statiiX[i])));
        return new TraseuSens(trip, 0, trip, List.of(new double[] {lat(y), lon(x0)}, new double[] {lat(y), lon(x1)}), stops);
    }

    private static Linie linie(int id) {
        return new Linie(id, String.valueOf(id), "linia " + id, "autobuz", List.of());
    }

    @Test
    void distantaSiMersPeJos() {
        // 1 %: helper-ul folosește 110540 m/grad (din spec, valoarea de la ecuator); haversine dă ~111200 la Cluj
        assertEquals(1000, Planificator.distantaM(lat(0), lon(0), lat(0), lon(1000)), 10);
        assertEquals(1000, Planificator.distantaM(lat(0), lon(0), lat(1000), lon(0)), 10);
        assertEquals(1000 * 1.3 / 1.4, Planificator.mersS(1000), 1e-9);  // ~15,5 min
    }

    @Test
    void gasesteVariantaDirectaSpreDestinatie() {
        // linia 1 spre est: stații la 0, 1000, 3000, 5000; destinația lângă 5000
        TraseuSens est = sens("1_0", 0, 0, 5000, 0, 1000, 3000, 5000);
        TraseuSens vest = sens("1_1", 20, 5000, 0, 5000, 3000, 1000, 0);   // sensul opus
        Map<Integer, DetaliiLinie> detalii = Map.of(1, new DetaliiLinie(1, "1", List.of(est, vest)));
        List<Statie> dest = List.of(new Statie(99, "Dest", lat(100), lon(5050)));

        // utilizatorul lângă stația de la 1000 m
        List<Optiune> o = Planificator.optiuni(List.of(linie(1)), detalii, lat(50), lon(1050), dest, 5);

        assertEquals(1, o.size());                       // doar sensul spre est duce acolo
        assertEquals("1_0", o.get(0).sens().tripId());
        assertEquals(1, o.get(0).iUrcare());             // cea mai apropiată stație, nu capătul
        assertEquals(3, o.get(0).iCoborare());
        assertEquals(4000, o.get(0).drumM(), 2);
    }

    @Test
    void ignoraLiniileDepartesauFaraDestinatie() {
        TraseuSens departe = sens("2_0", 3000, 0, 5000, 0, 5000);           // trece la 3 km de utilizator
        TraseuSens altundeva = sens("3_0", 0, 0, 2000, 0, 1000, 2000);       // nu ajunge la destinație
        Map<Integer, DetaliiLinie> detalii = Map.of(
                2, new DetaliiLinie(2, "2", List.of(departe)),
                3, new DetaliiLinie(3, "3", List.of(altundeva)));
        List<Statie> dest = List.of(new Statie(99, "Dest", lat(3000), lon(5000)));

        assertTrue(Planificator.optiuni(List.of(linie(2), linie(3)), detalii, lat(0), lon(0), dest, 5).isEmpty());
    }

    @Test
    void variantelesuntOrdonateDupaDurata() {
        TraseuSens lunga = sens("4_0", 0, 0, 9000, 0, 9000);                 // ocol: 9 km cu autobuzul
        TraseuSens scurta = sens("5_0", 10, 0, 5000, 0, 5000);               // 5 km
        Map<Integer, DetaliiLinie> detalii = Map.of(
                4, new DetaliiLinie(4, "4", List.of(lunga)),
                5, new DetaliiLinie(5, "5", List.of(scurta)));
        List<Statie> dest = List.of(new Statie(99, "Dest", lat(0), lon(5000)), new Statie(98, "Dest", lat(0), lon(9000)));

        List<Optiune> o = Planificator.optiuni(List.of(linie(4), linie(5)), detalii, lat(0), lon(0), dest, 5);

        assertEquals(2, o.size());
        assertEquals(5, o.get(0).linie().routeId());
    }
}
