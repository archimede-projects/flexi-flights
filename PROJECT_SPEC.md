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

Vincoli:

- uso personale e distribuzione APK via sideload;
- niente Play Store per ora;
- zero costi;
- nessuna carta di credito/debito richiesta;
- niente backend/server a pagamento;
- sviluppo GitHub-only;
- repository GitHub privata;
- nessun Android Studio locale;
- GitHub Actions per CI/build;
- GitHub Releases per distribuire l'APK;
- GitHub Codespaces opzionale;
- test UI/runtime su telefono Android reale;
- proprietario del progetto principiante assoluto.

---

# 2. Requisiti funzionali originali

1. **Weekend flessibili:** partenza venerdì sera oppure sabato mattina; ritorno domenica sera oppure lunedì; ricerca su più settimane o mesi.
2. **N notti:** origine + destinazione + numero esatto di notti, senza date fisse; trovare il periodo più economico.
3. **Data ±X giorni:** data target con flessibilità X scelta dall'utente.
4. **Range ampio di date:** es. 1–30 giugno, evitando brute force quando esistono metodi più efficienti.
5. **Multi-origine:** fino a 3 città/aeroporti alternativi.
6. **Destinazione:** singola città/aeroporto, fino a 3 alternative, Ovunque, oppure intero paese.
7. **Esclusione paese di scalo.**
8. **Stessa compagnia:** verificare che tutte le tratte siano operate dalla stessa compagnia quando richiesto.
9. **Durata massima dello scalo.**
10. **Dettaglio scalo:** aeroporto, città, paese, durata ed eventuale overnight.
11. **Scali >8h:** pulsante Maps tramite Android Intent; niente Maps SDK/API key.

---

# 3. Fonti dati scelte

## 3.1 SerpApi — provider primario

Quota free di riferimento del progetto: 250 ricerche/mese, 50/ora. Il saldo va considerato condiviso e verificato live secondo la policy.

Engine/ruoli:

- **Google Flights:** ricerca precisa e verifica finale;
- **Google Travel Explore:** Discovery economica per weekend, Ovunque, paesi/regioni e ricerche ampie;
- **Google Flights Deals:** Discovery quando il caso d'uso coincide con i suoi parametri.

SerpApi resta la fonte definitiva del futuro modello `FlightItinerary`.

### Account API

La SerpApi Account API è gratuita e non consuma la quota normale. Il saldo live restituito dall'Account API è la fonte autorevole.

### Nota: chiave SerpApi condivisa con altro progetto

La API key SerpApi usata da VolaFlex **non è dedicata all'app**: lo stesso account gratuito viene usato anche da un altro progetto personale.

Conseguenze:

- il saldo può diminuire mentre VolaFlex non viene usata;
- nessun contatore locale è fonte di verità;
- prima di ogni ricerca stimata costosa (>5 query) refresh live Account API obbligatorio;
- nelle funzioni già implementate viene usato un controllo ancora più prudente prima di ogni batch/fase live;
- se Account API fallisce, non avviare ricerche costose automaticamente;
- proteggere sempre una riserva minima di 5 query;
- non creare un secondo account SerpApi solo per separare la quota se ciò richiede fornire un numero di telefono.

## 3.2 SearchAPI.io Calendar — acceleratore mirato

Non è un secondo motore voli completo. È un `date discovery accelerator` da introdurre nei prossimi sotto-step v2 per:

- N notti + ±X giorni;
- range ampi con destinazione fissa;
- casi in cui SerpApi richiederebbe circa >=10 chiamate.

Decisioni già fissate:

- massimo 200 combinazioni andata/ritorno per richiesta;
- per N notti filtrare localmente la diagonale `return = departure + N`;
- blocchi sincronizzati fino a 14 date (`14²=196`; `15²=225` supera il limite);
- i 100 crediti gratuiti non sono considerati ricorrenti finché non confermato;
- VolaFlex deve continuare a funzionare anche senza SearchAPI.io.

Per D partenze candidate con N notti: circa `ceil(D/14)` query Calendar.

