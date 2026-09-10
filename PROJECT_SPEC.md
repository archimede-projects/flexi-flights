# VolaFlex — PROJECT_SPEC

**Repository:** `archimede-projects/flexi-flights` (privata)  
**Nome app:** VolaFlex  
**Application ID / namespace:** `com.archimedeprojects.volaflex`  
**Ruolo:** fonte di verità persistente del progetto  
**Ultimo aggiornamento:** 2026-09-10

## Regola di manutenzione

Aggiornare questo file dopo ogni requisito/decisione/modifica/step completato. `SESSION_HANDOFF.md` contiene il riepilogo operativo sintetico per riprendere il lavoro in una nuova chat.

---

# 1. Obiettivo e vincoli permanenti

VolaFlex è un'app Android personale per trovare voli economici con date flessibili, weekend, multi-aeroporto, geografia avanzata e futuri filtri sugli scali.

Vincoli permanenti:

- uso personale, APK sideload, niente Play Store per ora;
- **zero costi** e **nessuna carta di credito/debito**;
- niente backend/server a pagamento;
- sviluppo GitHub-only, repository privata;
- niente Android Studio locale;
- GitHub Actions per CI/build e GitHub Releases per APK;
- Codespaces opzionale;
- test runtime/UI su telefono Android reale;
- nessuna API key/keystore/password nel repository o in chat;
- proprietario principiante assoluto: mantenere workflow semplici e verificabili.

---

# 2. Requisiti funzionali originali

1. Weekend flessibili: partenza venerdì sera oppure sabato mattina; ritorno domenica sera oppure lunedì.
2. N notti: origine + destinazione + numero esatto di notti, data target con ±X giorni; trovare il periodo più economico.
3. Range ampi senza brute force inutile.
4. Multi-origine fino a 3 aeroporti.
5. Destinazione: singolo aeroporto, fino a 3 aeroporti alternativi, Ovunque oppure intero paese.
6. Esclusione paese di scalo.
7. Stessa compagnia operativa su tutti i segmenti quando richiesto.
8. Durata massima scalo.
9. Dettaglio scalo: aeroporto, città, paese, durata, overnight.
10. Scali >8h: pulsante Maps via Android Intent; niente Maps SDK.

Per v3 la destinazione è una scelta esclusiva fra `Airports`, `Anywhere`, `Country`: **niente destinazioni composite** come lista aeroporti + paese nello stesso input.

---

# 3. Architettura obbligatoria

Pattern generale:

`UI → logica ricerca → provider → cache/database`

Pattern dati:

**DISCOVERY → VERIFICA → DETTAGLIO**

## Discovery

Ridurre le query usando, secondo il caso:

- SerpApi Google Travel Explore;
- SearchAPI.io Google Flights Calendar;
- query multi-aeroporto native;
- cache locale;
- campionamento euristico.

## Verifica

Verificare solo pochi candidati migliori con SerpApi Google Flights preciso.

## Dettaglio

Recuperare on-demand solo quando serve:

- `departure_token` / ritorno associato;
- segmenti;
- scali;
- operating carrier/codeshare;
- paese degli scali;
- stessa compagnia;
- Maps.

## Protezione anti-esplosione combinatoria

Non eseguire il prodotto cartesiano ingenuo `origini × destinazioni × date × pattern`. Preferire:

1. parametri multi-airport nativi;
2. Explore per ridurre la geografia;
3. Calendar per ridurre le date;
4. verifica solo dei migliori 1–2 candidati;
5. Account API live prima dei batch;
6. cache TTL 4h;
7. diagnostica della strategia.

v3.1/v3.2 hanno validato end-to-end che Google Flights gestisce più origini/destinazioni in una sola query. v3.3 ha validato la stessa logica nella pipeline N notti sia con SerpApi diretto sia con SearchAPI Calendar. **Tutta la parte v3.1–v3.3 di geografia nativa via liste comma-separated è chiusa e validata.**

---

# 4. Provider dati

## 4.1 SerpApi — provider primario

Ruoli:

- `google_flights`: ricerche precise e verifiche finali;
- `google_travel_explore`: Discovery economica per weekend/Ovunque/paesi;
- Account API: quota live autorevole.

### Google Flights

Decisioni/API verificate:

- `engine=google_flights`;
- `type=1` round-trip;
- `departure_id` e `arrival_id` accettano più aeroporti/location comma-separated;
- multi-origine + multi-destinazione può quindi stare nella stessa richiesta;
- `outbound_date`, `return_date`: `YYYY-MM-DD`;
- `outbound_times` / `return_times`: 2 o 4 ore intere 0–23 separate da virgola, es. `17,23`, `5,11`, `0,23`, `4,18,3,19`;
- `layover_duration=MIN,MAX` in minuti;
- `exclude_conns` per aeroporti connessione;
- `include_airlines` / `exclude_airlines`;
- stessa compagnia operativa: controllo Kotlin;
- esclusione paese connessione: non nativa → lookup IATA→paese;
- risultato andata in `best_flights` / `other_flights` con segmenti `departure_airport.id/time`, `arrival_airport.id/time`;
- origine effettiva = primo segmento; destinazione effettiva = ultimo segmento;
- dettaglio esatto del ritorno round-trip richiede una seconda richiesta con `departure_token`.

Da v2.2 esiste `SerpApiTimeFilterGuard`, che blocca localmente filtri orari malformati prima della rete.

### Quota condivisa

La chiave SerpApi è condivisa con un altro progetto personale. Fonte autorevole: Account API live. Non mantenere un contatore locale autorevole.

Regole:

- >50: normale;
- <=50: cache/risparmio più aggressivi;
- <=20: forte prudenza per batch >5;
- <=5: niente batch automatico;
- riserva minima: 5 query;
- mai 30–40 chiamate automatiche con un tap.

Account API è trattata come gratuita/non conteggiata nella quota normale secondo docs verificate.

## 4.2 SearchAPI.io Calendar — acceleratore mirato

Endpoint: `https://www.searchapi.io/api/v1/search?engine=google_flights_calendar`

Ruolo: discovery date per N notti / ±X.

Regole:

- chiave opzionale architetturalmente; attualmente configurata sul telefono;
- `departure_id` e `arrival_id` supportano più aeroporti/location comma-separated;
- limite noto ~200 combinazioni date round-trip per richiesta;
- max side scelto 14 perché `14×14=196`, mentre `15×15=225`;
- per N notti si filtra localmente la diagonale `return = departure + N`;
- ≤10 partenze candidate: SerpApi esaustiva;
- >10 + SearchAPI configurata: Calendar → 1 verifica SerpApi;
- >10 senza SearchAPI: SerpApi sampling 5 + fino a 2 vicine;
- da v3.3 le liste multi-aeroporto vengono passate direttamente al Calendar e non moltiplicano i blocchi.

Esempi temporali:

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
- `arrival_id` è opzionale e restringe a destinazione specifica;
- **Ovunque:** omettere sia `arrival_id` sia `arrival_area_id`;
- paese/area: `arrival_area_id` con KGMID.

Per una ricerca destinazioni senza arrivo specificato, la risposta documentata usa `destinations[]` con almeno dati come `name`, `country`, `destination_airport.code`, `start_date`, `end_date`, `flight_price` quando disponibili.

### Rischio provider Explore — obbligatorio v3.4/v3.5

Release notes ufficiali 2026 mostrano fix recenti su:

- gennaio: Weekend `travel_duration`;
- febbraio: `max_duration` ignorato;
- marzo: `stops` ignorato;
- **09/07/2026:** molte ricerche valide potevano tornare vuote.

Decisione implementata da v3.4: una risposta Explore vuota/anomala non viene trattata automaticamente come “nessun volo”. Per Anywhere il parser usa JSON raw per distinguere assenza del campo `destinations` da lista presente ma vuota.

Classificazioni diagnostiche v3.4 `TRAVEL_EXPLORE`:

- `HTTP_ERROR` — status HTTP non 2xx;
- `PROVIDER_ERROR` — `error` esplicito nel payload;
- `BODY_MISSING` — HTTP valido ma body assente;
- `STRUCTURE_ANOMALY` — struttura/campi essenziali inattesi o incoerenti;
- `SURPRISING_EMPTY` — `destinations` presente ma lista vuota su una ricerca Ovunque ampia; non viene interpretato come prova di assenza voli;
- `NO_OPPORTUNITIES` — struttura valida/non anomala ma nessuna offerta weekend prezzata utilizzabile;
- `SUCCESS` — candidati validi;
- `NETWORK_ERROR` — errore di connettività.

### Mapping preset da non confondere

Travel Explore: `1 Weekend`, `2 1 week`, `3 2 weeks`.
Google Flights Deals: `1 1 week`, `2 Weekend`, `3 2 weeks`.

---

# 5. Credenziali API e sicurezza

API key salvate localmente con DataStore Preferences; mai repo/chat; `android:allowBackup=false`.

- SerpApi obbligatoria per ricerche reali;
- SearchAPI.io opzionale ma attualmente configurata;
- UI mostra configurata/non configurata;
- campi password-style;
- campo vuoto al salvataggio conserva il valore esistente.

