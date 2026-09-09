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

Vincoli:

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
- test UI iniziale su telefono Android reale;
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

Ricerca per:

- origine;
- destinazione;
- numero esatto di notti;
- periodo/date flessibili.

Obiettivo: trovare le date più economiche compatibili con il numero di notti.

## 2.3 Data ±X giorni

L'utente sceglie una data target e un valore X. SearchAPI.io Calendar viene usato quando evita molte query SerpApi. Se non disponibile: SerpApi esaustivo solo su range piccoli oppure campionamento euristico su range grandi.

## 2.4 Range ampio di date

Esempio: `1 giugno → 30 giugno`.

Evitare una query SerpApi per ogni giorno quando esistono strategie più efficienti.

## 2.5 Multi-origine

Supportare fino a 3 città/aeroporti alternativi.

## 2.6 Destinazione

Supportare:

1. singola città/aeroporto;
2. fino a 3 destinazioni alternative;
3. “Ovunque”;
4. intero paese, es. Marocco.

## 2.7 Esclusione paese per gli scali

Esempio: nessuno scalo nel Regno Unito.

SerpApi non offre filtro nativo per paese: implementare in Kotlin con lookup locale `IATA → ISO country`.

## 2.8 Stessa compagnia su tutte le tratte

Opzione: tutte le tratte devono essere operate dalla stessa compagnia.

`include_airlines` è solo un pre-filtro; controllo definitivo segmento-per-segmento lato Kotlin, preferendo il vettore operativo quando disponibile.

## 2.9 Durata massima dello scalo

Filtro nativo SerpApi:

`layover_duration=MIN,MAX`

Unità: minuti.

Eseguire anche controllo client-side su `layovers[].duration`.

## 2.10 Dettaglio scalo

Mostrare almeno:

- aeroporto;
- città quando disponibile;
- paese;
- durata;
- eventuale overnight.

## 2.11 Maps per scali >8h

Per scali oltre 8 ore mostrare pulsante verso Google Maps/browser tramite Android Intent.

Niente Google Maps SDK nella prima versione.

---

# 3. Fonti dati

## 3.1 SerpApi — provider primario

Quota free verificata:

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

Le query identiche possono essere servite dalla cache SerpApi senza consumo di quota quando il provider le considera cached. La cache locale Room resta comunque prevista per controllare direttamente il riuso lato app.

### Account API

Usare la SerpApi Account API per mostrare il saldo reale della quota quando verrà implementata la parte rete. La Account API non consuma la quota normale.

### Nota: chiave SerpApi condivisa con altro progetto

La API key SerpApi usata da VolaFlex **non è dedicata all'app**: appartiene allo stesso account gratuito già usato da un altro progetto personale.

Conseguenze architetturali:

- la quota mensile SerpApi è condivisa fra VolaFlex e l'altro progetto;
- il saldo può diminuire anche mentre VolaFlex non viene usata;
- un contatore locale interno a VolaFlex non è affidabile come fonte di verità;
- il valore autorevole è sempre quello restituito live dalla SerpApi Account API;
- prima di ogni ricerca stimata costosa (>5 query SerpApi), VolaFlex deve interrogare nuovamente l'Account API anche se ha già mostrato un saldo pochi minuti prima;
- dopo una ricerca costosa, aggiornare nuovamente il saldo visualizzato quando pratico;
- se il controllo live della Account API fallisce, VolaFlex non deve avviare automaticamente una ricerca >5 query: deve passare a una strategia <=5 query oppure chiedere un override esplicito all'utente.

Questa decisione è intenzionale: non creare un secondo account SerpApi solo per ottenere una quota separata se ciò richiede fornire un numero di telefono.

## 3.2 SearchAPI.io Calendar — acceleratore mirato

Non è un secondo provider voli completo.

Ruolo: `date discovery accelerator`.

Endpoint:

`google_flights_calendar`

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

- massimo circa 200 combinazioni andata/ritorno per richiesta;
- spezzare range grandi in blocchi.

I 100 crediti gratuiti non vengono considerati ricorrenti mensilmente perché il rinnovo non è confermato. L'app deve continuare a funzionare quando terminano.

## 3.3 `price_insights`

Non usare per ±X giorni.

