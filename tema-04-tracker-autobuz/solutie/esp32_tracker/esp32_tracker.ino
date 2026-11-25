// Tracker personal de autobuz - ESP32-C3 Super Mini.
//
// Aplicația Java calculează totul și publică starea pe "ssatr/<STUDENT>/tracker/stare".
// Placa doar afișează starea pe LED și trimite "URCAT" la apăsarea butonului BOOT.
//
// Taskuri (vezi README.md):
//   taskLed    periodic 50 ms (xTaskDelayUntil), prioritatea cea mai mare: tiparul LED + watchdog de date
//   taskButon  sporadic, așteaptă pe coadaButon: trimite URCAT, retrimite până la confirmare
//   taskMqtt   așteaptă pe coadaIesire: singurul care publică evenimente prin clientul MQTT
//
// Cozi:
//   ISR buton        -> coadaButon       -> taskButon
//   taskButon        -> coadaIesire      -> taskMqtt
//   callback MQTT    -> coadaStare (1)   -> taskLed       (xQueueOverwrite: doar ultima stare)
//   callback MQTT    -> coadaConfirmare  -> taskButon
//   taskButon        -> notificare       -> taskLed       (clipire locală la apăsare)

#include <ArduinoJson.h>

#include "config.h"
#include "connection.h"

// ---------------------------------------------------------------- stări

enum Stare : uint8_t { PORNIRE, FARA_DATE, DEPARTE, PLEACA, GRABESTE_TE, IN_STATIE, CONFIRMAT };

static const char* const NUME_STARE[] = {"PORNIRE", "FARA_DATE", "DEPARTE", "PLEACA",
                                         "GRABESTE_TE", "IN_STATIE", "CONFIRMAT"};

// numele din JSON -> Stare; false dacă numele e necunoscut (mesajul se ignoră)
static bool parse_stare(const char* nume, Stare* out) {
    if (nume == NULL) return false;
    for (int s = FARA_DATE; s <= CONFIRMAT; s++) {  // PORNIRE e doar locală
        if (strcmp(nume, NUME_STARE[s]) == 0) {
            *out = (Stare)s;
            return true;
        }
    }
    return false;
}

// Tiparul LED-ului: aprins sau stins la t ms de la intrarea în starea s.
static bool led_aprins(Stare s, uint32_t t) {
    switch (s) {
        case PORNIRE:     return t % 2000 < 1000;                      // 0,5 Hz
        case FARA_DATE: { uint32_t c = t % 1800;                       // 2 clipiri scurte, pauză ~1,5 s
                          return c < 100 || (c >= 200 && c < 300); }
        case DEPARTE:     return false;                                // stins
        case PLEACA:      return t % 1000 < 500;                       // 1 Hz
        case GRABESTE_TE: return t % 200 < 100;                        // 5 Hz
        case IN_STATIE:   return true;                                 // aprins continuu
        // 3 clipiri, apoi stins; fiecare începe cu o pauză, ca să se vadă și după IN_STATIE (aprins)
        case CONFIRMAT:   return t < 1200 && t % 400 >= 200;
    }
    return false;
}

// ---------------------------------------------------------------- cozi și taskuri

struct Eveniment {  // ce publică taskMqtt
    char text[64];
};

static QueueHandle_t coadaStare;        // Stare, lungime 1
static QueueHandle_t coadaConfirmare;   // uint8_t (doar semnal), lungime 1
static QueueHandle_t coadaButon;        // TickType_t (momentul apăsării)
static QueueHandle_t coadaIesire;       // Eveniment
static TaskHandle_t handleLed;

// ---------------------------------------------------------------- ISR buton

static void IRAM_ATTR isr_buton() {
    static TickType_t ultima = 0;  // folosită doar aici
    TickType_t acum = xTaskGetTickCountFromISR();
    if (acum - ultima < pdMS_TO_TICKS(DEBOUNCE_MS)) return;  // vibrația contactului
    ultima = acum;

    BaseType_t trezit = pdFALSE;
    xQueueSendFromISR(coadaButon, &acum, &trezit);
    if (trezit) portYIELD_FROM_ISR();
}

// ---------------------------------------------------------------- callback MQTT

// Rulează pe taskul clientului MQTT: parsează și pune rezultatul în coadă.
void message_received(const char* kind, const char* text) {
    JsonDocument doc;
    if (deserializeJson(doc, text)) {
        Serial.printf("[mqtt] JSON invalid pe %s, ignorat: %s\n", kind, text);
        return;
    }

    if (strcmp(kind, "stare") == 0) {
        Stare s;
        if (!parse_stare(doc["stare"], &s)) {
            Serial.printf("[stare] necunoscuta, ignorata: %s\n", text);
            return;
        }
        // O stare retained poate fi rămasă de la o rulare veche a aplicației Java.
        int64_t ts = doc["ts"] | (int64_t)0;
        if (time_valid() && (ts == 0 || epoch_ms() / 1000 - ts > MAX_STATE_AGE_S)) {
            Serial.printf("[stare] veche sau fara ts, ignorata: %s\n", text);
            return;
        }
        xQueueOverwrite(coadaStare, &s);
    } else if (strcmp(kind, "confirmare") == 0) {
        if (strcmp(doc["eveniment"] | "", "URCAT") == 0 && (doc["ok"] | false)) {
            uint8_t semnal = 1;
            xQueueOverwrite(coadaConfirmare, &semnal);
            Stare s = CONFIRMAT;  // tiparul CONFIRMAT imediat, fără să așteptăm următoarea stare
            xQueueOverwrite(coadaStare, &s);
        }
    }
}

