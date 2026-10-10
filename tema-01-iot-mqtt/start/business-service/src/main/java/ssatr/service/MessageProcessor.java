package ssatr.service;

import java.util.concurrent.BlockingQueue;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
/**
 * Preia mesajele de un anumit tip ("telemetry", "alarm" sau "status") din coada
 * inbox, le salvează și actualizează dashboard-ul.
 *
 * Formatul mesajelor (câmpuri separate prin ";"):
 *   telemetry:  deviceId;timestamp;temperatura
 *   alarm:      deviceId;timestamp;temperatura;prag
 *   status:     deviceId;timestamp;text
 */
public class MessageProcessor implements Runnable {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private final String kind;
    private final BlockingQueue<String> inbox;
    private final CsvStorage storage;
    private final Dashboard dashboard;

    public MessageProcessor(String kind, BlockingQueue<String> inbox, CsvStorage storage, Dashboard dashboard) {
        this.kind = kind;
        this.inbox = inbox;
        this.storage = storage;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String message = inbox.take();

                // fiecare mesaj se salvează exact cum a sosit
                storage.append(kind, message);

                try {
                    process(message);
                } catch (RuntimeException e) {
                    // un mesaj greșit nu oprește thread-ul
                    System.out.println("[" + kind + "] mesaj ignorat (" + e.getMessage() + "): " + message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void process(String message) {
        switch (kind) {
            case "telemetry": {
                String[] f = fields(message, 3);
                dashboard.updateTelemetry(f[0], parseLong(f[1]), parseDouble(f[2], "temperatura"));
                break;
            }
            case "alarm": {
                String[] f = fields(message, 4);
                long timestamp = parseLong(f[1]);
                double temperature = parseDouble(f[2], "temperatura");
                double threshold = parseDouble(f[3], "prag");
                dashboard.recordAlarm(f[0], temperature);
                System.out.printf(Locale.US, "%n*** ALARMĂ %s  %s: %.2f °C > prag %.2f °C%n",
                        TIME.format(Instant.ofEpochMilli(timestamp)), f[0], temperature, threshold);
                break;
            }
            case "status": {
                // textul poate conține spații; limităm split-ul la 3 câmpuri
                String[] f = fields(message, 3);
                long timestamp = parseLong(f[1]);
                dashboard.recordStatus(f[0], f[2]);
                System.out.printf("%n<<< %s  %s: %s%n", TIME.format(Instant.ofEpochMilli(timestamp)), f[0], f[2]);
                break;
            }
            default:
                throw new IllegalStateException("tip de mesaj necunoscut: " + kind);
        }
    }

    /** Împarte mesajul în exact n câmpuri nevide (ultimul câmp poate conține ";"). */
    private static String[] fields(String message, int n) {
        String[] f = message.trim().split(";", n);
        if (f.length != n) {
            throw new IllegalArgumentException("se așteptau " + n + " câmpuri, au sosit " + f.length);
        }
        for (int i = 0; i < n; i++) {
            f[i] = f[i].trim();
            if (f[i].isEmpty()) {
                throw new IllegalArgumentException("câmpul " + (i + 1) + " este gol");
            }
        }
        return f;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("timestamp invalid '" + s + "'");
        }
    }

    private static double parseDouble(String s, String what) {
        try {
            double v = Double.parseDouble(s);
            if (!Double.isFinite(v)) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " invalidă '" + s + "'");
        }
    }
}
