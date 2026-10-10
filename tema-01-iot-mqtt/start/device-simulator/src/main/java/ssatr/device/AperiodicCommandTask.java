package ssatr.device;

import java.util.concurrent.BlockingQueue;
import java.util.Locale;
/**
 * TASK APERIODIC: execută comenzile primite de la business service.
 *
 * Comenzile sosesc oricând, pe MQTT, fără un interval minim între ele, și sunt
 * puse în coada commands. Fiecare comandă eliberează un job al taskului.
 * Comenzi care trebuie suportate:
 *
 *   SET_INTERVAL <ms>       schimbă perioada T a taskului periodic
 *   SET_THRESHOLD <valoare> schimbă pragul de alarmă
 *   RESET                   readuce senzorul la valoarea inițială
 *   STATUS                  raportează perioada și pragul curente
 *
 * După executarea fiecărei comenzi, device-ul publică pe Config.statusTopic(deviceId)
 * o confirmare: "deviceId;timestamp;text", de exemplu
 * "device-1;1727600000000;OK SET_INTERVAL 1000".
 */
public class AperiodicCommandTask implements Runnable {

    /** Limitele acceptate pentru SET_INTERVAL. */
    static final long MIN_PERIOD_MS = 100;
    static final long MAX_PERIOD_MS = 60_000;
    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<String> commands;
    private final PeriodicTelemetryTask periodic;

    public AperiodicCommandTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                BlockingQueue<String> commands, PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.commands = commands;
        this.periodic = periodic;
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String command = commands.take();   // eliberare: sosirea unei comenzi
                long start = System.nanoTime();

                String reply;
                try {
                    reply = execute(command);
                } catch (IllegalArgumentException e) {
                    reply = "ERROR " + e.getMessage();
                } catch (RuntimeException e) {
                    // orice altă eroare: raportăm, dar taskul continuă
                    reply = "ERROR " + e.getClass().getSimpleName();
                }

                mqtt.publish(Config.statusTopic(deviceId), deviceId + ";" + System.currentTimeMillis() + ";" + reply);

                System.out.printf(Locale.US, "[aperiodic] comanda \"%s\" -> %s  (C = %.3f ms, în coadă: %d)%n",
                        command, reply, (System.nanoTime() - start) / 1_000_000.0, commands.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Execută o comandă și întoarce textul confirmării ("OK ..."). */
    private String execute(String command) {
        String[] parts = command.trim().split("\\s+");
        String name = parts[0].toUpperCase(Locale.ROOT);

        switch (name) {
            case "SET_INTERVAL": {
                String arg = singleArgument(parts, "SET_INTERVAL <ms>");
                long ms;
                try {
                    ms = Long.parseLong(arg);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("SET_INTERVAL: '" + arg + "' nu este un numar intreg");
                }
                if (ms < MIN_PERIOD_MS || ms > MAX_PERIOD_MS) {
                    throw new IllegalArgumentException("SET_INTERVAL: perioada trebuie sa fie intre "
                            + MIN_PERIOD_MS + " si " + MAX_PERIOD_MS + " ms");
                }
                periodic.setPeriodMs(ms);
                return "OK SET_INTERVAL " + ms;
            }
            case "SET_THRESHOLD": {
                String arg = singleArgument(parts, "SET_THRESHOLD <valoare>");
                double value;
                try {
                    value = Double.parseDouble(arg.replace(',', '.'));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("SET_THRESHOLD: '" + arg + "' nu este un numar");
                }
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("SET_THRESHOLD: valoare invalida");
                }
                periodic.setThreshold(value);
                return String.format(Locale.US, "OK SET_THRESHOLD %.2f", value);
            }
            case "RESET":
                noArguments(parts, "RESET");
                sensor.reset();
                return "OK RESET";
            case "STATUS":
                noArguments(parts, "STATUS");
                return String.format(Locale.US, "OK STATUS interval=%d threshold=%.2f",
                        periodic.getPeriodMs(), periodic.getThreshold());
            default:
                throw new IllegalArgumentException("comanda necunoscuta: " + (name.isEmpty() ? "(goala)" : name));
        }
    }

    private static String singleArgument(String[] parts, String usage) {
        if (parts.length != 2) {
            throw new IllegalArgumentException("format corect: " + usage);
        }
        return parts[1];
    }

    private static void noArguments(String[] parts, String usage) {
        if (parts.length != 1) {
            throw new IllegalArgumentException("format corect: " + usage + " (fara parametri)");
        }
    }
}
