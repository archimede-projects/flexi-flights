# VolaFlex — PROJECT_SPEC

**Repository:** `archimede-projects/flexi-flights` (privata)  
**Nome app:** VolaFlex  
**Application ID / namespace:** `com.archimedeprojects.volaflex`  
**Ruolo:** fonte di verità persistente del progetto  
**Ultimo aggiornamento:** 2026-09-10

## Regola di manutenzione

Aggiornare questo file ogni volta che cambia un requisito, una decisione architetturale, una tecnologia, una policy API/quota o lo stato reale. `SESSION_HANDOFF.md` contiene lo stato operativo sintetico da leggere all'inizio di una nuova chat.

---

# 1. Obiettivo e vincoli permanenti

VolaFlex è un'app Android personale per trovare voli economici con forte supporto a date flessibili, weekend, multi-aeroporto, geografia avanzata e filtri sugli scali.

Vincoli permanenti:

- uso personale e distribuzione APK via sideload;
- niente Play Store per ora;
- **zero costi**;
- **nessuna carta di credito/debito**;
- niente backend/server a pagamento;
- sviluppo GitHub-only;
- repository GitHub privata;
- nessun Android Studio locale;
- GitHub Actions per CI/build;
- GitHub Releases per distribuire l'APK;
- GitHub Codespaces opzionale;
- test runtime/UI su telefono Android reale;
- nessuna API key o segreto nel repository/chat;
- proprietario del progetto principiante assoluto.

---

# 2. Requisiti funzionali originali

1. Weekend flessibili: partenza venerdì sera oppure sabato mattina; ritorno domenica sera oppure lunedì.
2. N notti: origine + destinazione + numero esatto di notti, data target con ±X giorni; trovare il periodo più economico.
3. Data target ±X giorni.
4. Range ampi di date evitando brute force quando esistono metodi più efficienti.
5. Multi-origine fino a 3 alternative.
6. Destinazione singola, fino a 3 aeroporti alternativi, Ovunque oppure intero paese.
7. Esclusione paese di scalo.
8. Stessa compagnia operativa su tutti i segmenti quando richiesto.
9. Durata massima scalo.
10. Dettaglio scalo: aeroporto, città, paese, durata, overnight.
11. Scali >8h: pulsante Maps via Android Intent; niente Maps SDK.

Per v3 la destinazione è una scelta esclusiva fra `Airports`, `Anywhere`, `Country`: **niente destinazioni composite** come lista aeroporti + paese nello stesso input.

---

# 3. Architettura obbligatoria

Pattern generale:

`UI → logica ricerca → provider → cache/database`

Pattern dati:

**DISCOVERY → VERIFICA → DETTAGLIO**

## Discovery

Ridurre il numero di query usando, a seconda del caso:

- SerpApi Google Travel Explore;
- SearchAPI.io Google Flights Calendar;
- Google Flights Deals quando pertinente;
- query multi-aeroporto native;
- cache locale;
- campionamento euristico.

## Verifica

Verificare solo pochi candidati migliori con Google Flights preciso.

## Dettaglio

Recuperare on-demand solo quando serve:

- `departure_token` / ritorno associato;
- segmenti;
- scali;
- operating carrier/codeshare;
- paese degli scali;
- stessa compagnia;
- dettaglio scalo;
- Maps.

Mai scaricare automaticamente il dettaglio completo di decine di risultati.

## Protezione anti-esplosione combinatoria v3

Non eseguire il prodotto cartesiano ingenuo `origini × destinazioni × date × pattern`. Preferire nell'ordine:

1. parametri multi-airport nativi del provider;
2. Explore per ridurre la geografia;
3. Calendar per ridurre le date;
4. verifica dei soli migliori 1–2 candidati;
5. Account API live prima dei batch;
6. cache TTL 4h;
7. diagnostica della strategia eseguita.