## 3.3 Mapping preset da non confondere

Travel Explore:

- `travel_duration=1` = Weekend;
- `2` = 1 week;
- `3` = 2 weeks.

Google Flights Deals usa un mapping differente:

- `1` = 1 week;
- `2` = Weekend;
- `3` = 2 weeks.

## 3.4 `price_insights`

Non usare per ±X giorni: descrive statisticamente la stessa rotta/date interrogata e non è un calendario di date alternative.

---

# 4. Pattern architetturale obbligatorio

`DISCOVERY → VERIFICA → DETTAGLIO`

## Discovery

Trovare candidati con poche query usando, a seconda del caso:

- SerpApi Travel Explore;
- SearchAPI.io Calendar;
- Google Flights Deals;
- query multi-airport;
- cache locale;
- campionamento euristico.

## Verifica

Solo pochi candidati migliori (tipicamente 1–3) vengono verificati con SerpApi Google Flights usando date e filtri precisi.

## Dettaglio

Solo on-demand recuperare/analizzare:

- eventuale `departure_token` e ritorno associato;
- segmenti;
- scali;
- operating carrier/codeshare;
- paese scali;
- stessa compagnia;
- popup scalo;
- Maps.

Mai scaricare automaticamente il dettaglio completo di decine di risultati.

---

# 5. Filtri SerpApi verificati

- durata scalo: `layover_duration=MIN,MAX` in minuti + verifica Kotlin;
- esclusione aeroporto connessione: `exclude_conns`;
- esclusione paese connessione: non nativo → lookup locale IATA→paese;
- inclusione compagnie: `include_airlines`;
- esclusione compagnie: `exclude_airlines`;
- stessa compagnia operativa su ogni segmento: controllo Kotlin obbligatorio;
- Google Flights `outbound_times`: fascia oraria andata;
- Google Flights `return_times`: fascia oraria ritorno per round-trip.

Il dettaglio del volo di ritorno di un round-trip richiede una richiesta successiva con `departure_token`; i filtri del ritorno possono comunque essere applicati già alla ricerca iniziale.

---

# 6. Modelli dati

## 6.1 DatePriceCandidate

Modello Discovery generale previsto:

- outboundDate;
- returnDate;
- indicativePrice;
- currency;
- source.

## 6.2 WeekendCandidate — IMPLEMENTATO

Campi principali:

- outboundDate;
- returnDate;
- price;
- currency;
- destinationIata;
- destinationName;
- monthLabel;
- monthKey;
- eventuale `verification`.

Le date/prezzo base del `WeekendCandidate` sono dati **indicativi Travel Explore**.

## 6.3 VerifiedWeekendResult — IMPLEMENTATO v2.2

Contiene il miglior pattern verificato con Google Flights:

- pattern (`Venerdì sera → domenica sera` oppure `Sabato mattina → lunedì`);
- data andata;
- data ritorno;
- prezzo round-trip verificato;
- valuta;
- compagnie dell'andata;
- orario esatto partenza andata;
- orario esatto arrivo andata;
- numero scali andata;
- fascia ritorno applicata;
- quota live letta prima della fase di verifica;
- timestamp verifica.

Il dettaglio esatto del volo di ritorno non è ancora scaricato perché richiederebbe `departure_token` e una query addizionale.

## 6.4 FlightItinerary — modello finale previsto

- prezzo, valuta, durata totale;
- segmenti andata/ritorno;
- scali;
- provider;
- timestamp aggiornamento.

`FlightSegment`: aeroporti, date/orari, durata, airline, operating carrier, numero volo.

`Layover`: IATA, nome, città, paese, durata minuti, overnight.

## 6.5 SimpleFlightResult — modello transitorio v1

Usato dal flusso date fisse: prezzo round-trip, valuta, compagnie/orari/scali dell'andata, quota pre-ricerca, stato cache e timestamp cache.

---

# 7. Directory aeroporti locale — IMPLEMENTATA E VALIDATA

File: `data/local/AirportDirectory.kt`.

