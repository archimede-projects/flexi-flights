# VolaFlex — PROJECT_SPEC

**Repository:** `archimede-projects/flexi-flights` (privata)  
**Nome app:** VolaFlex  
**Application ID / namespace:** `com.archimedeprojects.volaflex`  
**Ruolo:** fonte di verità persistente del progetto  
**Ultimo aggiornamento:** 2026-09-10

## Regola di manutenzione

Aggiornare questo file quando cambia un requisito, una decisione architetturale, una tecnologia, una policy API/quota o lo stato reale. Non contraddire decisioni qui registrate senza richiesta esplicita del proprietario. `SESSION_HANDOFF.md` contiene lo stato operativo sintetico da leggere all'inizio di una nuova chat.

---

# 1. Obiettivo e vincoli permanenti

VolaFlex è un'app Android personale per trovare voli economici con forte supporto a date flessibili, weekend, multi-aeroporto, destinazioni geografiche ampie e filtri sugli scali.

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
- proprietario del progetto principiante assoluto;
- nessuna API key o segreto in repository, CI log o chat.

---

# 2. Requisiti funzionali originali

1. Weekend flessibili: partenza venerdì sera oppure sabato mattina; ritorno domenica sera oppure lunedì.
2. N notti: origine + destinazione + numero esatto di notti, data target con ±X giorni; trovare il periodo più economico.
3. Data target ±X giorni.
4. Range ampi di date evitando brute force quando esistono metodi più efficienti.
5. Multi-origine fino a 3 alternative.
6. Destinazione: singolo aeroporto/città, fino a 3 alternative, Ovunque oppure intero paese.
7. Esclusione paese di scalo.
8. Stessa compagnia operativa su tutti i segmenti quando richiesto.
9. Durata massima scalo.
10. Dettaglio scalo: aeroporto, città, paese, durata, overnight.
11. Scali >8h: pulsante Maps via Android Intent; niente Maps SDK.

Le modalità destinazione sono concettualmente alternative: `AIRPORT_LIST`, `ANYWHERE`, `COUNTRY`. Una futura unione arbitraria tipo “MAD + BCN + tutto il Marocco” non è inclusa nel requisito corrente.

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
- Google Flights Deals quando il suo caso d'uso coincide;
- query multi-aeroporto;
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

---

# 4. Provider dati

## 4.1 SerpApi — provider primario

Ruoli:

- `google_flights`: ricerca precisa e verifica finale;
- `google_travel_explore`: Discovery economica per weekend, Ovunque, paesi e range ampi;
- Google Flights Deals: Discovery selettiva quando i parametri coincidono col caso d'uso.

Quota free di riferimento del progetto: 250 ricerche/mese, 50/ora. La chiave è condivisa con un altro progetto personale, quindi il saldo live è sempre autorevole.

### Chiave SerpApi condivisa

La chiave SerpApi di VolaFlex non è dedicata: è condivisa con un altro progetto personale. Non creare un secondo account soltanto per separare la quota se ciò richiede fornire un numero di telefono.

Conseguenze:

- la quota può diminuire anche mentre VolaFlex non viene usata;
- nessun contatore locale può essere fonte autorevole;
- prima dei batch live/costosi leggere l'Account API;
- dopo ricerche costose aggiornare il saldo visualizzato quando pratico;
- se l'Account API fallisce, non avviare automaticamente una ricerca stimata >5 query: ridurre a strategia ≤5 oppure richiedere override esplicito in una futura UI.

### Account API

Usata per leggere la quota reale prima dei batch. Non conta nella normale quota di ricerca secondo la documentazione SerpApi verificata durante il progetto.

### Policy quota SerpApi

- >50 residue: normale;
- ≤50: cache/modalità risparmio più aggressive;
- ≤20: conferma/forte prudenza per batch >5;
- ≤5: cache/Discovery/query singole/euristiche;
- preservare una riserva minima di 5 query;
- mai 30–40 chiamate automatiche con un singolo tap.