v3.1 e v3.2 hanno validato end-to-end che Date fisse usa il multi-aeroporto nativo senza fan-out: più origini e più destinazioni restano una sola query Google Flights. v3.3 estende la stessa regola alla pipeline N notti: una query per data SerpApi o un blocco Calendar, indipendentemente dal numero di aeroporti nelle liste.

---

# 4. Provider dati

## 4.1 SerpApi — provider primario

Ruoli:

- `google_flights`: ricerche precise e verifiche finali;
- `google_travel_explore`: Discovery economica per weekend/Ovunque/paesi/range ampi;
- Google Flights Deals: Discovery quando il caso d'uso coincide con i suoi parametri.

### Google Flights

Decisioni/API verificate:

- `engine=google_flights`;
- `type=1` round-trip;
- `departure_id` accetta aeroporti multipli comma-separated;
- `arrival_id` accetta aeroporti multipli comma-separated;
- multi-origine + multi-destinazione può essere espresso nella stessa richiesta;
- `outbound_date`, `return_date` in `YYYY-MM-DD`;
- `outbound_times` / `return_times`: 2 o 4 ore intere 0–23 separate da virgola, es. `17,23`, `5,11`, `0,23`, `4,18,3,19`;
- `layover_duration=MIN,MAX` in minuti;
- `exclude_conns` per aeroporti di connessione;
- `include_airlines` / `exclude_airlines`;
- stessa compagnia operativa: controllo Kotlin;
- esclusione paese connessione: non nativa → lookup IATA→paese;
- risposta: `best_flights` / `other_flights`, segmenti con `departure_airport.id/time`, `arrival_airport.id/time`;
- origine effettiva dell'andata = `departure_airport.id` del primo segmento;
- destinazione effettiva dell'andata = `arrival_airport.id` dell'ultimo segmento;
- dettaglio esatto del ritorno round-trip richiede una seconda richiesta con `departure_token`.

Da v2.2 esiste `SerpApiTimeFilterGuard`: blocca localmente formati orari non validi prima della rete.

### Account API / quota condivisa

La chiave SerpApi è condivisa con un altro progetto personale; non creare un secondo account se richiede numero di telefono.

Fonte autorevole della quota: Account API live. Non mantenere un contatore locale autorevole.

Regole:

- >50: normale;
- <=50: cache/risparmio più aggressivi;
- <=20: forte prudenza/conferma futura per batch >5;
- <=5: niente batch costoso automatico;
- preservare una riserva minima di 5 query;
- mai 30–40 chiamate automatiche con un tap.

Account API è trattata come gratuita/non conteggiata nella quota normale, secondo documentazione verificata.

## 4.2 SearchAPI.io Calendar — acceleratore mirato

Endpoint:

`GET https://www.searchapi.io/api/v1/search?engine=google_flights_calendar`

Ruolo: date-discovery accelerator per N notti / ±X e range ampi.

Regole:

- SearchAPI.io è opzionale architetturalmente; chiave attualmente configurata sul telefono;
- `departure_id` e `arrival_id` supportano più aeroporti/location comma-separated;
- massimo circa 200 combinazioni round-trip per richiesta;
- blocco scelto: massimo 14 partenze perché `14×14=196`, mentre `15×15=225`;
- per N notti si filtra localmente la diagonale `return = departure + N`;
- oltre 10 date, se chiave disponibile: Calendar → 1 verifica SerpApi precisa;
- senza chiave e >10 date: campionamento SerpApi 5 date + fino a 2 vicine;
- ≤10 date: SerpApi esaustiva;
- da v3.3 le liste multi-aeroporto vengono passate direttamente al Calendar e non moltiplicano il numero dei blocchi temporali.

Esempi Calendar:

- ±5 → 11 partenze → 1 Calendar;
- ±7 → 15 → 2 Calendar;
- ±10 → 21 → 2 Calendar;
- ±15 → 31 → 3 Calendar;
- ±20 → 41 → 3 Calendar.

## 4.3 Google Travel Explore

