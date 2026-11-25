// Conexiunea plăcii: Wi-Fi, ora exactă (SNTP) și clientul MQTT.
// Pornește de la connection.h/.cpp din temele 02-03, adaptat pentru tracker:
//   - abonare la două topic-uri ("stare" și "confirmare"), callback-ul primește și topic-ul;
//   - mesaje primite de până la MSG_MAX_LEN octeți (starea are ~150 B);
//   - "online" retained "1" la conectare și LWT "0";
//   - publicare cu sau fără retain; mqtt_connected() pentru watchdog-ul LED-ului.
//
// Ordinea apelurilor, în setup():
//
//   wifi_connect();   // blochează până când placa este în rețea
//   time_sync();      // după Wi-Fi: ora exactă, pentru epoch_ms()
//   ...               // cozile (înainte de mqtt_start: callback-ul scrie în ele)
//   mqtt_start();     // mesajele primite ajung în message_received()
//   ...               // taskurile

#pragma once

#include <Arduino.h>

// lungimea maximă a unui mesaj primit, cu tot cu '\0'; textul mai lung este trunchiat
const size_t MSG_MAX_LEN = 256;

// Conectează placa la rețeaua WIFI_SSID din config.h. Blochează până la
// conectare; dacă durează, afișează rețelele pe care le vede placa.
// După conectare, Wi-Fi-ul se reconectează singur dacă pică.
void wifi_connect(void);

// Sincronizează ceasul plăcii prin SNTP (NTP_SERVER din config.h).
void time_sync(void);

// Timpul curent în ms de la 1 ianuarie 1970 (UTC). Corect după time_sync();
// înainte numără de la pornire.
int64_t epoch_ms(void);

// true dacă ceasul a fost sincronizat (epoch_ms() e o dată reală, nu timp de la pornire)
bool time_valid(void);

// Pornește clientul MQTT (ESP-IDF, rulează în propriul task și se reconectează
// singur). La fiecare (re)conectare: abonare la "stare" și "confirmare",
// publică "online" = "1" retained. LWT: "online" = "0" retained.
void mqtt_start(void);

// true cât timp clientul este conectat la broker
bool mqtt_connected(void);

// Publică text pe "ssatr/<STUDENT>/tracker/<kind>", QoS 1.
// Întoarce false dacă mesajul nu a putut fi pus în clientul MQTT.
bool mqtt_publish(const char* kind, const char* text, bool retain);

// Apelată pentru fiecare mesaj primit. kind este ultima parte a topic-ului
// ("stare" sau "confirmare"). O scrieți voi, în esp32_tracker.ino.
//
// Rulează pe taskul clientului MQTT: doar puneți rezultatul într-o coadă.
// kind și text sunt valide doar în timpul apelului.
void message_received(const char* kind, const char* text);
