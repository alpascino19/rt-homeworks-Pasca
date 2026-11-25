import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/** Trimite plăcii fiecare stare pe rând (10 s fiecare), apoi tace ca să declanșeze watchdog-ul. */
public class TestLed {
    public static void main(String[] a) throws Exception {
        String topic = "ssatr/Pasca-Alex/tracker/stare";
        MqttClient c = new MqttClient("tcp://control.aut.utcluj.ro:11188", "Pasca-Alex-testled-" + System.currentTimeMillis(), new MemoryPersistence());
        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        c.connect(o);
        // fără argumente: toată secvența; cu argumente: doar stările date, câte 10 s fiecare
        String[] stari = a.length > 0 ? a
                : new String[] {"DEPARTE", "PLEACA", "GRABESTE_TE", "IN_STATIE", "CONFIRMAT", "FARA_DATE", "PLEACA"};
        int pauza = a.length > 0 ? 3 : 20;
        System.out.println("conectat; astept " + pauza + " s");
        Thread.sleep(pauza * 1000L);
        for (String s : stari) {
            long ts = System.currentTimeMillis() / 1000;
            MqttMessage m = new MqttMessage(("{\"stare\":\"" + s + "\",\"linie\":\"25\",\"statie\":\"Test\",\"ts\":" + ts + "}").getBytes("UTF-8"));
            m.setQos(1);
            m.setRetained(true);
            c.publish(topic, m);
            System.out.println(java.time.LocalTime.now().withNano(0) + " trimis " + s);
            Thread.sleep(10_000);
        }
        System.out.println(java.time.LocalTime.now().withNano(0) + " nu mai trimit nimic: in ~30 s placa trebuie sa treaca singura pe FARA_DATE");
        c.disconnect();
        c.close();
    }
}