`price_insights` descrive statisticamente la stessa rotta/date interrogata e non è un calendario giorno-per-giorno di date alternative.

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

Dati JSON utili disponibili per filtro client-side:

- `layovers[]`: aeroporto/IATA e durata;
- `flights[]`: singoli segmenti con compagnia e dati del volo;
- quando presente, informazione del vettore operativo/codeshare da usare per il controllo “stessa compagnia operativa”.

---

# 6. Modello dati minimo

## 6.1 DatePriceCandidate

Campi:

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

---

# 7. Database aeroporti locale

Prevedere fonte locale:

`IATA → aeroporto → città → ISO country`

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

Le soglie restano valide anche con chiave condivisa, perché devono essere applicate al **saldo live dell'account**, non a un contatore locale stimato.

- >50 residue: funzionamento normale.
- <=50: cache/modalità risparmio più aggressive.
- <=20: conferma prima di una ricerca stimata >5 query.
- <=5: privilegiare cache, Explore, query singole ed euristiche.
- Mai 30–40 chiamate automatiche con un singolo tap.

Regola aggiuntiva per la chiave condivisa:

- prima di ogni ricerca stimata >5 query, refresh obbligatorio tramite SerpApi Account API;
- il saldo mostrato in UI può essere informativo, ma se non è appena stato aggiornato non va usato per autorizzare una ricerca costosa;
- se l'Account API non è raggiungibile, non avviare automaticamente >5 query: usare una modalità <=5 query oppure richiedere conferma/override esplicito.

## SearchAPI.io

Usare Calendar soprattutto quando sostituisce circa >=10 query SerpApi.

Quando i crediti finiscono:

- range piccolo → SerpApi esaustivo;
- range grande → campionamento 5–7 date;
- ricerca completa costosa solo su scelta esplicita;
- non disabilitare date flessibili.

SearchAPI.io è un acceleratore, non un requisito strutturale.

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

---

# 10. Stack tecnologico

## App

- Kotlin nativo;
- Jetpack Compose;
- Gradle;
- Android SDK;
- Retrofit + OkHttp;
- Kotlin Serialization;
- Kotlin Coroutines;
- Room;
- DataStore Preferences;
- Navigation Compose;
- Android Intent per Maps.

## Sviluppo

- repository privata GitHub;
- GitHub Codespaces opzionale;
- github.dev per piccoli edit;
- niente Android Studio locale;
- ChatGPT/GitHub connector per modifiche e analisi repo;
- GitHub Actions per CI/build;
- telefono Android reale per test UI.

---

# 11. Versioni iniziali fissate

- Android Gradle Plugin: 9.3.1;
- Kotlin / Compose compiler plugin: 2.4.20;
- Gradle: 9.5.0;
- Compose BOM: 2026.06.00;
- Activity Compose: 1.11.0;
- DataStore Preferences: 1.2.1;
- Navigation Compose: 2.9.8;
- compileSdk: 36;
- targetSdk: 36;
- minSdk: 26;
- JDK: 17.

AGP 9.x usa Kotlin integrato: non applicare `org.jetbrains.kotlin.android`. Applicare `org.jetbrains.kotlin.plugin.compose` al modulo Compose.

Navigation Compose resta sulla linea 2.9.x per mantenere questo progetto su compileSdk 36 durante la v1; non passare automaticamente alla linea che richiede/usa API 37 senza una decisione esplicita.

Android SDK in CI:

- `android-actions/setup-android@v4`;
- Command-Line Tools build `14742923`;
- `platform-tools`;
- `platforms;android-36`;
- `build-tools;36.0.0`.

Cambiare questi pin solo deliberatamente.

---

# 12. Identità del progetto

- Nome visibile: **VolaFlex**.
- Repository: **archimede-projects/flexi-flights**.
- `rootProject.name`: **VolaFlex**.
- namespace: **com.archimedeprojects.volaflex**.
- applicationId: **com.archimedeprojects.volaflex**.

`archimede-projects` contiene un trattino non valido in un package Java/Kotlin; è quindi normalizzato in `archimedeprojects`.

---

# 13. Workflow GitHub-only, distribuzione e firma

Flusso:

`codice GitHub → push main → GitHub Actions → Gradle → APK → firma persistente → GitHub Release → telefono Android`

Gli APK NON vengono usati come Actions artifacts temporanei.

