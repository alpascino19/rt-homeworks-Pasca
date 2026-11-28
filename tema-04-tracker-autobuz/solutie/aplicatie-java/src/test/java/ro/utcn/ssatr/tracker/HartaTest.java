package ro.utcn.ssatr.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import ro.utcn.ssatr.tracker.CtpApiClient.Statie;
import ro.utcn.ssatr.tracker.CtpApiClient.TraseuSens;
import ro.utcn.ssatr.tracker.MasinaStari.Stare;
import ro.utcn.ssatr.tracker.ServerLocatie.Locatie;
import ro.utcn.ssatr.tracker.Tracker.Rezultat;
import ro.utcn.ssatr.tracker.Tracker.VehiculHarta;

class HartaTest {

    @Test
    void traseulContinePuncteleSiStatiileMarcate() throws Exception {
        TraseuSens sens = new TraseuSens("14_0", 0, "Snagov Nord",
                List.of(new double[] {46.75, 23.55}, new double[] {46.77, 23.61}),
                List.of(new Statie(1, "Minerva", 46.75, 23.55), new Statie(2, "P-ța Mărăști", 46.77, 23.61)));

        JsonNode n = CtpApiClient.JSON.readTree(Harta.traseu("25", sens, 1, null));

        assertEquals("25", n.get("linie").asText());
        assertEquals(2, n.get("puncte").size());
        assertEquals(23.61, n.get("puncte").get(1).get(1).asDouble(), 1e-9);
        assertEquals("P-ța Mărăști", n.get("statii").get(1).get("nume").asText());
        assertEquals(1, n.get("urcare").asInt());
        assertFalse(n.has("coborare"));
    }

    @Test
    void stareaUrmaririiCuVehiculeSiLocatie() throws Exception {
        String json = Harta.urmarire(new Rezultat(Stare.PLEACA, 410L, 1650L, "319", 3, 1),
                List.of(new VehiculHarta("319", 46.76, 23.59, true, true, 12), new VehiculHarta("327", 46.75, 23.57, false, false, 30)),
                new Locatie(46.777, 23.61, 25), true, 300, true);
        JsonNode n = CtpApiClient.JSON.readTree(json);

        assertEquals("urmarire", n.get("faza").asText());
        assertEquals("PLEACA", n.get("stare").asText());
        assertEquals(410, n.get("eta_s").asInt());
        assertEquals(300, n.get("mers_s").asInt());
        assertTrue(n.get("placa_online").asBoolean());
        assertEquals(2, n.get("vehicule").size());
        assertTrue(n.get("vehicule").get(0).get("urmarit").asBoolean());
        assertEquals(25, n.get("eu").get("precizie_m").asDouble(), 1e-9);
    }

    @Test
    void fazaFaraUrmarire() throws Exception {
        JsonNode n = CtpApiClient.JSON.readTree(Harta.mesaj("alegere", "Variante", List.of("a", "b"), null, false));

        assertEquals("alegere", n.get("faza").asText());
        assertEquals(2, n.get("optiuni").size());
        assertFalse(n.has("eu"));
    }
}
