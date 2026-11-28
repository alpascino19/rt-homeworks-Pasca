package ro.utcn.ssatr.tracker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Pagina locală "călătoria mea" (http://localhost:PORT): harta cu traseul, autobuzele și
 * locația utilizatorului. Browserul trimite locația (navigator.geolocation) pe /locatie și
 * citește periodic /traseu și /stare. Serverul ascultă doar pe 127.0.0.1: locația nu pleacă
 * de pe calculator.
 */
public final class ServerLocatie implements AutoCloseable {

    public record Locatie(double lat, double lon, double precizieM) { }

    private static final int MAX_CORP = 1024;

    private final HttpServer server;
    private final String pagina;
    private final CountDownLatch prima = new CountDownLatch(1);
    // scrise de threadul tracker / HTTP, citite de celălalt: volatile, valori imutabile
    private volatile Locatie ultima;
    private volatile String traseu = "null";
    private volatile String stare = "{\"faza\":\"pornire\",\"mesaj\":\"Pornesc...\",\"optiuni\":[]}";

    public ServerLocatie(int port) throws IOException {
        try (InputStream in = ServerLocatie.class.getResourceAsStream("/web/harta.html")) {
            if (in == null) throw new IOException("lipsește resursa /web/harta.html");
            pagina = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::trateaza);
        server.start();
    }

    public String adresa() {
        return "http://localhost:" + server.getAddress().getPort() + "/";
    }

    public Locatie ultima() {
        return ultima;
    }

    public Locatie asteaptaPrima() throws InterruptedException {
        prima.await();
        return ultima;
    }

    /** JSON-ul traseului urmărit (Harta.traseu), sau "null" cât nu e ales. */
    public void publicaTraseu(String json) {
        traseu = json;
    }

    /** JSON-ul stării curente (Harta.urmarire / Harta.mesaj). */
    public void publicaStare(String json) {
        stare = json;
    }

    private void trateaza(HttpExchange ex) throws IOException {
        try (ex) {
            String cale = ex.getRequestURI().getPath();
            String metoda = ex.getRequestMethod();
            if ("GET".equals(metoda) && "/".equals(cale)) {
                raspunde(ex, 200, "text/html; charset=utf-8", pagina);
            } else if ("GET".equals(metoda) && "/traseu".equals(cale)) {
                raspunde(ex, 200, "application/json; charset=utf-8", traseu);
            } else if ("GET".equals(metoda) && "/stare".equals(cale)) {
                raspunde(ex, 200, "application/json; charset=utf-8", stare);
            } else if ("POST".equals(metoda) && "/locatie".equals(cale)) {
                byte[] corp;
                try (InputStream in = ex.getRequestBody()) {
                    corp = in.readNBytes(MAX_CORP + 1);
                }
                Locatie l = corp.length > MAX_CORP ? null : parse(new String(corp, StandardCharsets.UTF_8));
                if (l == null) {
                    raspunde(ex, 400, "text/plain; charset=utf-8", "locatie invalida");
                    return;
                }
                ultima = l;
                prima.countDown();
                raspunde(ex, 204, null, null);
            } else {
                raspunde(ex, 404, "text/plain; charset=utf-8", "nu exista");
            }
        } catch (UncheckedIOException e) {
            // browserul a închis conexiunea: nimic de făcut
        }
    }

    private static void raspunde(HttpExchange ex, int cod, String tip, String text) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (text == null) {
            ex.sendResponseHeaders(cod, -1);
            return;
        }
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", tip);
        ex.sendResponseHeaders(cod, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    /** {"lat":..,"lon":..,"acc":..}; null dacă lipsește ceva sau coordonatele nu sunt valide. */
    static Locatie parse(String json) {
        try {
            JsonNode n = CtpApiClient.JSON.readTree(json);
            if (n == null || !n.path("lat").isNumber() || !n.path("lon").isNumber()) return null;
            double lat = n.get("lat").asDouble(), lon = n.get("lon").asDouble();
            double acc = n.path("acc").isNumber() ? n.get("acc").asDouble() : 0;
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180
                    || !Double.isFinite(acc) || acc < 0) {
                return null;
            }
            return new Locatie(lat, lon, acc);
        } catch (IOException e) {
            return null;
        }
    }

    /** "46.77,23.59" (din --de-la); null dacă nu e valid. */
    static Locatie parseText(String text) {
        String[] p = text.split(",");
        if (p.length != 2) return null;
        try {
            return parse("{\"lat\":" + Double.parseDouble(p[0].trim()) + ",\"lon\":" + Double.parseDouble(p[1].trim()) + "}");
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