Uso:

- `engine=google_travel_explore`;
- `travel_duration=1` = Weekend;
- `departure_id` supporta origini multiple comma-separated;
- `arrival_id` può restringere a destinazione specifica;
- “Ovunque”: omettere `arrival_id` e `arrival_area_id`;
- paese/area: usare `arrival_area_id` con KGMID.

### Rischio provider Explore — importante per v3.4/v3.5

La cronologia ufficiale dei release notes 2026 mostra bug/fix recenti su filtri e risultati:

- gennaio 2026: correzione del comportamento `travel_duration` Weekend;
- febbraio 2026: correzione di `max_duration` ignorato;
- marzo 2026: correzione di `stops` ignorato;
- **09/07/2026:** correzione di un problema per cui la maggior parte delle ricerche valide poteva restituire risultati vuoti.

Decisione obbligatoria per v3.4/v3.5: implementare gestione robusta di risposte vuote/anomale. Una risposta Explore vuota non va trattata automaticamente come prova certa di “nessuna opportunità”; registrare diagnostica, distinguere HTTP/body/error/struttura anomala e mostrare un messaggio prudente quando il provider sembra non affidabile.

## 4.4 Mapping preset da non confondere

Travel Explore:

- `1` Weekend;
- `2` 1 week;
- `3` 2 weeks.

Google Flights Deals:

- `1` 1 week;
- `2` Weekend;
- `3` 2 weeks.

`price_insights` non è un calendario ±X: descrive statisticamente stessa rotta/date.

---

# 5. Credenziali API e sicurezza

API key salvate localmente con DataStore Preferences; mai nel repository/chat; `android:allowBackup=false`.

- SerpApi: obbligatoria per ricerche reali;
- SearchAPI.io: opzionale architetturalmente e attualmente configurata;
- UI mostra soltanto configurata/non configurata;
- campi password-style;
- campo vuoto al salvataggio mantiene il valore esistente.

DataStore non cifra autonomamente a riposo; rischio accettato per app personale nello storage privato Android.

---

# 6. Geografia locale

## 6.1 AirportDirectory

File:

`data/local/AirportDirectory.kt`

Mapping:

`IATA → nome aeroporto → città → ISO country`

Copertura iniziale: circa 180–200 aeroporti principali, Europa + Nord Africa + destinazioni comuni.

Usi attivi:

- guard anti-typo prima della rete;
- classificazione geografica;
- validazione di ogni elemento delle liste multi-origine e multi-destinazione su Date fisse e N notti.

Usi futuri:

- paese scali;
- autocomplete;
- filtro paese scalo.

Codice sconosciuto: warning `Correggi` / `Cerca comunque`, senza query automatica. Test `FC0`: PASS, 0 query.

## 6.2 CountryAreaCatalog — futuro v3.5

Per `arrival_area_id` il mapping IATA→ISO non basta: Explore richiede KGMID.

Piano approvato:

- catalogo statico locale `CountryAreaCatalog`;
- nome paese + ISO-2 + KGMID;
- almeno Europa + Nord Africa + destinazioni comuni;
- zero rete e zero costo.

## 6.3 Modello geografico v3 approvato

- `OriginSelection.Airports(List<IATA>)`, max 3;
- `DestinationSelection.Airports(List<IATA>)`, max 3;
- `DestinationSelection.Anywhere`;
- `DestinationSelection.Country(ISO2/KGMID)`.

Le modalità destinazione sono alternative, non composite.

v3.1/v3.2 implementano `Airports(max 3)` sui due assi di Date fisse. v3.3 riusa lo stesso modello nella modalità N notti. Il sealed model geografico generale potrà essere formalizzato quando verranno aggiunti Anywhere/Country.

---

# 7. Cache Room e Diagnostica

Database: `volaflex.db`.

Schema corrente: **versione 3**.

Tabelle:

- `flight_search_cache` — date fisse;
- `weekend_search_cache` — weekend;
- `nights_search_cache` — N notti / ±X;
- `diagnostic_events`.

TTL cache ricerche: **4 ore**.

Migrazioni validate:

- 1→2: `weekend_search_cache`;
- 2→3: `nights_search_cache`.

### Date fisse multi-aeroporto — v3.1/v3.2

Nessuna migrazione Room necessaria.

Cache key:

`origini canonicalizzate + destinazioni canonicalizzate + data andata + data ritorno`

Sia origini sia destinazioni vengono normalizzate, deduplicate e ordinate alfabeticamente. `flight_search_cache.departureId` e `.arrivalId` memorizzano la coppia effettiva vincente.

### N notti multi-aeroporto — v3.3

Nessuna migrazione Room necessaria; schema resta 3.

Cache key:

`NIGHTS | origini canonicalizzate | destinazioni canonicalizzate | N notti | target | ±X | strategia`

Le strategie rimangono:

- `SERP_EXHAUSTIVE`;
- `SERP_SAMPLE`;
- `SEARCHAPI_CALENDAR`.

Questo evita collisioni fra strategie e rende equivalenti ordini diversi delle stesse liste. I campi stringa `nights_search_cache.departureId` / `.arrivalId` contengono le liste canonicalizzate richieste. La coppia effettiva vincente è salvata nel JSON del risultato.

`NightsSearchResult` aggiunge `departureAirportId` e `arrivalAirportId` con default retrocompatibili, quindi vecchie cache JSON prive dei campi restano decodificabili.

### Diagnostica

Conserva gli ultimi 20 eventi.

Tipi correnti:

- `SERPAPI_ACCOUNT`;
- `GOOGLE_FLIGHTS`;
- `TRAVEL_EXPLORE`;
- `WEEKEND_VERIFY`;
- `SEARCHAPI_CALENDAR`;
- `CACHE`;
- `QUOTA_GUARD`.

Nessuna API key nei log. `Copia diagnostica` disponibile.

Da v3.3 gli eventi N notti includono `origins=<lista>`, `destinations=<lista>` e, per Google Flights con risultato, `winner=<origine>→<destinazione>`.

---

# 8. Modelli dati principali

## Correnti

### `SimpleFlightResult`

Include origine/destinazione effettive, prezzo, valuta, compagnia/e, orari andata, scali, quota e cache metadata.

### `WeekendCandidate`

Discovery indicativa Travel Explore.

### `VerifiedWeekendResult`

Risultato v2.2 verificato con pattern/date/prezzo/compagnia/orari/scali andata/fascia ritorno/quota/cache.

### `NightsSearchResult`

Da v3.3 include:

- date andata/ritorno e N notti;
- prezzo/valuta;
- compagnia, orari e scali andata;
- **origine effettiva** dal primo segmento;
- **destinazione effettiva** dall'ultimo segmento;
- target ±X;
- strategia;
- numero date candidate/valutate;
- prezzo indicativo Calendar quando presente;
- conteggi richieste provider;
- quota SerpApi;
- metadata cache.

## Futuri

`FlightItinerary`, `FlightSegment`, `Layover` rimangono il modello finale per Dettaglio e v4.

---

# 9. Stack tecnologico corrente

- Kotlin + Jetpack Compose;
- AGP 9.3.1;
- Kotlin/Compose compiler 2.4.20;
- Gradle 9.5.0;
- Compose BOM 2026.06.00;
- Activity Compose 1.11.0;
- Navigation Compose 2.9.8;
- DataStore Preferences 1.2.1;
- Retrofit 3.0.0;
- converter Kotlin Serialization 3.0.0;
- OkHttp 4.12.0;
- kotlinx-serialization-json 1.11.0;
- Room 2.8.4;
- KSP 2.3.11;
- compileSdk/targetSdk 36;
- minSdk 26;
- JDK 17.

AGP 9.x usa Kotlin integrato: non applicare `org.jetbrains.kotlin.android`.

