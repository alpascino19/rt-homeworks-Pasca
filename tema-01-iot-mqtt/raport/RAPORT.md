# Tema 01 – Raport

Student: `pasca` (valoarea `STUDENT` din `Config.java`, identică în ambele aplicații)

## Rulare

```bash
cd start/device-simulator
mvn -q compile exec:java -Dexec.args="device-1"      # alt terminal: device-2

cd start/business-service
mvn -q compile exec:java
```

Implicit se folosește brokerul facultății (`tcp://control.aut.utcluj.ro:11188`). Pentru teste cu un broker local,
`Config.BROKER` poate fi suprascris: `mvn -q compile exec:java -Dbroker=tcp://localhost:1883 ...`
(în ambele aplicații).

Comenzi în consola business-service: `show`, `device-1 SET_INTERVAL 1000`, `device-1 SET_THRESHOLD 25.5`,
`device-1 RESET`, `device-1 STATUS`, `help`, `exit`.

## Device simulator

### TODO 1 – task periodic (`PeriodicTelemetryTask`)

- Eliberările sunt pe grila $r_{k+1} = r_k + T$, calculată cu timpul monoton `System.nanoTime()`.
  Taskul așteaptă **absolut** până la $r_{k+1}$, nu `sleep(T)` după job, deci $C$ și întârzierile nu se acumulează (fără derivă).
  Testat: timestamp-urile telemetriei au diferențe de 2000 / 500 / 3000 ms, cu abateri de ±1 ms.
- Pentru fiecare job se afișează întârzierea față de grilă $L_k = s_k - r_k$, timpul de calcul $C$ și jitterul ($\max L - \min L$).
- Așteptarea se face cu `wait(timeout)` pe un monitor. `setPeriodMs()` face `notifyAll()`, iar taskul recalculează
  $r_{k+1} = r_k + T_{nou}$ imediat, fără să aștepte terminarea perioadei vechi (altfel, de la 60 s la 1 s, schimbarea ar
  întârzia până la 60 s). Dacă $r_k + T_{nou}$ a trecut deja, jobul se eliberează imediat și grila pornește din acel moment.
- Dacă un job depășește una sau mai multe perioade, eliberările ratate se sar (se afișează `DEPĂȘIRE`), nu se execută joburi în rafală.
- Temperatura este scrisă cu `Locale.US` (`23.45`). Dacă depășește pragul, jobul doar pune un eveniment în coada `alarmEvents` (`offer`, nu blochează).

### TODO 2 – task sporadic (`SporadicAlarmTask`)

- Preia evenimentele cu `take()`. Un eveniment care sosește la mai puțin de `MIN_INTERARRIVAL_MS = 5000` ms de la
  **ultima eliberare acceptată** este respins și nu se publică nimic. Primul eveniment este acceptat întotdeauna.
- Momentul sosirii este luat din eveniment (`nanoTime` pus de taskul periodic), nu din momentul `take()`. Astfel, intervalul
  minim se măsoară între sosiri, chiar dacă thread-ul sporadic ar fi întârziat.
- Pentru fiecare job se măsoară $R$ (de la sosire până după publicare) și se compară cu un termen $D = 500$ ms.
  În teste, $R$ a fost de ≈ 25 ms pe brokerul facultății și de 1–5 ms pe un broker local: `publish` cu QoS 1 așteaptă confirmarea (PUBACK) de la broker, deci timpul de calcul al joburilor este dominat de rețea.

**Alegerea pentru prag (cerută în TODO 2):** pragul se transmite **împreună cu temperatura**, în evenimentul
`AlarmEvent(temperatura, prag, timestamp, sosire)`. Nu este citit din `PeriodicTelemetryTask` în momentul publicării.

Motivul: alarma trebuie să descrie situația din momentul detectării. Dacă între detectare și publicare sosește
`SET_THRESHOLD`, citirea ulterioară ar putea produce un mesaj incoerent, de exemplu `25.10;28.00` (o temperatură *sub* prag
raportată ca alarmă). Evenimentul este imutabil (`record`), deci nu există condiții de cursă între thread-uri. Pentru asta,
tipul cozii s-a schimbat din `BlockingQueue<Double>` în `BlockingQueue<AlarmEvent>` (în `DeviceSimulator`).
`timestamp`-ul din alarmă este cel al citirii, deci alarma poate fi asociată exact cu rândul de telemetrie corespunzător.

Observație: cu $T = 1000$ ms, un eveniment sosit la exact 5 perioade după cel acceptat poate fi respins, deoarece jitterul
îl poate face să sosească la 4999 ms în loc de 5000 ms. Situația a apărut și în teste. Regula este cea cerută:
intervalul minim este impus strict.

### TODO 3 – task aperiodic (`AperiodicCommandTask`)

