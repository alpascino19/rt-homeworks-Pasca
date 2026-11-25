package ro.utcn.ssatr.tracker;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.RecoveryListener;

/**
 * Pozițiile vehiculelor: din RabbitMQ (exchange-ul fanout cluj.vehicles, printr-o
 * coadă proprie temporară) sau, la replay, dintr-un fișier înregistrat.
 * Trimite mai departe doar pozițiile valide ale liniei alese.
 *
 * Fișierul de înregistrare are o linie JSON pe mesaj: {"recv_ms": ..., "raw": "<mesajul brut>"}.
 */
public final class VehicleFeed implements AutoCloseable {

    private static final String EXCHANGE = "cluj.vehicles";

    private final int routeId;
    private final Consumer<Vehicul> destinatie;
    private final AtomicBoolean conectat = new AtomicBoolean(false);

    private Connection connection;
    private Channel channel;
    private BufferedWriter inregistrare;
    private Thread threadReplay;

    /** destinatie e apelată pe threadul RabbitMQ / de replay: trebuie să fie rapidă (o coadă). */
    public VehicleFeed(int routeId, Consumer<Vehicul> destinatie) {
        this.routeId = routeId;
        this.destinatie = destinatie;
    }

    /** true cât timp sursa dă date: conexiunea RabbitMQ e deschisă / replay-ul rulează. */
    public boolean conectat() {
        return conectat.get();
    }

    /** Pornește consumul din RabbitMQ; dacă fisierInregistrare != null, salvează mesajele brute. */
    public void pornesteLive(Config config, Path fisierInregistrare) throws Exception {
        if (fisierInregistrare != null) {
            inregistrare = Files.newBufferedWriter(fisierInregistrare, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.rabbitHost());
        factory.setPort(config.rabbitPort());
        factory.setUsername(config.rabbitUser());
        factory.setPassword(config.rabbitPass());
        factory.setAutomaticRecoveryEnabled(true);    // reconectare și redeclarare coadă/binding
        factory.setNetworkRecoveryInterval(5000);

        connection = factory.newConnection("tracker-autobuz");
        connection.addShutdownListener(cauza -> {
            conectat.set(false);
            if (!cauza.isInitiatedByApplication()) System.out.println("[rabbit] conexiune pierdută: " + cauza.getMessage());
        });
        ((Recoverable) connection).addRecoveryListener(new RecoveryListener() {
            @Override public void handleRecovery(Recoverable r) {
                conectat.set(true);
                System.out.println("[rabbit] reconectat");
            }
            @Override public void handleRecoveryStarted(Recoverable r) { }
        });

        channel = connection.createChannel();
        channel.exchangeDeclare(EXCHANGE, "fanout");             // exact așa: nedurabil, altfel PRECONDITION_FAILED
        String coada = channel.queueDeclare().getQueue();         // temporară, exclusivă, ștearsă la închidere
        channel.queueBind(coada, EXCHANGE, "");
        channel.basicConsume(coada, true,
                (tag, m) -> primit(System.currentTimeMillis(), new String(m.getBody(), StandardCharsets.UTF_8)),
                tag -> { });
        conectat.set(true);
        System.out.println("[rabbit] conectat la " + config.rabbitHost() + ":" + config.rabbitPort()
                + ", coada " + coada + " legată de " + EXCHANGE);
    }

    /** Redă un fișier înregistrat, cu ritmul original împărțit la viteza dată, pe un thread separat. */
    public void pornesteReplay(Path fisier, CeasReplay ceas, double viteza) {
        threadReplay = new Thread(() -> {
            conectat.set(true);
            long anterior = -1;
            int n = 0;
            try (BufferedReader r = Files.newBufferedReader(fisier, StandardCharsets.UTF_8)) {
                String linie;
                while ((linie = r.readLine()) != null) {
                    if (linie.isBlank()) continue;
                    JsonNode rec;
                    try {
                        rec = CtpApiClient.JSON.readTree(linie);
                    } catch (IOException e) {
                        continue;  // linie stricată (ex. fișier tăiat la oprire): o sărim
                    }
                    long recv = rec.path("recv_ms").asLong();
                    if (anterior > 0 && recv > anterior) Thread.sleep((long) ((recv - anterior) / viteza));
                    anterior = recv;
                    ceas.seteaza(recv);
                    primit(recv, rec.path("raw").asText());
                    n++;
                }
                System.out.println("[replay] terminat: " + n + " mesaje redate");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                System.out.println("[replay] eroare la citirea " + fisier + ": " + e.getMessage());
            } finally {
                conectat.set(false);  // fără date noi: tracker-ul trece pe FARA_DATE
            }
        }, "replay");
        threadReplay.setDaemon(true);
        threadReplay.start();
    }

    /** Un mesaj brut: înregistrat (dacă e cazul), parsat, filtrat pe linie. */
    private void primit(long recvMs, String raw) {
        if (inregistrare != null) {
            try {
                ObjectNode rec = CtpApiClient.JSON.createObjectNode().put("recv_ms", recvMs).put("raw", raw);
                inregistrare.write(rec.toString());
                inregistrare.newLine();
                inregistrare.flush();
            } catch (IOException e) {
                System.out.println("[record] nu pot scrie: " + e.getMessage());
            }
        }
        Vehicul v = Vehicul.parse(raw);
        if (v != null && v.routeId() != null && v.routeId() == routeId) destinatie.accept(v);
    }

    @Override
    public void close() {
        conectat.set(false);
        if (threadReplay != null) threadReplay.interrupt();
        try {
            if (channel != null && channel.isOpen()) channel.close();
            if (connection != null && connection.isOpen()) connection.close();
        } catch (Exception e) {
            System.out.println("[rabbit] eroare la închidere: " + e.getMessage());
        }
        try {
            if (inregistrare != null) inregistrare.close();
        } catch (IOException e) {
            System.out.println("[record] eroare la închidere: " + e.getMessage());
        }
    }
}