Struttura riutilizzabile:

`IATA → nome aeroporto → città → ISO country`.

Copertura iniziale: circa 180–200 aeroporti principali di Europa, Nord Africa e destinazioni internazionali comuni.

Usi:

- protezione anti-typo prima della rete;
- futuro lookup paese degli scali;
- futura selezione/autocomplete;
- filtro paese di scalo senza chiamate API.

Codice non riconosciuto: warning con `Correggi` / `Cerca comunque`, senza query automatica. Test reale `FC0`: PASS, 0 query.

---

# 8. Policy anti-esaurimento quota

Soglie generali sul saldo **live**:

- >50: funzionamento normale;
- <=50: cache/modalità risparmio più aggressive;
- <=20: conferma prima di una ricerca stimata >5 query;
- <=5: privilegiare cache/Explore/query singole/euristiche e bloccare ricerche non necessarie;
- mai 30–40 chiamate automatiche con un singolo tap.

Protezione già implementata:

1. validazione IATA locale prima della rete;
2. cache Room prima dell'Account API;
3. cache hit fresco: saltare Account API e provider;
4. cache miss/refresh: Account API → quota guard → provider;
5. `Aggiorna comunque` è override esplicito entro il TTL;
6. preservare riserva minima 5 query dopo il batch previsto.

### Weekend v2.2

Su cache miss completa:

1. controllo quota live;
2. 1 query Explore per mese selezionato;
3. secondo controllo quota live prima della Verifica;
4. fino a 2 query Google Flights sul solo candidato Explore più economico;
5. riserva minima di 5 query protetta anche prima della fase di Verifica.

Costo massimo:

- 1 mese: 1 Explore + 2 Google Flights = **3 query**;
- 2 mesi: 2 + 2 = **4 query**;
- 3 mesi: 3 + 2 = **5 query**.

Account API non conta nella quota normale.

---

# 9. Cache Room — IMPLEMENTATA

Tecnologia: Room 2.8.4 + KSP 2.3.11.  
Database: `volaflex.db`, versione schema **2**.

## 9.1 Date fisse

Tabella `flight_search_cache`.

Chiave:

`origine | destinazione | data andata | data ritorno`

TTL: **4 ore**.

## 9.2 Weekend

Tabella `weekend_search_cache`.

Chiave:

`WEEKEND | origine | destinazione | mese/intervallo`

TTL: **4 ore**.

I candidati sono serializzati in JSON. Da v2.2 il JSON contiene opzionalmente anche `VerifiedWeekendResult`.

Decisione v2.2: **nessuna migrazione Room 2→3**. La cache viene estesa solo nel JSON con nuovi campi dotati di default, così una cache v2.1 già presente resta decodificabile.

Comportamento:

- cache v2.2 già verificata → Discovery + Verifica riusate, **0 nuove query**;
- cache v2.1 fresca ma non verificata → saltare Explore e fare solo la fase di Verifica;
- verifica fallita/bloccata → conservare comunque il candidato Explore, evitando di ripagare Discovery al retry;
- `Aggiorna comunque` forza volontariamente Discovery + Verifica live.

Migrazione Room 1→2 già validata realmente sul telefono senza perdita dati.

---

# 10. Diagnostica — IMPLEMENTATA E VALIDATA

Room conserva gli ultimi 20 eventi:

- timestamp;
- tipo richiesta/evento;
- esito;
- status HTTP quando applicabile;
- messaggio sintetico.

Tipi attuali:

- `SERPAPI_ACCOUNT`;
- `GOOGLE_FLIGHTS`;
- `TRAVEL_EXPLORE`;
- `WEEKEND_VERIFY`;
- `CACHE`;
- `QUOTA_GUARD`.

Le API key non devono mai comparire nella diagnostica.

Schermata da Impostazioni con `Copia diagnostica`; validata sul telefono.

---

# 11. Stack tecnologico corrente

- Kotlin nativo + Jetpack Compose;
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

# 12. Identità, workflow, Release e firma

