# VolaFlex — PROJECT_SPEC

**Repository:** `archimede-projects/flexi-flights` (privata)  
**Nome app:** VolaFlex  
**Application ID / namespace:** `com.archimedeprojects.volaflex`  
**Ruolo:** fonte di verità persistente del progetto  
**Ultimo aggiornamento:** 2026-09-09

## Regola di manutenzione

Aggiornare questo file ogni volta che cambia un requisito, una decisione architetturale, una tecnologia, una policy API/quota o lo stato reale di avanzamento. Non contraddire decisioni già registrate senza richiesta esplicita del proprietario. `SESSION_HANDOFF.md` contiene solo lo stato operativo sintetico per riprendere il lavoro in una nuova chat.

---

# 1. Obiettivo e vincoli

VolaFlex è un'app Android personale per cercare voli economici con forte supporto a date flessibili, weekend, multi-aeroporto e filtri sugli scali.

Vincoli permanenti:

- uso personale;
- APK sideload, niente Play Store per ora;
- zero costi;
- nessuna carta di credito/debito richiesta;
- niente backend/server a pagamento;
- sviluppo GitHub-only;
- repository GitHub privata;
- nessun Android Studio locale;
- GitHub Actions per CI/build;
- GitHub Releases per distribuire l'APK;
- GitHub Codespaces opzionale;
- test UI su telefono Android reale;
- proprietario del progetto principiante assoluto.

---

# 2. Requisiti funzionali originali

## 2.1 Weekend flessibili

Supportare pattern configurabili:

- partenza venerdì sera oppure sabato mattina;
- ritorno domenica sera oppure lunedì;
- ricerca su più settimane o mesi.

L'app può usare discovery + verifica invece di interrogare ogni combinazione possibile.

## 2.2 Numero di notti

Ricerca per origine, destinazione, numero esatto di notti e periodo/date flessibili. Obiettivo: trovare le date più economiche compatibili con il numero di notti.

## 2.3 Data ±X giorni

L'utente sceglie una data target e un valore X. SearchAPI.io Calendar viene usato quando evita molte query SerpApi. Se non disponibile: SerpApi esaustivo solo su range piccoli oppure campionamento euristico su range grandi.

## 2.4 Range ampio di date

Esempio: `1 giugno → 30 giugno`. Evitare una query SerpApi per ogni giorno quando esistono strategie più efficienti.

## 2.5 Multi-origine

Supportare fino a 3 città/aeroporti alternativi.

## 2.6 Destinazione

Supportare:

1. singola città/aeroporto;
2. fino a 3 destinazioni alternative;
3. “Ovunque”;
4. intero paese, es. Marocco.

## 2.7 Esclusione paese per gli scali

Esempio: nessuno scalo nel Regno Unito. SerpApi non offre filtro nativo per paese: implementare in Kotlin con lookup locale `IATA → ISO country`.

## 2.8 Stessa compagnia su tutte le tratte

Opzione: tutte le tratte devono essere operate dalla stessa compagnia. `include_airlines` è solo un pre-filtro; controllo definitivo segmento-per-segmento lato Kotlin, preferendo il vettore operativo quando disponibile.

## 2.9 Durata massima dello scalo

Filtro nativo SerpApi: `layover_duration=MIN,MAX` in minuti. Eseguire anche controllo client-side su `layovers[].duration`.

## 2.10 Dettaglio scalo

Mostrare almeno aeroporto, città quando disponibile, paese, durata ed eventuale overnight.

## 2.11 Maps per scali >8h

Per scali oltre 8 ore mostrare pulsante verso Google Maps/browser tramite Android Intent. Niente Google Maps SDK nella prima versione.

---

# 3. Fonti dati

## 3.1 SerpApi — provider primario

Quota free di riferimento:

- 250 ricerche/mese;
- 50/ora;
- nessuna carta richiesta.

Usi previsti:

- Google Flights;
- Google Travel Explore;
- Google Flights Deals quando utile;
- date fisse;
- weekend;
- Anywhere;
- destinazione paese;
- multi-origine/multi-destinazione;
- filtri orari;
- filtri compagnie;
- durata scalo;
- esclusione aeroporti di connessione;
- itinerari completi;
- segmenti/scali;
- verifica prezzo finale;
- dettaglio finale.

