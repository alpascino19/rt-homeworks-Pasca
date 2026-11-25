package ro.utcn.ssatr.tracker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Locația utilizatorului, din browser: o pagină pe http://localhost:PORT cere locația
 * (navigator.geolocation) și o trimite înapoi la fiecare schimbare. Serverul ascultă
 * doar pe 127.0.0.1: locația nu pleacă de pe calculator.
 */
public final class ServerLocatie implements AutoCloseable {

    public record Locatie(double lat, double lon, double precizieM) { }

    private static final int MAX_CORP = 1024;

    private final HttpServer server;
    private final CountDownLatch prima = new CountDownLatch(1);
    private volatile Locatie ultima;  // scrisă de threadul HTTP, citită de threadul tracker

    public ServerLocatie(int port) throws IOException {
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

    private void trateaza(HttpExchange ex) throws IOException {
        try (ex) {
            String cale = ex.getRequestURI().getPath();
            if ("GET".equals(ex.getRequestMethod()) && "/".equals(cale)) {
                raspunde(ex, 200, "text/html; charset=utf-8", PAGINA);
            } else if ("POST".equals(ex.getRequestMethod()) && "/locatie".equals(cale)) {
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
        }
    }

    private static void raspunde(HttpExchange ex, int cod, String tip, String text) throws IOException {
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

    private static final String PAGINA = """
            <!doctype html>
            <html lang="ro"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Tracker autobuz – locația mea</title>
            <style>
              body { font-family: system-ui, sans-serif; max-width: 34rem; margin: 2rem auto; padding: 0 1rem; line-height: 1.5; }
              #stare { padding: .75rem 1rem; border-radius: .5rem; background: #eef; }
              #stare.ok { background: #e6f6e6; } #stare.eroare { background: #fde8e8; }
              input { font: inherit; width: 14rem; padding: .3rem; } button { font: inherit; padding: .3rem .8rem; }
            </style></head>
            <body>
            <h1>Locația mea</h1>
            <p>Țineți pagina deschisă: aplicația primește locația la fiecare schimbare și recalculează timpul de mers până la stație.</p>
            <p id="stare" role="status">Aștept permisiunea pentru locație…</p>
            <h2>Sau introduceți coordonatele</h2>
            <form id="manual">
              <label for="coord">lat, lon:</label>
              <input id="coord" placeholder="46.7712, 23.6236" required>
              <button>Trimite</button>
            </form>
            <script>
              const stare = document.getElementById('stare');
              function arata(text, cls) { stare.textContent = text; stare.className = cls || ''; }
              async function trimite(lat, lon, acc) {
                try {
                  const r = await fetch('/locatie', { method: 'POST', headers: { 'Content-Type': 'application/json' },
                                                      body: JSON.stringify({ lat, lon, acc }) });
                  if (!r.ok) throw new Error('aplicația a refuzat locația');
                  arata(`Trimis: ${lat.toFixed(5)}, ${lon.toFixed(5)} (precizie ~${Math.round(acc)} m), ${new Date().toLocaleTimeString()}`, 'ok');
                } catch (e) { arata('Aplicația nu răspunde: ' + e.message, 'eroare'); }
              }
              if ('geolocation' in navigator) {
                navigator.geolocation.watchPosition(
                  p => trimite(p.coords.latitude, p.coords.longitude, p.coords.accuracy),
                  e => arata('Locația nu e disponibilă (' + e.message + '). Introduceți coordonatele mai jos.', 'eroare'),
                  { enableHighAccuracy: true, maximumAge: 10000 });
              } else {
                arata('Browserul nu oferă locația. Introduceți coordonatele mai jos.', 'eroare');
              }
              document.getElementById('manual').addEventListener('submit', ev => {
                ev.preventDefault();
                const p = document.getElementById('coord').value.split(',').map(Number);
                if (p.length !== 2 || p.some(isNaN)) { arata('Format: lat, lon (ex. 46.7712, 23.6236)', 'eroare'); return; }
                trimite(p[0], p[1], 0);
              });
            </script>
            </body></html>
            """;
}
