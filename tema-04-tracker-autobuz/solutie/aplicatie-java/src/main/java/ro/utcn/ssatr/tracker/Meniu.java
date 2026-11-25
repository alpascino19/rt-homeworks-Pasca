package ro.utcn.ssatr.tracker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

import ro.utcn.ssatr.tracker.CtpApiClient.DetaliiLinie;
import ro.utcn.ssatr.tracker.CtpApiClient.Linie;
import ro.utcn.ssatr.tracker.CtpApiClient.Statie;
import ro.utcn.ssatr.tracker.CtpApiClient.TraseuSens;

/** Meniul de consolă: linia, sensul, stația și timpul de mers pe jos. */
public final class Meniu {

    /** Ce a ales utilizatorul. */
    public record Selectie(Linie linie, TraseuSens sens, Statie statie, int mersMin) { }

    public interface IncarcaLinie {
        DetaliiLinie incarca(int routeId) throws IOException, InterruptedException;
    }

    private final BufferedReader in;
    private final PrintStream out;

    public Meniu(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    public Selectie ruleaza(List<Linie> linii, IncarcaLinie api) throws IOException, InterruptedException {
        for (;;) {
            Linie linie = alege("Linia", linii,
                    l -> String.format("%-5s %-10s %s", l.line(), l.type(), l.name()));

            // sensurile fără traseu sau fără stații nu pot fi urmărite
            List<TraseuSens> sensuri = api.incarca(linie.routeId()).directions().stream()
                    .filter(s -> s.shape().size() >= 2 && !s.stops().isEmpty())
                    .collect(Collectors.toList());
            if (sensuri.isEmpty()) {
                out.println("Linia " + linie.line() + " nu are traseu sau stații în API; alegeți alta.");
                continue;
            }
            TraseuSens sens = alege("Sensul", sensuri,
                    s -> "spre " + s.headsign() + "  (" + s.stops().size() + " stații)");
            Statie statie = alege("Stația", sens.stops(), Statie::name);
            int mers = citesteMinute("Timpul de mers pe jos până la stație, în minute [5]: ", 5);
            return new Selectie(linie, sens, statie, mers);
        }
    }

    /**
     * Listă cu căutare: textul introdus filtrează (fără diacritice, fără majuscule),
     * un număr din listă alege elementul. O singură potrivire se alege direct.
     */
    <T> T alege(String titlu, List<T> elemente, Function<T, String> afisare) throws IOException {
        String cautare = "";
        // O listă lungă nu se afișează din prima: primul text e o căutare, nu un număr
        // de ordine (altfel "25" ar alege al 25-lea element, nu linia 25).
        boolean listat = elemente.size() <= LISTA_SCURTA;
        for (;;) {
            String c = normalizeaza(cautare);
            List<T> gasite = elemente.stream()
                    .filter(e -> normalizeaza(afisare.apply(e)).contains(c))
                    .collect(Collectors.toList());

            if (gasite.size() == 1) {
                out.println(titlu + ": " + afisare.apply(gasite.get(0)));
                return gasite.get(0);
            }
            if (gasite.isEmpty()) {
                out.println("Nimic găsit pentru \"" + cautare + "\".");
                listat = false;
            } else if (listat) {
                out.println();
                out.println(titlu + (cautare.isEmpty() ? "" : " (\"" + cautare + "\")") + ":");
                for (int i = 0; i < gasite.size(); i++) {
                    out.printf("  %3d. %s%n", i + 1, afisare.apply(gasite.get(i)));
                }
            }
            String raspuns = citesteLinie(listat
                    ? titlu + ": numărul din listă sau text de căutat (Enter = toate): "
                    : titlu + ": text de căutat (Enter = toate " + elemente.size() + "): ");
            if (listat) {
                try {
                    int nr = Integer.parseInt(raspuns);
                    if (nr >= 1 && nr <= gasite.size()) {
                        out.println(titlu + ": " + afisare.apply(gasite.get(nr - 1)));
                        return gasite.get(nr - 1);
                    }
                } catch (NumberFormatException e) {
                    // nu e număr de ordine: e text de căutat
                }
            }
            cautare = raspuns;
            listat = true;
        }
    }

    private static final int LISTA_SCURTA = 30;

    int citesteMinute(String prompt, int implicit) throws IOException {
        for (;;) {
            String r = citesteLinie(prompt);
            if (r.isEmpty()) return implicit;
            try {
                int m = Integer.parseInt(r);
                if (m >= 0 && m <= 60) return m;
            } catch (NumberFormatException e) {
                // cerem din nou
            }
            out.println("Introduceți un număr de minute între 0 și 60.");
        }
    }

    private String citesteLinie(String prompt) throws IOException {
        out.print(prompt);
        out.flush();
        String r = in.readLine();
        if (r == null) throw new IOException("intrarea s-a închis");
        return r.trim();
    }

    /** "Mărăști" -> "marasti", ca să se poată căuta și fără diacritice. */
    static String normalizeaza(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