- Nome: **VolaFlex**;
- repository: `archimede-projects/flexi-flights`;
- namespace/applicationId: `com.archimedeprojects.volaflex`.

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

---

# 13. Credenziali API e Impostazioni

API key salvate localmente con DataStore Preferences; mai nel codice/repository; `android:allowBackup=false`.

- SerpApi obbligatoria per ricerche reali;
- SearchAPI.io opzionale;
- UI mostra solo stato `configurata ✓`, mai la chiave salvata.

Persistenza testata dopo chiusura completa e riapertura dell'app.

DataStore Preferences non cifra autonomamente a riposo; rischio accettato per app personale su storage privato Android.

---

# 14. Navigazione UI corrente

Route:

- `home`;
- `search` — Date fisse;
- `weekend` — Weekend flessibile;
- `settings`;
- `diagnostics`.

Ricerca espone due modalità affiancate:

- `Date fisse`;
- `Weekend`.

---

# 15. v1 — Fondamenta: CHIUSA E VALIDATA

## 15.1 Firma stabile

**COMPLETATA E VALIDATA END-TO-END.**

`0.1.0-dev.5` installata sopra `0.1.0-dev.4` senza disinstallazione.

## 15.2 Impostazioni API key

**COMPLETATA E VALIDATA.** Persistenza reale confermata dopo chiusura completa.

## 15.3 Prima ricerca reale SerpApi

**COMPLETATA E VALIDATA.**

Test:

- `FCO → MAD`;
- 16–19 ottobre 2026;
- Ryanair;
- 104 EUR round-trip;
- 0 scali andata;
- quota live 131/250 prima della ricerca.

## 15.4 IATA guard + cache + diagnostica

**COMPLETATE E VALIDATE SUL TELEFONO.**

Round finale v1:

- anti-typo `FC0`: PASS;
- cache Room: PASS;
- diagnostica/clipboard: PASS;
- consumo previsto: 1 query;
- consumo osservato: **1 query**.

**Conclusione: v1 “Fondamenta” CHIUSA.**

---

# 16. v2 — Date flessibili

## 16.1 v2.1 Weekend Discovery — CHIUSA E VALIDATA SUL TELEFONO

Implementazione:

- un'origine e una destinazione;
- singolo mese tra i prossimi 6 oppure prossimi 2/3 mesi;
- `engine=google_travel_explore`;
- `travel_duration=1` Weekend;
- `travel_class=1`;
- `travel_mode=1` Flight only;
- 1 query Explore per mese;
- quota live prima del batch;
- Room cache 4h;
- diagnostica `TRAVEL_EXPLORE`.

### Test reale v2.1

Confermato dal proprietario:

- aggiornamento alla build v2.1 riuscito;
- migrazione Room 1→2: **PASS senza perdita dati**;
- cache/diagnostica v1 ancora presenti insieme ai nuovi eventi;
- rotta `FCO → MAD`;
- candidato Explore: **01/10/2026 → 05/10/2026**;
- prezzo indicativo: **95 EUR**;
- cache Weekend: PASS;
- consumo previsto: 1 query;
- consumo osservato: **1 query**.

### Osservazione architetturale reale

Il preset Explore `travel_duration=1` ha restituito **giovedì→lunedì, 4 notti**, non il weekend breve atteso dall'utente.

Conclusione: Travel Explore è utile per **Discovery/prezzo indicativo**, ma **non è sufficiente** per garantire il requisito:

`venerdì sera O sabato mattina → domenica sera O lunedì`.

Questa osservazione reale è la motivazione diretta della v2.2.

## 16.2 v2.2 Weekend Verifica precisa — IMPLEMENTATA E CI VERDE; DA VALIDARE SUL TELEFONO

Fase B del pattern `Discovery → Verifica → Dettaglio`.

### Strategia

Dopo Explore:

1. ordinare i candidati per prezzo;
2. scegliere **solo il candidato Explore più economico** dell'intervallo selezionato;
3. ricavare il venerdì di riferimento dentro/attorno al range indicato da Explore;
4. verificare al massimo due pattern con Google Flights;
5. scegliere il pattern verificato più economico;
6. non enumerare tutti i weekend del mese.