---

# 10. CI, Release e firma

Workflow:

`GitHub → Actions → Gradle → assembleRelease → zipalign → apksigner → GitHub Release → telefono`

Release sviluppo:

- tag `dev-latest`;
- titolo `VolaFlex - Development latest`;
- asset `VolaFlex-dev.apk`.

`PROJECT_SPEC.md`, `SESSION_HANDOFF.md` e `.github/workflows/android-build.yml` sono esclusi dal trigger push.

Firma persistente canonica:

`a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`

Signer:

`CN=VolaFlex, OU=Personal, O=archimede-projects`

RSA4096. Keystore con doppio backup personale.

### CI v3.1

Run #23 SUCCESS, versione `0.1.0-dev.23`, fingerprint invariato.

### CI v3.2

Run #24 SUCCESS, versione `0.1.0-dev.24`, commit applicativo `766fe25e9c25e5395c1905ff2337d443b28739b9`, `BUILD SUCCESSFUL in 2m 9s`, APK SHA-256 `edd5ff433f7b5d399ce1eb4778643872b822f1da849dc8169b28559906161265`, fingerprint invariato.

### CI v3.3

Implementazione su `main` al commit applicativo `8ebfa88085d8db9a9240fe91b919df02adfa137a`. Run #25 avviato; aggiornare qui a SUCCESS/firma/Release solo dopo chiusura verde.

---

# 11. Stato roadmap e test reali

## Fase 0 — Infrastruttura

**CHIUSA 100% E VALIDATA.**

CI, Release, firma persistente e upgrade reale senza disinstallazione validati.

## v1 — Fondamenta

**CHIUSA E VALIDATA 100% SUL TELEFONO.**

Include API key locali, prima ricerca reale, IATA guard, cache Room 4h, Diagnostica/clipboard.

Test storico: `FCO→MAD`, 16–19/10/2026, Ryanair 104 EUR, 0 scali andata. Round finale IATA/cache/diagnostica: 1 query osservata, coerente con la stima.

## v2 — Date flessibili

**COMPLETAMENTE CHIUSA E VALIDATA SUL TELEFONO REALE — 2026-09-10.**

### v2.1 Weekend Discovery — PASS

`FCO→MAD`, ottobre 2026, Explore 01/10→05/10, 95 EUR, 1 query, cache PASS.

### v2.2 Weekend Verifica precisa — PASS

`FCO→MAD`, novembre 2026: Explore 57 EUR → Pattern B Wizz Air 54 EUR, partenza 06:00 dentro `5,11`, 0 scali; 3 query iniziali e 0 replay.

### v2.3 N notti ramo SerpApi — PASS

`FCO→MAD`, 3 notti, target 25/10/2026, ±5: 11 candidate, 7 valutate; Ryanair 28→31/10, 53 EUR, 06:25→09:00, 0 scali; replay cache 0.

### v2.3 N notti ramo SearchAPI.io Calendar — PASS

Ramo Calendar + verifica SerpApi e cache validati sul telefono reale.

**Conclusione: v2 CHIUSA.**

## v3 — Geografia avanzata

Sequenza approvata:

1. v3.1 Multi-origine — Date fisse;
2. v3.2 Multi-destinazione + 3×3 — Date fisse;
3. **v3.3 Multi-aeroporto — N notti**;
4. v3.4 Anywhere — Discovery;
5. v3.5 Country — Discovery;
6. v3.6 Anywhere/Country — Verifica Weekend;
7. v3.7 integrazioni estreme + hardening.

### v3.1 — Multi-origine Date fisse

**CHIUSA E VALIDATA SUL TELEFONO REALE.**

Test reale: `FCO + CIA + MXP → MAD`, 20/11/2026→23/11/2026, 1 query Google Flights, vincitore **MXP**, **60 EUR**. Replay con origini riordinate → CACHE HIT, 0 query.

