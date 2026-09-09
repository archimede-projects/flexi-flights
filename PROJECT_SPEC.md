# VolaFlex — PROJECT_SPEC

**Repository:** `archimede-projects/flexi-flights` (privata)  
**Nome app:** VolaFlex  
**Application ID / namespace:** `com.archimedeprojects.volaflex`  
**Ruolo:** fonte di verità persistente del progetto  
**Ultimo aggiornamento:** 2026-09-09

## Regola di manutenzione

Aggiornare questo file ogni volta che cambia un requisito, una decisione architetturale, una tecnologia, una policy API/quota o lo stato reale. Non contraddire decisioni qui registrate senza richiesta esplicita del proprietario. `SESSION_HANDOFF.md` contiene solo lo stato operativo sintetico.

---

# 1. Obiettivo e vincoli

VolaFlex è un'app Android personale per trovare voli economici con date flessibili, weekend, multi-aeroporto e filtri sugli scali.

Vincoli permanenti:

- uso personale e APK sideload; niente Play Store per ora;
- zero costi e nessuna carta richiesta;
- niente backend/server a pagamento;
- sviluppo GitHub-only, repository privata;
- nessun Android Studio locale;
- GitHub Actions per CI/build;
- GitHub Releases per distribuire l'APK;
- Codespaces opzionale;
- test UI su telefono Android reale;
- proprietario principiante assoluto.

---

# 2. Requisiti funzionali originali

1. **Weekend flessibili:** partenza venerdì sera o sabato mattina; ritorno domenica sera o lunedì; ricerca su più settimane/mesi.
2. **N notti:** origine + destinazione + numero esatto di notti, senza date fisse; trovare il periodo più economico.
3. **Data ±X giorni:** data target con flessibilità scelta dall'utente.
4. **Range ampio:** es. 1–30 giugno, evitando brute force quando possibile.
5. **Multi-origine:** fino a 3 città/aeroporti alternativi.
6. **Destinazione:** singola città/aeroporto, fino a 3 alternative, Ovunque, oppure intero paese.
7. **Esclusione paese di scalo:** es. nessuno scalo nel Regno Unito.
8. **Stessa compagnia:** tutte le tratte devono poter essere verificate come operate dalla stessa compagnia.
9. **Durata massima scalo.**
10. **Dettaglio scali:** aeroporto, città, paese, durata, overnight quando disponibile.
11. **Scali >8h:** pulsante Maps tramite Android Intent, senza Google Maps SDK.

---

# 3. Fonti dati e ruoli

## 3.1 SerpApi — provider primario

Quota free di riferimento: 250 ricerche/mese, 50/ora. SerpApi è la fonte definitiva dei voli completi.

Engine/usi previsti:

- Google Flights per ricerche precise;
- Google Travel Explore per discovery geografica/Anywhere/paesi;
- Google Flights Deals quando utile;
- multi-airport, date fisse, filtri e verifica finale.

### Account API

La SerpApi Account API è gratuita e non consuma la quota normale. Il saldo live è la fonte autorevole.

### Nota: chiave SerpApi condivisa con altro progetto

La chiave SerpApi di VolaFlex **non è dedicata**: lo stesso account viene usato da un altro progetto personale.

Conseguenze:

- la quota può diminuire fuori da VolaFlex;
- nessun contatore locale è fonte di verità;
- prima di una ricerca stimata costosa (>5 query) è obbligatorio un refresh live Account API;
- nella v1, prudenzialmente, anche la singola ricerca Google Flights controlla la quota live prima della chiamata;
- se Account API fallisce, nessuna ricerca costosa automatica;
- se saldo <=5, la ricerca reale viene bloccata.

## 3.2 SearchAPI.io Calendar — acceleratore mirato

Non è un secondo motore voli completo. Serve come `date discovery accelerator` per N notti/±X/range ampi con destinazione fissa quando evita molte query SerpApi.

Limite noto: massimo 200 combinazioni andata/ritorno per richiesta. Per N notti, suddividere i range e filtrare localmente la diagonale `return = departure + N`.

I 100 crediti gratuiti non sono considerati ricorrenti: VolaFlex deve funzionare anche senza SearchAPI.io.

## 3.3 `price_insights`

Non è un calendario di date alternative e non va usato per ±X giorni.

---

# 4. Pattern architetturale

Pattern obbligatorio:

`DISCOVERY → VERIFICA → DETTAGLIO`