DataStore non cifra autonomamente a riposo; rischio accettato per app personale nello storage privato Android.

---

# 6. Geografia locale

## AirportDirectory

File `data/local/AirportDirectory.kt`.

Mapping:

`IATA → nome aeroporto → città → ISO country`

Circa 180–200 aeroporti principali, Europa + Nord Africa + destinazioni comuni.

Usi attivi:

- guard anti-typo;
- classificazione geografica;
- validazione aeroporti Date fisse, N notti e origini Weekend/Ovunque.

Codice sconosciuto: warning `Correggi` / `Cerca comunque`; con `Correggi` nessuna query.

## CountryAreaCatalog — futuro v3.5

Per `arrival_area_id` l'ISO country non basta: Explore richiede KGMID. Piano: catalogo statico locale `nome + ISO2 + KGMID`, almeno Europa/Nord Africa/destinazioni comuni, zero rete/costo.

## Modello geografico v3 approvato

- `OriginSelection.Airports(List<IATA>)`, max 3;
- `DestinationSelection.Airports(List<IATA>)`, max 3;
- `DestinationSelection.Anywhere`;
- `DestinationSelection.Country(ISO2/KGMID)`.

Le modalità destinazione sono alternative, non composite.

---

# 7. Cache Room e Diagnostica

Database `volaflex.db`, schema corrente **3**.

Tabelle:

- `flight_search_cache` — Date fisse;
- `weekend_search_cache` — Weekend, incluso Anywhere v3.4;
- `nights_search_cache` — N notti / ±X;
- `diagnostic_events`.

TTL cache: **4 ore**.

Migrazioni validate sul telefono:

- 1→2 Weekend;
- 2→3 N notti.

## Date fisse multi-aeroporto — v3.1/v3.2

Nessuna migrazione Room.

Cache key:

`origini canonicalizzate | destinazioni canonicalizzate | andata | ritorno`

Canonicalizzazione: trim, uppercase, dedup, sort alfabetico.

`flight_search_cache.departureId` / `.arrivalId` memorizzano la coppia effettiva vincente.

## N notti multi-aeroporto — v3.3

Nessuna migrazione Room; schema resta 3.

Cache key:

`NIGHTS | origini canonicalizzate | destinazioni canonicalizzate | N | target | ±X | strategia`

Strategie:

- `SERP_EXHAUSTIVE`;
- `SERP_SAMPLE`;
- `SEARCHAPI_CALENDAR`.

I campi stringa esistenti `nights_search_cache.departureId` / `.arrivalId` memorizzano le liste canonicalizzate richieste. La coppia effettiva vincente è nel JSON risultato.

`NightsSearchResult` da v3.3 aggiunge `departureAirportId` e `arrivalAirportId` con default retrocompatibili.

## Anywhere Weekend — v3.4

Nessuna migrazione Room; riuso della tabella `weekend_search_cache`.

Cache key:

`WEEKEND | origini canonicalizzate | ANYWHERE | periodo`

Esempio: `WEEKEND|CIA,FCO|ANYWHERE|2026-12`.

`arrivalId` del record cache usa il marker `ANYWHERE`. Il replay con le stesse origini in ordine diverso deve convergere sulla stessa chiave.

## Diagnostica

Conserva gli ultimi 20 eventi. Tipi principali:

- `SERPAPI_ACCOUNT`;
- `GOOGLE_FLIGHTS`;
- `TRAVEL_EXPLORE`;
- `WEEKEND_VERIFY`;
- `SEARCHAPI_CALENDAR`;
- `CACHE`;
- `QUOTA_GUARD`.

Nessuna API key nei log; `Copia diagnostica` disponibile.

Da v3.4 `TRAVEL_EXPLORE` usa le classificazioni robuste elencate nella sezione provider. Un `destinations=[]` Anywhere viene registrato come `SURPRISING_EMPTY`, non come normale successo senza risultati.

---

# 8. Modelli dati correnti

### SimpleFlightResult

Date fisse: prezzo/valuta/compagnia/orari/scali/quota/cache + origine e destinazione effettive.

### WeekendCandidate / VerifiedWeekendResult

Discovery Explore e risultato weekend verificato v2.2 per destinazione aeroporto.

### AnywhereWeekendCandidate — v3.4

Discovery Anywhere non verificata:

- città;
- paese;
- aeroporto IATA;
- nome aeroporto;
- data andata indicativa Explore;
- data ritorno indicativa Explore;
- prezzo indicativo;
- valuta;
- mese/periodo.

### NightsSearchResult

