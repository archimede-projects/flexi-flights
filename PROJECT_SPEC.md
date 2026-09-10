# VolaFlex — PROJECT_SPEC

**Repository:** `archimede-projects/flexi-flights` (privata)  
**Nome app:** VolaFlex  
**Application ID / namespace:** `com.archimedeprojects.volaflex`  
**Ruolo:** fonte di verità persistente del progetto  
**Ultimo aggiornamento:** 2026-09-10

## Regola di manutenzione

Aggiornare questo file ogni volta che cambia un requisito, una decisione architetturale, una tecnologia, una policy API/quota o lo stato reale. Non contraddire decisioni qui registrate senza richiesta esplicita del proprietario. `SESSION_HANDOFF.md` contiene lo stato operativo sintetico da leggere all'inizio di una nuova chat.

---

# 1. Obiettivo e vincoli permanenti

VolaFlex è un'app Android personale per trovare voli economici con forte supporto a date flessibili, weekend, multi-aeroporto e filtri sugli scali.

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
- proprietario del progetto principiante assoluto.

---

# 2. Requisiti funzionali originali

1. Weekend flessibili: partenza venerdì sera oppure sabato mattina; ritorno domenica sera oppure lunedì.
2. N notti: origine + destinazione + numero esatto di notti, data target con ±X giorni; trovare il periodo più economico.
3. Data target ±X giorni.
4. Range ampi di date evitando brute force quando esistono metodi più efficienti.
5. Multi-origine fino a 3 alternative.
6. Destinazione singola, fino a 3 alternative, Ovunque oppure intero paese.
7. Esclusione paese di scalo.
8. Stessa compagnia operativa su tutti i segmenti quando richiesto.
9. Durata massima scalo.
10. Dettaglio scalo: aeroporto, città, paese, durata, overnight.
11. Scali >8h: pulsante Maps via Android Intent; niente Maps SDK.

---

# 3. Architettura obbligatoria

Pattern generale:

`UI → logica ricerca → provider → cache/database`

Pattern dati:

**DISCOVERY → VERIFICA → DETTAGLIO**

## Discovery

Ridurre il numero di query usando a seconda del caso:

- SerpApi Google Travel Explore;
- SearchAPI.io Google Flights Calendar;
- Google Flights Deals;
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
- `google_travel_explore`: Discovery economica per weekend/Ovunque/paesi/range ampi;
- Google Flights Deals: Discovery quando il caso d'uso coincide con i suoi parametri.

Quota free di riferimento del progetto: 250 ricerche/mese, 50/ora. La chiave è condivisa con un altro progetto personale, quindi il saldo live è sempre autorevole.

### Account API

Usata prima dei batch live per leggere le query rimaste. Non conta nella quota normale secondo la documentazione SerpApi verificata durante il progetto.

### Regole quota SerpApi

- >50: normale;
- <=50: cache/modalità risparmio più aggressive;
- <=20: conferma/forte prudenza per batch >5;
- <=5: cache/Discovery/query singole/euristiche;
- preservare una riserva minima di 5 query;
- mai 30–40 chiamate automatiche con un singolo tap.

## 4.2 SearchAPI.io Calendar — acceleratore mirato

Endpoint:

`GET https://www.searchapi.io/api/v1/search?engine=google_flights_calendar`

Ruolo: **date discovery accelerator**, non secondo motore voli completo.

Usato per N notti / ±X e futuri range ampi quando il costo SerpApi equivalente supera circa 10 date da interrogare.

Regole verificate:

- round-trip: massimo circa **200 combinazioni outbound/return per chiamata**; sopra il limite la risposta può essere vuota;
- per N notti esatti non esiste un parametro “durata fissa N”: si inviano range andata/ritorno e si filtra localmente la diagonale `return = departure + N`;
- blocco massimo sincronizzato scelto: **14 partenze** perché `14×14=196`; `15×15=225` supera 200;
- per D partenze candidate: circa `ceil(D/14)` chiamate Calendar;
- autenticazione tramite `api_key`;
- valuta richiesta esplicitamente `EUR`;
- SearchAPI.io è opzionale: VolaFlex deve funzionare senza questa chiave;
- i 100 crediti gratuiti sono trattati come pool limitato/non necessariamente ricorrente finché non verificato diversamente.

Esempi di costo Calendar già fissati:

- ±5 → 11 partenze → 1 Calendar;
- ±7 → 15 partenze → 2 Calendar;
- ±10 → 21 partenze → 2 Calendar;
- ±15 → 31 partenze → 3 Calendar;
- ±20 → 41 partenze → 3 Calendar.