SerpApi è la fonte definitiva di `FlightItinerary`.

### Cache SerpApi

Le query identiche possono essere servite dalla cache SerpApi senza consumo di quota quando il provider le considera cached. La cache locale Room resta prevista per controllare direttamente il riuso lato app.

### Account API

Usare la SerpApi Account API per leggere il saldo reale. La Account API è gratuita e non consuma la quota normale.

### Nota: chiave SerpApi condivisa con altro progetto

La API key SerpApi usata da VolaFlex **non è dedicata all'app**: appartiene allo stesso account gratuito già usato da un altro progetto personale.

Conseguenze architetturali:

- la quota mensile SerpApi è condivisa;
- il saldo può diminuire anche mentre VolaFlex non viene usata;
- un contatore locale non è fonte di verità;
- il valore autorevole è quello restituito live dalla SerpApi Account API;
- prima di ogni ricerca stimata costosa (>5 query SerpApi), refresh Account API obbligatorio;
- se il controllo live fallisce, non avviare automaticamente una ricerca >5 query;
- usare una strategia <=5 query oppure chiedere override esplicito.

Questa decisione è intenzionale: non creare un secondo account SerpApi solo per separare la quota se ciò richiede fornire un numero di telefono.

## 3.2 SearchAPI.io Calendar — acceleratore mirato

Non è un secondo provider voli completo. Ruolo: `date discovery accelerator`.

Usare soprattutto per:

- N notti + ±X giorni;
- range ampi con destinazione fissa;
- casi in cui SerpApi richiederebbe circa >=10 chiamate.

Parametri principali:

- `outbound_date_start`;
- `outbound_date_end`;
- `return_date_start`;
- `return_date_end`.

Limite noto:

- massimo 200 combinazioni andata/ritorno per richiesta;
- spezzare range grandi in blocchi;
- per N notti filtrare localmente la diagonale `return = departure + N`.

I 100 crediti gratuiti non vengono considerati ricorrenti mensilmente perché il rinnovo non è confermato. L'app deve continuare a funzionare quando terminano.

## 3.3 `price_insights`

Non usare per ±X giorni. `price_insights` descrive statisticamente la stessa rotta/date interrogata e non è un calendario giorno-per-giorno di date alternative.

---

# 4. Pattern architetturale di ricerca

Pattern obbligatorio:

`DISCOVERY → VERIFICA → DETTAGLIO`

## 4.1 Discovery

Trovare candidati con poche query usando, a seconda del caso:

- SerpApi Travel Explore;
- SearchAPI.io Calendar;
- Google Flights Deals;
- query multi-airport;
- cache locale;
- campionamento euristico.

## 4.2 Verifica

Verificare solo 1–3 candidati migliori con SerpApi Google Flights, applicando date precise e filtri disponibili.

## 4.3 Dettaglio

Solo on-demand analizzare/recuperare:

- eventuale `departure_token` e ritorno associato;
- segmenti;
- scali;
- codeshare / operating carrier;
- paese scali;
- stessa compagnia;
- popup scalo;
- Maps.

Non scaricare automaticamente il dettaglio completo di decine di risultati.

---

# 5. Filtri SerpApi verificati

- Durata scalo: nativo `layover_duration=MIN,MAX` in minuti + verifica Kotlin.
- Esclusione aeroporto di connessione: nativo `exclude_conns`.
- Esclusione paese di connessione: non nativo; client-side con `IATA → paese`.
- Inclusione compagnie: nativo `include_airlines`.
- Esclusione compagnie: nativo `exclude_airlines`.
- Stessa compagnia operativa su tutti i segmenti: controllo Kotlin obbligatorio.

Dati JSON utili disponibili:

- `layovers[]`: aeroporto/IATA e durata;
- `flights[]`: singoli segmenti con compagnia e dati del volo;
- informazione vettore operativo/codeshare quando presente.

---

# 6. Modello dati

## 6.1 DatePriceCandidate

Campi minimi:

- `outboundDate`;
- `returnDate`;
- `indicativePrice`;
- `currency`;
- `source`.

Usato nella fase Discovery.

## 6.2 FlightItinerary

Campi principali:

