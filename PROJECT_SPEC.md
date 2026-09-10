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

v3.1/v3.2 hanno validato end-to-end che Google Flights gestisce più origini/destinazioni in una sola query. v3.3 estende la stessa logica alla pipeline N notti: una query per data SerpApi o un blocco Calendar, non `origini×destinazioni` query.

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
- `arrival_id` per destinazione specifica;
- Ovunque: omettere `arrival_id` e `arrival_area_id`;
- paese/area: `arrival_area_id` con KGMID.

### Rischio provider Explore — obbligatorio per v3.4/v3.5

Release notes ufficiali 2026 mostrano fix recenti su:

- gennaio: Weekend `travel_duration`;
- febbraio: `max_duration` ignorato;
- marzo: `stops` ignorato;
- **09/07/2026:** molte ricerche valide potevano tornare vuote.

Decisione: v3.4/v3.5 devono trattare risposte vuote/anomale con diagnostica robusta, distinguendo quando possibile HTTP error, body mancante, errore esplicito, struttura inattesa e assenza reale di opportunità. Non interpretare automaticamente un Explore vuoto come “nessun volo”.

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
- validazione di ogni aeroporto su Date fisse e N notti multi-aeroporto.

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
- `weekend_search_cache` — Weekend;
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

`NightsSearchResult` da v3.3 aggiunge `departureAirportId` e `arrivalAirportId` con default retrocompatibili, così vecchi JSON cache restano decodificabili.

## Diagnostica

Conserva gli ultimi 20 eventi. Tipi:

- `SERPAPI_ACCOUNT`;
- `GOOGLE_FLIGHTS`;
- `TRAVEL_EXPLORE`;
- `WEEKEND_VERIFY`;
- `SEARCHAPI_CALENDAR`;
- `CACHE`;
- `QUOTA_GUARD`.

Nessuna API key nei log; `Copia diagnostica` disponibile.

Da v3.3 gli eventi N notti includono `origins=<lista>`, `destinations=<lista>` e, sui risultati Google Flights, `winner=<origine>→<destinazione>`.

---

# 8. Modelli dati correnti

### SimpleFlightResult

Date fisse: prezzo/valuta/compagnia/orari/scali/quota/cache + origine e destinazione effettive.

### WeekendCandidate / VerifiedWeekendResult

Discovery Explore e risultato weekend verificato v2.2.

### NightsSearchResult

Da v3.3 include:

- date andata/ritorno e N notti;
- prezzo/valuta;
- compagnia/orari/scali andata;
- **origine effettiva**;
- **destinazione effettiva**;
- target ±X;
- strategia e label;
- date candidate/valutate;
- prezzo Calendar indicativo quando presente;
- conteggi provider;
- quota SerpApi;
- metadata cache.

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
- v3.2 run #24 SUCCESS, `0.1.0-dev.24`, APK SHA-256 `edd5ff433f7b5d399ce1eb4778643872b822f1da849dc8169b28559906161265`;
- **v3.3 run #25 SUCCESS**, build **`0.1.0-dev.25`**, commit applicativo `8ebfa88085d8db9a9240fe91b919df02adfa137a`, `BUILD SUCCESSFUL in 2m 13s`, 49 task; KSP/compile/lint/assembleRelease, zipalign, firma e pubblicazione `dev-latest` tutti verdi; v2/v3 signature true, signer count 1, fingerprint canonico invariato; APK SHA-256 **`12ca144a1fdfcb5adfbf30d36b16e3fdf8a402d17762b078b44cb93be2c76084`**, asset size **9,884,035 byte**.

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
3. **v3.3 Multi-aeroporto N notti**;
4. v3.4 Anywhere Discovery;
5. v3.5 Country Discovery;
6. v3.6 Anywhere/Country Verifica Weekend;
7. v3.7 integrazioni estreme + hardening.

### v3.1 — CHIUSA E VALIDATA

Test `FCO+CIA+MXP→MAD`, 20–23/11/2026: 1 query, vincitore **MXP**, **60 EUR**; replay origini riordinate = CACHE HIT, 0 query.

### v3.2 — CHIUSA E VALIDATA

Test reali PASS:

- UI multi-destinazione;
- blocco duplicati destinazione;
- blocco overlap origine/destinazione;
- live `FCO+CIA → BCN+MAD+VLC`: **1 sola Google Flights**, vincitore **FCO→BCN**, **48 EUR**;
- Diagnostica conferma liste canonicalizzate e una sola richiesta;
- replay con entrambi gli assi riordinati → **CACHE HIT**, 0 nuove query.

### v3.3 — IMPLEMENTATA + CI VERDE, TEST TELEFONO PENDENTE

Scope implementato e solo questo:

- `N notti` con 1–3 origini e 1–3 destinazioni;
- UI add/remove uguale al pattern validato di Date fisse;
- IATA guard su ogni aeroporto;
- duplicati bloccati per asse;
- sovrapposizione origine/destinazione vietata;
- canonicalizzazione su entrambi gli assi;
- SerpApi exact: liste comma-separated, una query per data;
- fallback senza SearchAPI: stesso 5+2, sempre con liste aggregate;
- SearchAPI Calendar: liste comma-separated e chunking temporale 14×14 invariato;
- verifica finale Calendar: 1 Google Flights con le stesse liste;
- cache key include origini, destinazioni, N, target, ±X, strategia;
- risultato mostra origine e destinazione effettive;
- nessuna migrazione Room;
- nessuna modifica a Weekend/Explore/Anywhere/Country.

Test telefono pianificati:

**A — SerpApi esaustivo:** `FCO+CIA → BCN+VLC`, 3 notti, target **10/12/2026**, ±1 = 3 candidate. Attesi max 3 Google Flights, zero SearchAPI. Replay con `CIA+FCO → VLC+BCN`, parametri identici → CACHE HIT, 0 query.

**B — Calendar:** `FCO+CIA → MAD+BCN+VLC`, 3 notti, target **15/01/2027**, ±7 = 15 candidate. Attesi 2 SearchAPI Calendar (14+1) + 1 SerpApi Google Flights finale. Replay con entrambe le liste riordinate e parametri identici → CACHE HIT, 0 query.

Il fallback multi-aeroporto `SERP_SAMPLE` non viene ritestato apposta con 5–7 query: usa la stessa funzione exact-date multi-list del ramo esaustivo e l'algoritmo 5+2 già validato in v2; spendere quota aggiuntiva sarebbe duplicativo salvo anomalia.

**Non avanzare a v3.4 finché i test v3.3 non sono PASS.**

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
- liste multi-airport richiedono cache canonicalizzata su entrambi gli assi e strategia;
- Explore è Discovery e ha avuto regressioni recenti nel 2026;
- dettaglio ritorno via `departure_token` resta on-demand;
- combinazioni estreme devono restare protette da quota/cache e non brute force.

---

# 13. Prossimo milestone operativo

1. Installare `0.1.0-dev.25` sopra la build corrente, senza disinstallare.
2. Eseguire Test A SerpApi esaustivo multi-aeroporto e replay canonicalizzato.
3. Eseguire Test B Calendar multi-aeroporto e replay canonicalizzato.
4. Verificare origine/destinazione effettive e Diagnostica per entrambi.
5. Se PASS, segnare v3.3 CHIUSA e solo allora implementare v3.4 Anywhere.
