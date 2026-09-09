# VolaFlex — PROJECT_SPEC

**Repository:** `archimede-projects/flexi-flights` (privata)  
**Application ID / namespace:** `com.archimedeprojects.volaflex`  
**Ruolo di questo file:** fonte di verità persistente del progetto.  
**Ultimo aggiornamento:** 2026-09-09

## Regola di manutenzione

Aggiornare questo file ogni volta che cambia un requisito, una decisione architetturale, una tecnologia, una policy API/quota o lo stato reale di avanzamento. Non contraddire decisioni già registrate senza richiesta esplicita del proprietario.

`SESSION_HANDOFF.md` contiene invece solo lo stato operativo sintetico per riprendere il lavoro in una nuova chat.

---

# 1. Obiettivo e vincoli

VolaFlex è un'app Android personale per cercare voli economici con forte supporto a date flessibili, weekend, multi-aeroporto e filtri sugli scali.

Vincoli non negoziabili attuali:

- uso personale;
- APK sideload, niente Play Store per ora;
- zero costi;
- nessuna carta di credito/debito richiesta;
- niente backend/server a pagamento;
- sviluppo GitHub-only;
- repository privata GitHub;
- nessun Android Studio installato localmente;
- GitHub Actions per la build;
- GitHub Releases per distribuire gli APK;
- proprietario del progetto principiante assoluto.

---

# 2. Requisiti funzionali

## 2.1 Weekend flessibili

Ricerca configurabile con pattern:

- partenza venerdì sera oppure sabato mattina;
- ritorno domenica sera oppure lunedì;
- ricerca su più settimane/mesi.

L'app può usare discovery + verifica invece di interrogare brutalmente ogni combinazione.

## 2.2 Numero di notti

Ricerca per:

- origine;
- destinazione;
- numero esatto di notti;
- periodo/date flessibili.

L'app cerca i periodi più economici compatibili.

## 2.3 Data ±X giorni

L'utente imposta una data target e un valore X. SearchAPI.io Calendar viene usato come acceleratore quando evita molte query SerpApi. Se non disponibile, usare una ricerca SerpApi esaustiva solo per range piccoli oppure campionamento euristico per range grandi.

## 2.4 Range ampio di date

Esempio: 1–30 giugno. Evitare una query SerpApi per ogni singolo giorno quando esistono strategie di discovery più economiche.

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

SerpApi non offre un filtro nativo per paese di scalo. Implementazione Kotlin tramite lookup locale `IATA -> ISO country` e filtro dell'itinerario.

## 2.8 Stessa compagnia su tutte le tratte

Opzione: tutte le singole tratte devono essere operate dalla stessa compagnia.

`include_airlines` di SerpApi è utile come pre-filtro ma non viene considerato sufficiente per garantire il requisito. Controllo finale segmento-per-segmento lato Kotlin, preferendo il vettore operativo quando disponibile.

## 2.9 Durata massima dello scalo

Filtro nativo SerpApi:

`layover_duration=MIN,MAX`

Unità: minuti. Eseguire anche un controllo finale client-side su `layovers[].duration`.

## 2.10 Dettaglio scalo

Mostrare almeno:

- aeroporto;
- città quando disponibile;
- paese;
- durata;
- overnight quando disponibile.

## 2.11 Maps per scali >8h

Per scali superiori a 8 ore mostrare un pulsante che apre Google Maps/browser tramite Android Intent. Niente Google Maps SDK nella prima versione.

---

# 3. Fonti dati

## 3.1 SerpApi — provider primario

Quota free verificata:

- 250 ricerche/mese;
- 50/ora;
- nessuna carta richiesta.

Usi principali:

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

Usarlo soprattutto per:

- N notti + ±X giorni;
- range ampi con destinazione fissa;
- casi in cui SerpApi richiederebbe circa >=10 chiamate.

Parametri importanti:

- `outbound_date_start`;
- `outbound_date_end`;
- `return_date_start`;
- `return_date_end`.

Limite noto: circa 200 combinazioni andata/ritorno per richiesta; dividere range grandi in blocchi.

I 100 crediti free non vengono considerati ricorrenti mensilmente perché il rinnovo non è confermato. L'app deve continuare a funzionare quando finiscono.

## 3.3 `price_insights`

Non usare per ±X giorni. È un'aggregazione statistica sulla stessa rotta/date, non un calendario giorno-per-giorno.

---

# 4. Pattern architetturale

Pattern obbligatorio:

`DISCOVERY -> VERIFICA -> DETTAGLIO`

## Discovery

Trovare candidati promettenti con poche query usando uno o più di:

- SerpApi Travel Explore;
- SearchAPI.io Calendar;
- Google Flights Deals se utile;
- query multi-airport;
- cache locale;
- campionamento euristico.

## Verifica

Verificare solo 1–3 candidati migliori con SerpApi Google Flights applicando date precise e filtri disponibili.

## Dettaglio

Solo quando serve recuperare/analizzare:

- ritorno associato / eventuale `departure_token`;
- segmenti;
- scali;
- codeshare / operating carrier;
- paese degli scali;
- stessa compagnia;
- popup scali;
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

Campi concettuali:

- `outboundDate`;
- `returnDate`;
- `indicativePrice`;
- `currency`;
- `source`.

Usato nella fase Discovery.

## FlightItinerary

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

La UI deve dipendere dai modelli interni, non dai DTO JSON specifici dei provider.

---

# 7. Database aeroporti locale

Prevedere una fonte locale:

`IATA -> aeroporto -> città -> ISO country`

Usi:

- mostrare paese dello scalo;
- filtrare paesi esclusi;
- selezione aeroporti;
- supportare ricerche geografiche;
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

Mostrare le query residue usando SerpApi Account API quando disponibile.