// ---------------------------------------------------------------- taskLed

static void taskLed(void*) {
    Stare afisata = PORNIRE;         // ce arată LED-ul acum
    Stare primita = PORNIRE;         // ultima stare validă de la Java
    bool primitCeva = false;
    TickType_t ultimaStare = 0;      // momentul ultimei stări valide (watchdog)
    TickType_t intrareStare = xTaskGetTickCount();
    TickType_t clipireLocala = 0;    // != 0: clipire de confirmare a apăsării, pornită atunci
    TickType_t urmator = xTaskGetTickCount();

    for (;;) {
        TickType_t acum = xTaskGetTickCount();

        Stare s;
        if (xQueueReceive(coadaStare, &s, 0) == pdTRUE) {
            primita = s;
            primitCeva = true;
            ultimaStare = acum;
        }
        if (ulTaskNotifyTake(pdTRUE, 0) > 0) clipireLocala = acum ? acum : 1;

        // Watchdog de date: o stare veche sau lipsa conexiunii nu trebuie să pară validă.
        Stare dorita = primita;
        if (!primitCeva) {
            dorita = PORNIRE;
        } else if (!mqtt_connected() || acum - ultimaStare > pdMS_TO_TICKS(DATA_TIMEOUT_MS)) {
            dorita = FARA_DATE;
        }

        if (dorita != afisata) {
            Serial.printf("[led] %s -> %s\n", NUME_STARE[afisata], NUME_STARE[dorita]);
            afisata = dorita;
            intrareStare = acum;
        }

        bool aprins = led_aprins(afisata, pdTICKS_TO_MS(acum - intrareStare));
        if (clipireLocala) {
            uint32_t t = pdTICKS_TO_MS(acum - clipireLocala);
            if (t < 400) aprins = t % 200 < 100;  // 2 clipiri rapide peste orice tipar
            else clipireLocala = 0;
        }
        digitalWrite(LED_PIN, aprins ? LOW : HIGH);  // LED activ pe LOW

        xTaskDelayUntil(&urmator, pdMS_TO_TICKS(LED_PERIOD_MS));
    }
}

// ---------------------------------------------------------------- taskButon

static void taskButon(void*) {
    for (;;) {
        TickType_t apasare;
        xQueueReceive(coadaButon, &apasare, portMAX_DELAY);
        xTaskNotifyGive(handleLed);  // feedback local imediat

        // Același ts la toate încercările: Java recunoaște retrimiterile.
        Eveniment ev;
        snprintf(ev.text, sizeof(ev.text), "{\"eveniment\":\"URCAT\",\"ts\":%lld}",
                 (long long)(epoch_ms() / 1000));

        xQueueReset(coadaConfirmare);  // o confirmare veche nu se aplică apăsării de acum
        bool confirmat = false;
        for (int incercare = 1; incercare <= MAX_SEND_ATTEMPTS && !confirmat; incercare++) {
            Serial.printf("[buton] URCAT, incercarea %d/%d\n", incercare, MAX_SEND_ATTEMPTS);
            xQueueSend(coadaIesire, &ev, pdMS_TO_TICKS(100));
            uint8_t semnal;
            confirmat = xQueueReceive(coadaConfirmare, &semnal, pdMS_TO_TICKS(CONFIRM_TIMEOUT_MS)) == pdTRUE;
        }
        Serial.println(confirmat ? "[buton] confirmat de aplicatia Java" : "[buton] fara confirmare dupa 3 incercari");

        xQueueReset(coadaButon);  // apăsările făcute cât așteptam nu mai trimit încă un URCAT
    }
}

// ---------------------------------------------------------------- taskMqtt

static void taskMqtt(void*) {
    for (;;) {
        Eveniment ev;
        xQueueReceive(coadaIesire, &ev, portMAX_DELAY);
        mqtt_publish("eveniment", ev.text, false);
    }
}

// ---------------------------------------------------------------- setup / loop

void setup() {
    Serial.begin(115200);
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, HIGH);  // stins
    pinMode(BUTTON_PIN, INPUT_PULLUP);
    delay(500);
    Serial.printf("\ntracker autobuz, device %s, student %s\n", DEVICE_ID, STUDENT);

    // Cozile înainte de mqtt_start() și de ISR: callback-urile scriu în ele.
    coadaStare = xQueueCreate(1, sizeof(Stare));
    coadaConfirmare = xQueueCreate(1, sizeof(uint8_t));
    coadaButon = xQueueCreate(4, sizeof(TickType_t));
    coadaIesire = xQueueCreate(4, sizeof(Eveniment));

    // taskLed pornește primul: tiparul PORNIRE e vizibil cât se conectează Wi-Fi-ul.
    xTaskCreate(taskLed, "taskLed", 3072, NULL, 3, &handleLed);

    wifi_connect();
    time_sync();
    mqtt_start();

    xTaskCreate(taskMqtt, "taskMqtt", 4096, NULL, 2, NULL);
    xTaskCreate(taskButon, "taskButon", 3072, NULL, 2, NULL);
    attachInterrupt(digitalPinToInterrupt(BUTTON_PIN), isr_buton, FALLING);
}

void loop() {
    vTaskDelete(NULL);  // totul rulează în taskuri
}