Durante sviluppo:

- tag: `dev-latest`;
- Release title: `VolaFlex - Development latest`;
- asset: `VolaFlex-dev.apk`.

Ogni push di codice/config su `main` sposta `dev-latest` e sostituisce l'APK. Release permanenti solo per tag `v*` come `v0.1.0`.

`PROJECT_SPEC.md`, `SESSION_HANDOFF.md` e `.github/workflows/android-build.yml` sono esclusi dal trigger `push` tramite `paths-ignore`. Gli aggiornamenti documentali e le modifiche isolate al workflow non consumano build/minuti CI.

Se il connettore ChatGPT non espone `workflow_dispatch`, è accettabile attivare la stessa pipeline con un push tecnico innocuo su un file non ignorato.

Non attivare Immutable Releases mentre usiamo `dev-latest` sovrascrivibile.

Workflow senza Gradle Wrapper: `gradle/actions/setup-gradle` installa Gradle 9.5.0. Il Wrapper potrà essere aggiunto più avanti.

## 13.1 Firma persistente — VALIDATA END-TO-END

Keystore JKS stabile generato dal proprietario e mai condiviso in chat.

GitHub Actions Secrets:

- `KEYSTORE_BASE64`;
- `KEYSTORE_PASSWORD`;
- `KEY_ALIAS`;
- `KEY_PASSWORD`.

Workflow:

1. valida la presenza dei 4 Secrets;
2. ricostruisce temporaneamente il keystore in `${RUNNER_TEMP}`;
3. compila `:app:assembleRelease`;
4. esegue `zipalign`;
5. firma con `apksigner`;
6. verifica con `apksigner verify --verbose --print-certs`;
7. pubblica l'APK firmato nella Release;
8. cancella il keystore temporaneo con step `if: always()`.

Fingerprint SHA-256 del certificato stabile:

`a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`

Signer DN:

`CN=VolaFlex, OU=Personal, O=archimede-projects`

Chiave: RSA 4096 bit.

Validazioni:

- run #4: firma v2/v3 valida;
- run #5: fingerprint identico;
- `0.1.0-dev.4` installata dopo l'unica disinstallazione necessaria per cambiare dalla vecchia debug key;
- `0.1.0-dev.5` installata realmente SOPRA `0.1.0-dev.4` usando “Aggiorna”, senza disinstallare;
- l'app si è aperta correttamente e ha mostrato `Versione: 0.1.0-dev.5`.

**Firma persistente: CHIUSA E VALIDATA END-TO-END NEL MONDO REALE.**

---

# 14. Credenziali API e schermata Impostazioni

## 14.1 Regole

- Mai committare chiavi API.
- Mai inserire chiavi reali come costanti nel codice.
- Mai mostrare in UI il valore già salvato.
- SearchAPI.io è opzionale.
- Nessuna chiamata di rete viene eseguita nello step Impostazioni.

## 14.2 Storage locale

Implementato con DataStore Preferences.

File logico DataStore:

`api_keys`

Chiavi interne:

- `serp_api_key`;
- `search_api_key`.

Le API key vengono salvate nello storage privato dell'app.

La UI mostra solo stato:

- `SerpApi: configurata ✓`;
- `SearchAPI.io: configurata ✓`;
- oppure stato non configurato.

I campi usano visualizzazione password e vengono svuotati dopo il salvataggio. Se una chiave è già configurata, lasciare il relativo campo vuoto durante un salvataggio mantiene il valore precedente.

## 14.3 Sicurezza storage

DataStore Preferences NON è cifratura del dato a riposo. Lo storage è privato dell'app Android, ma un dispositivo rootato/compromesso può rappresentare un rischio.

Per ridurre esposizione accidentale è stato impostato:

`android:allowBackup="false"`

Questa scelta è accettata per l'APK personale. Se in futuro l'app diventasse pubblica o multiutente, rivalutare una strategia di protezione/segreti più forte e un backend.

---

# 15. Navigazione UI

Navigation Compose implementata con due route:

- `home`;
- `settings`.

Home:

- mantiene `VolaFlex - Build OK`;
- mostra versione;
- pulsante `Impostazioni`.

Impostazioni:

- stato SerpApi;
- campo SerpApi API key;
- stato SearchAPI.io;
- campo SearchAPI.io API key opzionale;
- pulsante `Salva`;
- conferma `Chiavi salvate ✓`;
- pulsante `Torna alla Home`.

---

# 16. Decisioni e perché

## Kotlin + Compose, non Flutter

Solo Android, meno stack da imparare, miglior allineamento con documentazione e API native Android.

## SerpApi come primario

Free tier senza carta, 250 query/mese, Google Flights/Explore e dati compatibili con i requisiti. Amadeus Self-Service è stato dismesso nel 2026.

## SearchAPI Calendar come acceleratore, non backup completo

Riduce drasticamente le query per date/range flessibili senza mantenere due parser completi. I crediti SearchAPI potrebbero essere one-time.

## Discovery → Verifica → Dettaglio

Rende sostenibile la quota ed evita brute force.

## GitHub-only

Decisione esplicita: niente Android Studio locale. Accettati feedback più lenti e assenza di emulator/debugger/Compose Preview locali.

## GitHub Release, non Actions artifact

APK persistente e facilmente scaricabile dal telefono; niente dipendenza dalla retention degli artifact.

## Singola Release `dev-latest`

Evita decine di Release inutili durante lo sviluppo.

## `paths-ignore` per memoria e workflow

Evita build inutili per aggiornamenti documentali o modifiche isolate alla CI.

## Database IATA locale

Necessario per paese degli scali ed esclusione di un paese senza consumare API.

## Keystore stabile gestito dal proprietario

Serve perché Android accetta un APK come aggiornamento solo se firmato in modo compatibile con la versione installata. Il keystore resta sotto il controllo del proprietario, viene conservato come Secret GitHub in forma base64 per la CI e ha doppio backup personale esterno a GitHub. La password è conservata separatamente su carta.

## Gestione Secrets via interfaccia web quando `gh` non ha permessi

Nel Codespace il comando `gh secret set` ha restituito `403 Resource not accessible by integration`. Il token disponibile al Codespace non aveva permessi sufficienti per amministrare i repository Secrets. In casi simili usare l'interfaccia web GitHub e non assumere che la CLI abbia automaticamente privilegi amministrativi.

## DataStore per API key nella v1

Scelta perché:

- storage locale semplice e ufficiale Android;
- asincrono;
- adatto a poche preferenze;
- nessun backend;
- nessun costo;
- chiavi fuori dal codice/repository.

Limite accettato: non fornisce cifratura a riposo da solo.

## Navigation Compose 2.9.8

Scelta per introdurre navigazione stabile senza spostare ora VolaFlex a compileSdk 37.

---

# 17. Roadmap

## Fase 0 — Pipeline infrastrutturale — COMPLETATA AL 100%

Criteri soddisfatti:

1. progetto Compose minimo in repo;
2. workflow `Android Build` su `main`;
3. build GitHub Actions verde;
4. Release `dev-latest`;
5. asset `VolaFlex-dev.apk`;
6. APK installato sul telefono;
7. schermata `VolaFlex - Build OK` visibile;
8. versione corretta visualizzata.

## v1 — Fondamenta (7–10 settimane stimate GitHub-only)

### Step v1.1 — Firma stabile

**COMPLETATO E VALIDATO END-TO-END.**

### Step v1.2 — Impostazioni API key

**IMPLEMENTATO E BUILDATO; IN ATTESA DI TEST REALE SUL TELEFONO.**

Implementato:

- DataStore Preferences;
- SerpApi key;
- SearchAPI.io key opzionale;
- indicatori configurazione;
- Home/Settings con Navigation Compose;
- nessuna chiamata di rete;
- backup Android disabilitato;
- CI run #12 verde;
- Release `VolaFlex-dev.apk` versione `0.1.0-dev.12` pubblicata;
- firma SHA-256 invariata: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`.

### Step v1 successivi

- prima chiamata SerpApi;
- origine/destinazione singole;
- date fisse;
- lista risultati;
- loading/errori;
- diagnostica;
- cache base.

## v2 — Date flessibili (5–7 settimane)

- weekend;
- N notti;
- ±X;
- range;
- SearchAPI Calendar;
- fallback euristico;
- quota/warning costi.

## v3 — Geografia avanzata (5–7 settimane)

- 3 origini;
- 3 destinazioni;
- Ovunque;
- paese;
- Explore;
- database IATA;
- deduplicazione.

## v4 — Scali avanzati (4–6 settimane)

- durata max scalo;
- esclusione paese;
- stessa compagnia operativa;
- dettaglio scali;
- scali >8h;
- Maps;
- robustezza/fallback.

Stima complessiva: 21–30 settimane part-time.

---

# 18. Rischi noti e accettati

- SerpApi/SearchAPI.io non sono fonti ufficiali Google Flights e possono subire regressioni/cambi JSON/downtime.
- Ricerca non sempre matematicamente esaustiva: campionamento accettato su range grandi.
- Quota SerpApi limitata e condivisa con un altro progetto personale: mitigata con Account API live, cache, Explore, Calendar, multi-airport e discovery.
- Il saldo SerpApi può diminuire per consumi esterni a VolaFlex; non considerare autorevole un contatore locale o un saldo non appena aggiornato.
- Prezzi Calendar indicativi: verifica finale con SerpApi.
- Crediti SearchAPI.io potenzialmente one-time: non deve essere single point of failure.
- Workflow senza Android Studio: debug più lento; mitigazione con CI, Codespaces, diagnostica e telefono reale.
- Perdita del keystore stabile: gli APK già installati non potranno più essere aggiornati con una nuova chiave; sarà necessaria disinstallazione/reinstallazione. Il keystore ha doppio backup personale esterno a GitHub; la password è conservata separatamente su carta.
- In Codespaces `gh secret set` può fallire con `403 Resource not accessible by integration`; in tal caso gestire i Secrets via interfaccia web GitHub.
- DataStore Preferences non cifra da solo le API key a riposo; rischio accettato per app personale in storage privato, con backup app disabilitato.

---

# 19. Stato di avanzamento reale

## Fase 0

**CHIUSA AL 100%.**

## Firma persistente

**CHIUSA E VALIDATA END-TO-END.**

Prova reale:

- `0.1.0-dev.4` installata con nuova firma;
- `0.1.0-dev.5` installata sopra con pulsante Android `Aggiorna`;
- nessuna disinstallazione;
- app avviata correttamente;
- versione `0.1.0-dev.5` confermata.

## Schermata Impostazioni API key

Implementazione repository completata:

- `ApiKeyStore.kt` con DataStore Preferences;
- `HomeScreen.kt`;
- `SettingsScreen.kt`;
- `VolaFlexApp.kt` con Navigation Compose;
- `MainActivity.kt` collegata al nuovo app graph;
- `app/build.gradle.kts` con DataStore e Navigation Compose;
- `AndroidManifest.xml` con `android:allowBackup="false"`;
- nessuna permission Internet aggiunta;
- nessuna chiamata HTTP aggiunta.

Validazione CI:

- GitHub Actions run #12: **SUCCESS**;
- `assembleRelease`: **SUCCESS**;
- `zipalign`: **SUCCESS**;
- `apksigner verify`: **SUCCESS**;
- fingerprint SHA-256 firma: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`;
- Release `VolaFlex - Development latest` aggiornata;
- versione APK: `0.1.0-dev.12`;
- SHA-256 file APK build #12: `d99ca309cab06fbf2fb02c7111c35d542cd2618f4cbae861012a7865501ff20e`.

**Stato:** codice + CI completati; manca il test reale sul telefono della UI e persistenza DataStore.

---

# 20. Prossimo milestone

**Milestone immediata: validare Impostazioni API key sul telefono.**

Criteri:

1. installare `0.1.0-dev.12` sopra `0.1.0-dev.5` senza disinstallare;
2. Home mostra versione `0.1.0-dev.12`;
3. pulsante `Impostazioni` apre la schermata Settings;
4. inserire una SerpApi key e salvare;
5. vedere `SerpApi: configurata ✓` senza vedere il valore salvato;
6. opzionalmente inserire SearchAPI.io key e vedere `SearchAPI.io: configurata ✓`;
7. tornare alla Home e riaprire Impostazioni: lo stato resta configurato;
8. chiudere completamente e riaprire l'app: lo stato resta configurato;
9. nessuna chiamata di rete deve essere effettuata in questo step.

Dopo conferma del milestone, step successivo v1: **prima chiamata reale SerpApi su rotta fissa**.