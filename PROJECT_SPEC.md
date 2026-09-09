# VolaFlex — PROJECT_SPEC

**Repository:** `archimede-projects/flexi-flights` (privata)  
**Nome app:** VolaFlex  
**Application ID / namespace:** `com.archimedeprojects.volaflex`  
**Ruolo:** fonte di verità persistente del progetto  
**Ultimo aggiornamento:** 2026-09-09

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
- test UI e runtime su telefono Android reale;
- proprietario del progetto principiante assoluto.

---

# 2. Requisiti funzionali originali

1. **Weekend flessibili:** partenza venerdì sera oppure sabato mattina; ritorno domenica sera oppure lunedì; ricerca su più settimane o mesi.
2. **N notti:** origine + destinazione + numero esatto di notti, senza date fisse; trovare il periodo più economico.
3. **Data ±X giorni:** data target con flessibilità X scelta dall'utente.
4. **Range ampio di date:** es. 1–30 giugno, evitando brute force quando esistono metodi più efficienti.
5. **Multi-origine:** fino a 3 città/aeroporti alternativi.
6. **Destinazione:** singola città/aeroporto, fino a 3 alternative, Ovunque, oppure intero paese (es. Marocco).
7. **Esclusione paese di scalo:** es. nessuno scalo nel Regno Unito.
8. **Stessa compagnia:** verificare che tutte le tratte siano operate dalla stessa compagnia quando richiesto.
9. **Durata massima dello scalo.**
10. **Dettaglio scalo:** aeroporto, città, paese, durata ed eventuale overnight.
11. **Scali >8h:** pulsante Maps tramite Android Intent; niente Google Maps SDK/API key.

---

# 3. Fonti dati scelte

## 3.1 SerpApi — provider primario e fonte definitiva

Quota free di riferimento del progetto: 250 ricerche/mese, 50/ora. La quota va sempre considerata condivisa e verificata live quando richiesto dalla policy.

Engine/ruoli:

- **Google Flights**: ricerca precisa, verifica finale, risultati completi, segmenti e scali;
- **Google Travel Explore**: Discovery economica per weekend, Ovunque, paesi/regioni e altre ricerche ampie;
- **Google Flights Deals**: Discovery aggiuntiva quando il caso d'uso coincide con i suoi parametri.

SerpApi resta la fonte definitiva del futuro modello `FlightItinerary`.

### Account API

La SerpApi Account API è gratuita e non consuma la quota normale. Il saldo restituito live è la fonte autorevole per VolaFlex.

### Nota: chiave SerpApi condivisa con altro progetto

La API key SerpApi usata da VolaFlex **non è dedicata all'app**: lo stesso account gratuito viene usato anche da un altro progetto personale.

Conseguenze:

- il saldo può diminuire mentre VolaFlex non viene usata;
- nessun contatore locale è fonte di verità;
- prima di ogni ricerca stimata costosa (>5 query) è obbligatorio un refresh live tramite Account API;
- nelle funzioni già implementate viene usato un controllo ancora più prudente prima di ogni batch live;
- se Account API fallisce, non partire con una ricerca costosa automatica;
- la riserva minima di 5 query deve essere protetta;
- non creare un secondo account SerpApi solo per separare la quota se ciò richiede fornire un numero di telefono.

## 3.2 SearchAPI.io Calendar — acceleratore mirato

Non è un secondo motore voli completo. È un `date discovery accelerator` da introdurre nella v2 per casi come:

- N notti + ±X giorni;
- range ampi con destinazione fissa;
- casi in cui SerpApi richiederebbe circa >=10 chiamate.

Limiti/decisioni già registrati:

- massimo 200 combinazioni andata/ritorno per richiesta;
- per N notti va filtrata localmente la diagonale `return = departure + N`;
- blocchi sincronizzati fino a 14 date sono sicuri perché `14² = 196`, mentre `15² = 225` supera il limite;
- i 100 crediti gratuiti non vengono considerati ricorrenti mensilmente finché non confermato;
- VolaFlex deve continuare a funzionare anche quando SearchAPI.io non è disponibile o i crediti sono finiti.

## 3.3 `price_insights`

Non usare per ±X giorni: descrive statisticamente la stessa rotta/date interrogata e non è un calendario di date alternative.