- **Discovery:** Explore, SearchAPI Calendar, Deals, multi-airport, cache, campionamento.
- **Verifica:** 1–3 candidati migliori con SerpApi Google Flights.
- **Dettaglio:** solo on-demand, inclusi eventuale `departure_token`, ritorno associato, segmenti, scali, codeshare, paese scali e Maps.

Mai scaricare automaticamente il dettaglio completo di decine di risultati.

---

# 5. Filtri SerpApi verificati

- durata scalo: `layover_duration=MIN,MAX` in minuti + verifica Kotlin;
- esclusione aeroporto di connessione: `exclude_conns`;
- esclusione paese di connessione: non nativo, quindi lookup locale IATA→paese;
- inclusione compagnie: `include_airlines`;
- esclusione compagnie: `exclude_airlines`;
- stessa compagnia operativa su tutti i segmenti: controllo Kotlin obbligatorio.

Dati utili disponibili nel JSON: `layovers[]`, `flights[]`, vettore operativo/codeshare quando presente.

---

# 6. Modello dati

## 6.1 DatePriceCandidate

- outboundDate;
- returnDate;
- indicativePrice;
- currency;
- source.

## 6.2 FlightItinerary — modello finale previsto

- prezzo, valuta, durata totale;
- segmenti andata/ritorno;
- scali;
- provider;
- timestamp.

`FlightSegment`: aeroporti, date/orari, durata, airline, operating carrier, numero volo.

`Layover`: IATA, nome, città, paese, durata, overnight.

## 6.3 SimpleFlightResult — modello v1 attuale

Usato dal primo flusso reale: prezzo round-trip, valuta, compagnie/orari/scali dell'andata, quota registrata prima della ricerca, stato cache e timestamp cache.

---

# 7. Directory aeroporti locale — IMPLEMENTATA

File: `data/local/AirportDirectory.kt`.

Struttura riutilizzabile:

`IATA → nome aeroporto → città → ISO country`.

Contiene circa 180–200 aeroporti principali di Europa, Nord Africa e destinazioni internazionali comuni.

Usi attuali/futuri:

- protezione anti-typo prima della query;
- futuro lookup paese degli scali;
- futura selezione/autocomplete aeroporti;
- filtro paese di scalo senza chiamate API.

### Regola anti-typo

Se un codice non è nella lista locale:

- **non parte nessuna query** immediatamente;
- mostrare avviso `Codice 'XXX' non riconosciuto nella lista locale — controlla che sia corretto prima di continuare`;
- pulsanti `Correggi` e `Cerca comunque`;
- il codice sconosciuto non viene bloccato definitivamente perché la lista locale non pretende di essere esaustiva.

---

# 8. Policy anti-esaurimento quota

Soglie generali sul saldo **live**:

- >50: funzionamento normale;
- <=50: cache/modalità risparmio più aggressive;
- <=20: conferma prima di una ricerca stimata >5 query;
- <=5: privilegiare cache/Explore/query singole/euristiche e bloccare ricerche reali non necessarie;
- mai 30–40 chiamate automatiche con un tap.

Protezione aggiuntiva già implementata nella v1:

1. validazione IATA locale prima della rete;
2. cache locale Room prima dell'Account API;
3. su **cache hit fresco**, saltare sia Account API sia Google Flights;
4. solo su cache miss/refresh forzato: Account API → quota guard → Google Flights;
5. `Aggiorna comunque` è l'unico modo esplicito per forzare una query entro il TTL cache.

---

# 9. Cache Room minima — IMPLEMENTATA

Tecnologia: Room 2.8.4 + KSP 2.3.11.

Database: `volaflex.db`.

Tabella `flight_search_cache`.

Chiave v1:

`origine | destinazione | data andata | data ritorno`.

TTL: **4 ore**.

Comportamento:

- prima della rete viene cercata la stessa combinazione in Room;
- se fresca, mostrare il risultato cached senza SerpApi;
- UI: `Risultato da cache — aggiornato alle HH:MM`;
- pulsante `Aggiorna comunque` forza Account API + nuova Google Flights;
- risultati più vecchi del TTL vengono ignorati/rimossi progressivamente.

Questa cache v1 non comprende ancora filtri complessi, passeggeri o multi-aeroporto; verrà estesa quando quei parametri esisteranno.

---

# 10. Diagnostica — IMPLEMENTATA

Room contiene anche `diagnostic_events`.

Conservare solo gli ultimi 20 eventi rilevanti:

- timestamp;
- tipo richiesta/evento;
- esito;
- status HTTP quando applicabile;
- messaggio sintetico errore/esito.

Tipi attuali includono:

- `SERPAPI_ACCOUNT`;
- `GOOGLE_FLIGHTS`;
- `CACHE`;
- `QUOTA_GUARD`.

Le API key **non devono mai comparire** nella diagnostica.

Schermata `Diagnostica` raggiungibile da Impostazioni con pulsante `Copia diagnostica`, che copia testo negli appunti Android.

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

# 12. Identità, workflow e firma

- Nome: VolaFlex;
- repo: `archimede-projects/flexi-flights`;
- namespace/applicationId: `com.archimedeprojects.volaflex`.

Workflow:

`GitHub → Actions → Gradle → assembleRelease → zipalign → apksigner → GitHub Release → telefono`.

Release sviluppo:

- tag `dev-latest`;
- titolo `VolaFlex - Development latest`;
- asset `VolaFlex-dev.apk`;
- niente Actions artifacts temporanei.

`PROJECT_SPEC.md`, `SESSION_HANDOFF.md` e il workflow stesso sono ignorati dai trigger push per evitare build inutili.

Firma stabile validata end-to-end:

`a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`

Keystore con doppio backup personale; password conservata separatamente. Se il keystore viene perso, gli APK già installati non sono più aggiornabili con una nuova chiave.

---

# 13. Credenziali API e Impostazioni

API key salvate localmente con DataStore Preferences; mai nel codice/repository; `android:allowBackup=false`.

- SerpApi obbligatoria per ricerca reale;
- SearchAPI.io opzionale;
- UI mostra solo `configurata ✓`, mai il valore salvato.

**Test reale completato:** persistenza confermata dopo chiusura completa e riapertura dell'app.

Nota operativa: `gh secret set` nel Codespace ha già restituito `403 Resource not accessible by integration`; per Secrets amministrativi usare la UI GitHub quando il token Codespaces non ha permessi.

---

# 14. Navigazione UI corrente

Route:

- `home`;
- `search`;
- `settings`;
- `diagnostics`.

Home: build/versione, `Ricerca voli`, `Impostazioni`.

Ricerca: origine, destinazione, date picker andata/ritorno, validazione IATA, Cerca, loading, risultato/errori, cache/refresh.

Impostazioni: stato e modifica API key, accesso a Diagnostica.

Diagnostica: ultimi 20 eventi + `Copia diagnostica`.

---

# 15. Prima ricerca reale SerpApi — VALIDATA SUL TELEFONO

Flusso v1:

1. cache locale;
2. se cache miss: Account API;
3. quota >5;
4. una chiamata `engine=google_flights`, `type=1`, Economy, EUR, `hl=it`, `gl=it`;
5. parsing primo risultato.

Non viene ancora usato `departure_token`, quindi i dettagli specifici del ritorno non sono ancora scaricati: prezzo = round-trip; compagnia/orari/scali mostrati = andata.

### Test reale confermato dal proprietario

Build `0.1.0-dev.13`:

- rotta `FCO → MAD`;
- date 16–19 ottobre 2026;
- risultato: Ryanair;
- prezzo: 104 EUR round-trip;
- scali andata: 0;
- Account API: 131/250 query rimaste prima della ricerca;
- nessun crash;
- firma invariata.

Questo chiude la validazione della prima chiamata reale SerpApi.

---

# 16. Decisioni e perché

- **Kotlin + Compose, non Flutter:** solo Android, meno stack e allineamento con documentazione ufficiale.
- **SerpApi primario, non Amadeus:** Google Flights/Explore e free tier coerenti col progetto; Amadeus Self-Service non è una base attuale valida.
- **SearchAPI Calendar acceleratore:** riduce query per date flessibili senza mantenere due motori voli completi.
- **Discovery→Verifica→Dettaglio:** protegge quota ed evita brute force.
- **GitHub-only:** requisito esplicito; accettati cicli di debug più lenti.
- **GitHub Release, non artifact:** APK persistente e scaricabile dal telefono.
- **Singola `dev-latest`:** niente proliferazione di Release.
- **DataStore per API key:** semplice, locale, gratuito; rischio a riposo accettato per app personale.
- **Room per cache + diagnostica:** una sola persistenza locale strutturata per proteggere quota e facilitare debug.
- **Directory IATA statica:** zero rete/costo e riutilizzabile per il futuro filtro paese scali.
- **Warning, non blocco, su IATA sconosciuto:** lista ridotta non può essere autorità assoluta.

---

# 17. Roadmap

## Fase 0 — Pipeline infrastrutturale

**COMPLETATA AL 100%.**

## v1 — Fondamenta

