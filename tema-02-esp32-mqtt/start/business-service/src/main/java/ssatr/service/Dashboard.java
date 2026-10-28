package ssatr.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ultima stare cunoscută a fiecărui device. Este actualizată de thread-urile
 * MessageProcessor și citită de CommandConsole, deci metodele sunt synchronized.
 */
public class Dashboard {

    /** Un device este marcat INACTIV dacă nu a trimis nimic de atâtea perioade. */
    private static final int OFFLINE_PERIODS = 3;
    private static final long OFFLINE_DEFAULT_MS = 10_000;

    /** Starea unui device. Câmpurile se modifică doar sub lacătul Dashboard-ului. */
    private static final class DeviceState {
        final String deviceId;
        double lastTemperature = Double.NaN;
        long lastTelemetryDeviceTime = 0;  // timestamp-ul din ultimul mesaj de telemetrie
        long estimatedPeriodMs = 0;        // diferența dintre ultimele două timestamp-uri
        long lastSeenLocal = 0;            // când a sosit ultimul mesaj (ceasul serviciului)
        int telemetryCount = 0;
        int alarmCount = 0;
        double lastAlarmTemperature = Double.NaN;
        String lastStatus = "-";

        DeviceState(String deviceId) {
            this.deviceId = deviceId;
        }

        DeviceState copy() {
            DeviceState c = new DeviceState(deviceId);
            c.lastTemperature = lastTemperature;
            c.lastTelemetryDeviceTime = lastTelemetryDeviceTime;
            c.estimatedPeriodMs = estimatedPeriodMs;
            c.lastSeenLocal = lastSeenLocal;
            c.telemetryCount = telemetryCount;
            c.alarmCount = alarmCount;
            c.lastAlarmTemperature = lastAlarmTemperature;
            c.lastStatus = lastStatus;
            return c;
        }
    }

    // TreeMap: device-urile apar sortate după ID în tabel
    private final Map<String, DeviceState> devices = new TreeMap<>();

    private DeviceState state(String deviceId) {
        DeviceState s = devices.computeIfAbsent(deviceId, DeviceState::new);
        s.lastSeenLocal = System.currentTimeMillis();
        return s;
    }

    public synchronized void updateTelemetry(String deviceId, long timestamp, double temperature) {
        DeviceState s = state(deviceId);
        if (s.lastTelemetryDeviceTime > 0 && timestamp > s.lastTelemetryDeviceTime) {
            s.estimatedPeriodMs = timestamp - s.lastTelemetryDeviceTime;
        }
        s.lastTelemetryDeviceTime = timestamp;
        s.lastTemperature = temperature;
        s.telemetryCount++;
    }

    public synchronized void recordAlarm(String deviceId, double temperature) {
        DeviceState s = state(deviceId);
        s.alarmCount++;
        s.lastAlarmTemperature = temperature;
    }

    public synchronized void recordStatus(String deviceId, String text) {
        state(deviceId).lastStatus = text;
    }

    public synchronized boolean knows(String deviceId) {
        return devices.containsKey(deviceId);
    }

    /** Afișează un tabel cu starea tuturor device-urilor. */
    public void print() {
        // copiem starea sub lacăt, ca afișarea să nu blocheze procesarea mesajelor
        List<DeviceState> snapshot = new ArrayList<>();
        synchronized (this) {
            for (DeviceState s : devices.values()) {
                snapshot.add(s.copy());
            }
        }

        if (snapshot.isEmpty()) {
            System.out.println("Niciun device nu a trimis date încă.");
            return;
        }

        long now = System.currentTimeMillis();
        String format = "%-12s %8s %-15s %-8s %7s %8s %8s  %s%n";
        System.out.println();
        System.out.printf(format, "DEVICE", "TEMP", "ULTIMUL MESAJ", "STARE", "T (ms)", "MESAJE", "ALARME", "ULTIMA CONFIRMARE");
        System.out.println("-".repeat(100));
        for (DeviceState s : snapshot) {
            long age = now - s.lastSeenLocal;
            long offlineAfter = s.estimatedPeriodMs > 0 ? OFFLINE_PERIODS * s.estimatedPeriodMs : OFFLINE_DEFAULT_MS;
            System.out.printf(format,
                    s.deviceId,
                    Double.isNaN(s.lastTemperature) ? "-" : String.format(Locale.US, "%.2f", s.lastTemperature),
                    "acum " + formatAge(age),
                    age > offlineAfter ? "INACTIV" : "activ",
                    s.estimatedPeriodMs > 0 ? String.valueOf(s.estimatedPeriodMs) : "-",
                    s.telemetryCount,
                    s.alarmCount,
                    s.lastStatus);
        }
        System.out.println();
    }

    private static String formatAge(long ms) {
        long s = ms / 1000;
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return (s / 60) + " min " + (s % 60) + " s";
        }
        return (s / 3600) + " h " + (s % 3600 / 60) + " min";
    }
}