---

# 4. Pattern architetturale obbligatorio

`DISCOVERY → VERIFICA → DETTAGLIO`

## Discovery

Trovare candidati con poche query usando a seconda del caso:

- SerpApi Travel Explore;
- SearchAPI.io Calendar;
- Google Flights Deals;
- query multi-airport;
- cache locale;
- campionamento euristico.

## Verifica

Solo pochi candidati migliori (tipicamente 1–3) vengono verificati con SerpApi Google Flights, applicando date precise e filtri disponibili.

## Dettaglio

Solo on-demand recuperare/analizzare:

- eventuale `departure_token` e ritorno associato;
- segmenti;
- scali;
- operating carrier / codeshare;
- paese scali;
- stessa compagnia;
- popup scalo;
- Maps.

Mai scaricare automaticamente il dettaglio completo di decine di risultati.

---

# 5. Filtri SerpApi verificati

- durata scalo: nativo `layover_duration=MIN,MAX` in minuti + verifica Kotlin;
- esclusione aeroporto di connessione: nativo `exclude_conns`;
- esclusione paese di connessione: non nativo, quindi client-side con lookup IATA→paese;
- inclusione compagnie: nativo `include_airlines`;
- esclusione compagnie: nativo `exclude_airlines`;
- stessa compagnia operativa su tutti i segmenti: controllo Kotlin obbligatorio.

Dati JSON necessari disponibili: `layovers[]`, `flights[]` e informazioni su vettore operativo/codeshare quando presenti.

---

# 6. Modello dati

## 6.1 DatePriceCandidate

Modello Discovery minimo previsto:

- `outboundDate`;
- `returnDate`;
- `indicativePrice`;
- `currency`;
- `source`.

## 6.2 WeekendCandidate — IMPLEMENTATO v2.1

- `outboundDate`;
- `returnDate`;
- `price`;
- `currency`;
- `destinationIata`;
- `destinationName`;
- `monthLabel`.

È un modello di **Discovery indicativa**, non un itinerario verificato.

## 6.3 FlightItinerary — modello finale previsto

- prezzo, valuta, durata totale;
- segmenti andata/ritorno;
- scali;
- provider;
- timestamp aggiornamento.

`FlightSegment`: aeroporti, date/orari, durata, airline, operating carrier, numero volo.

`Layover`: IATA, nome, città, paese, durata minuti, overnight.

## 6.4 SimpleFlightResult — modello transitorio v1

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
- futura selezione/autocomplete aeroporti;
- filtro paese di scalo senza chiamate API.

### Regola anti-typo

Codice non riconosciuto localmente:

- nessuna query parte subito;
- avviso non invasivo;
- pulsanti `Correggi` e `Cerca comunque`;
- non bloccare definitivamente perché la lista locale non è esaustiva.

Test reale `FC0` eseguito con successo: warning mostrato e 0 query consumate.

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
4. su cache miss/refresh forzato: Account API → quota guard → provider;
5. `Aggiorna comunque` è l'override esplicito entro il TTL;
6. per un batch Weekend Explore, preservare almeno 5 query residue dopo il batch previsto.

Quindi per Weekend Explore:

- 1 mese: almeno 6 query residue;
- 2 mesi: almeno 7;
- 3 mesi: almeno 8.

---

# 9. Cache Room — IMPLEMENTATA

Tecnologia: Room 2.8.4 + KSP 2.3.11.  
Database: `volaflex.db`.

## 9.1 Date fisse

Tabella `flight_search_cache`.

Chiave:

`origine | destinazione | data andata | data ritorno`

TTL: **4 ore**.

Cache hit mostra `Risultato da cache — aggiornato alle HH:MM` e consuma 0 query provider.

## 9.2 Weekend Explore — IMPLEMENTATO v2.1

Tabella `weekend_search_cache`.

Chiave concettuale:

`WEEKEND | origine | destinazione | mese/intervallo`

Il periodo usa valori `YYYY-MM`, quindi intervalli diversi hanno chiavi distinte.

TTL: **4 ore**.

I candidati vengono serializzati localmente in JSON. Cache hit salta sia Account API sia Travel Explore. È disponibile `Aggiorna comunque` per forzare volontariamente un nuovo batch.

