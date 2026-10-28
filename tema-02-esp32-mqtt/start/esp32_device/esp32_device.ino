// Device-ul IoT din tema 01, de data aceasta pe un ESP32-C3 Super Mini.
//
// Aceeași logică și același protocol ca device-simulator din tema 01, cu trei
// taskuri FreeRTOS în loc de trei thread-uri Java:
//
//   task periodic --(coada de alarme)--> task sporadic
//        |                                     |
//        +---- telemetry ---> MQTT <--- alarm -+
//                              |
//                          commands --> command_received() --(coada de comenzi)--> task aperiodic ---> status
//
// Gata implementate (nu trebuie modificate):
//   config.h         setările: Wi-Fi, broker, STUDENT, valorile inițiale; DEVICE_ID
//                    se citește din adresa MAC a plăcii
//   connection.h/cpp Wi-Fi, ora exactă (SNTP), clientul MQTT, mqtt_publish(), epoch_ms()
//
// Așa cum este, schița se conectează la Wi-Fi și la broker și afișează pe
// Serial comenzile primite. Taskurile le scrieți voi: TODO 0 ... TODO 3.
// Exemplele de pornire sunt în laboratorul 0 din rt-labs-esp32c3; vezi
// exemple/README.md din folderul temei.
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include "config.h"
#include "connection.h"
#include <ctype.h>    // toupper
#include <math.h>     // isfinite
#include <stdlib.h>   // strtol, strtof
#include <string.h>   // strncpy, strcmp, strtok_r

// Temperatura cipului, în °C, de la senzorul intern al ESP32-C3.
// Este temperatura siliciului, nu a camerei: imediat după pornirea Wi-Fi-ului
// poate fi în jur de 50 °C, apoi scade în câteva minute spre 35-40 °C.
// Rezoluția este de aproximativ 1 °C. Ca să provocați alarme, puneți pragul
// cu SET_THRESHOLD cu un grad sub valoarea citită.



float read_temperature(void) {
    return temperatureRead();
}

// Eveniment de alarma, pus in coada prin valoare, se trimite impreuna cu temperatura
// alarma descrie situatia din momentul detectarii
struct AlarmEvent {
    float temperature;
    float threshold;
    int64_t timestamp; //epoch_ms() al citirii
    uint32_t arrival_ms; // millis() la sosire, pentru intervalul minim
};

//Comanda primita cu textul copiat

struct Command {
    char text[COMMAND_MAX_LEN];
};

// Lungimea cozilor: alarmele sunt golite dupa taskul sporadic, iar comenzile pot veni in rafala (reconectare la MQTT de exemplu)
const UBaseType_t ALARM_QUEUE_LEN =5;
const UBaseType_t COMMAND_QUEUE_LEN=10;

//Cozile declarate global , fiind folosite de mai multe taskuri

QueueHandle_t g_alarm_queue=NULL;
QueueHandle_t g_command_queue=NULL;

// Prioritățile, între 2 și 19: peste loopTask (1), sub taskurile sistemului
// (20, 23). Periodic > sporadic > aperiodic: taskul periodic trebuie să rămână
// pe grilă, alarmele sunt mai urgente decât comenzile.
const UBaseType_t PRIO_PERIODIC = 5;
const UBaseType_t PRIO_SPORADIC = 4;
const UBaseType_t PRIO_APERIODIC = 3;

// Stiva fiecărui task, în octeți (pe ESP32). snprintf cu %f are nevoie de stivă.
const uint32_t TASK_STACK = 4096;

//Perioada si pragul: scrise de comenzi, citite de taskul periodic la fiecare job
// se citesc mereu din memorie
volatile uint32_t g_period_ms= PERIOD_MS_INITIAL;
volatile float g_threshold= THRESHOLD_INITIAL;
const uint32_t MIN_PERIOD_MS = 100;
const uint32_t MAX_PERIOD_MS = 60000;

