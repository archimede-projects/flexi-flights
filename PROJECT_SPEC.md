# VolaFlex — PROJECT_SPEC

**Repository:** `archimede-projects/flexi-flights` (privata)  
**Nome app:** VolaFlex  
**Application ID / namespace:** `com.archimedeprojects.volaflex`  
**Ruolo:** fonte di verità persistente del progetto.  
**Ultimo aggiornamento:** 2026-09-09

## Regola di manutenzione

Aggiornare questo file ogni volta che cambia un requisito, una decisione architetturale, una tecnologia, una policy API/quota o lo stato reale di avanzamento. Non contraddire decisioni già registrate senza richiesta esplicita del proprietario. `SESSION_HANDOFF.md` contiene solo lo stato operativo sintetico per riprendere il lavoro in una nuova chat.

---

# 1. Obiettivo e vincoli

VolaFlex è un'app Android personale per cercare voli economici con forte supporto a date flessibili, weekend, multi-aeroporto e filtri sugli scali.

Vincoli attuali:

- uso personale;
- APK sideload, niente Play Store per ora;
- zero costi;
- nessuna carta di credito/debito richiesta;
- niente backend/server a pagamento;
- sviluppo GitHub-only;
- repository GitHub privata;
- nessun Android Studio locale;
- GitHub Actions per build/CI;
- GitHub Releases per distribuire l'APK;
- test UI iniziale su telefono Android reale;
- proprietario del progetto principiante assoluto.

---

# 2. Requisiti funzionali

## 2.1 Weekend flessibili

Ricerca configurabile con pattern:

- partenza venerdì sera oppure sabato mattina;
- ritorno domenica sera oppure lunedì;
- ricerca su più settimane/mesi.

L'app può usare discovery + verifica invece di interrogare ogni combinazione.

## 2.2 Numero di notti

Ricerca per origine, destinazione, numero esatto di notti e periodo/date flessibili, trovando i periodi più economici compatibili.

## 2.3 Data ±X giorni

L'utente sceglie data target e X. SearchAPI.io Calendar viene usato quando evita molte query SerpApi. Se non disponibile: SerpApi esaustivo solo su range piccoli oppure campionamento euristico su range grandi.

## 2.4 Range ampio di date

Esempio: 1–30 giugno. Evitare una query SerpApi per ogni giorno quando esistono strategie più efficienti.

## 2.5 Multi-origine

Supportare fino a 3 città/aeroporti alternativi.

## 2.6 Destinazione

Supportare:

1. singola città/aeroporto;
2. fino a 3 destinazioni alternative;
3. “Ovunque”;
4. intero paese, es. Marocco.

## 2.7 Esclusione paese per gli scali

Esempio: nessuno scalo nel Regno Unito. SerpApi non offre filtro nativo per paese: implementare in Kotlin con lookup locale `IATA -> ISO country`.

## 2.8 Stessa compagnia su tutte le tratte

Opzione: tutte le tratte devono essere operate dalla stessa compagnia. `include_airlines` è solo un pre-filtro; controllo definitivo segmento-per-segmento lato Kotlin, preferendo il vettore operativo quando disponibile.

## 2.9 Durata massima dello scalo

Filtro nativo SerpApi: `layover_duration=MIN,MAX`, in minuti. Eseguire anche controllo client-side su `layovers[].duration`.

## 2.10 Dettaglio scalo

Mostrare almeno aeroporto, città quando disponibile, paese, durata e overnight quando disponibile.

## 2.11 Maps per scali >8h

Per scali oltre 8 ore mostrare pulsante verso Google Maps/browser via Android Intent. Niente Google Maps SDK nella prima versione.

---

# 3. Fonti dati

## 3.1 SerpApi — provider primario

Quota free verificata:

- 250 ricerche/mese;
- 50/ora;
- nessuna carta richiesta.

Usi:

- Google Flights;
- Google Travel Explore;
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

## 3.2 SearchAPI.io Calendar — acceleratore mirato

Non è un secondo provider voli completo. Ruolo: `date discovery accelerator`.

Endpoint: `google_flights_calendar`.

Usare soprattutto per:

- N notti + ±X giorni;
- range ampi con destinazione fissa;
- casi in cui SerpApi richiederebbe circa >=10 chiamate.

