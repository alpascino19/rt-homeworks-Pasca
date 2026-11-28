package ro.utcn.ssatr.tracker;

import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ro.utcn.ssatr.tracker.CtpApiClient.Statie;
import ro.utcn.ssatr.tracker.CtpApiClient.TraseuSens;
import ro.utcn.ssatr.tracker.ServerLocatie.Locatie;
import ro.utcn.ssatr.tracker.Tracker.Rezultat;
import ro.utcn.ssatr.tracker.Tracker.VehiculHarta;

/**
 * JSON-ul paginii "călătoria mea" (servit de ServerLocatie pe /traseu și /stare).
 * Se construiește pe threadul tracker; pagina doar îl afișează.
 */
public final class Harta {

    private Harta() { }

    /** Traseul urmărit (static): linia, sensul, punctele, stațiile, stația de urcare și, opțional, de coborâre. */
    public static String traseu(String linie, TraseuSens sens, int iUrcare, Integer iCoborare) {
        ObjectNode n = CtpApiClient.JSON.createObjectNode();
        n.put("linie", linie);
        n.put("spre", sens.headsign());
        ArrayNode puncte = n.putArray("puncte");
        for (double[] p : sens.shape()) puncte.addArray().add(p[0]).add(p[1]);
        ArrayNode statii = n.putArray("statii");
        for (Statie s : sens.stops()) statii.addObject().put("nume", s.name()).put("lat", s.lat()).put("lon", s.lon());
        n.put("urcare", iUrcare);
        if (iCoborare != null) n.put("coborare", iCoborare);
        return n.toString();
    }

    /** Starea curentă: ce e pe LED, ETA, vehiculele liniei, locația utilizatorului și timpul lui de mers. */
    public static String urmarire(Rezultat r, List<VehiculHarta> vehicule, Locatie eu, boolean locatieFixa,
                                  double mersS, boolean placaOnline) {
        ObjectNode n = CtpApiClient.JSON.createObjectNode();
        n.put("faza", "urmarire");
        n.put("locatie_fixa", locatieFixa);
        n.put("stare", r.stare().name());
        if (r.etaS() != null) n.put("eta_s", r.etaS());
        if (r.restM() != null) n.put("rest_m", r.restM());
        if (r.vehicul() != null) n.put("vehicul", r.vehicul());
        n.put("mers_s", Math.round(mersS));
        n.put("candidati", r.candidati());
        n.put("placa_online", placaOnline);
        ArrayNode v = n.putArray("vehicule");
        for (VehiculHarta x : vehicule) {
            v.addObject().put("label", x.label()).put("lat", x.lat()).put("lon", x.lon())
                    .put("candidat", x.candidat()).put("urmarit", x.urmarit()).put("vechime_s", x.vechimeS());
        }
        if (eu != null) n.putObject("eu").put("lat", eu.lat()).put("lon", eu.lon()).put("precizie_m", eu.precizieM());
        return n.toString();
    }

    /** O fază fără urmărire (așteptarea locației, ascultarea autobuzelor, alegerea variantei). */
    public static String mesaj(String faza, String text, List<String> optiuni, Locatie eu, boolean locatieFixa) {
        ObjectNode n = CtpApiClient.JSON.createObjectNode();
        n.put("faza", faza);
        n.put("locatie_fixa", locatieFixa);
        n.put("mesaj", text);
        ArrayNode o = n.putArray("optiuni");
        optiuni.forEach(o::add);
        if (eu != null) n.putObject("eu").put("lat", eu.lat()).put("lon", eu.lon()).put("precizie_m", eu.precizieM());
        return n.toString();
    }
}