Le soglie 50/20/5 restano valide con la chiave condivisa perché sono applicate al saldo live.

### Google Flights — capability già verificate

- `departure_id` accetta più aeroporti/località separati da virgola;
- `arrival_id` accetta più aeroporti/località separati da virgola;
- una query può quindi rappresentare fino a 3 origini × 3 destinazioni senza fare 9 richieste distinte;
- round-trip preciso con date fisse;
- `outbound_times` / `return_times` in ore intere 0–23 separate da virgola;
- `layover_duration=MIN,MAX` in minuti;
- `exclude_conns` per aeroporti di connessione;
- `include_airlines` / `exclude_airlines`;
- esclusione paese scalo non nativa: controllo locale IATA→paese;
- stessa compagnia operativa: controllo client Kotlin;
- dettaglio esatto del ritorno può richiedere `departure_token` e va recuperato solo on-demand.

Esempi filtri orari validi:

- `17,23`;
- `5,11`;
- `0,23`;
- `4,18,3,19`.

Da v2.2 esiste `SerpApiTimeFilterGuard`: un interceptor OkHttp blocca localmente filtri orari non validi prima della rete.

### Google Travel Explore — capability già verificate

- engine: `google_travel_explore`;
- `departure_id` obbligatorio e può contenere origini multiple separate da virgola;
- `arrival_id` per destinazione specifica;
- `arrival_area_id` per area geografica/paese tramite KGMID;
- per “Ovunque” è possibile fare Discovery lasciando non impostati `arrival_id` e `arrival_area_id`;
- `travel_duration=1` = Weekend;
- `travel_duration=2` = 1 settimana;
- `travel_duration=3` = 2 settimane;
- `month` lavora sui prossimi mesi supportati dal provider;
- risultato utile come Discovery, non come garanzia di date/orari finali.

Test reale v2.1 ha dimostrato che `travel_duration=1` può restituire giovedì→lunedì (4 notti), quindi la Verifica Google Flights resta necessaria per il weekend breve.

### Google Flights Deals

Mapping `travel_duration` differente da Explore:

- `1` = 1 settimana;
- `2` = Weekend;
- `3` = 2 settimane.

Non confondere i mapping.

### `price_insights`

Non usare come calendario ±X: descrive statisticamente la stessa rotta/date interrogata, non date alternative.

---

## 4.2 SearchAPI.io Google Flights Calendar — acceleratore mirato

Endpoint:

`GET https://www.searchapi.io/api/v1/search?engine=google_flights_calendar`

Ruolo: **date discovery accelerator**, non secondo motore voli completo.

Usato per N notti / ±X e futuri range ampi quando il costo SerpApi equivalente supera circa 10 date da interrogare.

Regole verificate:

- `departure_id` supporta anche più aeroporti/località separati da virgola;
- `arrival_id` supporta anche più aeroporti/località separati da virgola;
- round-trip: massimo circa **200 combinazioni outbound/return per chiamata**; sopra il limite la risposta può essere vuota;
- per N notti esatti non esiste un parametro “durata fissa N”: inviare range andata/ritorno e filtrare localmente `return = departure + N`;
- blocco massimo scelto: **14 partenze**, perché `14×14=196`; `15×15=225` supera 200;
- per D partenze candidate: circa `ceil(D/14)` chiamate Calendar;
- autenticazione `api_key`;
- valuta `EUR`;
- chiave opzionale a livello architetturale, attualmente configurata sul telefono;
- i 100 crediti gratuiti sono trattati come pool limitato/non necessariamente ricorrente finché non verificato diversamente.

Esempi costo Calendar:

- ±5 → 11 partenze → 1 Calendar;
- ±7 → 15 partenze → 2 Calendar;
- ±10 → 21 partenze → 2 Calendar;
- ±15 → 31 partenze → 3 Calendar;
- ±20 → 41 partenze → 3 Calendar.

---

# 5. Credenziali API e sicurezza

API key salvate localmente con DataStore Preferences; mai nel repository; mai inviate in chat; `android:allowBackup=false`.

