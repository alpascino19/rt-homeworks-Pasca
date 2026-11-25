package ro.utcn.ssatr.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import ro.utcn.ssatr.tracker.ServerLocatie.Locatie;

class ServerLocatieTest {

    @Test
    void parseazaLocatiaDinBrowser() {
        assertEquals(new Locatie(46.77, 23.6, 15), ServerLocatie.parse("{\"lat\":46.77,\"lon\":23.6,\"acc\":15}"));
        assertEquals(new Locatie(46.77, 23.6, 0), ServerLocatie.parse("{\"lat\":46.77,\"lon\":23.6}"));
    }

    @Test
    void respingeLocatiiInvalide() {
        assertNull(ServerLocatie.parse("{\"lat\":91,\"lon\":23.6}"));
        assertNull(ServerLocatie.parse("{\"lat\":46.7,\"lon\":\"x\"}"));
        assertNull(ServerLocatie.parse("{\"lat\":46.7}"));
        assertNull(ServerLocatie.parse("{\"lat\":46.7,\"lon\":23.6,\"acc\":-1}"));
        assertNull(ServerLocatie.parse("nu e json"));
    }

    @Test
    void parseazaTextulDinLiniaDeComanda() {
        assertEquals(new Locatie(46.7712, 23.6236, 0), ServerLocatie.parseText("46.7712, 23.6236"));
        assertNull(ServerLocatie.parseText("46.7712"));
        assertNull(ServerLocatie.parseText("a,b"));
    }

    @Test
    void serverulPrimesteLocatiaPrinPost() throws Exception {
        try (ServerLocatie s = new ServerLocatie(0)) {  // port liber ales de sistem
            HttpClient http = HttpClient.newHttpClient();
            URI uri = URI.create(s.adresa());

            assertEquals(200, http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            HttpResponse<Void> rau = http.send(HttpRequest.newBuilder(uri.resolve("/locatie"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"lat\":200}")).build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(400, rau.statusCode());
            assertNull(s.ultima());

            HttpResponse<Void> bun = http.send(HttpRequest.newBuilder(uri.resolve("/locatie"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"lat\":46.77,\"lon\":23.6,\"acc\":10}")).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(204, bun.statusCode());
            assertEquals(new Locatie(46.77, 23.6, 10), s.asteaptaPrima());
        }
    }
}