- prezzo;
- valuta;
- durata totale;
- segmenti andata;
- segmenti ritorno;
- scali;
- provider;
- timestamp aggiornamento.

### FlightSegment

- aeroporto partenza;
- aeroporto arrivo;
- data/ora partenza;
- data/ora arrivo;
- durata;
- airline;
- operating carrier;
- numero volo.

### Layover

- IATA aeroporto;
- nome aeroporto;
- città;
- paese;
- durata minuti;
- overnight.

La UI deve dipendere dai modelli interni, non direttamente dai DTO JSON dei provider.

## 6.3 Modello temporaneo v1 prima ricerca

Per il primo test reale è implementato anche un modello minimale `SimpleFlightResult` con:

- prezzo round-trip;
- valuta;
- compagnie dell'andata;
- orario partenza andata;
- orario arrivo andata;
- numero scali andata;
- quota SerpApi verificata prima della ricerca.

Questo modello è transitorio e verrà sostituito/assorbito dal modello completo `FlightItinerary` quando implementeremo la lista risultati completa.

---

# 7. Database aeroporti locale

Prevedere fonte locale `IATA → aeroporto → città → ISO country`.

Usi:

- mostrare paese dello scalo;
- filtrare paesi esclusi;
- selezione aeroporti;
- ricerca geografica;
- popup scalo.

Nessuna query API per questo lookup.

---

# 8. Policy anti-esaurimento quota

## SerpApi

Le soglie sono applicate al **saldo live dell'account**:

- >50 residue: funzionamento normale;
- <=50: cache/modalità risparmio più aggressive;
- <=20: conferma prima di una ricerca stimata >5 query;
- <=5: privilegiare cache, Explore, query singole ed euristiche;
- mai 30–40 chiamate automatiche con un singolo tap.

Regola chiave condivisa:

- prima di ogni ricerca stimata >5 query, refresh Account API obbligatorio;
- il saldo mostrato in UI è informativo se non appena aggiornato;
- se Account API non è raggiungibile, non avviare automaticamente >5 query.

### Regola prudenziale del primo test reale

Per il primo endpoint Google Flights a date fisse, VolaFlex controlla comunque la Account API **prima di ogni singola ricerca**, anche se la ricerca costa solo 1 query. Se il saldo è <=5 o non è verificabile, la ricerca viene bloccata. Questa regola è intenzionalmente più prudente del minimo richiesto dalla policy generale e potrà essere rilassata in seguito solo con decisione esplicita.

## SearchAPI.io

Usare Calendar soprattutto quando sostituisce circa >=10 query SerpApi.

Quando i crediti finiscono:

- range piccolo → SerpApi esaustivo;
- range grande → campionamento 5–7 date;
- ricerca completa costosa solo su scelta esplicita;
- non disabilitare date flessibili.

---

# 9. Cache

Tecnologia prevista: Room.

Chiave concettuale:

- origine/i;
- destinazione/i;
- date;
- passeggeri;
- filtri;
- provider.

Salvare risultato e timestamp e riusare ricerche identiche recenti.

**Non ancora implementata.**

---

# 10. Stack tecnologico

## App

- Kotlin nativo;
- Jetpack Compose;
- Gradle;
- Android SDK;
- Retrofit;
- OkHttp;
- Kotlin Serialization;
- Kotlin Coroutines;
- Room (previsto, non ancora implementato);
- DataStore Preferences;
- Navigation Compose;
- Android Intent per Maps.

## Versioni fissate nella v1 corrente

- Android Gradle Plugin: 9.3.1;
- Kotlin / Compose compiler plugin: 2.4.20;
- Kotlin Serialization compiler plugin: 2.4.20;
- Gradle: 9.5.0;
- Compose BOM: 2026.06.00;
- Activity Compose: 1.11.0;
- DataStore Preferences: 1.2.1;
- Navigation Compose: 2.9.8;
- Retrofit: 3.0.0;
- Retrofit converter kotlinx-serialization: 3.0.0;
- OkHttp: 4.12.0;
- kotlinx-serialization-json: 1.11.0;
- compileSdk: 36;
- targetSdk: 36;
- minSdk: 26;
- JDK: 17.

AGP 9.x usa Kotlin integrato: non applicare `org.jetbrains.kotlin.android`.

