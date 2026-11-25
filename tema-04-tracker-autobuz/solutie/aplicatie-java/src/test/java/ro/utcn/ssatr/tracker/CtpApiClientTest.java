package ro.utcn.ssatr.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ro.utcn.ssatr.tracker.CtpApiClient.DetaliiLinie;
import ro.utcn.ssatr.tracker.CtpApiClient.Linie;

class CtpApiClientTest {

    @Test
    void parseazaLiniileCuCampuriNecunoscuteSiLipsa() throws Exception {
        String json = """
                {"agency_id": 2, "lines": [
                  {"route_id": 1, "line": "1", "name": "Str. Bucium - P-ta 1 Mai", "type": "troleibuz",
                   "camp_nou": 42,
                   "directions": [{"trip_id": "1_0", "direction_id": 0, "headsign": "P-ța 1 Mai"},
                                  {"trip_id": "1_1", "direction_id": 1, "headsign": "Disp. Bucium"}]},
                  {"route_id": 12, "line": "12", "name": "Altă linie 12", "type": "autobuz", "directions": null},
                  {"route_id": 99, "line": "?", "name": null}
                ]}""";

        List<Linie> linii = CtpApiClient.parseLinii(json);

        assertEquals(3, linii.size());
        assertEquals("P-ța 1 Mai", linii.get(0).directions().get(0).headsign());
        assertTrue(linii.get(1).directions().isEmpty());
        assertTrue(linii.get(2).directions().isEmpty());
        assertNull(linii.get(2).name());
    }

    @Test
    void faraListaDeLiniiNuCrapa() throws Exception {
        assertTrue(CtpApiClient.parseLinii("{}").isEmpty());
    }

    @Test
    void parseazaTraseulSiStatiile() throws Exception {
        String json = """
                {"route_id": 1, "line": "1", "directions": [
                  {"trip_id": "1_0", "direction_id": 0, "headsign": "P-ța 1 Mai",
                   "shape": [[46.75174, 23.54385], [46.75181, 23.54553]],
                   "stops": [{"id": 46, "name": "Disp. Bucium", "lat": 46.7517, "lon": 23.54342},
                             {"id": 2, "name": "Primăverii", "lat": 46.75181, "lon": 23.54553}]},
                  {"trip_id": "1_1", "direction_id": 1, "headsign": "Disp. Bucium"}
                ]}""";

        DetaliiLinie d = CtpApiClient.parseLinie(json);

        assertEquals(2, d.directions().size());
        assertEquals(2, d.directions().get(0).shape().size());
        assertEquals(23.54553, d.directions().get(0).shape().get(1)[1], 1e-9);
        assertEquals("Primăverii", d.directions().get(0).stops().get(1).name());
        assertTrue(d.directions().get(1).shape().isEmpty());
        assertTrue(d.directions().get(1).stops().isEmpty());
    }
}
