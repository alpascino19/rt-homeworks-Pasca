// Conexiunea plăcii: Wi-Fi, ora exactă (SNTP) și clientul MQTT.
//
// Pornește de la connection.cpp din temele 02-03. Clientul MQTT este cel din
// ESP-IDF, inclus în core-ul Arduino (nu trebuie instalată nicio bibliotecă).
// El rulează în propriul task FreeRTOS, se reconectează singur și apelează
// on_mqtt_event() pentru conectare, deconectare și mesaje primite.

#include "connection.h"

#include <WiFi.h>
#include <atomic>
#include <esp_mac.h>
#include <sys/time.h>
#include <time.h>

#include "config.h"
#include "mqtt_client.h"

// "esp32-" + ultimii 3 octeți ai adresei MAC (partea proprie fiecărei plăci).
static const char* read_device_id(void) {
    static char id[16];
    uint8_t mac[6];
    esp_read_mac(mac, ESP_MAC_WIFI_STA);
    snprintf(id, sizeof(id), "esp32-%02x%02x%02x", mac[3], mac[4], mac[5]);
    return id;
}

// inițializată înainte de setup()
const char* const DEVICE_ID = read_device_id();

static esp_mqtt_client_handle_t g_client = NULL;
static char g_topic_stare[80];
static char g_topic_confirmare[80];
static char g_topic_online[80];

// scris de taskul MQTT, citit de taskLed: atomic, fără mutex
static std::atomic<bool> g_connected{false};

void wifi_connect(void) {
    Serial.printf("conectare la Wi-Fi \"%s\"", WIFI_SSID);
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);  // după o cădere, Wi-Fi-ul revine singur
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    // Antena mică a plăcii Super Mini se conectează adesea mai bine la putere redusă.
    WiFi.setTxPower(WIFI_POWER_8_5dBm);

    uint32_t start = millis();
    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
        if (millis() - start > 20000) {
            Serial.printf("\nnu m-am conectat la \"%s\"; rețele vizibile:\n", WIFI_SSID);
            WiFi.disconnect();
            int n = WiFi.scanNetworks();
            for (int i = 0; i < n; i++) {
                Serial.printf("  %s (%d dBm)\n", WiFi.SSID(i).c_str(), (int)WiFi.RSSI(i));
            }
            Serial.println("verificați SECRET_WIFI_SSID și SECRET_WIFI_PASSWORD din secrets.h; reîncerc");
            WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
            start = millis();
        }
    }
    Serial.printf("\nWi-Fi conectat, adresa IP %s\n", WiFi.localIP().toString().c_str());
}

void time_sync(void) {
    configTime(0, 0, NTP_SERVER);  // UTC; SNTP continuă să corecteze ceasul în fundal
    struct tm now;
    if (getLocalTime(&now, 10000)) {
        Serial.printf("ora sincronizată prin SNTP: %04d-%02d-%02d %02d:%02d:%02d UTC\n", now.tm_year + 1900,
                      now.tm_mon + 1, now.tm_mday, now.tm_hour, now.tm_min, now.tm_sec);
    } else {
        Serial.println("ora NU s-a sincronizat încă: vechimea stărilor nu poate fi verificată");
    }
}

int64_t epoch_ms(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (int64_t)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

bool time_valid(void) {
    return epoch_ms() > 1700000000000LL;  // după noiembrie 2023: ceasul a fost setat
}

bool mqtt_connected(void) {
    return g_connected.load();
}

// Apelată de taskul clientului MQTT.
static void on_mqtt_event(void* handler_arg, esp_event_base_t base, int32_t event_id, void* event_data) {
    (void)handler_arg;
    (void)base;
    esp_mqtt_event_handle_t ev = (esp_mqtt_event_handle_t)event_data;

    if (event_id == MQTT_EVENT_CONNECTED) {
        // Abonările se refac la fiecare (re)conectare: brokerul le uită (clean session).
        esp_mqtt_client_subscribe(g_client, g_topic_stare, 1);
        esp_mqtt_client_subscribe(g_client, g_topic_confirmare, 1);
        esp_mqtt_client_publish(g_client, g_topic_online, "1", 0, 1, 1);
        g_connected = true;
        Serial.printf("[mqtt] conectat la %s, abonat la %s și %s\n", BROKER_URI, g_topic_stare, g_topic_confirmare);
    } else if (event_id == MQTT_EVENT_DISCONNECTED) {
        g_connected = false;
        Serial.println("[mqtt] deconectat, clientul reîncearcă singur");
    } else if (event_id == MQTT_EVENT_DATA) {
        // Un mesaj mai mare decât bufferul clientului vine în bucăți; mesajele
        // noastre sunt < 256 B, deci o bucată cu offset != 0 nu e de la noi.
        if (ev->current_data_offset != 0 || ev->topic_len <= 0) return;

        // ev->topic și ev->data NU se termină cu '\0': le copiem.
        char topic[80];
        int tn = ev->topic_len < (int)sizeof(topic) - 1 ? ev->topic_len : (int)sizeof(topic) - 1;
        memcpy(topic, ev->topic, tn);
        topic[tn] = '\0';

        char text[MSG_MAX_LEN];
        int n = ev->data_len < (int)MSG_MAX_LEN - 1 ? ev->data_len : (int)MSG_MAX_LEN - 1;
        memcpy(text, ev->data, n);
        text[n] = '\0';

        const char* kind = strrchr(topic, '/');
        message_received(kind ? kind + 1 : topic, text);
    } else if (event_id == MQTT_EVENT_ERROR) {
        Serial.println("[mqtt] eroare (broker inaccesibil? portul blocat de rețea?)");
    }
}

void mqtt_start(void) {
    make_topic(g_topic_stare, sizeof(g_topic_stare), "stare");
    make_topic(g_topic_confirmare, sizeof(g_topic_confirmare), "confirmare");
    make_topic(g_topic_online, sizeof(g_topic_online), "online");

    // client id unic pe broker: doi clienți cu același id se deconectează unul pe altul
    static char client_id[64];
    snprintf(client_id, sizeof(client_id), "%s-%s", STUDENT, DEVICE_ID);

    esp_mqtt_client_config_t cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.broker.address.uri = BROKER_URI;
    cfg.credentials.client_id = client_id;
    // LWT: dacă placa dispare fără DISCONNECT, brokerul publică "0" retained pe "online"
    cfg.session.last_will.topic = g_topic_online;
    cfg.session.last_will.msg = "0";
    cfg.session.last_will.qos = 1;
    cfg.session.last_will.retain = 1;
    cfg.session.keepalive = 15;                 // brokerul observă căderea în ~22 s
    cfg.network.reconnect_timeout_ms = 5000;    // pauza dintre reîncercări

    g_client = esp_mqtt_client_init(&cfg);
    esp_mqtt_client_register_event(g_client, MQTT_EVENT_ANY, on_mqtt_event, NULL);
    esp_mqtt_client_start(g_client);
}

bool mqtt_publish(const char* kind, const char* text, bool retain) {
    if (g_client == NULL) {
        Serial.println("[mqtt] mqtt_start() nu a fost apelat, mesaj pierdut");
        return false;
    }
    char topic[80];
    make_topic(topic, sizeof(topic), kind);
    int id = esp_mqtt_client_publish(g_client, topic, text, 0, 1, retain ? 1 : 0);
    if (id < 0) {
        Serial.printf("[mqtt] publicarea pe %s a eșuat\n", topic);
        return false;
    }
    return true;
}