Da v3.3 include date/N notti, prezzo/valuta, compagnia/orari/scali, origine/destinazione effettive, target ±X, strategia, conteggi provider, quota e cache.

Futuri: `FlightItinerary`, `FlightSegment`, `Layover` per Dettaglio/v4.

---

# 9. Stack corrente

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

AGP 9 usa Kotlin integrato: non applicare `org.jetbrains.kotlin.android`.

---

# 10. CI, Release e firma

Workflow:

`GitHub → Actions → Gradle → assembleRelease → zipalign → apksigner → dev-latest → telefono`

Release sviluppo:

- tag `dev-latest`;
- titolo `VolaFlex - Development latest`;
- asset `VolaFlex-dev.apk`.

Docs e workflow sono esclusi dal trigger push.

Firma canonica:

`a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`

Signer `CN=VolaFlex, OU=Personal, O=archimede-projects`, RSA4096.

CI principali recenti:

- v3.1 run #23 SUCCESS, `0.1.0-dev.23`;
- v3.2 run #24 SUCCESS, `0.1.0-dev.24`;
- v3.3 run #25 SUCCESS, `0.1.0-dev.25`, APK SHA-256 `12ca144a1fdfcb5adfbf30d36b16e3fdf8a402d17762b078b44cb93be2c76084`;
- **v3.4 run #28 SUCCESS**, build **`0.1.0-dev.28`**, commit applicativo **`6aef440e7b397f6f543539535fcd6d36d1edfc57`**, `BUILD SUCCESSFUL in 2m 14s`, 49 task; KSP/compile/lint/assembleRelease, zipalign, firma, pubblicazione `dev-latest` e cleanup tutti verdi; v2/v3 signature true, signer count 1, fingerprint canonico invariato; APK SHA-256 **`9ea540c368fd077906b630cf9d3b41925261384ef549242cb335eb88a9b3c40a`**, asset size **9,916,803 byte**.

Nota operativa: durante la preparazione v3.4 è stato creato per errore un file root `NOOP` e immediatamente rimosso. Non ha toccato codice applicativo, dati o provider e non ha consumato query API. I run #26/#27 sono quindi transitori e non autorevoli; il run applicativo v3.4 da usare è **#28**.

---

# 11. Stato roadmap e test reali

## Fase 0 — Infrastruttura

**CHIUSA 100% E VALIDATA.** CI, Release, firma persistente e upgrade senza disinstallazione validati.

## v1 — Fondamenta

**CHIUSA E VALIDATA 100% SUL TELEFONO.** API key locali, prima ricerca reale, IATA guard, Room cache 4h, Diagnostica/clipboard.

Test storico: `FCO→MAD`, 16–19/10/2026, Ryanair 104 EUR, 0 scali. Round IATA/cache/diagnostica: 1 query osservata = stima.

## v2 — Date flessibili

**COMPLETAMENTE CHIUSA E VALIDATA.**

- v2.1 Weekend Discovery: `FCO→MAD`, ottobre 2026, Explore 01→05/10, 95 EUR, 1 query, cache PASS; evidenziato limite 4 notti del preset Explore.
- v2.2 Weekend Verifica: novembre 2026, Explore 57 EUR → Pattern B Wizz Air 54 EUR, partenza 06:00 dentro `5,11`, 0 scali; 3 query e replay 0.
- v2.3 N notti SerpApi: `FCO→MAD`, 3 notti, target 25/10, ±5, 11 candidate/7 valutate; Ryanair 28→31/10, 53 EUR, 06:25→09:00, 0 scali; replay 0.
- v2.3 SearchAPI Calendar: ramo Calendar + verifica SerpApi + cache validati sul telefono.

## v3 — Geografia avanzata

Sequenza approvata:

1. v3.1 Multi-origine Date fisse;
2. v3.2 Multi-destinazione Date fisse;
3. v3.3 Multi-aeroporto N notti;
4. **v3.4 Anywhere Discovery**;
5. v3.5 Country Discovery;
6. v3.6 Anywhere/Country Verifica Weekend;
7. v3.7 integrazioni estreme + hardening.

### v3.1 — CHIUSA E VALIDATA

`FCO+CIA+MXP→MAD`, 20–23/11/2026: 1 query, vincitore **MXP**, **60 EUR**; replay origini riordinate = CACHE HIT, 0 query.

### v3.2 — CHIUSA E VALIDATA

`FCO+CIA → BCN+MAD+VLC`: 1 sola Google Flights, vincitore **FCO→BCN**, **48 EUR**; replay con entrambi gli assi riordinati → CACHE HIT, 0 query. UI/duplicati/overlap tutti PASS.

### v3.3 — CHIUSA E VALIDATA

