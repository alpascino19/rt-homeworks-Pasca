package ro.utcn.ssatr.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import ro.utcn.ssatr.tracker.MasinaStari.Stare;
import ro.utcn.ssatr.tracker.Tracker.Rezultat;

class MqttBridgeTest {

    @Test
    void stareaContineCampurileProtocolului() throws Exception {
        String json = MqttBridge.jsonStare(new Rezultat(Stare.PLEACA, 410L, 1650L, "848", 4, 1),
                "25", "P-ța Mărăști", 1764100000L);
        JsonNode n = CtpApiClient.JSON.readTree(json);

        assertEquals("PLEACA", n.get("stare").asText());
        assertEquals(410, n.get("eta_s").asInt());
        assertEquals(1650, n.get("rest_m").asInt());
        assertEquals("25", n.get("linie").asText());
        assertEquals("P-ța Mărăști", n.get("statie").asText());
        assertEquals("848", n.get("vehicul").asText());
        assertEquals(1764100000L, n.get("ts").asLong());
    }

    @Test
    void faraAutobuzLipsescEtaSiRest() throws Exception {
        JsonNode n = CtpApiClient.JSON.readTree(MqttBridge.jsonStare(
                new Rezultat(Stare.FARA_DATE, null, null, null, 0, 0), "25", "X", 1L));

        assertFalse(n.has("eta_s"));
        assertFalse(n.has("vehicul"));
    }

    @Test
    void mesajulIncapeInBufferulPlacii() {
        String json = MqttBridge.jsonStare(new Rezultat(Stare.GRABESTE_TE, 99999L, 99999L, "12345", 9, 9),
                "M26", "Ăîșțâ".repeat(60), 1764100000L);

        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= MqttBridge.MAX_OCTETI);
        assertTrue(json.contains("\"stare\":\"GRABESTE_TE\""));
    }

    @Test
    void evenimentUrcat() {
        assertEquals(1764100123L, MqttBridge.tsUrcat("{\"eveniment\":\"URCAT\",\"ts\":1764100123}"));
        assertNull(MqttBridge.tsUrcat("{\"eveniment\":\"ALTCEVA\",\"ts\":1}"));
        assertNull(MqttBridge.tsUrcat("{\"eveniment\":\"URCAT\"}"));
        assertNull(MqttBridge.tsUrcat("URCAT"));
    }
}
