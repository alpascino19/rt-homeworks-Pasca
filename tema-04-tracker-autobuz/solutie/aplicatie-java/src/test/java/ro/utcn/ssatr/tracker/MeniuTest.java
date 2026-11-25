package ro.utcn.ssatr.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ro.utcn.ssatr.tracker.CtpApiClient.DetaliiLinie;
import ro.utcn.ssatr.tracker.CtpApiClient.Linie;
import ro.utcn.ssatr.tracker.CtpApiClient.Sens;
import ro.utcn.ssatr.tracker.CtpApiClient.Statie;
import ro.utcn.ssatr.tracker.CtpApiClient.TraseuSens;

class MeniuTest {

    private static Meniu meniu(String intrare) {
        return new Meniu(new BufferedReader(new StringReader(intrare)),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    @Test
    void normalizeazaDiacriticele() {
        assertEquals("marasti", Meniu.normalizeaza("Mărăști"));
        assertEquals("p-ta 1 mai", Meniu.normalizeaza("P-ța 1 Mai"));
    }

    /** 40 de linii (listă lungă): "25" caută linia 25, nu alege al 25-lea element. */
    @Test
    void numarulInListaLungaEsteCautare() throws Exception {
        List<String> linii = new ArrayList<>();
        for (int i = 30; i < 70; i++) linii.add("linia M" + i);  // niciuna nu conține "25"
        linii.add("linia 25");

        String ales = meniu("25\n").alege("Linia", linii, s -> s);

        assertEquals("linia 25", ales);
    }

    @Test
    void douaLiniiCuAcelasiNumarSeDeosebescDupaNume() throws Exception {
        List<String> linii = List.of("12 autobuz Mănăștur", "12 autobuz Zorilor", "3 tramvai X");

        String ales = meniu("12\n2\n").alege("Linia", linii, s -> s);

        assertEquals("12 autobuz Zorilor", ales);
    }

    @Test
    void fluxCompletCuTimpImplicit() throws Exception {
        Statie a = new Statie(1, "Disp. Bucium", 46.75, 23.54);
        Statie b = new Statie(2, "Mărăști", 46.77, 23.61);
        TraseuSens dus = new TraseuSens("1_0", 0, "P-ța 1 Mai",
                List.of(new double[] {46.75, 23.54}, new double[] {46.77, 23.61}), List.of(a, b));
        TraseuSens gol = new TraseuSens("1_1", 1, "Disp. Bucium", List.of(), List.of());
        Linie linie = new Linie(1, "1", "Bucium - 1 Mai", "troleibuz",
                List.of(new Sens("1_0", 0, "P-ța 1 Mai"), new Sens("1_1", 1, "Disp. Bucium")));

        // linia aleasă direct (una singură), sensul gol e eliminat, "marasti" găsește stația, Enter = 5 min
        Meniu.Selectie s = meniu("marasti\n\n").ruleaza(List.of(linie),
                id -> new DetaliiLinie(1, "1", List.of(dus, gol)));

        assertEquals("1_0", s.sens().tripId());
        assertEquals(2, s.statie().id());
        assertEquals(5, s.mersMin());
    }
}