// TODO 1: TASK PERIODIC, cu perioada T (inițial PERIOD_MS_INITIAL).
//   Fiecare job:
//   - citește temperatura: read_temperature()
//   - publică telemetria: mqtt_publish("telemetry", "deviceId;timestamp;temperatura")
//     (timestamp = epoch_ms(), tipărit cu %lld; temperatura cu "%.2f")
//   - dacă temperatura depășește pragul (inițial THRESHOLD_INITIAL), pune
//     un eveniment în coada de alarme, fără să se blocheze (timeout 0).
//     Taskul periodic NU tratează alarma: o tratează taskul sporadic.
//   Eliberările respectă grila r_k = r_0 + kT: folosiți așteptare absolută
//   (xTaskDelayUntil), nu relativă (delay, vTaskDelay), ca să nu apară derivă.
//   Perioada și pragul se schimbă prin comenzi (taskul aperiodic), deci sunt
//   variabile globale volatile, citite la fiecare job.

void periodic_task(void* arg){
    (void)arg;
    TickType_t last= xTaskGetTickCount();// prima eliberare
    uint32_t job=0;

    for(;;){
        float t=read_temperature();
        int64_t ts= epoch_ms();
        float threshold= g_threshold;// pragul citit o data per job

        char msg[96];
        snprintf(msg,sizeof(msg), "%s;%lld;%.2f",DEVICE_ID,(long long)ts,t);
        mqtt_publish("telemetry",msg);

        if(t >threshold){
            AlarmEvent ev= {t,threshold,ts,millis()};
            if(xQueueSend(g_alarm_queue, &ev,0)!=pdTRUE){ //timeout 0:nu ese blocheaza
                Serial.println("[periodic] coada de alarma este plina, eveniment pierdut");
            }
        }

        uint32_t period=g_period_ms;
        Serial.printf("[periodic] job %lu: %.2f *C (prag %.2f), T=%lu ms\n",
        (unsigned long)job++,t,threshold,(unsigned long)period);

        //asteptarea absoluta pana la last +T (fara deriva)
        if(xTaskDelayUntil(&last, pdMS_TO_TICKS(period))==pdFALSE){
            //moment last+ T deja trecut, nu se recupereaza joburile ratate in rafala
            Serial.println("[periodic] DEPASIRE: eliberare ratata, grila reporneste de acum");
            last=xTaskGetTickCount();
        }
    }
}

// TODO 2: TASK SPORADIC, tratează alarmele de temperatură.
//   - așteaptă un eveniment din coada de alarme
//   - dacă de la ultima alarmă publicată au trecut mai puțin de
//     MIN_INTERARRIVAL_MS, respinge evenimentul (afișați un mesaj pe Serial)
//   - altfel publică: mqtt_publish("alarm", "deviceId;timestamp;temperatura;prag")
//   Pentru intervale folosiți un timp monoton, millis(), nu epoch_ms().

void sporadic_task(void* arg){
    (void)arg;
    bool accepted_before= false;
    uint32_t last_accepted_ms=0;

    for(;;){
        AlarmEvent ev;
        xQueueReceive(g_alarm_queue,&ev,portMAX_DELAY);//inactiv pana vine un eveniment

        uint32_t since=ev.arrival_ms-last_accepted_ms;
        if(accepted_before&&since<MIN_INTERARRIVAL_MS){
            Serial.printf("[sporadic] alarma respinsa: %.2f *C, la %lu ms dupa ultima (minim %lu ms)\n",
            ev.temperature,(unsigned long)since,(unsigned long)MIN_INTERARRIVAL_MS);
            continue;//sarim peste publicare si se asteapta urmatorul eveniment
        }

        accepted_before = true;
        last_accepted_ms = ev.arrival_ms;

        char msg[96];
        snprintf(msg, sizeof(msg), "%s;%lld;%.2f;%.2f",
                 DEVICE_ID, (long long)ev.timestamp, ev.temperature, ev.threshold);
        mqtt_publish("alarm", msg);

        Serial.printf("[sporadic] ALARMA publicata: %.2f *C > prag %.2f *C\n", ev.temperature, ev.threshold);
    }}


