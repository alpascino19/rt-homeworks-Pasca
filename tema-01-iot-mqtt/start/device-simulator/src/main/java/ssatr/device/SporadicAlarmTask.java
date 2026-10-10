package ssatr.device;

import java.util.concurrent.BlockingQueue;
import java.util.Locale;
/**
 * TASK SPORADIC: tratează alarmele de temperatură.
 *
 * Evenimentele sosesc în coada alarmEvents (puse de taskul periodic).
 * Intervalul minim între sosiri este MIN_INTERARRIVAL_MS: între două eliberări
 * ale taskului (două alarme publicate) trebuie să treacă cel puțin atât.
 * Intervalul este impus de cod: evenimentele care sosesc mai des sunt
 * respinse, ca să nu fie inundat brokerul.
 */
public class SporadicAlarmTask implements Runnable {

    static final long MIN_INTERARRIVAL_MS = 5000;
    /** Termenul-limită relativ D al unui job (de la sosirea evenimentului până la publicare). */
    static final long DEADLINE_MS = 500;

    private final String deviceId;
    private final MqttConnection mqtt;
    private final BlockingQueue<AlarmEvent> alarmEvents;

    public SporadicAlarmTask(String deviceId, MqttConnection mqtt, BlockingQueue<AlarmEvent> alarmEvents) {
        this.deviceId = deviceId;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        boolean released = false;   // a existat deja o eliberare?
        long lastRelease = 0;       // nanoTime-ul sosirii ultimului eveniment acceptat
        int accepted = 0;
        int rejected = 0;

        try {
            while (!Thread.currentThread().isInterrupted()) {
                AlarmEvent e = alarmEvents.take();   // așteaptă (blocat) următorul eveniment

                long sinceLastMs = (e.arrivalNanos() - lastRelease) / 1_000_000L;
                if (released && sinceLastMs < MIN_INTERARRIVAL_MS) {
                    rejected++;
                    System.out.printf(Locale.US,
                            "[sporadic]  alarmă %.2f RESPINSĂ: au trecut doar %d ms de la eliberarea precedentă (minim %d ms)  [acceptate %d, respinse %d]%n",
                            e.temperature(), sinceLastMs, MIN_INTERARRIVAL_MS, accepted, rejected);
                    continue;
                }

                // eliberare: r = momentul sosirii evenimentului
                released = true;
                lastRelease = e.arrivalNanos();
                accepted++;

                // calculul jobului: publicarea alarmei
                String message = deviceId + ";" + e.timestamp() + ";"
                        + String.format(Locale.US, "%.2f;%.2f", e.temperature(), e.threshold());
                mqtt.publish(Config.alarmTopic(deviceId), message);


                double responseMs = (System.nanoTime() - e.arrivalNanos()) / 1_000_000.0;
                System.out.printf(Locale.US,
                        "[sporadic]  ALARMĂ publicată: %.2f > %.2f  R = %.3f ms -> %s (D = %d ms)  [acceptate %d, respinse %d]%n",
                        e.temperature(), e.threshold(), responseMs,
                        responseMs <= DEADLINE_MS ? "termen respectat" : "TERMEN RATAT", DEADLINE_MS,
                        accepted, rejected);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