Pattern implementati:

- **Venerdì sera → domenica sera**
  - andata: venerdì 17:00–23:59;
  - ritorno: domenica 17:00–23:59.
- **Sabato mattina → lunedì**
  - andata: sabato 05:00–11:59;
  - ritorno: lunedì, tutta la giornata.

Queste fasce sono l'interpretazione operativa corrente di “sera/mattina” e possono diventare configurabili in uno step successivo.

### Query Google Flights

Per ogni pattern:

- `engine=google_flights`;
- date esatte;
- `outbound_times` coerente col pattern;
- `return_times` coerente col pattern;
- `type=1` round-trip;
- Economy;
- `sort_by=2` prezzo;
- EUR, `hl=it`, `gl=it`.

Vengono eseguite **massimo 2 query Google Flights** per ricerca Weekend, indipendentemente dal numero di weekend presenti nel mese.

### Quota

Dopo Discovery viene effettuato un **nuovo controllo Account API live** prima della Verifica. Il controllo protegge la riserva minima di 5 query considerando 1 o 2 pattern effettivamente verificabili.

Se quota insufficiente o Account API non leggibile:

- non partire con Google Flights;
- mostrare comunque il candidato Explore come indicativo;
- registrare il blocco in Diagnostica.

### Output UI

Se la Verifica riesce mostrare card distinta:

**`Weekend verificato ✓`**

con:

- pattern scelto;
- date precise;
- prezzo round-trip verificato;
- compagnia dell'andata;
- partenza esatta dell'andata;
- arrivo esatto dell'andata;
- scali dell'andata;
- data/fascia del ritorno realmente applicata alla query;
- quota live prima della fase di verifica.

Limite intenzionale: l'orario/segmento esatto del ritorno non viene ancora scaricato perché richiede `departure_token` e quindi un'altra query. Questo resta nella futura fase Dettaglio.

### Diagnostica

Nuovo tipo:

`WEEKEND_VERIFY`

Registrare SUCCESS / EMPTY / ERROR e HTTP status quando disponibile per ciascun pattern provato.

### Cache

Nessuna nuova tabella e nessuna migrazione DB.

`VerifiedWeekendResult` viene salvato nello stesso JSON di `weekend_search_cache`.

- ricerca identica già verificata entro 4h → 0 query;
- vecchia cache v2.1 fresca senza verification → saltare Explore e fare solo Verifica;
- se Verifica fallisce, il candidato Discovery resta cached.

### CI v2.2

GitHub Actions **run #17: SUCCESS**.

