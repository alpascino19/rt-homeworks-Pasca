// Setările tracker-ului: rețea, broker, topic-uri, pini, perioade și praguri.
//
// Brokerul este partajat de toți studenții. STUDENT trebuie să fie identic cu
// cel din aplicația Java (config.properties: mqtt.student).

#pragma once

#include <Arduino.h>

// secrets.h nu e în git: copiați secrets.example.h ca secrets.h și completați-l.
#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "Lipseste secrets.h: copiati secrets.example.h ca secrets.h si completati Wi-Fi-ul."
#endif

const char* const WIFI_SSID = SECRET_WIFI_SSID;
const char* const WIFI_PASSWORD = SECRET_WIFI_PASSWORD;

const char* const BROKER_URI = "mqtt://control.aut.utcluj.ro:11188";

// serverul de la care placa ia ora exactă (SNTP), pentru "ts" și vechimea stărilor
const char* const NTP_SERVER = "pool.ntp.org";

const char* const STUDENT = "Pasca-Alex";

// ID-ul device-ului: "esp32-" + ultimele 6 cifre hexa ale MAC-ului (connection.cpp).
// Folosit în client id-ul MQTT, ca două plăci să nu se deconecteze reciproc.
extern const char* const DEVICE_ID;

// Pinii plăcii ESP32-C3 Super Mini
const int BUTTON_PIN = 9;  // butonul BOOT: LOW cât timp este apăsat (INPUT_PULLUP)
const int LED_PIN = 8;     // LED-ul albastru de pe placă: aprins pe LOW

// taskLed: perioada fixă (xTaskDelayUntil)
const uint32_t LED_PERIOD_MS = 50;

// watchdog de date: fără stare validă atâta timp => FARA_DATE
const uint32_t DATA_TIMEOUT_MS = 30000;

// o stare (retained) mai veche de atât, după "ts", este respinsă
const int64_t MAX_STATE_AGE_S = 60;

// ISR: apăsări mai apropiate de atât sunt considerate vibrații ale contactului
const uint32_t DEBOUNCE_MS = 250;

// URCAT: cât așteaptă confirmarea Java și de câte ori trimite în total
const uint32_t CONFIRM_TIMEOUT_MS = 5000;
const int MAX_SEND_ATTEMPTS = 3;

// Scrie în buf topic-ul "ssatr/<STUDENT>/tracker/<kind>". kind este:
//   "stare"       Java -> placă, retained: {"stare":"PLEACA","eta_s":410,...,"ts":...}
//   "confirmare"  Java -> placă: {"eveniment":"URCAT","ok":true}
//   "eveniment"   placă -> Java: {"eveniment":"URCAT","ts":...}
//   "online"      placă -> broker, retained: "1"; LWT "0"
inline void make_topic(char* buf, size_t size, const char* kind) {
    snprintf(buf, size, "ssatr/%s/tracker/%s", STUDENT, kind);
}