Parametri principali:

- `outbound_date_start`;
- `outbound_date_end`;
- `return_date_start`;
- `return_date_end`.

Limite noto: circa 200 combinazioni andata/ritorno per richiesta; spezzare range grandi in blocchi.

I 100 crediti gratuiti non vengono considerati ricorrenti mensilmente perché il rinnovo non è confermato. L'app deve continuare a funzionare quando terminano.

## 3.3 `price_insights`

Non usare per ±X giorni: descrive statisticamente la stessa rotta/date, non un calendario giorno-per-giorno.

---

# 4. Pattern architetturale

Pattern obbligatorio:

`DISCOVERY -> VERIFICA -> DETTAGLIO`

## Discovery

Trovare candidati con poche query usando, a seconda del caso:

- SerpApi Travel Explore;
- SearchAPI.io Calendar;
- Google Flights Deals se utile;
- query multi-airport;
- cache locale;
- campionamento euristico.

## Verifica

Verificare solo 1–3 candidati migliori con SerpApi Google Flights, applicando date precise e filtri disponibili.

## Dettaglio

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
- Esclusione paese di connessione: non nativo; client-side con IATA -> paese.
- Inclusione compagnie: nativo `include_airlines`.
- Esclusione compagnie: nativo `exclude_airlines`.
- Stessa compagnia operativa su tutti i segmenti: controllo Kotlin obbligatorio.

---

# 6. Modello dati minimo

## DatePriceCandidate

- `outboundDate`;
- `returnDate`;
- `indicativePrice`;
- `currency`;
- `source`.

Usato nella fase Discovery.

## FlightItinerary

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

Prevedere fonte locale `IATA -> aeroporto -> città -> ISO country` per:

- mostrare paese dello scalo;
- filtrare paesi esclusi;
- selezione aeroporti;
- ricerca geografica;
- popup scalo.

Nessuna query API per questo lookup.

---

# 8. Policy anti-esaurimento quota

## SerpApi

- >50 residue: funzionamento normale.
- <=50: cache/modalità risparmio più aggressive.
- <=20: conferma prima di una ricerca stimata >5 query.
- <=5: privilegiare cache, Explore, query singole ed euristiche.
- Mai 30–40 chiamate automatiche con un singolo tap.

Mostrare le query residue via SerpApi Account API quando disponibile.

## SearchAPI.io

Usare Calendar soprattutto quando sostituisce circa >=10 query SerpApi. Quando i crediti finiscono:

- range piccolo -> SerpApi esaustivo;
- range grande -> campionamento 5–7 date;
- ricerca completa costosa solo su scelta esplicita;
- non disabilitare date flessibili.

---

# 9. Cache

Tecnologia prevista: Room.

Chiave concettuale: origine/i + destinazione/i + date + passeggeri + filtri + provider. Salvare risultato e timestamp e riusare ricerche identiche recenti.

---

# 10. Stack tecnologico

App:

- Kotlin nativo;
- Jetpack Compose;
- Gradle;
- Android SDK;
- Retrofit + OkHttp;
- Kotlin Serialization;
- Kotlin Coroutines;
- Room;
- DataStore;
- Navigation Compose;
- Android Intent per Maps.

Sviluppo:

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
- compileSdk: 36;
- targetSdk: 36;
- minSdk: 26;
- JDK: 17.

AGP 9.x usa Kotlin integrato: non applicare `org.jetbrains.kotlin.android`. Applicare `org.jetbrains.kotlin.plugin.compose` al modulo Compose.

Android SDK in CI: `android-actions/setup-android@v4`, Command-Line Tools build `14742923`, `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`.

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

`codice GitHub -> push main -> GitHub Actions -> Gradle -> APK -> firma persistente -> GitHub Release -> telefono Android`

Gli APK NON vengono usati come Actions artifacts temporanei.

Durante sviluppo:

- tag: `dev-latest`;
- Release title: `VolaFlex - Development latest`;
- asset: `VolaFlex-dev.apk`.

Ogni push di codice/config su `main` sposta `dev-latest` e sostituisce l'APK. Release permanenti solo per tag `v*` come `v0.1.0`.

