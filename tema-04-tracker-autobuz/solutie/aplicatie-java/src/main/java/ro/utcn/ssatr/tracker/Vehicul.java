package ro.utcn.ssatr.tracker;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * O poziție de vehicul, din RabbitMQ. Câmpurile opționale sunt null când lipsesc
 * (trip_id lipsește chiar la vehicule în mișcare, route_id poate fi necunoscut).
 * speed nu e citit: unitatea nu e documentată, nu-l folosim.
 */
public record Vehicul(long id, String label, Integer routeId, String tripId, String line,
                      double lat, double lon, Instant timestamp, Integer age) {

    /**
     * Parsează un mesaj; null dacă lipsește ceva fără de care poziția nu are sens
     * (id, lat, lon, timestamp) sau dacă nu e JSON valid.
     */
    public static Vehicul parse(String json) {
        try {
            JsonNode n = CtpApiClient.JSON.readTree(json);
            if (n == null || !n.path("id").isNumber() || !n.path("lat").isNumber() || !n.path("lon").isNumber()
                    || !n.path("timestamp").isTextual()) {
                return null;
            }
            return new Vehicul(
                    n.get("id").asLong(),
                    n.path("label").isTextual() ? n.get("label").asText() : String.valueOf(n.get("id").asLong()),
                    n.path("route_id").isInt() ? n.get("route_id").asInt() : null,
                    n.path("trip_id").isTextual() ? n.get("trip_id").asText() : null,
                    n.path("line").isTextual() ? n.get("line").asText() : "?",
                    n.get("lat").asDouble(),
                    n.get("lon").asDouble(),
                    Instant.parse(n.get("timestamp").asText()),
                    n.path("age").isNumber() ? n.get("age").asInt() : null);
        } catch (DateTimeParseException | java.io.IOException e) {
            return null;
        }
    }
}
