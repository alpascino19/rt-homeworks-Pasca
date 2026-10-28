package ssatr.service;

import java.util.Scanner;

/**
 * Consola operatorului: citește comenzi de la tastatură.
 *
 *   show                               afișează dashboard-ul
 *   <deviceId> <COMANDA> [parametru]   trimite o comandă unui device, de exemplu
 *                                      device-1 SET_INTERVAL 1000
 *   help                               afișează comenzile
 *   exit                               oprește serviciul
 */
public class CommandConsole implements Runnable {

    private final MqttConnection mqtt;
    private final Dashboard dashboard;

    public CommandConsole(MqttConnection mqtt, Dashboard dashboard) {
        this.mqtt = mqtt;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        Scanner scanner = new Scanner(System.in);
        printHelp();

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();

            if (line.isEmpty()) {
                continue;
            } else if (line.equals("exit")) {
                break;
            } else if (line.equals("help")) {
                printHelp();
            } else if (line.equals("show")) {
                dashboard.print();
            } else {
                sendCommand(line);
            }
        }
    }

    private void sendCommand(String line) {
        // "device-1 SET_INTERVAL 1000" -> "device-1" și "SET_INTERVAL 1000"
        String[] parts = line.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            System.out.println("Format greșit. Folosiți: <deviceId> <COMANDA> [parametru], de exemplu device-1 SET_INTERVAL 1000");
            return;
        }

        String deviceId = parts[0];
        // wildcard-urile și "/" ar schimba topic-ul (comanda ar ajunge la alte device-uri)
        if (deviceId.contains("+") || deviceId.contains("#") || deviceId.contains("/")) {
            System.out.println("ID de device invalid: " + deviceId);
            return;
        }

        // numele comenzii cu majuscule; validarea completă o face device-ul (răspunde cu ERROR)
        String[] cmd = parts[1].trim().split("\\s+", 2);
        String command = cmd[0].toUpperCase(java.util.Locale.ROOT) + (cmd.length > 1 ? " " + cmd[1] : "");

        if (!dashboard.knows(deviceId)) {
            System.out.println("Atenție: " + deviceId + " nu a trimis încă niciun mesaj; comanda se trimite oricum.");
        }

        mqtt.publish(Config.commandTopic(deviceId), command);
        System.out.println(">>> " + deviceId + ": " + command);
    }

    private void printHelp() {
        System.out.println("Comenzi: show | <deviceId> SET_INTERVAL <ms> | <deviceId> SET_THRESHOLD <valoare>");
        System.out.println("         <deviceId> RESET | <deviceId> STATUS | help | exit");
    }
}
