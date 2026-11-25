package ro.utcn.ssatr.tracker;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import ro.utcn.ssatr.tracker.CtpApiClient.DetaliiLinie;
import ro.utcn.ssatr.tracker.CtpApiClient.Linie;
import ro.utcn.ssatr.tracker.CtpApiClient.Statie;
import ro.utcn.ssatr.tracker.CtpApiClient.TraseuSens;
import ro.utcn.ssatr.tracker.Meniu.Selectie;
import ro.utcn.ssatr.tracker.Planificator.Optiune;
import ro.utcn.ssatr.tracker.ServerLocatie.Locatie;
import ro.utcn.ssatr.tracker.Tracker.Rezultat;

/**
 * Tracker personal de autobuz.
 *
 *   java -jar tracker-autobuz-1.0.jar [--route-id N --trip-id T --stop-id S] [--walk-min M]
 *                                     [--de-la lat,lon]
 *                                     [--record fisier.jsonl | --replay fisier.jsonl [--speed 10]]
 *
 * Fără --route-id, un meniu întreabă: linie/sens/stație sau destinație (de la locația utilizatorului).
 * Locația vine din --de-la sau dintr-o pagină locală (http://localhost:8080) deschisă în browser.
 */
public final class Main {

    /** Cât se ascultă autobuzele variantelor înainte de clasament (modul destinație). */
    static final Duration COLECTARE = Duration.ofSeconds(60);

    public static void main(String[] args) throws Exception {
        System.setOut(new Meniu.ConsolaFaraDiacritice(System.out));
        System.setErr(new Meniu.ConsolaFaraDiacritice(System.err));
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
        // tastatura se citește în codificarea consolei (pe Windows nu e UTF-8); un singur cititor pentru tot meniul
        Charset cs = System.console() != null ? System.console().charset() : Charset.defaultCharset();
        Meniu meniu = new Meniu(new BufferedReader(new InputStreamReader(System.in, cs)), System.out);

        // Callback-urile RabbitMQ / replay / MQTT doar pun evenimente în coadă (Vehicul, Urcat,
        // PlacaOnline); un singur thread (tracker) le prelucrează, deci Tracker nu are nevoie de lacăte.
        BlockingQueue<Object> coada = new LinkedBlockingQueue<>();
        Tracker.Setari setari = config.setariTracker();

        try {
            Selectie sel;
            Supplier<Locatie> locatie = null;
            Tracker tracker = null;
            Sursa sursa;
            if (arg.containsKey("route-id")) {
                sel = dinArgumente(arg, api);
            } else {
                System.out.println("Citesc liniile ...");
                List<Linie> linii = api.linii();
                System.out.println(linii.size() + " linii.");
                if (!meniu.alegeDestinatia()) {
                    sel = meniu.ruleaza(linii, api::linie);
                } else {
                    Map<Integer, DetaliiLinie> detalii = api.toateLiniile(linii, Path.of("cache-trasee.json"));
                    locatie = sursaLocatie(arg, config);
                    Plan plan = planifica(meniu, linii, detalii, locatie, setari, arg, config, coada);
                    if (plan == null) return;
                    sel = plan.sel();
                    tracker = plan.tracker();
                    sursa = plan.sursa();
                    urmareste(sel, tracker, sursa, locatie, config, coada);
                    return;
                }
            }
            if (sel.mersMin() == Meniu.DIN_LOCATIE) locatie = sursaLocatie(arg, config);

            sursa = pornesteSursa(Set.of(sel.linie().routeId()), arg, config, coada);
            Traseu traseu = new Traseu(sel.sens());
            int indexStatie = sel.sens().stops().indexOf(sel.statie());
            System.out.printf("Traseu: %.0f m, stația la %.0f m de la capăt. Imaginea flotei se adună în ~1 minut. Ctrl+C pentru oprire.%n",
                    traseu.lungime(), traseu.sStatie(indexStatie));
            tracker = new Tracker(traseu, sel.sens().tripId(), indexStatie, Math.max(0, sel.mersMin()), setari, sursa.ceas());
            urmareste(sel, tracker, sursa, locatie, config, coada);
        } catch (CtpApiClient.ApiException | IllegalArgumentException e) {
            System.err.println("Eroare: " + e.getMessage());
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ sursa pozițiilor

    record Sursa(VehicleFeed feed, Clock ceas) { }

    /** RabbitMQ (cu --record opțional) sau replay; shutdown hook-ul închide feed-ul. */
    private static Sursa pornesteSursa(Set<Integer> routeIds, Map<String, String> arg, Config config,
                                       BlockingQueue<Object> coada) throws Exception {
        VehicleFeed feed = new VehicleFeed(routeIds, coada::offer);
        Runtime.getRuntime().addShutdownHook(new Thread(feed::close, "oprire-feed"));
        if (arg.containsKey("replay")) {
            double viteza = Double.parseDouble(arg.getOrDefault("speed", "1"));
            CeasReplay ceas = new CeasReplay(viteza);
            feed.pornesteReplay(Path.of(arg.get("replay")), ceas, viteza);
            System.out.println("[replay] " + arg.get("replay") + ", viteza x" + viteza);
            return new Sursa(feed, ceas);
        }
        feed.pornesteLive(config, arg.containsKey("record") ? Path.of(arg.get("record")) : null);
        if (arg.containsKey("record")) System.out.println("[record] salvez mesajele brute în " + arg.get("record"));
        return new Sursa(feed, Clock.systemUTC());
    }

    // ------------------------------------------------------------------ locația

    /** --de-la lat,lon (fixă) sau pagina locală din browser (se actualizează cât e deschisă). */
    private static Supplier<Locatie> sursaLocatie(Map<String, String> arg, Config config) throws Exception {
        if (arg.containsKey("de-la")) {
            Locatie l = ServerLocatie.parseText(arg.get("de-la"));
            if (l == null) throw new IllegalArgumentException("--de-la trebuie să fie lat,lon (ex. 46.7712,23.6236)");
            System.out.printf("Locația: %.5f, %.5f (din --de-la)%n", l.lat(), l.lon());
            return () -> l;
        }
        ServerLocatie server = new ServerLocatie(config.locatiePort());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "oprire-locatie"));
        System.out.println();
        System.out.println("Deschideți în browser " + server.adresa() + " și permiteți accesul la locație");
        System.out.println("(sau introduceți acolo coordonatele). Țineți pagina deschisă: timpul de mers se actualizează.");
        deschideBrowser(server.adresa());
        Locatie l = server.asteaptaPrima();
        System.out.printf("Locația: %.5f, %.5f (precizie ~%.0f m)%n", l.lat(), l.lon(), l.precizieM());
        return server::ultima;
    }