## 4.3 Mapping preset da non confondere

Travel Explore:

- `travel_duration=1` = Weekend;
- `2` = 1 week;
- `3` = 2 weeks.

Google Flights Deals usa mapping differente:

- `1` = 1 week;
- `2` = Weekend;
- `3` = 2 weeks.

## 4.4 `price_insights`

Non usare come calendario ±X: descrive statisticamente la stessa rotta/date interrogata, non date alternative.

---

# 5. Filtri SerpApi già verificati

- `layover_duration=MIN,MAX` in minuti + controllo Kotlin;
- `exclude_conns` per aeroporti di connessione;
- esclusione paese connessione: non nativa → lookup locale IATA→paese;
- `include_airlines` / `exclude_airlines`;
- stessa compagnia operativa: controllo Kotlin;
- `outbound_times` / `return_times`: **2 o 4 ore intere 0–23 separate da virgola**.

Esempi validi:

- `17,23`;
- `5,11`;
- `0,23`;
- `4,18,3,19`.

Da v2.2 esiste `SerpApiTimeFilterGuard`: un interceptor OkHttp blocca localmente formati non validi prima della rete, evitando consumo quota per regressioni tipo `17:00,23:59`.

Il dettaglio esatto del ritorno di un round-trip richiede una richiesta successiva con `departure_token`; non viene richiesto automaticamente.

---

# 6. Credenziali API e sicurezza

API key salvate localmente con DataStore Preferences; mai nel repository; mai inviate in chat; `android:allowBackup=false`.

- SerpApi: obbligatoria per le ricerche reali;
- SearchAPI.io: opzionale;
- UI mostra solo configurata/non configurata;
- campi password-style;
- campo vuoto al salvataggio mantiene il valore esistente.

DataStore non cifra autonomamente a riposo; rischio accettato per app personale nello storage privato Android.

---

# 7. Directory aeroporti locale

`data/local/AirportDirectory.kt`

Mapping:

`IATA → aeroporto → città → ISO country`

Usi:

- guard anti-typo prima della rete;
- futuro paese scali;
- futura autocomplete;
- filtro paese scalo.

Codice sconosciuto: warning `Correggi` / `Cerca comunque`, senza query automatica. Test reale `FC0`: PASS, 0 query.

---

# 8. Cache Room e diagnostica

## Room

Database `volaflex.db`.

Schema corrente con v2.3: **versione 3**.

Tabelle:

- `flight_search_cache` — date fisse;
- `weekend_search_cache` — weekend;
- `nights_search_cache` — N notti / ±X;
- `diagnostic_events`.

TTL cache ricerche: **4 ore**.

Migrazioni:

- 1→2: aggiunta `weekend_search_cache`, validata sul telefono senza perdita dati;
- 2→3: aggiunta `nights_search_cache`, compilata/validata da Room/KSP in CI; validazione reale sul telefono ancora richiesta.

La cache N notti salva il risultato completo serializzato JSON e include la strategia (`SERP_EXHAUSTIVE`, `SEARCHAPI_CALENDAR`, `SERP_SAMPLE`) nella chiave. Questo permette, dopo futura configurazione SearchAPI.io, di non riusare per errore una cache euristica creata senza Calendar.

## Diagnostica

Conserva gli ultimi 20 eventi.

Tipi attuali:

- `SERPAPI_ACCOUNT`;
- `GOOGLE_FLIGHTS`;
- `TRAVEL_EXPLORE`;
- `WEEKEND_VERIFY`;
- `SEARCHAPI_CALENDAR`;
- `CACHE`;
- `QUOTA_GUARD`.

Le API key non devono mai apparire nei log diagnostici.

---

# 9. Modelli dati principali

## `WeekendCandidate`

Discovery indicativa Travel Explore.

## `VerifiedWeekendResult`

Risultato finale v2.2 con pattern, date, prezzo, compagnia/orari/scali andata, fascia ritorno applicata, quota e timestamp.

## `NightsSearchResult`

Risultato v2.3:

- data andata;
- data ritorno;
- numero notti;
- prezzo verificato;
- valuta;
- compagnia/orari/scali andata;
- data target e ±X;
- strategia usata;
- date candidate totali e valutate;
- prezzo Discovery indicativo quando Calendar;
- numero chiamate Calendar;
- numero chiamate SerpApi Google Flights;
- quota live prima della ricerca;
- timestamp;
- stato cache.

## `FlightItinerary` — futuro modello finale

- prezzo/valuta/durata;
- segmenti andata/ritorno;
- scali;
- provider;
- timestamp.