- Preia comenzile cu `take()`, în ordinea sosirii. Numele comenzii nu ține cont de majuscule/minuscule. Parametrii sunt validați:
  `SET_INTERVAL` acceptă un întreg între 100 și 60000 ms, `SET_THRESHOLD` acceptă un număr finit (sunt acceptate atât `.`, cât și `,`), iar `RESET` și `STATUS` nu au parametri.
- Confirmare pe `status`: `device-1;<ts>;OK SET_INTERVAL 1000`, `OK SET_THRESHOLD 25.50`, `OK RESET`,
  `OK STATUS interval=2000 threshold=28.00`.
- O comandă greșită produce `ERROR <motiv>` (de exemplu `ERROR comanda necunoscuta: FOO`,
  `ERROR SET_INTERVAL: 'abc' nu este un numar intreg`), iar taskul continuă. Orice `RuntimeException` este prinsă.
- Textele publicate sunt ASCII și nu conțin `;`, ca să nu strice formatul CSV și să fie citite corect indiferent de codificarea implicită.

## Business service

### TODO 4 – `MessageProcessor`

- Fiecare mesaj este salvat **exact cum a sosit** cu `CsvStorage`, apoi este interpretat. Un mesaj cu format greșit
  (număr greșit de câmpuri, număr invalid) este afișat ca ignorat, iar thread-ul continuă.
- Telemetria actualizează dashboard-ul. Alarmele (`*** ALARMĂ ...`) și confirmările (`<<< device-1: OK ...`) se afișează imediat.
- Pentru `status`, textul este al treilea câmp (`split(";", 3)`) și poate conține spații.

### TODO 5 – `Dashboard`

- Folosește un `TreeMap<String, DeviceState>` (device-urile apar sortate în tabel). Toate metodele sunt `synchronized`.
  `print()` copiază starea sub lacăt și afișează în afara lui, ca să nu blocheze thread-urile de procesare.
- Tabelul conține: ultima temperatură, vechimea ultimului mesaj, starea `activ`/`INACTIV` (fără mesaje de 3 perioade),
  perioada estimată din timestamp-urile telemetriei, numărul de mesaje, numărul de alarme și ultima confirmare.
- Vechimea se calculează cu ceasul serviciului (momentul recepției), nu cu timestamp-ul device-ului. Astfel nu este afectată de diferențele dintre ceasurile mașinilor.

```
DEVICE           TEMP ULTIMUL MESAJ   STARE     T (ms)   MESAJE   ALARME  ULTIMA CONFIRMARE
----------------------------------------------------------------------------------------------------
device-1        28.76 acum 0 s        activ        500       24        2  OK RESET
device-2        27.71 acum 1 s        activ       2000        8        0  ERROR SET_INTERVAL: perioada trebuie sa fie intre 100 si 60000 ms
```

### TODO 6 – `CommandConsole`

- `device-1 SET_INTERVAL 1000` este separat în `deviceId` și restul liniei (`split("\\s+", 2)`) și se publică pe
  `ssatr/pasca/devices/device-1/commands`. Numele comenzii este trecut cu majuscule.
- Erori tratate: o linie fără comandă afișează formatul corect. Un `deviceId` cu `+`, `#` sau `/` este refuzat, ca să nu se
  trimită comanda la mai multe device-uri prin wildcard. Pentru un device care nu a trimis încă nimic se afișează un avertisment, dar comanda se trimite.
- Validarea completă a comenzii este făcută de device, care răspunde cu `ERROR ...`. Astfel, regulile sunt definite într-un singur loc.

## Diagrame (`diagrame/`)

Sursa este `diagrame.drawio`, cu câte un tab pentru fiecare diagramă. Imaginile sunt exportate din draw.io:

| Imagine | Conținut |
|---------|----------|
| `diagrama-de-componente.png` | device-simulator, brokerul MQTT (oferă interfața IMqtt), business-service, cu părțile interne, cozile și topic-urile |
| `secventa-alarma.png` | telemetria depășește pragul → eveniment în coadă → taskul sporadic publică alarma (fragment `alt` pentru alarma respinsă) |
| `secventa-set-interval.png` | operatorul trimite `SET_INTERVAL 1000` → jobul aperiodic schimbă perioada → confirmarea ajunge înapoi în consolă |
| `diagrama-de-timp.png` | 10 s: eliberările și joburile lui τ1 (T = 2000 → 1000 ms), o alarmă acceptată, una respinsă, una acceptată din nou, și jobul aperiodic `SET_INTERVAL` |

## Testare

Testele s-au făcut cu un broker MQTT local, cu două device-uri și business-service-ul alimentat cu un script de comenzi:
`SET_INTERVAL` (mărire și micșorare), `SET_THRESHOLD`, `STATUS`, `RESET`, comenzi greșite (`SET_INTERVAL abc`, `FOO`,
`RESET 5`, `SET_INTERVAL 50`), linie fără comandă și device necunoscut. S-au verificat fișierele `data/telemetry.csv`,
`alarm.csv` și `status.csv`, precum și intervalele dintre timestamp-urile telemetriei. Codul compilează cu `--release 17` fără avertismente.
