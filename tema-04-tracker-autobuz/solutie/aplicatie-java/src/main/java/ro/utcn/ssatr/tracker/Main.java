package ro.utcn.ssatr.tracker;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import ro.utcn.ssatr.tracker.CtpApiClient.DetaliiLinie;
import ro.utcn.ssatr.tracker.CtpApiClient.Linie;
import ro.utcn.ssatr.tracker.CtpApiClient.Statie;
import ro.utcn.ssatr.tracker.CtpApiClient.TraseuSens;
import ro.utcn.ssatr.tracker.Meniu.Selectie;

/**
 * Tracker personal de autobuz.
 *
 *   java -jar tracker-autobuz-1.0.jar [--route-id N --trip-id T --stop-id S] [--walk-min M]
 *                                     [--record fisier.jsonl | --replay fisier.jsonl [--speed 10]]
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        Map<String, String> arg = argumente(args);
        Config config = new Config(Path.of("config.properties"));
        CtpApiClient api;
        try {
            api = new CtpApiClient(config.apiUrl(), config.apiKey());
        } catch (IllegalStateException e) {  // configurare lipsă: mesaj, nu stack trace
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        Selectie sel;
        try {
            sel = arg.containsKey("route-id") ? dinArgumente(arg, api) : dinMeniu(api);
        } catch (CtpApiClient.ApiException | IllegalArgumentException e) {
            System.err.println("Eroare: " + e.getMessage());
            System.exit(1);
            return;
        }
        System.out.printf("%nUrmăresc linia %s (route_id %d), sensul spre %s (%s), stația %s (id %d), mers pe jos %d min.%n",
                sel.linie().line(), sel.linie().routeId(), sel.sens().headsign(), sel.sens().tripId(),
                sel.statie().name(), sel.statie().id(), sel.mersMin());

        // Callback-urile RabbitMQ / replay doar pun pozițiile în coadă; un singur thread le prelucrează.
        BlockingQueue<Vehicul> coada = new LinkedBlockingQueue<>();
        VehicleFeed feed = new VehicleFeed(sel.linie().routeId(), coada::offer);
        Clock ceas;
        if (arg.containsKey("replay")) {
            double viteza = Double.parseDouble(arg.getOrDefault("speed", "1"));
            CeasReplay ceasReplay = new CeasReplay(viteza);
            ceas = ceasReplay;
            feed.pornesteReplay(Path.of(arg.get("replay")), ceasReplay, viteza);
            System.out.println("[replay] " + arg.get("replay") + ", viteza x" + viteza);
        } else {
            ceas = Clock.systemUTC();
            feed.pornesteLive(config, arg.containsKey("record") ? Path.of(arg.get("record")) : null);
            if (arg.containsKey("record")) System.out.println("[record] salvez mesajele brute în " + arg.get("record"));
        }
        Runtime.getRuntime().addShutdownHook(new Thread(feed::close, "oprire"));

        Traseu traseu = new Traseu(sel.sens());
        double sStatie = traseu.sStatie(sel.sens().stops().indexOf(sel.statie()));
        System.out.printf("Traseu: %.0f m, stația la %.0f m de la capăt. Ctrl+C pentru oprire.%n", traseu.lungime(), sStatie);

        // shortcut: până la pasul 6 (Tracker) doar afișăm pozițiile liniei, proiectate pe traseu
        for (;;) {
            Vehicul v = coada.poll(1, TimeUnit.SECONDS);
            if (v == null) continue;
            Traseu.Proiectie p = traseu.proiecteaza(v.lat(), v.lon());
            long vechime = Duration.between(v.timestamp(), ceas.instant()).toSeconds();
            System.out.printf("%-5s trip=%-6s s=%6.0f m lateral=%5.0f m rest=%6.0f m vechime=%3d s%n",
                    v.label(), v.tripId(), p.s(), p.lateral(), sStatie - p.s(), vechime);
        }
    }

    private static Selectie dinMeniu(CtpApiClient api) throws Exception {
        System.out.println("Citesc liniile ...");
        List<Linie> linii = api.linii();
        System.out.println(linii.size() + " linii.");
        // tastatura se citește în codificarea consolei (pe Windows nu e UTF-8)
        Charset cs = System.console() != null ? System.console().charset() : Charset.defaultCharset();
        Meniu meniu = new Meniu(new BufferedReader(new InputStreamReader(System.in, cs)), System.out);
        return meniu.ruleaza(linii, api::linie);
    }

    /** Pornire fără meniu: --route-id, --trip-id, --stop-id (și opțional --walk-min). */
    private static Selectie dinArgumente(Map<String, String> arg, CtpApiClient api) throws Exception {
        int routeId = Integer.parseInt(arg.get("route-id"));
        String tripId = arg.get("trip-id");
        String stopId = arg.get("stop-id");
        if (tripId == null || stopId == null) {
            throw new IllegalArgumentException("cu --route-id trebuie date și --trip-id și --stop-id");
        }
        DetaliiLinie d = api.linie(routeId);
        TraseuSens sens = d.directions().stream().filter(s -> tripId.equals(s.tripId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("linia " + routeId + " nu are sensul " + tripId));
        Statie statie = sens.stops().stream().filter(s -> String.valueOf(s.id()).equals(stopId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("sensul " + tripId + " nu are stația " + stopId));
        if (sens.shape().size() < 2) throw new IllegalArgumentException("sensul " + tripId + " nu are traseu");
        Linie linie = new Linie(routeId, d.line(), "", "", List.of());
        return new Selectie(linie, sens, statie, Integer.parseInt(arg.getOrDefault("walk-min", "5")));
    }

    /** "--cheie valoare" -> map. */
    static Map<String, String> argumente(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("argument neașteptat: " + args[i]);
            }
            m.put(args[i].substring(2), args[++i]);
        }
        return m;
    }
}
