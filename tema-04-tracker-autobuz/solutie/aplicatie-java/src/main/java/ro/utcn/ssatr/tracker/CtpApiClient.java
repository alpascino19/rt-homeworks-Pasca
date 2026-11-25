package ro.utcn.ssatr.tracker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Clientul API-ului REST CTP Cluj live: /api/lines și /api/line?route_id=N.
 * Datele sunt statice: se citesc o dată și se țin în memorie de apelant.
 */
public final class CtpApiClient {

    // ---------------------------------------------------------------- modelul datelor

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sens(@JsonProperty("trip_id") String tripId,
                       @JsonProperty("direction_id") int directionId,
                       @JsonProperty("headsign") String headsign) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Linie(@JsonProperty("route_id") int routeId,
                        @JsonProperty("line") String line,
                        @JsonProperty("name") String name,
                        @JsonProperty("type") String type,
                        @JsonProperty("directions") List<Sens> directions) {
        public Linie {
            directions = directions == null ? List.of() : directions;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Statie(@JsonProperty("id") int id,
                         @JsonProperty("name") String name,
                         @JsonProperty("lat") double lat,
                         @JsonProperty("lon") double lon) { }

    /** Un sens al liniei: traseul (puncte [lat, lon], în ordine) și stațiile, în ordinea parcurgerii. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TraseuSens(@JsonProperty("trip_id") String tripId,
                             @JsonProperty("direction_id") int directionId,
                             @JsonProperty("headsign") String headsign,
                             @JsonProperty("shape") List<double[]> shape,
                             @JsonProperty("stops") List<Statie> stops) {
        public TraseuSens {
            shape = shape == null ? List.of() : shape;
            stops = stops == null ? List.of() : stops;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DetaliiLinie(@JsonProperty("route_id") int routeId,
                               @JsonProperty("line") String line,
                               @JsonProperty("directions") List<TraseuSens> directions) {
        public DetaliiLinie {
            directions = directions == null ? List.of() : directions;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RaspunsLinii(@JsonProperty("lines") List<Linie> lines) { }

    /** Eroare la care reîncercarea nu ajută (cheie greșită, linie inexistentă, cerere greșită). */
    public static final class ApiException extends IOException {
        public final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    // ---------------------------------------------------------------- client

    static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false);

    private static final int MAX_INCERCARI = 5;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final String apiKey;

    public CtpApiClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    public List<Linie> linii() throws IOException, InterruptedException {
        return parseLinii(get("/api/lines"));
    }

    public DetaliiLinie linie(int routeId) throws IOException, InterruptedException {
        return parseLinie(get("/api/line?route_id=" + routeId));
    }

    /**
     * Traseele tuturor liniilor (pentru căutarea după destinație). Sunt date statice: se păstrează
     * în fisierCache și se cer din nou doar dacă fișierul e mai vechi de 7 zile.
     */
    public Map<Integer, DetaliiLinie> toateLiniile(List<Linie> linii, Path fisierCache) throws IOException, InterruptedException {
        if (Files.exists(fisierCache)
                && Files.getLastModifiedTime(fisierCache).toInstant().isAfter(Instant.now().minus(Duration.ofDays(7)))) {
            try {
                Map<Integer, DetaliiLinie> m = JSON.readValue(fisierCache.toFile(), new TypeReference<Map<Integer, DetaliiLinie>>() { });
                if (!m.isEmpty()) return m;
            } catch (IOException e) {
                System.out.println("[api] cache-ul de trasee e stricat, îl refac: " + e.getMessage());
            }
        }
        System.out.println("[api] citesc traseele celor " + linii.size() + " linii (o singură dată, apoi din " + fisierCache + ") ...");
        Map<Integer, DetaliiLinie> m = new TreeMap<>();
        for (Linie l : linii) {
            try {
                m.put(l.routeId(), linie(l.routeId()));
            } catch (ApiException e) {
                if (e.status != 404) throw e;  // linie fără traseu: o sărim
            }
            if (m.size() % 40 == 0) System.out.println("[api]   " + m.size() + "/" + linii.size());
        }
        JSON.writeValue(fisierCache.toFile(), m);
        return m;
    }

    static List<Linie> parseLinii(String json) throws IOException {
        RaspunsLinii r = JSON.readValue(json, RaspunsLinii.class);
        return r.lines() == null ? List.of() : r.lines();
    }

    static DetaliiLinie parseLinie(String json) throws IOException {
        return JSON.readValue(json, DetaliiLinie.class);
    }

    /** GET cu cheia în header; 502/503 și erorile de rețea se reîncearcă cu backoff exponențial. */
    private String get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("X-API-Key", apiKey)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        long pauzaMs = 1000;
        for (int incercare = 1; ; incercare++) {
            String problema;
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int st = resp.statusCode();
                switch (st) {
                    case 200: return resp.body();
                    case 401: throw new ApiException(st, "401: cheia API lipsește sau e greșită (api.key / CHEIE_API)");
                    case 404: throw new ApiException(st, "404: adresă sau route_id necunoscut (" + path + ")");
                    case 502:
                    case 503: problema = st + ": serverul nu are încă date sau sursa nu răspunde"; break;
                    default:  throw new ApiException(st, st + " la " + path + ": " + resp.body());
                }
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                problema = "eroare de rețea: " + e.getMessage();
            }
            if (incercare >= MAX_INCERCARI) {
                throw new IOException(problema + " (după " + MAX_INCERCARI + " încercări)");
            }
            System.out.printf("[api] %s; reîncerc în %d s%n", problema, pauzaMs / 1000);
            Thread.sleep(pauzaMs);
            pauzaMs *= 2;
        }
    }
}