Navigation Compose resta sulla linea 2.9.x per mantenere VolaFlex su compileSdk 36 durante la v1.

---

# 11. Identità del progetto

- Nome visibile: **VolaFlex**.
- Repository: **archimede-projects/flexi-flights**.
- `rootProject.name`: **VolaFlex**.
- namespace: **com.archimedeprojects.volaflex**.
- applicationId: **com.archimedeprojects.volaflex**.

`archimede-projects` contiene un trattino non valido in un package Java/Kotlin; è normalizzato in `archimedeprojects`.

---

# 12. Workflow GitHub-only, distribuzione e firma

Flusso:

`codice GitHub → push main → GitHub Actions → Gradle → APK → firma persistente → GitHub Release → telefono Android`

Gli APK NON vengono usati come Actions artifacts temporanei.

Durante sviluppo:

- tag: `dev-latest`;
- Release title: `VolaFlex - Development latest`;
- asset: `VolaFlex-dev.apk`.

Ogni push di codice/config su `main` sposta `dev-latest` e sostituisce l'APK. Release permanenti solo per tag `v*`.

`PROJECT_SPEC.md`, `SESSION_HANDOFF.md` e `.github/workflows/android-build.yml` sono esclusi dal trigger `push` tramite `paths-ignore`.

Se il connettore ChatGPT non espone `workflow_dispatch`, è accettabile attivare la stessa pipeline con un push tecnico innocuo o un fast-forward di `main` contenente modifiche reali.

Non attivare Immutable Releases mentre usiamo `dev-latest` sovrascrivibile.

## 12.1 Android SDK CI

- `android-actions/setup-android@v4`;
- Command-Line Tools build `14742923`;
- `platform-tools`;
- `platforms;android-36`;
- `build-tools;36.0.0`.

## 12.2 Firma persistente — VALIDATA END-TO-END

Keystore JKS stabile generato dal proprietario e mai condiviso in chat.

GitHub Actions Secrets:

- `KEYSTORE_BASE64`;
- `KEYSTORE_PASSWORD`;
- `KEY_ALIAS`;
- `KEY_PASSWORD`.

Workflow:

1. valida i 4 Secrets;
2. ricostruisce temporaneamente il keystore in `${RUNNER_TEMP}`;
3. compila `:app:assembleRelease`;
4. esegue `zipalign`;
5. firma con `apksigner`;
6. verifica con `apksigner verify --verbose --print-certs`;
7. pubblica l'APK firmato;
8. cancella il keystore temporaneo.

Fingerprint SHA-256 certificato stabile:

`a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`

Signer DN:

`CN=VolaFlex, OU=Personal, O=archimede-projects`

Chiave: RSA 4096 bit.

Validazione reale:

- `0.1.0-dev.4` installata con nuova firma;
- `0.1.0-dev.5` installata sopra usando “Aggiorna”, senza disinstallare;
- app avviata correttamente;
- firma persistente chiusa e validata nel mondo reale.

---

# 13. Credenziali API e Impostazioni

Regole:

- mai committare API key;
- mai inserire chiavi reali come costanti;
- mai mostrare in UI il valore già salvato;
- SearchAPI.io opzionale.

Storage:

- DataStore Preferences `api_keys`;
- chiavi interne `serp_api_key` e `search_api_key`;
- `android:allowBackup="false"`.

La UI mostra solo:

- `SerpApi: configurata ✓`;
- `SearchAPI.io: configurata ✓`;
- oppure stato non configurato.

DataStore Preferences non cifra da solo i dati a riposo; rischio accettato per APK personale su storage privato.

**Step Impostazioni testato con successo sul telefono, inclusa persistenza dopo chiusura completa dell'app.**

---

# 14. Navigazione UI

Navigation Compose con tre route:

- `home`;
- `search`;
- `settings`.

Home:

- `VolaFlex - Build OK`;
- versione;
- pulsante `Ricerca voli`;
- pulsante `Impostazioni`.

Impostazioni:

- stato SerpApi;
- campo SerpApi API key;
- stato SearchAPI.io;
- campo SearchAPI.io opzionale;
- pulsante `Salva`;
- ritorno Home.

Ricerca:

- partenza testuale;
- arrivo testuale;
- date picker andata;
- date picker ritorno;
- pulsante `Cerca`;
- loading;
- risultato minimo o errore leggibile;
- link a Impostazioni se manca/non è valida la key.

---

# 15. Prima chiamata reale SerpApi — implementazione v1

## Endpoint

SerpApi Google Flights:

- `engine=google_flights`;
- `type=1` round-trip;
- `departure_id`;
- `arrival_id`;
- `outbound_date`;
- `return_date`;
- `travel_class=1` Economy;
- `currency=EUR`;
- `hl=it`;
- `gl=it`.

Prima della query voli viene eseguita la SerpApi Account API e letto `total_searches_left`, con fallback a `plan_searches_left`.

Se quota <=5: blocco.

Se Account API fallisce o non restituisce saldo: blocco.

## Una sola query voli

La prima ricerca esegue **una sola query Google Flights**. Non usa ancora `departure_token`.

Conseguenza:

- `price` mostrato = prezzo round-trip restituito dal risultato iniziale;
- compagnia, orari e numero scali mostrati = itinerario di andata;
- dettagli specifici del ritorno non vengono ancora recuperati perché richiederebbero una seconda query con `departure_token`.

## Risultato minimo mostrato

- prezzo round-trip;
- valuta;
- compagnia/e andata;
- orario partenza andata;
- orario arrivo andata;
- numero scali andata;
- quota verificata prima della ricerca.

## Error handling

Gestire senza crash:

- key mancante;
- key non valida/non autorizzata;
- quota insufficiente;
- limite HTTP/quota;
- errore rete;
- parametri non accettati;
- risposta JSON non prevista;
- nessun volo;
- risposta vuota.

Nessun logging interceptor viene usato: evitare di stampare URL contenenti la API key.

---

# 16. Decisioni e perché

## Kotlin + Compose, non Flutter

Solo Android, meno stack da imparare, miglior allineamento con documentazione e API native Android.

## SerpApi come primario

Free tier senza carta, Google Flights/Explore e dati compatibili con i requisiti. Amadeus Self-Service non è una base valida per il nuovo progetto.

## SearchAPI Calendar come acceleratore, non backup completo

Riduce le query per date/range flessibili senza mantenere due parser completi. I crediti SearchAPI potrebbero essere one-time.

## Discovery → Verifica → Dettaglio

Rende sostenibile la quota ed evita brute force.

## GitHub-only

Decisione esplicita: niente Android Studio locale. Accettati feedback più lenti e assenza di emulator/debugger/Compose Preview locali.

## GitHub Release, non Actions artifact

APK persistente e facilmente scaricabile dal telefono; niente dipendenza dalla retention degli artifact.

## Singola Release `dev-latest`

Evita decine di Release inutili durante lo sviluppo.

## Database IATA locale

Necessario per paese degli scali ed esclusione di un paese senza consumare API.

## Keystore stabile gestito dal proprietario

Serve per aggiornamenti Android senza disinstallare. Doppio backup personale esterno a GitHub; password conservata separatamente.

## DataStore per API key nella v1

Storage locale semplice, ufficiale Android, asincrono, senza backend e senza costo. Limite accettato: non cifra da solo a riposo.

## Retrofit + OkHttp + Kotlin Serialization

Retrofit separa l'interfaccia HTTP dal resto dell'app; OkHttp gestisce HTTPS; Kotlin Serialization mantiene DTO tipizzati e permette di ignorare campi SerpApi non ancora usati.

---

# 17. Roadmap

## Fase 0 — Pipeline infrastrutturale

**COMPLETATA AL 100%.**

## v1 — Fondamenta

### v1.1 Firma stabile

**COMPLETATA E VALIDATA END-TO-END.**

### v1.2 Impostazioni API key

**COMPLETATA E TESTATA SUL TELEFONO.**

Persistenza confermata anche dopo chiusura completa dell'app.

### v1.3 Prima ricerca reale SerpApi a date fisse

**IMPLEMENTATA E VALIDATA IN CI; IN ATTESA DEL PRIMO TEST REALE SUL TELEFONO.**

Implementato:

