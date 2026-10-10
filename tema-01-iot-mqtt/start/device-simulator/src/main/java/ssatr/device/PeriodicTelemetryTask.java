package ssatr.device;

import java.util.concurrent.BlockingQueue;
import java.util.Locale;
/**
 * TASK PERIODIC, cu perioada T = periodMs: fiecare job citește senzorul și
 * publică valoarea pe topic-ul de telemetrie.
 *
 * Dacă temperatura depășește pragul, taskul NU tratează el alarma, ci doar
 * semnalează un eveniment în coada alarmEvents. Alarma este tratată de
 * taskul sporadic.
 */
public class PeriodicTelemetryTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<AlarmEvent> alarmEvents;

    // volatile: sunt citite aici, dar modificate de taskul aperiodic (alt thread)
    private volatile long periodMs = 2000;
    private volatile double threshold = 28.0;
    private final Object timer = new Object();
    public PeriodicTelemetryTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                 BlockingQueue<AlarmEvent> alarmEvents) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        long release = System.nanoTime();   // r_0: prima eliberare este chiar acum
        long k = 0;
        double minLateness = Double.MAX_VALUE;
        double maxLateness = 0;
        long jobPeriod = periodMs;          // T cu care a fost eliberat jobul curent

        try {
            while (!Thread.currentThread().isInterrupted()) {
                // s_k: pornirea jobului; L_k = s_k - r_k (întârzierea față de grilă)
                long start = System.nanoTime();
                if (periodMs != jobPeriod) {
                    // perioada s-a schimbat (SET_INTERVAL): grilă nouă, jitterul se măsoară din nou
                    jobPeriod = periodMs;
                    minLateness = Double.MAX_VALUE;
                    maxLateness = 0;
                }
                double latenessMs = (start - release) / 1_000_000.0;
                minLateness = Math.min(minLateness, latenessMs);
                maxLateness = Math.max(maxLateness, latenessMs);

                // calculul jobului
                double temperature = sensor.read();
                long timestamp = System.currentTimeMillis();
                String message = deviceId + ";" + timestamp + ";" + String.format(Locale.US, "%.2f", temperature);
                mqtt.publish(Config.telemetryTopic(deviceId), message);

                double currentThreshold = threshold;
                boolean alarm = temperature > currentThreshold;
                if (alarm) {
                    // doar semnalăm evenimentul; îl tratează taskul sporadic
                    alarmEvents.offer(new AlarmEvent(temperature, currentThreshold, timestamp, System.nanoTime()));
                }

                double computationMs = (System.nanoTime() - start) / 1_000_000.0;
                System.out.printf(Locale.US,
                        "[periodic]  job #%-4d T = %5d ms  L = %7.3f ms  C = %6.3f ms  jitter = %7.3f ms  temp = %6.2f%s%n",
                        k, periodMs, latenessMs, computationMs, maxLateness - minLateness, temperature,
                        alarm ? "  > prag " + String.format(Locale.US, "%.2f", currentThreshold) + " -> eveniment alarmă" : "");

                // eliberarea următoare, pe grilă: r_{k+1} = r_k + T
                release = waitForNextRelease(release);
                k++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Așteptare absolută până la r_{k+1} = r_k + T.
     * Dacă SET_INTERVAL schimbă perioada în timpul așteptării, eliberarea se recalculează.
     * Dacă jobul a depășit perioade întregi, eliberările ratate se sar.
     */
    private long waitForNextRelease(long previousRelease) throws InterruptedException {
        long initialPeriod = periodMs;
        synchronized (timer) {
            while (true) {
                long periodNanos = periodMs * 1_000_000L;
                long next = previousRelease + periodNanos;
                long now = System.nanoTime();
                long remaining = next - now;

                if (remaining <= 0 && periodMs != initialPeriod) {
                    // perioada a fost schimbată în timpul așteptării: grilă nouă, cu r_0' = acum
                    return now;
                }
                if (remaining <= 0) {
                    long missed = -remaining / periodNanos;   // perioade întregi ratate
                    if (missed > 0) {
                        System.out.printf("[periodic]  DEPĂȘIRE: %d eliberări ratate, se trece la următoarea de pe grilă%n", missed);
                        next += missed * periodNanos;
                    }
                    return next;
                }
                // wait poate reveni mai devreme (notify, trezire falsă): bucla verifică din nou
                timer.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            }
        }
    }

    public void setPeriodMs(long periodMs) {
        this.periodMs = periodMs;
        synchronized (timer) {
            timer.notifyAll();   // taskul recalculează eliberarea următoare cu noua perioadă

        }}

    public void setThreshold(double threshold) {
        this.threshold = threshold;
       }

    public long getPeriodMs() {
        return periodMs;
    }

    public double getThreshold() {
        return threshold;
    }
}