Database portato da versione 1 a 2 con migrazione esplicita `1 → 2` che aggiunge la nuova tabella senza eliminare cache/diagnostica esistenti. La compilazione CI è riuscita; l'applicazione reale della migrazione va confermata installando la build v2.1 sopra la v1 sul telefono.

---

# 10. Diagnostica — IMPLEMENTATA E VALIDATA

Room conserva gli ultimi 20 eventi rilevanti:

- timestamp;
- tipo richiesta/evento;
- esito;
- status HTTP quando applicabile;
- messaggio sintetico.

Tipi:

- `SERPAPI_ACCOUNT`;
- `GOOGLE_FLIGHTS`;
- `TRAVEL_EXPLORE`;
- `CACHE`;
- `QUOTA_GUARD`.

Le API key non devono mai comparire nella diagnostica.

Schermata raggiungibile da Impostazioni con `Copia diagnostica` negli appunti Android. Testata con successo sul telefono nella v1.

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
- asset `VolaFlex-dev.apk`;
- nessun Actions artifact temporaneo come canale di distribuzione.

`PROJECT_SPEC.md`, `SESSION_HANDOFF.md` e il workflow stesso sono ignorati dai trigger push per evitare build inutili.

Firma persistente validata end-to-end:

`a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`

Keystore con doppio backup personale; password conservata separatamente. La perdita del keystore impedirebbe di aggiornare gli APK già installati senza disinstallazione/reinstallazione.

Nota operativa: in Codespaces `gh secret set` ha già restituito `403 Resource not accessible by integration`; per Secrets amministrativi usare la UI GitHub quando il token Codespaces non ha permessi sufficienti.

---

# 13. Credenziali API e Impostazioni

API key salvate localmente con DataStore Preferences; mai nel codice/repository; `android:allowBackup=false`.

- SerpApi obbligatoria per le ricerche reali;
- SearchAPI.io opzionale;
- UI mostra solo stato `configurata ✓`, mai il valore salvato.

Persistenza testata realmente anche dopo chiusura completa e riapertura dell'app.

DataStore Preferences non cifra autonomamente i valori a riposo; rischio accettato per questa app personale con storage privato Android.

---

# 14. Navigazione UI corrente

Route attuali:

- `home`;
- `search` — Date fisse;
- `weekend` — Weekend flessibile;
- `settings`;
- `diagnostics`.

Nella schermata Ricerca sono presenti le due modalità affiancate:

- `Date fisse`;
- `Weekend`.

---

# 15. v1 — Fondamenta: CHIUSA E VALIDATA SUL TELEFONO

## 15.1 Firma stabile

**COMPLETATA E VALIDATA END-TO-END.**

Aggiornamento reale installato sopra la versione precedente senza disinstallazione.

## 15.2 Impostazioni API key

**COMPLETATA E VALIDATA.**

Persistenza reale confermata dopo chiusura completa dell'app.

## 15.3 Prima ricerca reale SerpApi a date fisse

**COMPLETATA E VALIDATA.**

Test reale:

- `FCO → MAD`;
- 16–19 ottobre 2026;
- Ryanair;
- 104 EUR round-trip;
- 0 scali andata;
- quota live 131/250 prima della ricerca;
- nessun crash.

## 15.4 IATA guard + cache Room + diagnostica

**COMPLETATE E VALIDATE SUL TELEFONO.**

Il proprietario ha confermato:

- anti-typo IATA `FC0`: PASS;
- Room cache 4h: PASS;
- diagnostica ultimi 20 eventi: PASS;
- `Copia diagnostica`: PASS;
- firma/app funzionanti dopo aggiornamento: PASS.

### Consumo quota osservato

L'intero round di test finale v1 ha consumato **1 sola query SerpApi**, esattamente coerente con la stima:

- warning typo: 0;
- prima ricerca valida: 1;
- ripetizione identica da cache: 0.

**Conclusione: v1 “Fondamenta” è CHIUSA.**

---

# 16. v2 — Date flessibili

## v2.1 Weekend flessibile con SerpApi Travel Explore — IMPLEMENTATA, CI VERDE, DA VALIDARE SUL TELEFONO