// TODO 3: TASK APERIODIC, execută comenzile primite de la business service.
//   Comenzile ajung în command_received() (mai jos), care le pune în coada de
//   comenzi; fiecare comandă eliberează un job al taskului:
//     SET_INTERVAL <ms>       schimbă perioada T a taskului periodic
//     SET_THRESHOLD <valoare> schimbă pragul de alarmă
//     RESET                   readuce perioada și pragul la valorile inițiale
//     STATUS                  raportează perioada și pragul curente
//   După fiecare comandă publică o confirmare:
//     mqtt_publish("status", "deviceId;timestamp;OK SET_INTERVAL 1000")
//   O comandă necunoscută sau un parametru invalid nu oprește taskul: se
//   publică "ERROR <motiv>".

// true doar dacă TOT textul e un întreg: "1000" da; "10x", "abc", "" nu
static bool parse_long(const char* s, long* out){
    char* end;
    long v=strtol(s,&end,10);
    if(end==s||*end!='\0')return false;
    *out=v;
    return true;
}

//numar zecimal, accepta si virgula
static bool parse_float(const char* s, float*out){
    char buf[32];
    strncpy(buf,s,sizeof(buf)-1);
    buf[sizeof(buf)-1]='\0';
    for(char* p=buf;*p;p++){
        if(*p==',') *p='.';
    }
    char* end;
    float v=strtof(buf,&end);
    if(end==buf||*end!='\0'||!isfinite(v))return false;
    *out=v;
    return true;
}

// Execută comanda și scrie în reply "OK ..." sau "ERROR ...".
static void execute_command(const char* text, char* reply, size_t size) {
    // copie locală: strtok_r modifică textul pe care îl împarte
    char buf[COMMAND_MAX_LEN];
    strncpy(buf, text, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';

    // împărțim după spații: nume, parametru, și ce mai e în plus
    char* save;
    char* name = strtok_r(buf, " \t\r\n", &save);
    if (name == NULL) {
        snprintf(reply, size, "ERROR comanda goala");
        return;
    }
    for (char* p = name; *p; p++) *p = toupper((unsigned char)*p);   // set_interval -> SET_INTERVAL
    char* arg = strtok_r(NULL, " \t\r\n", &save);
    char* extra = arg ? strtok_r(NULL, " \t\r\n", &save) : NULL;

    if (strcmp(name, "SET_INTERVAL") == 0) {
        long ms;
        if (arg == NULL || extra != NULL) {
            snprintf(reply, size, "ERROR format corect: SET_INTERVAL <ms>");
        } else if (!parse_long(arg, &ms)) {
            snprintf(reply, size, "ERROR SET_INTERVAL: '%s' nu este un numar intreg", arg);
        } else if (ms < (long)MIN_PERIOD_MS || ms > (long)MAX_PERIOD_MS) {
            snprintf(reply, size, "ERROR SET_INTERVAL: perioada trebuie sa fie intre %lu si %lu ms",
                     (unsigned long)MIN_PERIOD_MS, (unsigned long)MAX_PERIOD_MS);
        } else {
            g_period_ms = (uint32_t)ms;
            snprintf(reply, size, "OK SET_INTERVAL %ld", ms);
        }
    } else if (strcmp(name, "SET_THRESHOLD") == 0) {
        float value;
        if (arg == NULL || extra != NULL) {
            snprintf(reply, size, "ERROR format corect: SET_THRESHOLD <valoare>");
        } else if (!parse_float(arg, &value)) {
            snprintf(reply, size, "ERROR SET_THRESHOLD: '%s' nu este un numar", arg);
        } else {
            g_threshold = value;
            snprintf(reply, size, "OK SET_THRESHOLD %.2f", value);
        }
    } else if (strcmp(name, "RESET") == 0) {
        if (arg != NULL) {
            snprintf(reply, size, "ERROR format corect: RESET (fara parametri)");
        } else {
            g_period_ms = PERIOD_MS_INITIAL;     // senzorul intern nu se poate reseta:
            g_threshold = THRESHOLD_INITIAL;     // revin perioada și pragul inițiale
            snprintf(reply, size, "OK RESET");
        }
    } else if (strcmp(name, "STATUS") == 0) {
        if (arg != NULL) {
            snprintf(reply, size, "ERROR format corect: STATUS (fara parametri)");
        } else {
            snprintf(reply, size, "OK STATUS interval=%lu threshold=%.2f",
                     (unsigned long)g_period_ms, (float)g_threshold);
        }
    } else {
        snprintf(reply, size, "ERROR comanda necunoscuta: %s", name);
    }

    // un ';' venit din parametru ar strica formatul "deviceId;timestamp;text"
    for (char* p = reply; *p; p++) {
        if (*p == ';') *p = ',';
    }
}

void aperiodic_task(void* arg) {
    (void)arg;
    for (;;) {
        Command cmd;
        xQueueReceive(g_command_queue, &cmd, portMAX_DELAY);   // fiecare comandă eliberează un job

        char reply[96];
        execute_command(cmd.text, reply, sizeof(reply));

        char msg[160];
        snprintf(msg, sizeof(msg), "%s;%lld;%s", DEVICE_ID, (long long)epoch_ms(), reply);
        mqtt_publish("status", msg);

        Serial.printf("[aperiodic] comanda \"%s\" -> %s\n", cmd.text, reply);
    }
}

// Apelată de clientul MQTT pentru fiecare comandă primită (vezi connection.h).
void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);
    // TODO 3: puneți comanda în coada de comenzi, fără să vă blocați
    //   (timeout 0). text este valid doar în timpul acestui apel: elementul
    //   cozii trebuie să conțină textul însuși (de exemplu un struct cu
    //   char text[COMMAND_MAX_LEN]), nu un pointer.
    Command cmd;
    strncpy(cmd.text,text,sizeof(cmd.text)-1);//copiere text in element
    cmd.text[sizeof(cmd.text)-1]='\0';

    if(g_command_queue==NULL || xQueueSend(g_command_queue,&cmd,0)!=pdTRUE){
        Serial.println("[mqtt] coada de comenzi este plina, comanda este pierduta");
    }
}