## SearchAPI.io

Usare Calendar soprattutto quando sostituisce circa >=10 query SerpApi.

Quando i crediti finiscono:

- range piccolo -> SerpApi esaustivo;
- range grande -> campionamento 5–7 date;
- eventuale ricerca completa costosa solo su scelta esplicita;
- non disabilitare la funzione date flessibili.

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

Salvare risultato + timestamp e riusare ricerche identiche recenti.

---

# 10. Stack tecnologico

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

- GitHub privata;
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

- Nome app visibile: **VolaFlex**.
- Repository: **archimede-projects/flexi-flights**.
- `rootProject.name`: **VolaFlex**.
- namespace: **com.archimedeprojects.volaflex**.
- applicationId: **com.archimedeprojects.volaflex**.

Il proprietario GitHub contiene un trattino (`archimede-projects`), non valido in un package Java/Kotlin; per questo è normalizzato in `archimedeprojects`.

---

# 13. Workflow GitHub-only e distribuzione

Flusso:

`codice GitHub -> push main -> GitHub Actions -> Gradle -> APK debug -> GitHub Release -> telefono Android`

Gli APK NON devono essere pubblicati come Actions artifacts temporanei.

Durante lo sviluppo usare una sola Release sovrascrivibile:

- tag: `dev-latest`;
- title: `VolaFlex - Development latest`;
- asset: `VolaFlex-dev.apk`.

Ogni push su `main` sposta `dev-latest` e sostituisce l'APK.

Release permanenti solo per tag versionati `v*` (es. `v0.1.0`).

Non attivare Immutable Releases mentre esiste il flusso `dev-latest` sovrascrivibile.

Workflow iniziale senza Gradle Wrapper: `gradle/actions/setup-gradle` installa Gradle 9.5.0. Il Wrapper potrà essere aggiunto più avanti.

Firma iniziale: debug standard. Dopo la validazione della pipeline aggiungere una firma debug stabile per consentire aggiornamenti senza disinstallazione.

---

# 14. Credenziali API

Non committare chiavi API.

Strategia prevista nella v1: schermata Impostazioni dove l'utente inserisce SerpApi/SearchAPI.io key sul telefono; salvataggio locale tramite DataStore.

---

# 15. Decisioni e perché

## Kotlin + Compose, non Flutter

Solo Android; meno stack da imparare; migliore allineamento con documentazione e API Android native.

## SerpApi come primario

Free tier senza carta, 250 query/mese, Google Flights/Explore e dati compatibili con i requisiti. Amadeus Self-Service è stato dismesso nel 2026.

## SearchAPI Calendar come acceleratore, non backup completo

Riduce drasticamente le query per range/date flessibili senza obbligare a mantenere due parser completi di itinerari. I suoi crediti potrebbero essere one-time.

## Discovery -> Verifica -> Dettaglio

Necessario per rendere sostenibile la quota SerpApi ed evitare brute force.

## GitHub-only

Decisione esplicita del proprietario: niente Android Studio locale. Accettati cicli di feedback più lenti e assenza di emulator/debugger/Compose Preview locali.

## GitHub Release, non Actions artifact

APK persistente e facilmente scaricabile dal telefono; niente dipendenza dalla retention degli artifact.

## Singola Release `dev-latest`

Evita accumulo di decine di release durante lo sviluppo.

## Database IATA locale

Necessario per paese degli scali ed esclusione di un paese senza consumare API.

---

# 16. Roadmap

## Fase 0 — Pipeline infrastrutturale

Criteri di completamento:

1. progetto Compose minimo presente in repo;
2. push su `main`;
3. workflow `Android Build` verde;
4. Release `dev-latest` creata/aggiornata;
5. asset `VolaFlex-dev.apk` disponibile;
6. APK installato sul telefono;
7. schermata `VolaFlex - Build OK` visibile;
8. versione build corretta visualizzata.

## v1 — Fondamenta (7–10 settimane stimate GitHub-only)

- firma debug stabile;
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

Stima complessiva attuale: 21–30 settimane part-time.

---

# 17. Rischi noti e accettati

- SerpApi/SearchAPI.io sono fonti non ufficiali basate su Google Flights e possono avere regressioni/cambi JSON/downtime.
- Ricerca non sempre matematicamente esaustiva: su range grandi è accettato il campionamento.
- Quota SerpApi limitata: mitigata con cache, Explore, Calendar, multi-airport e discovery.
- Prezzi Calendar indicativi: verifica finale con SerpApi.
- Crediti SearchAPI.io potenzialmente one-time: non deve essere un single point of failure.
- Workflow senza Android Studio: debug e feedback più lenti; mitigazione con CI, Codespaces, diagnostica e telefono reale.
- Firma debug iniziale non stabile: da correggere dopo la validazione Fase 0.

---

# 18. Stato di avanzamento reale

## Implementato nella repository

- repository privata `archimede-projects/flexi-flights` inizializzata;
- `.gitignore`;
- root `build.gradle.kts`;
- `settings.gradle.kts` con `rootProject.name = "VolaFlex"`;
- `gradle.properties`;
- modulo `app/build.gradle.kts`;
- namespace/applicationId `com.archimedeprojects.volaflex`;
- `AndroidManifest.xml` con label `VolaFlex`;
- `MainActivity.kt` Compose con testo `VolaFlex - Build OK` e versione app.

## Ancora da verificare/completare nella Fase 0

- workflow GitHub Actions da aggiungere/validare;
- prima build verde;
- Release `VolaFlex - Development latest`;
- asset `VolaFlex-dev.apk`;
- installazione APK sul telefono;
- conferma schermata e versione sul dispositivo.

Non segnare questi elementi come completati finché non sono realmente verificati.