**Ramo SerpApi diretto (≤10 date):**

- `FCO+CIA → VLC+BCN`;
- 3 date candidate/valutate;
- **3 query SerpApi**;
- vincitore **FCO→VLC**;
- **40 EUR**;
- replay con entrambi gli assi invertiti → **CACHE HIT**, 0 nuove query, stesso risultato.

**Ramo SearchAPI.io Calendar (>10 date):**

- `FCO+CIA → MAD+BCN+VLC`;
- 3 notti;
- target `15/01/2027`, ±7;
- 15 date candidate tutte valutate;
- strategia `SearchAPI.io Calendar → verifica SerpApi` attivata automaticamente;
- **2 richieste SearchAPI Calendar + 1 SerpApi**;
- vincitore **FCO→VLC**;
- **45 EUR verificato**.

Nota test/cache: il replay specifico del ramo Calendar **non è stato ripetuto esplicitamente**, per evitare consumo non necessario. La funzione di canonicalizzazione origini/destinazioni è condivisa con il ramo SerpApi già validato end-to-end e la cache key include già la strategia (`SERP_EXHAUSTIVE`, `SERP_SAMPLE`, `SEARCHAPI_CALENDAR`) come separatore. Decisione accettata per protezione quota.

**Conclusione: v3.1–v3.3, cioè tutta la geografia nativa tramite liste comma-separated, è completa e validata sul telefono reale.**

### v3.4 — ANYWHERE DISCOVERY IMPLEMENTATA + CI VERDE; TEST TELEFONO PENDENTE

Scope implementato e solo questo:

- modalità Weekend;
- 1–3 origini, canonicalizzate e inviate comma-separated;
- selettore destinazione `Aeroporto singolo` / `Ovunque`;
- il ramo aeroporto continua il comportamento v2.2 già validato;
- `Ovunque` usa un repository separato `AnywhereWeekendSearchRepository`;
- query Travel Explore con `departure_id`, `month`, `travel_duration=1`, Economy/flight/EUR/it;
- **nessun `arrival_id` e nessun `arrival_area_id`**;
- **nessuna Google Flights verification** in v3.4;
- lista risultati con città, paese, aeroporto IATA/nome, date indicative e prezzo indicativo;
- cache `WEEKEND|<origini>|ANYWHERE|<periodo>`, TTL 4h;
- Account API live + quota guard prima del batch;
- una Explore per mese selezionato;
- robustezza JSON/Diagnostica come definita nella sezione Explore;
- nessuna migrazione Room, schema resta 3;
- N notti, Date fisse, Country e v3.6 non modificati.

**Non avanzare a v3.5 finché v3.4 non è validata sul telefono.**

---

# 12. Rischi noti

- provider possono cambiare JSON/parametri o avere downtime;
- SerpApi/SearchAPI dipendono dall'ecosistema Google Flights;
- quota SerpApi limitata e condivisa;
- SearchAPI free pool limitato;
- sampling senza Calendar non è matematicamente esaustivo;
- perdita keystore impedisce aggiornamenti con stessa identità;
- DataStore non cifra autonomamente a riposo;
- AirportDirectory non è esaustiva;
- paesi richiedono catalogo KGMID;
- Explore è Discovery e ha avuto regressioni recenti nel 2026;
- una risposta Anywhere `destinations=[]` è classificata come anomalia prudenziale (`SURPRISING_EMPTY`), non come prova certa di assenza voli;
- dettaglio ritorno via `departure_token` resta on-demand;
- combinazioni estreme devono restare protette da quota/cache e non brute force.

---

# 13. Prossimo milestone operativo

1. Installare **`0.1.0-dev.28`** sopra la build corrente, senza disinstallare.
2. Aprire `Ricerca → Weekend`, scegliere `Ovunque`.
3. Usare origini `FCO + CIA`, periodo di un solo mese nuovo (raccomandato dicembre 2026).
4. Eseguire una sola Discovery: budget **1 query SerpApi Explore**, Account API gratuita, nessuna Google Flights/SearchAPI.
5. Verificare lista città/paese/aeroporto/date/prezzo e Diagnostica `TRAVEL_EXPLORE`.
6. Se `SUCCESS`, ripetere con origini invertite `CIA + FCO` e stesso periodo: atteso `CACHE HIT`, 0 nuove query.
7. Se `SURPRISING_EMPTY`, `STRUCTURE_ANOMALY` o `BODY_MISSING`, non ripetere alla cieca: copiare Diagnostica e analizzare prima di consumare altra quota.
8. Se PASS, segnare v3.4 CHIUSA e solo allora implementare v3.5 Country/KGMID.
