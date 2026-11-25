package ro.utcn.ssatr.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class VehiculTest {

    @Test
    void mesajComplet() {
        Vehicul v = Vehicul.parse("""
                {"id": 8, "label": "848", "route_id": 84, "trip_id": "84_0",
                 "timestamp": "2026-11-10T07:40:59.000Z", "lat": 46.7786816, "lon": 23.6162583,
                 "speed": 0, "age": 29, "line": "M45", "route_name": "Str. A. Vlaicu - Pata",
                 "color": "#f3513c", "headsign": "M-Primarie Pata Sos", "type": "autobuz",
                 "fetched_at": "2026-11-10T07:41:28.353624+00:00"}""");

        assertNotNull(v);
        assertEquals(8, v.id());
        assertEquals("848", v.label());
        assertEquals(84, v.routeId());
        assertEquals("84_0", v.tripId());
        assertEquals(Instant.parse("2026-11-10T07:40:59Z"), v.timestamp());
        assertEquals(29, v.age());
    }

    @Test
    void tripIdNullSiRouteIdNecunoscut() {
        Vehicul v = Vehicul.parse("""
                {"id": 9, "label": "901", "route_id": 999, "trip_id": null, "line": "?",
                 "timestamp": "2026-11-10T07:40:59.000Z", "lat": 46.77, "lon": 23.61, "headsign": ""}""");

        assertNotNull(v);
        assertNull(v.tripId());
        assertEquals(999, v.routeId());
        assertNull(v.age());
    }

    @Test
    void routeIdLipsa() {
        Vehicul v = Vehicul.parse("""
                {"id": 9, "timestamp": "2026-11-10T07:40:59.000Z", "lat": 46.77, "lon": 23.61}""");

        assertNotNull(v);
        assertNull(v.routeId());
        assertEquals("9", v.label());
    }

    @Test
    void mesajeInutilizabileSuntRespinse() {
        assertNull(Vehicul.parse("nu e json"));
        assertNull(Vehicul.parse("{\"id\": 1, \"lat\": 46.7, \"lon\": 23.6}"));                                   // fără timestamp
        assertNull(Vehicul.parse("{\"id\": 1, \"lat\": null, \"lon\": 23.6, \"timestamp\": \"2026-11-10T07:40:59Z\"}"));
        assertNull(Vehicul.parse("{\"id\": 1, \"lat\": 46.7, \"lon\": 23.6, \"timestamp\": \"ieri\"}"));
    }
}
