package ro.utcn.ssatr.tracker;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configurația și secretele: din config.properties (ignorat de git) și din
 * variabile de mediu, care au prioritate. Vezi config.example.properties.
 */
public final class Config {

    private final Properties props = new Properties();

    public Config(Path file) throws IOException {
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(r);
            }
        }
    }

    /** Valoarea din variabila de mediu env, altfel din fișier, altfel def. */
    private String get(String key, String env, String def) {
        String v = env == null ? null : System.getenv(env);
        if (v == null || v.isBlank()) v = props.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    /** Ca get(), dar lipsa valorii este o eroare de configurare. */
    private String required(String key, String env) {
        String v = get(key, env, null);
        if (v == null) {
            throw new IllegalStateException("Lipsește '" + key + "' (sau variabila de mediu " + env
                    + "): copiați config.example.properties ca config.properties și completați-l.");
        }
        return v;
    }

    public String apiUrl()       { return get("api.url", "URL_APLICATIE", "https://control.aut.utcluj.ro/publictransport"); }
    public String apiKey()       { return required("api.key", "CHEIE_API"); }

    public String rabbitHost()   { return get("rabbit.host", "RABBIT_HOST", "control.aut.utcluj.ro"); }
    public int rabbitPort()      { return Integer.parseInt(get("rabbit.port", "RABBIT_PORT", "11156")); }
    public String rabbitUser()   { return required("rabbit.user", "RABBIT_USER"); }
    public String rabbitPass()   { return required("rabbit.pass", "RABBIT_PASS"); }

    public String mqttBroker()   { return get("mqtt.broker", "MQTT_BROKER", "tcp://control.aut.utcluj.ro:11188"); }
    public String mqttStudent()  { return get("mqtt.student", "MQTT_STUDENT", "Pasca-Alex"); }

    /** Portul paginii locale prin care browserul trimite locația. */
    public int locatiePort()     { return Integer.parseInt(get("locatie.port", "LOCATIE_PORT", "8080")); }

    /** Pragurile stărilor și viteza implicită; lipsă = valorile din Tracker.Setari.IMPLICITE. */
    public Tracker.Setari setariTracker() {
        MasinaStari.Praguri d = MasinaStari.Praguri.IMPLICITE;
        return new Tracker.Setari(new MasinaStari.Praguri(
                numar("prag.pleaca_s", d.pleacaS()),
                numar("prag.in_statie_m", d.inStatieM()),
                numar("histerezis.s", d.histerezisS()),
                numar("histerezis.m", d.histerezisM())),
                numar("viteza.implicita_mps", Tracker.Setari.IMPLICITE.vitezaImplicita()));
    }

    private double numar(String key, double def) {
        return Double.parseDouble(get(key, null, String.valueOf(def)));
    }
}