`PROJECT_SPEC.md`, `SESSION_HANDOFF.md` e `.github/workflows/android-build.yml` sono esclusi dal trigger `push` tramite `paths-ignore`. Gli aggiornamenti documentali e le modifiche isolate al workflow non consumano build/minuti CI. Se il connettore ChatGPT non espone `workflow_dispatch`, è accettabile attivare la stessa pipeline con un push tecnico innocuo su un file non ignorato.

Non attivare Immutable Releases mentre usiamo `dev-latest` sovrascrivibile.

Workflow senza Gradle Wrapper: `gradle/actions/setup-gradle` installa Gradle 9.5.0. Il Wrapper potrà essere aggiunto più avanti.

## Firma persistente

Keystore JKS stabile generato dal proprietario e mai condiviso in chat.

GitHub Actions Secrets obbligatori:

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

Verifica run #4:

- APK Signature Scheme v2: **true**;
- APK Signature Scheme v3: **true**;
- numero signer: **1**.

Il primo APK firmato con la nuova chiave NON può aggiornare `0.1.0-dev.3`, che usa la vecchia firma debug. È necessaria una sola disinstallazione/reinstallazione iniziale. Da quel momento tutti gli aggiornamenti dovranno usare sempre lo stesso keystore.

---

# 14. Credenziali API

Non committare chiavi API. Nella v1 prevedere schermata Impostazioni dove l'utente inserisce SerpApi/SearchAPI.io key sul telefono; salvataggio locale tramite DataStore.

---

# 15. Decisioni e perché

## Kotlin + Compose, non Flutter

Solo Android, meno stack da imparare, miglior allineamento con documentazione e API native Android.

## SerpApi come primario

Free tier senza carta, 250 query/mese, Google Flights/Explore e dati compatibili con i requisiti. Amadeus Self-Service è stato dismesso nel 2026.

## SearchAPI Calendar come acceleratore, non backup completo

Riduce drasticamente le query per date/range flessibili senza mantenere due parser completi. I crediti SearchAPI potrebbero essere one-time.

## Discovery -> Verifica -> Dettaglio

Rende sostenibile la quota ed evita brute force.

## GitHub-only

Decisione esplicita: niente Android Studio locale. Accettati feedback più lenti e assenza di emulator/debugger/Compose Preview locali.

## GitHub Release, non Actions artifact

APK persistente e facilmente scaricabile dal telefono; niente dipendenza dalla retention degli artifact.

## Singola Release `dev-latest`

Evita decine di release inutili durante lo sviluppo.

## `paths-ignore` per memoria e workflow

Evita build inutili per aggiornamenti documentali o modifiche isolate alla CI.

## Database IATA locale

Necessario per paese degli scali ed esclusione di un paese senza consumare API.

## Keystore stabile gestito dal proprietario

Serve perché Android accetta un APK come aggiornamento solo se firmato in modo compatibile con la versione installata. Il keystore resta sotto il controllo del proprietario, viene conservato come Secret GitHub in forma base64 per la CI e deve avere almeno un backup personale esterno a GitHub.

---

# 16. Roadmap

## Fase 0 — Pipeline infrastrutturale — **COMPLETATA AL 100%**

Criteri:

1. progetto Compose minimo in repo — **COMPLETATO**;
2. workflow `Android Build` su `main` — **COMPLETATO**;
3. build GitHub Actions verde — **COMPLETATO**;
4. Release `dev-latest` creata/aggiornata — **COMPLETATO**;
5. asset `VolaFlex-dev.apk` disponibile — **COMPLETATO**;
6. APK installato sul telefono — **COMPLETATO**;
7. schermata `VolaFlex - Build OK` visibile — **COMPLETATO**;
8. versione `0.1.0-dev.3` visualizzata correttamente — **COMPLETATO**.

Conferma reale sul telefono ricevuta il 2026-09-09.

## v1 — Fondamenta (7–10 settimane stimate GitHub-only)

Firma stabile persistente: **COMPLETATA TECNICAMENTE IN CI**.

Prossimo controllo operativo: reinstallazione una tantum sul telefono e successivo test di aggiornamento sopra la versione stabile senza disinstallazione.

Poi:

- impostazioni API key;
- SerpApi;
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

# 17. Rischi noti e accettati

