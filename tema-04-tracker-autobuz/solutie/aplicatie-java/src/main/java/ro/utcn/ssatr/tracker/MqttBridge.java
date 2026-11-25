package ro.utcn.ssatr.tracker;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ro.utcn.ssatr.tracker.Tracker.Rezultat;

/**
 * Legătura MQTT cu placa (brokerul cursului, topic-uri sub ssatr/<student>/tracker/):
 *   stare       Java -> placă, QoS 1, retained
 *   eveniment   placă -> Java: {"eveniment":"URCAT","ts":...}
 *   confirmare  Java -> placă: {"eveniment":"URCAT","ok":true}
 *   online      placă -> broker, retained: "1" / LWT "0"
 *
 * Mesajele primite nu se prelucrează pe threadul Paho: devin evenimente puse în coada
 * threadului tracker (Urcat, PlacaOnline).
 */
public final class MqttBridge implements AutoCloseable {

    /** Placa a trimis URCAT; ts = epoch seconds, același la retrimiterile aceleiași apăsări. */
    public record Urcat(long ts) { }

    /** Placa s-a conectat (true) sau a dispărut (false, din LWT). */
    public record PlacaOnline(boolean online) { }

    static final int MAX_OCTETI = 250;  // bufferul plăcii are 256 B

    private final MqttClient client;
    private final String prefix;
    private final Consumer<Object> evenimente;

    public MqttBridge(String broker, String student, Consumer<Object> evenimente) throws MqttException {
        this.prefix = "ssatr/" + student + "/tracker/";
        this.evenimente = evenimente;
        // ID-ul clientului trebuie să fie unic pe broker
        this.client = new MqttClient(broker, student + "-tracker-java-" + System.currentTimeMillis(), new MemoryPersistence());
        client.setTimeToWait(3000);  // publish() nu blochează threadul tracker mai mult de atât
    }

    public void conecteaza() throws MqttException {
        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconectare, String uri) {
                // cu clean session, brokerul uită abonările: le refacem la fiecare (re)conectare
                try {
                    client.subscribe(prefix + "eveniment", 1);
                    client.subscribe(prefix + "online", 1);
                    System.out.println("[mqtt] " + (reconectare ? "reconectat" : "conectat") + " la " + uri);
                } catch (MqttException e) {
                    System.out.println("[mqtt] abonare eșuată: " + e.getMessage());
                }
            }

            @Override
            public void connectionLost(Throwable cauza) {
                System.out.println("[mqtt] conexiune pierdută (" + cauza.getMessage() + "), reîncerc automat");
            }

            @Override
            public void messageArrived(String topic, MqttMessage m) {
                String text = new String(m.getPayload(), StandardCharsets.UTF_8);
                if (topic.endsWith("/online")) {
                    evenimente.accept(new PlacaOnline("1".equals(text.trim().replace("\"", ""))));
                } else if (topic.endsWith("/eveniment")) {
                    Long ts = tsUrcat(text);
                    if (ts != null) evenimente.accept(new Urcat(ts));
                    else System.out.println("[mqtt] eveniment necunoscut, ignorat: " + text);
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) { }
        });

        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        o.setAutomaticReconnect(true);
        o.setConnectionTimeout(10);
        client.connect(o);
    }

    public void publicaStare(Rezultat r, String linie, String statie) {
        publica("stare", jsonStare(r, linie, statie, System.currentTimeMillis() / 1000), true);
    }

    public void confirmaUrcat() {
        publica("confirmare", "{\"eveniment\":\"URCAT\",\"ok\":true}", false);
    }

    private void publica(String kind, String text, boolean retained) {
        try {
            MqttMessage m = new MqttMessage(text.getBytes(StandardCharsets.UTF_8));
            m.setQos(1);
            m.setRetained(retained);
            client.publish(prefix + kind, m);
        } catch (MqttException e) {
            // fără conexiune: următorul heartbeat (10 s) reîncearcă
            System.out.println("[mqtt] publicarea pe " + kind + " a eșuat: " + e.getMessage());
        }
    }

    /**
     * {"stare":"PLEACA","eta_s":410,"rest_m":1650,"linie":"25","statie":"Mărăști","vehicul":"848","ts":...}.
     * ts = ora reală de trimitere (și la replay): după ea placa respinge stările retained vechi.
     * Sub MAX_OCTETI în UTF-8: numele stației se scurtează dacă e nevoie.
     */
    static String jsonStare(Rezultat r, String linie, String statie, long tsEpochS) {
        String nume = statie;
        for (;;) {
            ObjectNode n = CtpApiClient.JSON.createObjectNode();
            n.put("stare", r.stare().name());
            if (r.etaS() != null) n.put("eta_s", r.etaS());
            if (r.restM() != null) n.put("rest_m", r.restM());
            n.put("linie", linie);
            n.put("statie", nume);
            if (r.vehicul() != null) n.put("vehicul", r.vehicul());
            n.put("ts", tsEpochS);
            String json = n.toString();
            if (json.getBytes(StandardCharsets.UTF_8).length <= MAX_OCTETI || nume.isEmpty()) return json;
            nume = nume.substring(0, nume.length() - 1);
        }
    }

    /** ts-ul unui eveniment URCAT, sau null dacă mesajul nu e un URCAT valid. */
    static Long tsUrcat(String json) {
        try {
            JsonNode n = CtpApiClient.JSON.readTree(json);
            if (n == null || !"URCAT".equals(n.path("eveniment").asText()) || !n.path("ts").isNumber()) return null;
            return n.get("ts").asLong();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) client.disconnect(2000);
            client.close();
        } catch (MqttException e) {
            System.out.println("[mqtt] eroare la închidere: " + e.getMessage());
        }
    }
}