- v1.1 firma stabile: **COMPLETATA E VALIDATA END-TO-END**;
- v1.2 Impostazioni API key: **COMPLETATA E TESTATA**;
- v1.3 prima ricerca SerpApi: **COMPLETATA E TESTATA REALMENTE**;
- v1.4 validazione IATA locale: **IMPLEMENTATA, CI VERDE, DA TESTARE SUL TELEFONO**;
- v1.5 cache Room 4h: **IMPLEMENTATA, CI VERDE, DA TESTARE SUL TELEFONO**;
- v1.6 diagnostica: **IMPLEMENTATA, CI VERDE, DA TESTARE SUL TELEFONO**.

Dopo questi test reali, la v1 Fondamenta è sostanzialmente chiusa e il passo successivo sarà consolidare lista risultati/modello `FlightItinerary` prima di entrare nelle date flessibili.

## v2 — Date flessibili

Weekend, N notti, ±X, range, SearchAPI Calendar, fallback euristico, quota/warning costi.

## v3 — Geografia avanzata

3 origini, 3 destinazioni, Ovunque, paese, Explore, directory aeroporti estesa, deduplicazione.

## v4 — Scali avanzati

Durata max scalo, esclusione paese, stessa compagnia operativa, dettaglio scali, >8h, Maps, robustezza/fallback.

---

# 18. Rischi noti e accettati

- SerpApi/SearchAPI.io non sono fonti ufficiali Google Flights; possibili cambi JSON/regressioni/downtime.
- Ricerca non sempre matematicamente esaustiva su range ampi.
- Quota SerpApi limitata e condivisa con altro progetto.
- Prezzi SearchAPI Calendar indicativi: verifica finale con SerpApi.
- Crediti SearchAPI.io potenzialmente one-time.
- Workflow senza Android Studio: debug più lento.
- perdita keystore: impossibilità di aggiornare APK già firmati senza reinstallazione;
- DataStore non cifra da solo le API key a riposo;
- directory IATA locale volutamente non esaustiva;
- cache v1 non comprende ancora futuri filtri/passeggeri: la chiave va estesa quando quei parametri vengono introdotti;
- prima query Google Flights non recupera ancora dettaglio ritorno via `departure_token`.

---

# 19. Stato di avanzamento reale

## Confermato nel mondo reale

- Fase 0 completa;
- firma persistente e aggiornamenti senza disinstallazione;
- Impostazioni API key + persistenza;
- Account API reale;
- Google Flights reale FCO→MAD;
- risultato reale Ryanair 104 EUR, 0 scali, quota 131/250 prima della query.

## Implementato e validato in CI, non ancora testato sul telefono

- directory IATA locale + warning/override;
- Room cache 4h;
- `Aggiorna comunque`;
- diagnostica ultimi 20 eventi;
- copia diagnostica negli appunti.

### Build corrente

GitHub Actions **run #15: SUCCESS**.

- versione: `0.1.0-dev.15`;
- `kspReleaseKotlin`: SUCCESS;
- `assembleRelease`: SUCCESS;
- `zipalign`: SUCCESS;
- `apksigner verify`: SUCCESS;
- fingerprint firma invariato: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`;
- APK SHA-256 build #15: `df9523a2475ec150c0d41c9dca71d9cc032698ff416fcfd408492a76f557f4aa`;
- Release `VolaFlex - Development latest` aggiornata.

Nota CI: run #14 è stato cancellato dalla regola `cancel-in-progress` quando è partito il run #15 sul commit funzionale; non è stato un errore di codice.

---

# 20. Prossimo milestone

**Validazione reale sul telefono di IATA guard + cache + diagnostica.**

Criteri:

1. installare `0.1.0-dev.15` sopra la versione corrente senza disinstallare;
2. `FC0 → MAD`: deve comparire warning locale prima di qualsiasi rete;
3. `Correggi` deve tornare all'input senza consumare query;
4. `FCO → MAD`, stessa rotta/date: prima ricerca deve popolare Room;
5. ripetizione identica entro 4h deve mostrare `Risultato da cache — aggiornato alle HH:MM` e consumare 0 query;
6. `Aggiorna comunque` deve essere disponibile ma usato solo volontariamente perché forza nuova query;
7. Impostazioni → Diagnostica deve mostrare gli eventi recenti;
8. `Copia diagnostica` deve copiare testo leggibile negli appunti senza API key.

Dopo conferma di questi punti: segnare v1 Fondamenta come chiusa e passare al consolidamento del modello/lista risultati completa.