void setup() {
    Serial.begin(115200);
    // Fără așteptare la scriere: cu placa la USB, dar fără Serial Monitor deschis,
    // fiecare Serial.printf() ar aștepta până la 2 s să fie citit și ar întârzia
    // taskul care scrie. Așa, textul pe care nu-l citește nimeni se pierde.
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);  // așteptăm Serial Monitor, cel mult 3 s
    delay(200);
    Serial.println();
    Serial.printf("=== tema 02: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    wifi_connect();
    time_sync();

    // TODO 0 (a): creați cele două cozi (xQueueCreate): coada de alarme și
    //   coada de comenzi. Înainte de mqtt_start(): de acolo încolo pot sosi
    //   comenzi, iar command_received() le pune în coada de comenzi.
    g_alarm_queue= xQueueCreate(ALARM_QUEUE_LEN, sizeof(AlarmEvent));
    g_command_queue=xQueueCreate(COMMAND_QUEUE_LEN, sizeof(Command));
    if(g_alarm_queue==NULL||g_command_queue==NULL){
        Serial.println("Cozile nu au putut fi create (memorie insuficienta)");
        for(;;) delay(1000);
    }

    mqtt_start();

    // TODO 0 (b): porniți cele trei taskuri (xTaskCreate). După mqtt_start(),
    //   ca primele mesaje publicate să nu se piardă. Alegeți prioritățile
    //   între 2 și 19: peste loopTask (1), sub taskurile sistemului pentru
    //   evenimente și Wi-Fi (20, 23). Stiva, în octeți: cel puțin 4096.

    xTaskCreate(periodic_task, "periodic", TASK_STACK, NULL, PRIO_PERIODIC, NULL);
    xTaskCreate(sporadic_task, "sporadic", TASK_STACK, NULL, PRIO_SPORADIC, NULL);
    xTaskCreate(aperiodic_task, "aperiodic", TASK_STACK, NULL, PRIO_APERIODIC, NULL);

    Serial.printf("setup terminat: T=%lu ms, prag= %.2f *C, interval minim alarme= %lu ms\n",
    (unsigned long)PERIOD_MS_INITIAL, THRESHOLD_INITIAL, (unsigned long)MIN_INTERARRIVAL_MS);
}

void loop() {
    // Totul se face în taskuri: loop() nu are nimic de făcut.
    delay(1000);
}