### v3.2 — Multi-destinazione Date fisse

**CHIUSA E VALIDATA SUL TELEFONO REALE — 2026-09-10.**

Test completi PASS:

- UI multi-destinazione add/remove/max 3;
- duplicati destinazione bloccati localmente;
- sovrapposizione origine/destinazione bloccata localmente;
- live `FCO + CIA → BCN + MAD + VLC` su date fisse;
- **1 sola query Google Flights** nonostante 2×3 aeroporti;
- vincitore **FCO→BCN**;
- prezzo **48 EUR**;
- Diagnostica ha confermato liste canonicalizzate e una sola richiesta;
- replay con origini E destinazioni riordinate → **CACHE HIT**, 0 nuove query.

**Conclusione: canonicalizzazione multi-aeroporto su entrambi gli assi validata end-to-end.**

### v3.3 — Multi-aeroporto dentro N notti/±X

**IMPLEMENTATA SU MAIN; CI/TEST TELEFONO DA CHIUDERE.**

Scope implementato e solo questo:

- modalità N notti con 1–3 origini e 1–3 destinazioni;
- UI add/remove coerente con Date fisse;
- IATA guard su ogni elemento;
- duplicati bloccati per ciascun asse;
- sovrapposizione origine/destinazione vietata;
- canonicalizzazione trim/uppercase/dedup/sort su entrambi gli assi;
- SerpApi esaustivo: liste comma-separated, una query per data;
- fallback senza SearchAPI: stesso campionamento 5 + fino a 2 vicine, sempre con liste aggregate;
- SearchAPI.io Calendar: liste comma-separated, chunking temporale max 14×14=196 invariato;
- verifica Calendar finale: 1 SerpApi Google Flights con le stesse liste;
- cache key include origini, destinazioni, N notti, target, ±X e strategia;
- risultato mostra origine e destinazione effettive vincenti;
- nessuna migrazione Room e nessuna modifica a Weekend/Explore/Anywhere/Country.

**Non avanzare a v3.4 finché v3.3 non è validata sul telefono.**

---

# 12. Rischi noti e accettati

- provider possono cambiare JSON/parametri o avere downtime;
- SerpApi e SearchAPI.io dipendono dall'ecosistema Google Flights;
- quota SerpApi è limitata e condivisa;
- SearchAPI.io free pool può essere limitato/one-time;
- ricerca euristica senza Calendar non è matematicamente esaustiva;
- perdita keystore impedisce aggiornamenti con stessa identità;
- DataStore non cifra autonomamente le API key a riposo;
- AirportDirectory non è esaustiva;
- per i paesi serve un catalogo KGMID aggiuntivo;
- liste multi-airport richiedono canonicalizzazione cache su entrambi gli assi e su ogni strategia temporale;
- Explore è Discovery, non garantisce da solo vincoli precisi;
- **Explore ha avuto regressioni/fix recenti nel 2026, incluse risposte vuote per ricerche valide: v3.4/v3.5 devono implementare error handling/diagnostica particolarmente robusti**;
- dettaglio preciso ritorno via `departure_token` resta on-demand;
- combinazioni geografiche estreme richiedono controllo quota e non brute force.

---

# 13. Prossimo milestone operativo

1. Attendere/verificare CI v3.3 #25 e pubblicazione Release firmata.
2. Testare sul telefono il ramo SerpApi esaustivo N notti con 2 origini + 2/3 destinazioni e un range piccolo.
3. Verificare origine/destinazione effettive, numero query e Diagnostica.
4. Riordinare entrambi gli assi e verificare cache hit della stessa strategia.
5. Testare separatamente il ramo SearchAPI Calendar con >10 date candidate e liste multi-aeroporto.
6. Verificare 2 Calendar + 1 SerpApi finale nel caso ±7 e replay cache a 0 query.
7. Se entrambi PASS, segnare v3.3 CHIUSA e solo allora implementare v3.4 Anywhere.