Obiettivo: introdurre la fase **Discovery** dei weekend senza usare ancora SearchAPI.io Calendar e senza fare ancora la verifica precisa degli orari.

### Input attuali

- un solo aeroporto di partenza;
- una sola destinazione aeroporto;
- periodo selezionabile tra:
  - ciascuno dei prossimi 6 mesi disponibili;
  - `Prossimi 2 mesi`;
  - `Prossimi 3 mesi`.

Restano fuori da questo sotto-step:

- multi-aeroporto;
- Ovunque/paese;
- orari venerdì sera/sabato mattina e domenica sera/lunedì;
- verifica Google Flights dei candidati;
- N notti / ±X;
- SearchAPI.io Calendar.

### Engine e parametri

Per ciascun mese selezionato:

- `engine=google_travel_explore`;
- origine `departure_id`;
- destinazione `arrival_id`;
- `month=<mese>`;
- `travel_duration=1` = Weekend;
- `travel_class=1` = Economy;
- `travel_mode=1` = voli;
- `currency=EUR`;
- `hl=it`;
- `gl=it`.

### Strategia query

- singolo mese: 1 query Explore;
- prossimi 2 mesi: 2 query Explore;
- prossimi 3 mesi: 3 query Explore;
- Account API viene letta una sola volta prima del batch ed è gratuita;
- quota guard calcolata prima di partire;
- cache fresca salta completamente Account API + Explore.

### Output

Per ogni mese con risultato viene mostrato un candidato Discovery con:

- data andata;
- data ritorno;
- prezzo indicativo;
- valuta;
- destinazione.

I candidati vengono ordinati per prezzo.

**Importante:** il prezzo/data Explore è indicativo. Non significa ancora che il volo rispetti esattamente venerdì sera/sabato mattina e domenica sera/lunedì. La verifica precisa con Google Flights appartiene al raffinamento successivo.

### Diagnostica e cache

- nuovo tipo diagnostico `TRAVEL_EXPLORE`;
- cache Room 4h dedicata;
- cache key origine + destinazione + mese/intervallo;
- `Aggiorna comunque` disponibile come override esplicito.

### Build CI v2.1

GitHub Actions **run #16: SUCCESS**.