- SerpApi: obbligatoria per ricerche reali;
- SearchAPI.io: opzionale architetturalmente e attualmente configurata;
- UI mostra soltanto configurata/non configurata;
- campi password-style;
- campo vuoto al salvataggio mantiene il valore esistente.

DataStore non cifra autonomamente a riposo; rischio accettato per app personale nello storage privato Android.

---

# 6. Geografia locale

## 6.1 AirportDirectory

File corrente:

`data/local/AirportDirectory.kt`

Mapping:

`IATA → nome aeroporto → città → ISO country`

Copertura iniziale: circa 180–200 aeroporti principali, con Europa, Nord Africa e destinazioni comuni.

Usi già attivi:

- guard anti-typo prima della rete;
- visualizzazione/classificazione geografica.

Usi futuri:

- paese degli scali;
- autocomplete;
- filtro paese scalo.

Codice sconosciuto: warning `Correggi` / `Cerca comunque`, senza query automatica. Test reale `FC0`: PASS, 0 query.

## 6.2 Paesi / KGMID per v3

Per `google_travel_explore&arrival_area_id=...` il solo mapping IATA→ISO country non basta: SerpApi richiede il KGMID Google/Freebase del paese o area.

Piano v3, ancora non implementato:

- introdurre piccolo catalogo statico locale `CountryAreaCatalog`;
- campi minimi: nome paese, ISO-2, KGMID;
- almeno Europa + Nord Africa + destinazioni comuni, estendibile senza rete;
- usare `AirportDirectory` per IATA→country e `CountryAreaCatalog` per ISO/country→KGMID;
- nessun servizio geocoding esterno e nessun costo.

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

Migrazioni:

- 1→2: `weekend_search_cache`, validata sul telefono senza perdita dati;
- 2→3: `nights_search_cache`, validata sul telefono reale senza regressioni riportate.

La cache N notti include la strategia (`SERP_EXHAUSTIVE`, `SERP_SAMPLE`, `SEARCHAPI_CALENDAR`) nella chiave per evitare contaminazioni fra provider/strategie.

Diagnostica conserva gli ultimi 20 eventi. Tipi attuali:

- `SERPAPI_ACCOUNT`;
- `GOOGLE_FLIGHTS`;
- `TRAVEL_EXPLORE`;
- `WEEKEND_VERIFY`;
- `SEARCHAPI_CALENDAR`;
- `CACHE`;
- `QUOTA_GUARD`.

Le API key non devono mai apparire nei log. È disponibile `Copia diagnostica` negli appunti.

Per v3 le cache key dovranno canonicalizzare liste di origini/destinazioni e includere il tipo di selezione geografica, per evitare collisioni fra `AIRPORT_LIST`, `ANYWHERE` e `COUNTRY`.

---

# 8. Modelli dati principali

## Correnti

### `WeekendCandidate`

Discovery indicativa Travel Explore.

### `VerifiedWeekendResult`

Risultato v2.2 con pattern, date, prezzo, compagnia/orari/scali andata, fascia ritorno applicata, quota e timestamp.

### `NightsSearchResult`

Risultato v2.3 con:

- data andata/ritorno;
- notti;
- prezzo verificato;
- valuta;
- compagnia/orari/scali andata;
- target e ±X;
- strategia;
- candidate totali/valutate;
- prezzo indicativo Calendar quando applicabile;
- conteggio Calendar/SerpApi;
- quota live;
- timestamp/cache.

## Futuri

### `FlightItinerary`

- prezzo/valuta/durata;
- segmenti andata/ritorno;
- scali;
- provider;
- timestamp.

### `FlightSegment`

- aeroporti;
- datetimes;
- durata;
- marketing airline;
- operating carrier;
- flight number.

### `Layover`

- IATA;
- aeroporto;
- città;
- paese;
- durata;
- overnight.

### v3 — modello geografico proposto, non ancora implementato