    private static void deschideBrowser(String adresa) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(java.net.URI.create(adresa));
        } catch (Exception | Error e) {
            // fără mediu grafic: utilizatorul deschide singur adresa afișată
        }
    }

    private static double mersPanaLa(Locatie l, Statie s) {
        return Planificator.mersS(Planificator.distantaM(l.lat(), l.lon(), s.lat(), s.lon()));
    }

    // ------------------------------------------------------------------ modul destinație

    record Plan(Selectie sel, Tracker tracker, Sursa sursa) { }

    /** Destinația din meniu, variantele directe, un minut de ascultat autobuzele, apoi clasamentul. */
    private static Plan planifica(Meniu meniu, List<Linie> linii, Map<Integer, DetaliiLinie> detalii,
                                  Supplier<Locatie> locatie, Tracker.Setari setari, Map<String, String> arg,
                                  Config config, BlockingQueue<Object> coada) throws Exception {
        // toate stațiile, grupate după nume (aceeași stație apare pe mai multe linii și sensuri)
        Map<String, List<Statie>> dupaNume = new TreeMap<>();
        for (DetaliiLinie d : detalii.values()) {
            for (TraseuSens s : d.directions()) {
                for (Statie st : s.stops()) dupaNume.computeIfAbsent(st.name(), k -> new ArrayList<>()).add(st);
            }
        }
        String dest = meniu.alege("Destinația", new ArrayList<>(dupaNume.keySet()), x -> x);
        List<Statie> statiiDest = dupaNume.get(dest);

        Locatie l = locatie.get();
        double peJos = statiiDest.stream().mapToDouble(s -> mersPanaLa(l, s)).min().orElse(0);
        List<Optiune> optiuni = Planificator.optiuni(linii, detalii, l.lat(), l.lon(), statiiDest, setari.vitezaImplicita());
        if (optiuni.isEmpty()) {
            System.out.printf("Nicio linie directă de lângă tine (stație la max. %.0f m) până la %s. Pe jos: ~%s.%n",
                    Planificator.URCARE_MAX_M, dest, minute(peJos));
            return null;
        }
        System.out.printf("%n%d variante directe până la %s (pe jos ar fi ~%s). Ascult autobuzele lor %d s ...%n",
                optiuni.size(), dest, minute(peJos), COLECTARE.toSeconds());

        Sursa sursa = pornesteSursa(optiuni.stream().map(o -> o.linie().routeId()).collect(Collectors.toSet()), arg, config, coada);
        List<Tracker> trackere = new ArrayList<>();
        for (Optiune o : optiuni) {
            Tracker t = new Tracker(new Traseu(o.sens()), o.sens().tripId(), o.iUrcare(), 0, setari, sursa.ceas());
            t.seteazaMers(o.mersPanaS());
            trackere.add(t);
        }

        Instant start = sursa.ceas().instant();
        long ultimulMesaj = System.currentTimeMillis();
        while (Duration.between(start, sursa.ceas().instant()).compareTo(COLECTARE) < 0) {
            Object ev = coada.poll(200, TimeUnit.MILLISECONDS);
            if (ev instanceof Vehicul v) {
                for (int k = 0; k < optiuni.size(); k++) {
                    if (optiuni.get(k).linie().routeId() == v.routeId()) trackere.get(k).actualizeaza(v);
                }
            }
            if (System.currentTimeMillis() - ultimulMesaj >= 15_000) {
                ultimulMesaj = System.currentTimeMillis();
                System.out.printf("  ... încă %d s%n", COLECTARE.minus(Duration.between(start, sursa.ceas().instant())).toSeconds());
            }
        }

        // clasament după ora de sosire la destinație (cu primul autobuz pe care îl poți prinde)
        Instant acum = sursa.ceas().instant();
        List<Integer> ordine = new ArrayList<>();
        Map<Integer, Instant> sosire = new HashMap<>();
        for (int k = 0; k < optiuni.size(); k++) {
            Optiune o = optiuni.get(k);
            Tracker t = trackere.get(k);
            t.seteazaMers(mersPanaLa(locatie.get(), o.urcare()));
            t.evalueaza(sursa.feed().conectat());
            Double eta = t.etaPrimulPrins();
            if (eta != null) {
                sosire.put(k, acum.plusSeconds(Math.round(eta + o.drumM() / setari.vitezaImplicita() + o.mersDupaS())));
            }
            ordine.add(k);
        }
        ordine.sort(Comparator.comparing((Integer k) -> sosire.getOrDefault(k, Instant.MAX)));

        int ales = meniu.alege("Varianta", ordine, k -> descrie(optiuni.get(k), trackere.get(k), sosire.get(k), setari));
        Optiune o = optiuni.get(ales);
        return new Plan(new Selectie(o.linie(), o.sens(), o.urcare(), Meniu.DIN_LOCATIE), trackere.get(ales), sursa);
    }

    private static String descrie(Optiune o, Tracker t, Instant sosire, Tracker.Setari setari) {
        String autobuz = t.etaPrimulPrins() == null
                ? "niciun autobuz pe care să-l prinzi acum"
                : "autobuzul vine în " + minute(t.etaPrimulPrins()) + ", ajungi la " + ORA_SCURTA.format(sosire);
        return String.format("linia %s spre %s: urci la %s (%s pe jos), cobori la %s după %.1f km; %s",
                o.linie().line(), o.sens().headsign(), o.urcare().name(), minute(o.mersPanaS()),
                o.coborare().name(), o.drumM() / 1000, autobuz);
    }

    // ------------------------------------------------------------------ urmărirea

    /** Threadul "tracker" (aici, main): golește coada, recalculează cel mult o dată pe secundă. */
    private static void urmareste(Selectie sel, Tracker tracker, Sursa sursa, Supplier<Locatie> locatie,
                                  Config config, BlockingQueue<Object> coada) throws Exception {
        System.out.printf("%nUrmăresc linia %s (route_id %d), sensul spre %s (%s), stația %s (id %d), mers pe jos %s.%n",
                sel.linie().line(), sel.linie().routeId(), sel.sens().headsign(), sel.sens().tripId(),
                sel.statie().name(), sel.statie().id(),
                locatie != null ? "calculat din locație" : sel.mersMin() + " min");

        MqttBridge mqtt = new MqttBridge(config.mqttBroker(), config.mqttStudent(), coada::offer);
        mqtt.conecteaza();
        Runtime.getRuntime().addShutdownHook(new Thread(mqtt::close, "oprire-mqtt"));

        int routeId = sel.linie().routeId();
        String linie = sel.linie().line();
        String statie = sel.statie().name();
        Rezultat afisat = null, publicat = null;
        long urmatoareaEvaluare = 0, ultimaAfisare = 0, ultimaPublicare = 0;
        Long ultimulUrcat = null;
        for (;;) {
            Object ev = coada.poll(200, TimeUnit.MILLISECONDS);
            if (ev instanceof Vehicul v) {
                if (v.routeId() == routeId) tracker.actualizeaza(v);  // în modul destinație vin și alte linii
            } else if (ev instanceof MqttBridge.PlacaOnline p) {
                System.out.println(p.online() ? "[placa] ONLINE" : "[placa] OFFLINE (fără conexiune)");
            } else if (ev instanceof MqttBridge.Urcat u) {
                // retrimiterile aceleiași apăsări au același ts: confirmăm din nou, fără să anunțăm iar
                if (ultimulUrcat == null || u.ts() != ultimulUrcat) {
                    ultimulUrcat = u.ts();
                    System.out.println();
                    System.out.println("**************** URCAT: confirmat de la placă, urmărirea s-a oprit ****************");
                    System.out.println();
                }
                Rezultat r = tracker.confirma();
                mqtt.confirmaUrcat();
                mqtt.publicaStare(r, linie, statie);
                publicat = r;
                ultimaPublicare = System.currentTimeMillis();
            }

            long acum = System.currentTimeMillis();
            if (acum < urmatoareaEvaluare) continue;
            urmatoareaEvaluare = acum + 1000;

            if (locatie != null) tracker.seteazaMers(mersPanaLa(locatie.get(), sel.statie()));
            Rezultat r = tracker.evalueaza(sursa.feed().conectat());
            // spre placă: la schimbarea stării / autobuzului și heartbeat la 10 s (watchdog-ul plăcii are 30 s)
            if (publicat == null || r.stare() != publicat.stare() || !Objects.equals(r.vehicul(), publicat.vehicul())
                    || acum - ultimaPublicare >= 10_000) {
                mqtt.publicaStare(r, linie, statie);
                publicat = r;
                ultimaPublicare = acum;
            }
            boolean schimbat = afisat == null || r.stare() != afisat.stare()
                    || !Objects.equals(r.vehicul(), afisat.vehicul()) || r.candidati() != afisat.candidati();
            if (schimbat || acum - ultimaAfisare >= 5000) {
                String mers = locatie != null ? "  | tu: " + minute(tracker.mersS()) + " pe jos" : "";
                System.out.println(linieStare(r, sursa.ceas()) + mers);
                afisat = r;
                ultimaAfisare = acum;
            }
        }
    }

    private static final ZoneId CLUJ = ZoneId.of("Europe/Bucharest");
    private static final DateTimeFormatter ORA = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(CLUJ);
    private static final DateTimeFormatter ORA_SCURTA = DateTimeFormatter.ofPattern("HH:mm").withZone(CLUJ);

    static String linieStare(Rezultat r, Clock ceas) {
        String autobuz = r.vehicul() == null ? "niciun autobuz"
                : String.format("autobuz %-5s %5s m pe drum  ETA %s", r.vehicul(),
                        r.restM() == null ? "?" : r.restM(),
                        r.etaS() == null ? "?" : String.format("%d:%02d", r.etaS() / 60, r.etaS() % 60));
        return String.format("[%s] %-11s  %s  (%d candidați, %d vehicule active pe linie)",
                ORA.format(ceas.instant()), r.stare(), autobuz, r.candidati(), r.activi());
    }

    /** 437 s -> "7 min"; sub un minut -> "<1 min". */
    static String minute(double secunde) {
        long m = Math.round(secunde / 60);
        return m < 1 ? "<1 min" : m + " min";
    }

    /** Pornire fără meniu: --route-id, --trip-id, --stop-id (și opțional --walk-min sau --de-la). */
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
        // cu --de-la și fără --walk-min, timpul de mers se calculează din locație
        int mers = arg.containsKey("walk-min") ? Integer.parseInt(arg.get("walk-min"))
                : arg.containsKey("de-la") ? Meniu.DIN_LOCATIE : 5;
        return new Selectie(linie, sens, statie, mers);
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