- versione APK: `0.1.0-dev.16`;
- `kspReleaseKotlin`: SUCCESS;
- `compileReleaseKotlin`: SUCCESS;
- `assembleRelease`: SUCCESS;
- `zipalign`: SUCCESS;
- `apksigner verify`: SUCCESS;
- fingerprint firma invariato: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`;
- Release `VolaFlex - Development latest` aggiornata con `VolaFlex-dev.apk`.

### Prossimo raffinamento dopo validazione v2.1

Aggiungere la fase **Verifica** dei candidati Weekend con poche query Google Flights per applicare gli orari/pattern precisi, senza enumerare brutalmente ogni combinazione.

## v2.2 N notti / ±X

Previsto dopo il raffinamento Weekend. Qui entrerà SearchAPI.io Calendar come acceleratore mirato, con fallback SerpApi/euristico.

## v2.3 Range ampi

Da implementare successivamente con la stessa logica quota-first e Discovery economica.

---

# 17. v3 — Geografia avanzata

Previsto:

- fino a 3 origini;
- fino a 3 destinazioni;
- Ovunque;
- paese/area;
- Travel Explore/Deals dove efficienti;
- directory aeroporti estesa;
- deduplicazione risultati.

---

# 18. v4 — Scali avanzati

Previsto:

- durata massima scalo;
- esclusione paese di scalo;
- stessa compagnia operativa;
- dettaglio scali;
- scali >8h;
- Maps;
- robustezza/fallback.

---

# 19. Decisioni e perché

- **Kotlin + Compose, non Flutter:** solo Android, meno stack da imparare e migliore allineamento con documentazione/API native.
- **SerpApi primario, non Amadeus:** Google Flights/Explore e free tier coerenti con i requisiti; Amadeus Self-Service non è una base valida per questo nuovo progetto.
- **SearchAPI Calendar acceleratore, non backup completo:** riduce query per date flessibili senza mantenere due motori/parsing completi.
- **Travel Explore per Weekend Discovery:** il preset Weekend permette di trovare candidati mensili con 1 query per mese invece di enumerare ogni weekend con Google Flights.
- **Discovery → Verifica → Dettaglio:** protezione strutturale della quota.
- **GitHub-only:** requisito esplicito; accettati cicli di debug più lenti e assenza di IDE/emulatore locale.
- **GitHub Release, non Actions artifact:** APK persistente e facilmente scaricabile dal telefono.
- **Singola Release `dev-latest`:** evita proliferazione di release durante sviluppo.
- **Keystore stabile gestito dal proprietario:** necessario per aggiornare l'app senza disinstallare.
- **DataStore per API key:** semplice, locale, gratuito, nessun backend.
- **Room per cache + diagnostica:** protegge quota e rende i problemi riproducibili.
- **Directory IATA statica:** zero rete/costo e riutilizzabile per il filtro paese scali.
- **Warning e non blocco su IATA sconosciuto:** la lista ridotta non può essere autorità assoluta.
- **Release v2.1 con migrazione Room esplicita:** non distruggere i dati locali costruiti durante v1.

---

# 20. Rischi noti e accettati

- SerpApi/SearchAPI.io non sono fonti ufficiali Google Flights e possono subire cambi JSON, regressioni o downtime;
- ricerche ampie non sempre matematicamente esaustive: campionamento/Discovery accettati quando necessario;
- quota SerpApi limitata e condivisa con altro progetto;
- prezzi Travel Explore e SearchAPI Calendar sono candidati indicativi, non verità finale;
- crediti SearchAPI.io potenzialmente one-time;
- entrambe le fonti principali dipendono dall'ecosistema Google Flights, quindi non costituiscono vera ridondanza di upstream;
- workflow senza Android Studio: debug più lento;
- perdita keystore: impossibilità di aggiornare APK già firmati senza reinstallazione;
- DataStore non cifra autonomamente le API key a riposo;
- directory IATA locale volutamente non esaustiva;
- cache va estesa quando verranno introdotti nuovi filtri/passeggeri/multi-aeroporto;
- la prima query Google Flights date fisse non recupera ancora il dettaglio ritorno via `departure_token`;
- Travel Explore Weekend non garantisce ancora gli orari precisi desiderati: serve la successiva fase di Verifica;
- la migrazione Room 1→2 è compilata ma va verificata sul telefono durante l'aggiornamento reale alla build v2.1.

---

# 21. Stato di avanzamento reale

## Chiuso e validato nel mondo reale

- **Fase 0:** 100%;
- **v1 Fondamenta:** 100%;
- firma persistente;
- Impostazioni/API key;
- Account API reale;
- Google Flights reale;
- IATA anti-typo;
- Room cache 4h;
- Diagnostica + clipboard;
- consumo round finale v1: **1 query osservata, coerente con stima**.

## Implementato e validato in CI, da testare sul telefono

- **v2.1 Weekend Travel Explore Discovery**;
- nuova route/modalità Weekend;
- selettore mese/2 mesi/3 mesi;
- quota guard batch;
- `TRAVEL_EXPLORE` diagnostics;
- weekend cache 4h;
- migrazione Room 1→2.

Build corrente: **`0.1.0-dev.16`**, GitHub Actions run #16 SUCCESS, firma invariata.

---

# 22. Prossimo milestone

**Validazione reale v2.1 Weekend sul telefono.**

Criteri:

1. installare `0.1.0-dev.16` sopra la build attuale senza disinstallare;
2. app deve aprirsi normalmente, confermando la migrazione Room senza perdita dei dati locali;
3. Ricerca → `Weekend`;
4. test `FCO → MAD` su un singolo mese futuro, preferibilmente ottobre 2026;
5. Account API deve verificare quota sufficiente;
6. deve partire una sola query `TRAVEL_EXPLORE` per il mese;
7. deve comparire un candidato con data andata, data ritorno, prezzo indicativo e destinazione;
8. Diagnostica deve mostrare `SERPAPI_ACCOUNT` e `TRAVEL_EXPLORE`;
9. ripetere identica ricerca entro 4h: deve arrivare da cache e consumare 0 query provider;
10. nessun crash/schermata bianca.

Dopo conferma: segnare v2.1 Discovery Weekend come validata e implementare il raffinamento di Verifica con Google Flights sugli orari precisi.
