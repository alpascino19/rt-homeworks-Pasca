package ro.utcn.ssatr.tracker;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

import ro.utcn.ssatr.tracker.CtpApiClient.Linie;
import ro.utcn.ssatr.tracker.Meniu.Selectie;

/** Tracker personal de autobuz: alegerea liniei, sensului și stației. */
public final class Main {

    public static void main(String[] args) throws Exception {
        Config config = new Config(Path.of("config.properties"));
        CtpApiClient api;
        try {
            api = new CtpApiClient(config.apiUrl(), config.apiKey());
        } catch (IllegalStateException e) {  // configurare lipsă: mesaj, nu stack trace
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("Citesc liniile de la " + config.apiUrl() + " ...");
        List<Linie> linii;
        try {
            linii = api.linii();
        } catch (CtpApiClient.ApiException e) {
            System.err.println("Eroare API " + e.getMessage());
            System.exit(1);
            return;
        }
        System.out.println(linii.size() + " linii.");

        // tastatura se citește în codificarea consolei (pe Windows nu e UTF-8)
        Charset cs = System.console() != null ? System.console().charset() : Charset.defaultCharset();
        Meniu meniu = new Meniu(new BufferedReader(new InputStreamReader(System.in, cs)), System.out);
        Selectie sel = meniu.ruleaza(linii, api::linie);

        System.out.printf("%nUrmăresc linia %s (route_id %d), sensul spre %s (%s), stația %s (id %d), mers pe jos %d min.%n",
                sel.linie().line(), sel.linie().routeId(), sel.sens().headsign(), sel.sens().tripId(),
                sel.statie().name(), sel.statie().id(), sel.mersMin());
    }
}