- INTERNET permission;
- Retrofit/OkHttp/Kotlin Serialization;
- route Ricerca;
- campi origine/destinazione;
- date picker andata/ritorno;
- Account API quota live;
- blocco con <=5 query residue;
- una query `google_flights` round-trip;
- loading;
- parsing primo risultato;
- error handling leggibile;
- nessuna cache Room;
- nessun filtro avanzato;
- nessuna data flessibile;
- nessun multi-aeroporto.

CI:

- GitHub Actions run #13: **SUCCESS**;
- versione APK: `0.1.0-dev.13`;
- `assembleRelease`: SUCCESS;
- `zipalign`: SUCCESS;
- `apksigner verify`: SUCCESS;
- fingerprint firma invariato: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`;
- Release `VolaFlex - Development latest` aggiornata.

### Step v1 successivi

- validazione reale prima ricerca;
- lista risultati più completa;
- modello `FlightItinerary` pieno;
- diagnostica;
- cache Room base.

## v2 — Date flessibili

- weekend;
- N notti;
- ±X;
- range;
- SearchAPI Calendar;
- fallback euristico;
- quota/warning costi.

## v3 — Geografia avanzata

- 3 origini;
- 3 destinazioni;
- Ovunque;
- paese;
- Explore;
- database IATA;
- deduplicazione.

## v4 — Scali avanzati

- durata max scalo;
- esclusione paese;
- stessa compagnia operativa;
- dettaglio scali;
- scali >8h;
- Maps;
- robustezza/fallback.

Stima complessiva originaria GitHub-only: 21–30 settimane part-time.

---

# 18. Rischi noti e accettati

- SerpApi/SearchAPI.io non sono fonti ufficiali Google Flights e possono subire regressioni/cambi JSON/downtime.
- Ricerca non sempre matematicamente esaustiva: campionamento accettato su range grandi.
- Quota SerpApi limitata e condivisa con altro progetto.
- Prezzi Calendar indicativi: verifica finale con SerpApi.
- Crediti SearchAPI.io potenzialmente one-time.
- Workflow senza Android Studio: debug più lento; mitigazione con CI, Codespaces, diagnostica e telefono reale.
- Perdita del keystore stabile: richiederebbe disinstallazione/reinstallazione con nuova firma; backup doppio già eseguito.
- In Codespaces `gh secret set` può fallire con `403 Resource not accessible by integration`; in tal caso gestire Secrets via web GitHub.
- DataStore Preferences non cifra da solo le API key a riposo; rischio accettato per app personale.
- Prima query Google Flights non scarica il ritorno dettagliato: il ritorno richiede una seconda chiamata `departure_token`, rimandata per proteggere quota e complessità.

---

# 19. Stato di avanzamento reale

## Completato realmente

- Fase 0 completa;
- APK Release installabile;
- firma persistente validata con aggiornamento reale senza disinstallazione;
- Home/Impostazioni/Navigation;
- salvataggio API key DataStore;
- persistenza API key verificata dopo chiusura completa;
- chiave SerpApi condivisa e policy quota registrate;
- infrastruttura rete compilata;
- prima schermata Ricerca implementata;
- Account API + Google Flights implementati;
- CI #13 verde;
- Release `0.1.0-dev.13` pronta.

## Non ancora confermato nel mondo reale

- Account API eseguita dal telefono con la key reale;
- prima query Google Flights eseguita dal telefono;
- visualizzazione di un volo reale;
- gestione reale di eventuale risposta SerpApi specifica dell'account/rotta.

---

# 20. Prossimo milestone

**Milestone immediata: prima ricerca reale FCO → MAD dal telefono.**

Criteri:

1. installare `0.1.0-dev.13` sopra la versione corrente senza disinstallare;
2. aprire `Ricerca voli`;
3. usare codici IATA `FCO` e `MAD`;
4. scegliere due date future valide;
5. premere `Cerca`;
6. Account API deve verificare quota >5;
7. deve partire una sola query Google Flights;
8. mostrare almeno prezzo round-trip, compagnia andata, orari andata e numero scali;
9. nessun crash/schermata bianca;
10. se qualcosa fallisce, riportare esattamente il messaggio mostrato in UI.

Dopo conferma del milestone: consolidare il parsing e passare alla lista risultati più completa/modello `FlightItinerary`.