- `OriginSelection.Airports(List<IATA>)`, max 3;
- `DestinationSelection.Airports(List<IATA>)`, max 3;
- `DestinationSelection.Anywhere`;
- `DestinationSelection.Country(ISO2/KGMID)`.

Questa separazione impedisce combinazioni ambigue e permette ai repository di scegliere il provider corretto.

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

RSA 4096. Keystore con doppio backup personale; password conservata separatamente.

Nota Codespaces: `gh secret set` può fallire con `403 Resource not accessible by integration`; per Secrets amministrativi usare UI GitHub se il token non ha permessi.

Ultima CI applicativa autorevole:

- run **#22 = SUCCESS**;
- versione **`0.1.0-dev.22`**;
- commit `b4e632da7723fe201a7d16be2ab66ceb382005e8`;
- `kspReleaseKotlin`: SUCCESS;
- `compileReleaseKotlin`: SUCCESS;
- `assembleRelease`: SUCCESS;
- zipalign: SUCCESS;
- `apksigner verify`: SUCCESS;
- signature v2: true;
- signature v3: true;
- signer: 1;
- fingerprint invariato;
- APK SHA-256 `880ba101cc7c1416846a30edeb78ce306621b44e4c02070bb8e5813595131843`;
- `VolaFlex-dev.apk` pubblicato su `dev-latest`.

Le modifiche documentali successive non richiedono build.

---

# 11. Stato roadmap e test reali

## Fase 0 — Infrastruttura

**CHIUSA 100% E VALIDATA.**

Completato:

- progetto Android Kotlin/Compose;
- CI GitHub Actions;
- Release `dev-latest`;
- firma APK persistente;
- upgrade reale `.4→.5` senza disinstallazione;
- fingerprint firma invariato.

## v1 — Fondamenta

**CHIUSA E VALIDATA 100% SUL TELEFONO.**

Completato:

- API key locali DataStore;
- SerpApi + SearchAPI.io opzionale;
- prima ricerca reale SerpApi;
- IATA anti-typo;
- Room cache 4h;
- Diagnostica ultimi 20 eventi + clipboard.

Test storico date fisse:

- `FCO→MAD`;
- 16–19/10/2026;
- Ryanair;
- 104 EUR round-trip;
- 0 scali andata;
- quota live verificata;
- firma/installazione PASS.

Test finale v1 IATA/cache/diagnostica:

- typo `FC0` bloccato localmente prima della rete;
- cache identica servita senza query;
- diagnostica e copia appunti PASS;
- consumo osservato complessivo del round: **1 query**, coerente con stima.

## v2 — Date flessibili

**COMPLETAMENTE CHIUSA E VALIDATA SUL TELEFONO REALE — 2026-09-10.**

Tutti i rami previsti sono passati, comprese le relative cache.

### v2.1 — Weekend Discovery

**CHIUSA E VALIDATA.**

Test `FCO→MAD`, ottobre 2026:

- Travel Explore `travel_duration=1`;
- candidato 01/10→05/10;
- 95 EUR indicativi;
- 1 query;
- cache PASS.

Osservazione determinante: Explore ha proposto 4 notti giovedì→lunedì, quindi Discovery da sola non garantisce il weekend breve richiesto.

### v2.2 — Weekend Verifica precisa

**CHIUSA E VALIDATA END-TO-END.**

Pattern:

- A: venerdì `17,23` → domenica `17,23`;
- B: sabato `5,11` → lunedì `0,23`.

Test `FCO→MAD`, novembre 2026:

- Explore: 57 EUR indicativi, 26/11→30/11;
- vincitore verifica: Pattern B sabato→lunedì;
- Wizz Air;
- 54 EUR round-trip;
- partenza andata 06:00, dentro `5,11`;
- 0 scali;
- consumo iniziale 3 query SerpApi = budget previsto;
- replay identico: 0 nuove query, Discovery + Verifica da cache.

### v2.3 — N notti / ±X

**ENTRAMBI I RAMI CHIUSI E VALIDATI.**

Strategia implementata:

1. `D <= 10` → `SERP_EXHAUSTIVE`;
2. `D > 10` + SearchAPI.io configurata → `SEARCHAPI_CALENDAR`;
3. `D > 10` senza SearchAPI.io → `SERP_SAMPLE`.

#### Ramo SerpApi campionato — PASS

Test reale:

- `FCO→MAD`;
- 3 notti;
- target 25/10/2026;
- ±5 giorni;
- 11 candidate;
- 7 valutate (`5+2` vicine);
- vincitore 28/10→31/10;
- Ryanair;
- 53 EUR;
- 06:25→09:00;
- 0 scali;
- replay identico: 0 nuove query, tutto da cache.

#### Ramo SearchAPI.io Calendar — PASS

- chiave SearchAPI.io configurata localmente;
- scenario >10 date ha attivato il ramo Calendar come previsto;
- Discovery Calendar + verifica SerpApi finale validate sul telefono reale;
- cache del ramo Calendar validata con replay a 0 nuove query provider;
- nessun dettaglio di fare/date aggiuntivo viene inventato qui perché non è stato riportato separatamente dal proprietario nella conferma finale.

**Conclusione complessiva: v2 Date flessibili CHIUSA.**

---

# 12. v3 — Geografia avanzata

**STATO: PIANIFICAZIONE ARCHITETTURALE. NESSUN CODICE v3 IMPLEMENTATO ANCORA.**

Scope originale:

- multi-origine fino a 3 aeroporti;
- multi-destinazione fino a 3 aeroporti;
- destinazione Ovunque;
- destinazione intero paese;
- riuso IATA→paese locale;
- integrazione progressiva con date fisse, Weekend e N notti.

Principi già verificati prima dell'implementazione:

- Google Flights SerpApi supporta liste comma-separated sia in `departure_id` sia in `arrival_id`;
- Google Travel Explore supporta più origini comma-separated;
- Explore usa `arrival_area_id` KGMID per un paese/area;
- Explore senza `arrival_id`/`arrival_area_id` è adatto alla Discovery “Ovunque”;
- SearchAPI.io Calendar supporta più origini e più destinazioni comma-separated, utile quando N notti verrà combinato con multi-aeroporto;
- l'architettura `DISCOVERY → VERIFICA → DETTAGLIO` resta valida: la geografia aumenta il fan-in/fan-out dei candidati ma non richiede un backend diverso.

Da concordare in chat prima del codice: suddivisione v3.1/v3.2/... e ordine dei test.

---

# 13. Rischi noti e accettati

- provider possono cambiare JSON/parametri o avere downtime;
- SearchAPI.io e SerpApi dipendono entrambi dall'ecosistema Google Flights, quindi non sono vera ridondanza upstream;
- SearchAPI.io free pool può essere limitato/one-time;
- ricerca euristica senza Calendar non è matematicamente esaustiva;
- quota SerpApi limitata e condivisa;
- perdita keystore impedisce aggiornamenti con la stessa identità;
- DataStore non cifra autonomamente le API key a riposo;
- AirportDirectory non è esaustiva;
- per i paesi serve un catalogo KGMID aggiuntivo rispetto al solo IATA→ISO;
- multi-origine/multi-destinazione richiedono cache key canonicalizzate per evitare duplicati dovuti all'ordine degli aeroporti;
- Explore è Discovery e non garantisce da solo vincoli di orario/durata;
- il dettaglio preciso del ritorno via `departure_token` resta on-demand;
- le combinazioni geografiche estreme richiedono forte controllo della quota, non brute force del prodotto cartesiano.

---

# 14. Prossimo milestone operativo

1. Concordare il piano v3 dettagliato in sotto-step testabili.
2. Non modificare codice v3 prima dell'approvazione del piano.
3. Dopo approvazione, implementare soltanto v3.1.
4. Build CI + firma + test telefono + cache/diagnostica.
5. Aggiornare `PROJECT_SPEC.md` e `SESSION_HANDOFF.md`.
6. Solo dopo PASS avanzare a v3.2.