`FlightSegment`: aeroporti, date/orari, durata, marketing airline, operating carrier, numero volo.

`Layover`: IATA, aeroporto, città, paese, durata, overnight.

---

# 10. Stack tecnologico corrente

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

# 11. Identità, CI, Release e firma

Workflow:

`GitHub → Actions → Gradle → assembleRelease → zipalign → apksigner → GitHub Release → telefono`

Release sviluppo:

- tag `dev-latest`;
- titolo `VolaFlex - Development latest`;
- asset `VolaFlex-dev.apk`.

`PROJECT_SPEC.md`, `SESSION_HANDOFF.md` e `.github/workflows/android-build.yml` sono esclusi dal trigger push.

Firma persistente validata end-to-end:

`a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`

Keystore con doppio backup personale; password conservata separatamente.

Nota Codespaces: `gh secret set` può fallire con `403 Resource not accessible by integration`; per Secrets amministrativi usare UI GitHub se il token non ha permessi.

### Ultima CI v2.3

GitHub Actions run **#22 = SUCCESS**.

- commit: `b4e632da7723fe201a7d16be2ab66ceb382005e8`;
- versione: **`0.1.0-dev.22`**;
- `kspReleaseKotlin`: SUCCESS;
- `compileReleaseKotlin`: SUCCESS;
- `assembleRelease`: SUCCESS (`BUILD SUCCESSFUL in 2m 1s`);
- zipalign: SUCCESS;
- `apksigner verify`: SUCCESS;
- signature scheme v2: true;
- signature scheme v3: true;
- numero signer: 1;
- fingerprint SHA-256 certificato: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`;
- APK SHA-256: `880ba101cc7c1416846a30edeb78ce306621b44e4c02070bb8e5813595131843`;
- asset `VolaFlex-dev.apk` pubblicato su `dev-latest`;
- size asset: 9,867,651 byte.

---

# 12. Stato roadmap

## Fase 0 — infrastruttura

**CHIUSA 100%.**

## v1 — Fondamenta

**CHIUSA E VALIDATA 100% SUL TELEFONO.**

Include:

- firma stabile;
- API key DataStore;
- prima ricerca reale SerpApi;
- IATA guard;
- Room cache;
- diagnostica/clipboard.

Test date fisse storico: `FCO→MAD`, 16–19/10/2026, Ryanair, 104 EUR, 0 scali andata.

## v2.1 — Weekend Discovery

**CHIUSA E VALIDATA.**

Test reale `FCO→MAD`, ottobre 2026:

- Explore: 01/10→05/10;
- 95 EUR indicativi;
- consumo 1 query;
- cache PASS.

Osservazione: `travel_duration=1` può restituire un intervallo più lungo del weekend breve richiesto. Da qui la necessità della v2.2.

## v2.2 — Weekend Verifica precisa

**CHIUSA E VALIDATA END-TO-END SUL TELEFONO — 2026-09-10.**

Architettura:

1. Explore Discovery;
2. candidato più economico;
3. massimo due pattern Google Flights precisi;
4. cache Discovery + Verifica.

Pattern:

- A: venerdì `17,23` → domenica `17,23`;
- B: sabato `5,11` → lunedì `0,23`.

### Test reale definitivo v2.2

Rotta: **FCO → MAD**, novembre 2026.

Discovery Travel Explore:

- prezzo indicativo: **57 EUR**;
- range indicativo: **26/11/2026 → 30/11/2026**.

Verifica Google Flights:

- pattern vincente: **B — sabato → lunedì**;
- compagnia: **Wizz Air**;
- prezzo round-trip verificato: **54 EUR**;
- partenza andata: **06:00**;
- `06:00` è correttamente dentro `outbound_times=5,11`;
- scali andata: **0**.

Consumo reale:

- ricerca iniziale: **3 query SerpApi**;
- budget previsto: **3**;
- ripetizione identica entro TTL: **0 nuove query**;
- Discovery + Verifica riutilizzate interamente da cache.

**Conclusione: v2.2 CHIUSA.**

## v2.3 — N notti / ±X giorni

**IMPLEMENTATA, CI VERDE E RELEASE PUBBLICATA; DA VALIDARE SUL TELEFONO.**

### UI

Terza modalità nella schermata Ricerca:

`Date fisse | Weekend | N notti`

Input iniziali:

- singolo aeroporto partenza;
- singolo aeroporto destinazione;
- numero notti 1–30;
- data target di partenza;
- flessibilità ±X, inizialmente 0–60.

### Doppio binario / strategia

Sia `D` il numero effettivo di partenze candidate nel range.

**A. Range piccolo — `D <= 10`**

- SerpApi Google Flights diretto;
- una query precisa per ogni data candidata;
- selezione del prezzo più basso;
- nessuna query duplicata di “verifica”, perché ogni chiamata Discovery è già una Google Flights precisa con date esatte e ordinamento prezzo.

**B. Range ampio — `D > 10` + SearchAPI.io configurata**

- SearchAPI.io Calendar;
- `chunked(14)`;
- ogni blocco produce massimo `14×14=196` combinazioni;
- filtro locale `return = departure + N`;
- selezione candidato Calendar più economico;
- **1 query SerpApi Google Flights precisa** sul candidato migliore per verifica finale.

**C. Range ampio — `D > 10` senza SearchAPI.io**

- modalità risparmio quota SerpApi;
- 5 date iniziali distribuite uniformemente nell'intervallo;
- fino a 2 date adiacenti alla migliore iniziale;
- massimo **7 query SerpApi**;
- le query campionate sono già Google Flights precise e quindi il miglior campione è già verificato senza duplicare una chiamata identica.

### Quota v2.3

Prima di qualsiasi batch live: Account API SerpApi.

Richiesta minima:

`riserva 5 + massimo numero di query SerpApi previsto dalla strategia`.

Quindi:

- Serp esaustivo: 5 + D;
- Serp sample: 5 + massimo 7;
- Calendar: 5 + 1 SerpApi finale.

Le chiamate SearchAPI.io usano il pool SearchAPI, non la quota SerpApi.

### Cache v2.3

Nuova `nights_search_cache`, TTL 4 ore.

Cache hit identico:

- 0 Account API;
- 0 SearchAPI Calendar;
- 0 SerpApi Google Flights.

`Aggiorna comunque` forza un nuovo run live.

### Diagnostica v2.3

Nuovo tipo:

`SEARCHAPI_CALENDAR`

Ogni blocco registra SUCCESS / EMPTY / ERROR senza API key.

### CI v2.3

Run #22 SUCCESS, build `0.1.0-dev.22`, firma persistente invariata e Release `dev-latest` aggiornata. Il test runtime resta necessario per validare la migrazione Room 2→3, la scelta strategia e il consumo reale.

---

# 13. Prossimi step dopo v2.3

Dopo validazione reale v2.3:

- completare/chiudere v2 “Date flessibili” con eventuale rifinitura range ampi;
- v3 geografia: multi-origine, multi-destinazione, Ovunque, paese;
- v4 scali avanzati: durata, paese escluso, stessa compagnia, dettagli, Maps.

---

# 14. Rischi noti e accettati

- provider possono cambiare JSON/parametri o avere downtime;
- SearchAPI.io e SerpApi dipendono entrambi dall'ecosistema Google Flights, quindi non sono vera ridondanza upstream;
- SearchAPI.io free pool può essere limitato/one-time;
- ricerca euristica senza Calendar non è matematicamente esaustiva;
- quota SerpApi limitata e condivisa;
- perdita keystore impedisce aggiornamenti con la stessa identità;
- DataStore non cifra autonomamente le API key a riposo;
- directory IATA non esaustiva;
- cache key andranno estese con futuri filtri/passeggeri/multi-aeroporto;
- v2.2/v2.3 non scaricano automaticamente il dettaglio preciso del ritorno via `departure_token`;
- Room migration 2→3 deve essere validata sul telefono insieme alla prima build v2.3.

---

# 15. Prossimo milestone operativo

1. installare **`0.1.0-dev.22`** sopra la build corrente senza disinstallare;
2. verificare che Impostazioni/API key e Diagnostica pregresse siano ancora presenti, validando Room 2→3;
3. **non configurare ancora SearchAPI.io**: testare prima il fallback SerpApi campionato;
4. test consigliato: `FCO → MAD`, **3 notti**, target **15/12/2026**, **±5 giorni**;
5. ±5 produce 11 partenze candidate, quindi senza SearchAPI.io deve scegliere `SERP_SAMPLE`;
6. consumo previsto: **5–7 query SerpApi**, Account API gratuita, **0 SearchAPI.io**;
7. la data ritorno vincente deve essere esattamente 3 giorni dopo l'andata;
8. Diagnostica: `SERPAPI_ACCOUNT` + `GOOGLE_FLIGHTS`, nessun `SEARCHAPI_CALENDAR`;
9. ripetizione identica entro 4h: `CACHE HIT`, **0 query provider**;
10. solo dopo PASS del fallback, configurare SearchAPI.io e usare un nuovo set >10 date per validare Calendar + 1 verifica SerpApi precisa.