- versione APK: `0.1.0-dev.17`;
- `compileReleaseKotlin`: SUCCESS;
- `assembleRelease`: SUCCESS (`BUILD SUCCESSFUL`);
- `zipalign`: SUCCESS;
- `apksigner verify`: SUCCESS;
- firma v2/v3 valida;
- fingerprint invariato: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`;
- APK SHA-256 build #17: `f5999c86a80615a7bc80dedfe79e283c6f60cfc14aee6077af499e7b39984821`;
- Release `VolaFlex - Development latest` aggiornata con `VolaFlex-dev.apk`.

## 16.3 Prossimi sotto-step v2

Dopo validazione v2.2:

- N notti / ±X con SearchAPI.io Calendar come acceleratore;
- range ampi;
- fallback euristico/quota-saver.

---

# 17. v3 — Geografia avanzata

Previsto:

- fino a 3 origini;
- fino a 3 destinazioni;
- Ovunque;
- paese/area;
- Explore/Deals dove efficienti;
- directory aeroporti estesa;
- deduplicazione.

---

# 18. v4 — Scali avanzati

Previsto:

- durata massima scalo;
- esclusione paese;
- stessa compagnia operativa;
- dettaglio scali;
- >8h;
- Maps;
- robustezza/fallback.

---

# 19. Decisioni e motivazioni

- **Kotlin + Compose:** Android-only, meno stack.
- **SerpApi primario:** Google Flights/Explore e free tier compatibili col progetto.
- **SearchAPI Calendar acceleratore:** riduce query in N notti/±X senza mantenere due motori voli completi.
- **Travel Explore solo Discovery:** il test reale v2.1 ha dimostrato che “Weekend” può produrre un range più lungo del requisito.
- **Verifica mirata massimo 2 query:** corregge il limite Explore senza brute force su tutti i weekend.
- **Discovery → Verifica → Dettaglio:** protezione strutturale della quota.
- **GitHub-only:** requisito esplicito.
- **GitHub Release `dev-latest`:** APK persistente e facilmente scaricabile.
- **DataStore per API key:** locale, semplice, gratuito.
- **Room per cache + diagnostica:** protegge quota e facilita debug.
- **Directory IATA locale:** zero rete/costo e riutilizzabile per scali.
- **Cache verification nello stesso JSON:** evita migrazione Room inutile e mantiene compatibilità v2.1.

---

# 20. Rischi noti e accettati

- SerpApi/SearchAPI.io possono subire cambi JSON, regressioni o downtime;
- ricerche ampie non sempre matematicamente esaustive;
- quota SerpApi limitata e condivisa;
- Travel Explore/SearchAPI Calendar danno candidati indicativi, non sempre verità finale;
- SearchAPI.io crediti potenzialmente one-time;
- entrambe le fonti dipendono dall'ecosistema Google Flights e non danno vera ridondanza upstream;
- workflow senza Android Studio rende il debug più lento;
- perdita keystore impedisce aggiornamenti con la stessa identità;
- DataStore non cifra autonomamente le API key a riposo;
- directory IATA volutamente non esaustiva;
- cache key andranno estese con nuovi filtri/passeggeri/multi-aeroporto;
- v2.2 non scarica ancora il segmento preciso del ritorno via `departure_token`;
- le fasce 17–23 / 05–11 sono una prima interpretazione operativa e potrebbero diventare configurabili.

---

# 21. Stato reale

## Chiuso e validato sul telefono

- Fase 0: 100%;
- v1 Fondamenta: 100%;
- firma persistente;
- Impostazioni/API key;
- prima Google Flights reale;
- IATA guard;
- Room cache;
- Diagnostica/clipboard;
- v2.1 Weekend Discovery;
- migrazione Room 1→2;
- cache Weekend v2.1;
- consumo v2.1: **1 query osservata = 1 stimata**.

## Implementato/CI verde, da testare sul telefono

- **v2.2 Weekend Verifica precisa**;
- massimo 2 pattern Google Flights sul solo candidato più economico;
- nuovo controllo quota live prima della Verifica;
- `WEEKEND_VERIFY` diagnostics;
- card `Weekend verificato ✓`;
- cache del risultato verificato nello stesso JSON.

Build corrente funzionale: **`0.1.0-dev.17`**, run #17 SUCCESS, firma invariata.

---

# 22. Prossimo milestone

**Validazione reale v2.2 sul telefono.**

Criteri:

1. installare `0.1.0-dev.17` sopra la build attuale senza disinstallare;
2. aprire Ricerca → Weekend;
3. usare una rotta/mese non già in cache per testare Discovery + Verifica completa;
4. deve partire 1 query Explore per il mese;
5. deve avvenire un secondo controllo quota live;
6. devono partire al massimo 2 query `WEEKEND_VERIFY`;
7. se almeno un pattern ha voli, mostrare `Weekend verificato ✓`;
8. le date finali devono essere venerdì→domenica oppure sabato→lunedì, non giovedì→lunedì;
9. mostrare prezzo verificato e orari esatti dell'andata;
10. Diagnostica deve mostrare `TRAVEL_EXPLORE` + `WEEKEND_VERIFY`;
11. ripetizione identica entro 4h deve arrivare interamente da cache e consumare 0 query provider;
12. nessun crash/schermata bianca.

Dopo conferma: chiudere v2.2 e passare a **N notti / ±X con SearchAPI.io Calendar**.