- SerpApi/SearchAPI.io non sono fonti ufficiali Google Flights e possono subire regressioni/cambi JSON/downtime.
- Ricerca non sempre matematicamente esaustiva: campionamento accettato su range grandi.
- Quota SerpApi limitata: mitigata con cache, Explore, Calendar, multi-airport e discovery.
- Prezzi Calendar indicativi: verifica finale con SerpApi.
- Crediti SearchAPI.io potenzialmente one-time: non deve essere single point of failure.
- Workflow senza Android Studio: debug più lento; mitigazione con CI, Codespaces, diagnostica e telefono reale.
- Perdita del keystore stabile: gli APK già installati non potranno più essere aggiornati con una nuova chiave; sarà necessaria disinstallazione/reinstallazione. Il keystore ha doppio backup personale esterno a GitHub; la password è conservata separatamente su carta.
- Migrazione dalla vecchia firma debug `0.1.0-dev.3` alla nuova firma stabile: richiede una sola disinstallazione/reinstallazione iniziale.
- In Codespaces `gh secret set` può fallire con `403 Resource not accessible by integration` quando il token del Codespace non ha permessi sufficienti sui repository secrets. In tal caso gestire i Secrets tramite interfaccia web GitHub. Non assumere che `gh` nel Codespace abbia permessi amministrativi solo perché il repository è accessibile.

---

# 18. Stato di avanzamento reale

## Fase 0 — completata e verificata end-to-end

Implementato e verificato:

- repository privata `archimede-projects/flexi-flights`;
- progetto Kotlin/Compose minimo;
- Android SDK 36 in CI con `android-actions/setup-android@v4`;
- build Gradle reale verde;
- Release `VolaFlex - Development latest` / tag `dev-latest`;
- asset `VolaFlex-dev.apk`;
- APK scaricato e installato realmente sul telefono;
- schermata `VolaFlex - Build OK` verificata sul telefono;
- versione `0.1.0-dev.3` verificata sul telefono;
- `PROJECT_SPEC.md` e `SESSION_HANDOFF.md` esclusi dalle build docs-only.

**Fase 0: CHIUSA AL 100%.**

## v1 — firma persistente — completata tecnicamente

Completato e verificato:

- keystore JKS stabile generato dal proprietario;
- password conservata separatamente su carta;
- doppio backup personale del file `.jks`;
- 4 GitHub Actions Secrets presenti via interfaccia web;
- workflow aggiornato per `assembleRelease` + `zipalign` + `apksigner`;
- GitHub Actions run #4 completato con successo;
- `Validate signing secrets`: **success**;
- `Restore signing keystore`: **success**;
- `Build unsigned release APK`: **success**;
- `Align and sign APK with persistent key`: **success**;
- `apksigner verify`: **success**;
- firma v2 e v3 valide;
- fingerprint SHA-256 certificato stabile: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`;
- Release `VolaFlex - Development latest` aggiornata;
- asset `VolaFlex-dev.apk` versione `0.1.0-dev.4` pubblicato;
- SHA-256 del file APK build #4: `172e74546de32487999abc48ad04c42b9599b93d35ea8a13574c85b116a399f1`;
- keystore temporaneo cancellato dal runner a fine job.

Nota operativa: il connettore GitHub di questa chat non espone un'azione per avviare `workflow_dispatch`; la build #4 è stata attivata con un push tecnico innocuo su `gradle.properties`, ottenendo la stessa pipeline e gli stessi Secrets.

---

# 19. Prossimo milestone

**Milestone immediata: migrazione sul telefono + prova reale di aggiornamento con firma stabile.**

Criteri di completamento:

1. scaricare `VolaFlex-dev.apk` versione `0.1.0-dev.4` dalla Release;
2. disinstallare una sola volta la vecchia `0.1.0-dev.3` firmata con la debug key precedente;
3. installare `0.1.0-dev.4` firmata con il certificato stabile;
4. verificare che l'app si avvii e mostri correttamente la versione;
5. produrre una build successiva firmata con lo stesso keystore;
6. verificare che il fingerprint SHA-256 resti `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`;
7. installare la build successiva SOPRA `0.1.0-dev.4` senza disinstallazione;
8. confermare che Android accetta l'aggiornamento e che i dati/app identity restano coerenti.

Solo dopo questo milestone si passa allo step v1 successivo: schermata Impostazioni API key.
