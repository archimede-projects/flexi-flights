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

- uso personale e APK sideload; niente Play Store per ora;
- zero costi e nessuna carta di credito/debito;
- niente backend/server a pagamento;
- sviluppo GitHub-only, repository privata;
- nessun Android Studio locale;
- GitHub Actions per CI/build e GitHub Releases per APK;
- Codespaces opzionale;
- test runtime/UI su telefono Android reale;
- proprietario principiante assoluto.

---

# 2. Requisiti funzionali originali

1. Weekend flessibili: venerdì sera **oppure** sabato mattina → domenica sera **oppure** lunedì.
2. N notti: origine + destinazione + numero esatto notti, senza date fisse.
3. Data target ±X giorni.
4. Range ampio di date.
5. Multi-origine fino a 3 alternative.
6. Destinazione singola, fino a 3 alternative, Ovunque o intero paese.
7. Esclusione paese di scalo.
8. Stessa compagnia operativa su tutti i segmenti quando richiesto.
9. Durata massima scalo.
10. Dettaglio scalo: aeroporto/città/paese/durata/overnight.
11. Scali >8h: Maps tramite Android Intent, senza Maps SDK.

---

# 3. Fonti dati e ruoli

## 3.1 SerpApi — provider primario

Quota free di riferimento: 250 ricerche/mese, 50/ora. Il saldo deve essere considerato **condiviso** e verificato live secondo la policy.

Engine:

- `google_flights`: ricerca precisa, Verifica e futuro Dettaglio;
- `google_travel_explore`: Discovery economica per Weekend, Ovunque, paesi/regioni e ricerche ampie;
- Google Flights Deals: Discovery aggiuntiva quando appropriata.

### Account API

La Account API SerpApi è gratuita e non consuma la quota normale. Il saldo live restituito è la fonte autorevole per VolaFlex.

### Nota: chiave SerpApi condivisa con altro progetto

La API key SerpApi **non è dedicata a VolaFlex**: lo stesso account viene usato da un altro progetto personale.

Conseguenze:

- il saldo può diminuire fuori dall'app;
- nessun contatore locale è fonte di verità;
- refresh live Account API obbligatorio prima di ricerche stimate costose (>5 query);
- nelle funzioni già implementate si usa un controllo ancora più prudente prima di ogni batch/fase live;
- se Account API fallisce, non avviare una ricerca costosa automaticamente;
- proteggere sempre una riserva minima di 5 query;
- non creare un secondo account solo per separare la quota se richiede fornire un numero di telefono.

## 3.2 SearchAPI.io Calendar — acceleratore mirato

Da introdurre nei prossimi sotto-step v2 per N notti/±X/range ampi. Non è un secondo motore voli completo.

Decisioni già fissate:

- massimo 200 combinazioni andata/ritorno per richiesta;
- per N notti filtrare localmente `return = departure + N`;
- chunk sincronizzati max 14 date (`14²=196`, `15²=225` supera 200);
- circa `ceil(D/14)` query Calendar per D partenze candidate;
- i 100 crediti gratuiti non sono considerati ricorrenti finché non confermato;
- VolaFlex deve funzionare anche senza SearchAPI.io.

## 3.3 Mapping preset da non confondere

Travel Explore: `travel_duration=1` Weekend, `2` 1 week, `3` 2 weeks.  
Google Flights Deals: `1` 1 week, `2` Weekend, `3` 2 weeks.

## 3.4 `price_insights`

Non usare come calendario ±X: descrive statisticamente la stessa rotta/date, non date alternative.

---

# 4. Pattern architetturale obbligatorio

`DISCOVERY → VERIFICA → DETTAGLIO`

- **Discovery:** Explore, SearchAPI Calendar, Deals, multi-airport, cache, campionamento.
- **Verifica:** solo 1–3 candidati migliori con Google Flights e filtri precisi.
- **Dettaglio:** solo on-demand, incluso `departure_token`, ritorno associato, segmenti, scali, operating carrier/codeshare, paesi scalo, stessa compagnia e Maps.

Mai scaricare automaticamente dettagli completi di decine di risultati.

---

# 5. Filtri SerpApi verificati

- `layover_duration=MIN,MAX` in minuti;
- `exclude_conns` per aeroporti di connessione;
- esclusione paese connessione: non nativa → lookup locale IATA→paese;
- `include_airlines` / `exclude_airlines`;
- stessa compagnia operativa: controllo Kotlin client-side;
- `outbound_times` e `return_times` per fasce orarie Google Flights round-trip.

## 5.1 Formato ufficiale `outbound_times` / `return_times` — REGOLA CRITICA

Formato SerpApi ufficiale: **2 oppure 4 numeri interi di ora `0..23`, separati da virgola**. Ogni numero rappresenta l'inizio di un'ora. **Non usare `HH:MM`.**

Esempi validi:

- `17,23` = partenza dalle 17:00 fino a mezzanotte;
- `5,11` = partenza dalle 05:00 fino a circa mezzogiorno;
- `0,23` = partenza senza restrizione pratica sull'intera giornata;
- 4 valori possono filtrare separatamente partenza e arrivo.

Pattern Weekend v2.2 correnti:

- Pattern A venerdì sera → domenica sera: `outbound_times=17,23`, `return_times=17,23`;
- Pattern B sabato mattina → lunedì: `outbound_times=5,11`, `return_times=0,23`.

### Audit 2026-09-10

Il proprietario ha fermato il test per verificare il formato ufficiale. Audit del codice: **la build `0.1.0-dev.17` conteneva già esattamente i valori corretti sopra**. L'errore era nella descrizione testuale dell'assistente che rappresentava le fasce in forma leggibile `17:00–23:59`; non nel valore inviato dall'app. Quindi nessuna query è stata sprecata per un formato `HH:MM`, perché quel formato non era nel runtime.

Hardening aggiunto: `SerpApiTimeFilterGuard`, interceptor OkHttp che, per `engine=google_flights`, valida localmente `outbound_times`/`return_times` e rifiuta prima di `chain.proceed` qualsiasi valore che non sia composto da 2 o 4 ore intere `0..23` con intervalli ordinati. Obiettivo: impedire a future regressioni di uscire dal telefono e consumare quota.

Il dettaglio esatto del volo di ritorno richiede una richiesta successiva con `departure_token`; i filtri sul ritorno possono invece essere applicati già alla query iniziale.

---

# 6. Modelli dati

## 6.1 DatePriceCandidate

`outboundDate`, `returnDate`, `indicativePrice`, `currency`, `source`.

## 6.2 WeekendCandidate

`outboundDate`, `returnDate`, `price`, `currency`, `destinationIata`, `destinationName`, `monthLabel`, `monthKey`, eventuale `verification`.

I dati base sono Discovery indicativa Travel Explore.

## 6.3 VerifiedWeekendResult — v2.2

Contiene:

- pattern scelto;
- date precise;
- prezzo round-trip verificato;
- valuta;
- compagnie dell'andata;
- orari esatti partenza/arrivo dell'andata;
- scali andata;
- fascia ritorno applicata;
- quota live prima della Verifica;
- timestamp verifica.

Il segmento/orario esatto del ritorno resta rinviato a `departure_token`.

## 6.4 FlightItinerary — modello finale previsto

Prezzo/valuta/durata, segmenti andata/ritorno, scali, provider, timestamp.  
`FlightSegment`: aeroporti, date/orari, durata, airline, operating carrier, numero volo.  
`Layover`: IATA, nome, città, paese, durata, overnight.

## 6.5 SimpleFlightResult — transitorio v1

Prezzo round-trip, valuta, compagnie/orari/scali dell'andata, quota pre-ricerca, cache/timestamp.

---

# 7. Directory aeroporti locale — IMPLEMENTATA E VALIDATA

`data/local/AirportDirectory.kt`, circa 180–200 aeroporti principali. Struttura riutilizzabile:

`IATA → nome aeroporto → città → ISO country`

Usi: anti-typo, futuro autocomplete, lookup paese scali e filtro paese di scalo. Codice sconosciuto → warning `Correggi` / `Cerca comunque`, senza query automatica. Test `FC0`: PASS, 0 query.

---

# 8. Policy anti-esaurimento quota

Soglie sul saldo **live**:

- >50: normale;
- ≤50: cache/modalità risparmio più aggressive;
- ≤20: conferma prima di ricerca stimata >5 query;
- ≤5: privilegiare cache/Explore/query singole/euristiche e bloccare ricerche non necessarie;
- mai 30–40 chiamate automatiche con un tap.

Protezione implementata:

1. validazione IATA prima della rete;
2. cache Room prima dell'Account API;
3. cache hit fresco salta Account API + provider;
4. cache miss/refresh → Account API → quota guard → provider;
5. `Aggiorna comunque` è override esplicito;
6. riserva minima 5 query dopo il batch previsto;
7. time-filter guard impedisce parametri orari malformati prima della rete.

### Weekend v2.2 cache miss completa

- 1 mese: 1 Explore + max 2 Google Flights = **max 3 query**;
- 2 mesi: max 4;
- 3 mesi: max 5;
- Account API gratuita e ripetuta prima della Verifica.

---

# 9. Cache Room — IMPLEMENTATA

Room 2.8.4 + KSP 2.3.11. DB `volaflex.db`, schema **2**.

## 9.1 Date fisse

Tabella `flight_search_cache`, chiave `origine|destinazione|andata|ritorno`, TTL 4h.

## 9.2 Weekend

Tabella `weekend_search_cache`, chiave `WEEKEND|origine|destinazione|mese/intervallo`, TTL 4h.

Da v2.2 il JSON contiene opzionalmente `VerifiedWeekendResult`; nessuna migrazione Room 2→3.

- cache già verificata entro 4h → 0 query;
- cache v2.1 fresca senza verification → saltare Explore e fare solo Verifica;
- verifica fallita/bloccata → conservare Discovery;
- `Aggiorna comunque` forza volontariamente il live refresh.

Migrazione Room 1→2 validata realmente sul telefono senza perdita dati.

---

# 10. Diagnostica — IMPLEMENTATA E VALIDATA

Ultimi 20 eventi con timestamp, tipo, esito, HTTP status e messaggio sintetico. Tipi attuali:

`SERPAPI_ACCOUNT`, `GOOGLE_FLIGHTS`, `TRAVEL_EXPLORE`, `WEEKEND_VERIFY`, `CACHE`, `QUOTA_GUARD`.

Nessuna API key nei log. Schermata da Impostazioni con `Copia diagnostica`, validata sul telefono.

---

# 11. Stack tecnologico corrente

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

# 12. Identità, workflow, Release e firma

Workflow:

`GitHub → Actions → Gradle → assembleRelease → zipalign → apksigner → GitHub Release → telefono`

Release sviluppo:

- tag `dev-latest`;
- titolo `VolaFlex - Development latest`;
- asset `VolaFlex-dev.apk`.

`PROJECT_SPEC.md`, `SESSION_HANDOFF.md` e `.github/workflows/android-build.yml` sono esclusi dai trigger push per evitare build documentali.

Firma persistente validata end-to-end:

`a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`

Keystore con doppio backup personale; password conservata separatamente. Per Secrets amministrativi usare UI GitHub se il token Codespaces (`gh secret set`) restituisce 403.

---

# 13. Credenziali API e Impostazioni

API key in DataStore Preferences locale, mai codice/repository; `android:allowBackup=false`.

- SerpApi obbligatoria;
- SearchAPI.io opzionale;
- UI mostra solo `configurata ✓`, mai il valore.

Persistenza testata dopo chiusura completa. DataStore non cifra autonomamente a riposo: rischio accettato per app personale su storage privato Android.

La chiave personale non è disponibile nel repository, nei Secrets CI o in chat. Di conseguenza CI/connettore non devono e non possono eseguire test live autenticati SerpApi con la chiave dell'utente.

---

# 14. Navigazione UI corrente

Route: `home`, `search` (Date fisse), `weekend`, `settings`, `diagnostics`.

Ricerca espone `Date fisse | Weekend`.

---

# 15. v1 — Fondamenta: CHIUSA E VALIDATA

- firma stabile: completata/validata end-to-end;
- Impostazioni API key: completate/validate;
- prima ricerca reale: `FCO→MAD`, 16–19/10/2026, Ryanair, 104 EUR, 0 scali andata, quota 131/250;
- IATA guard/cache/diagnostica: validate;
- round finale v1: **1 query osservata = 1 stimata**.

---

# 16. v2 — Date flessibili

## 16.1 v2.1 Weekend Discovery — CHIUSA E VALIDATA

Travel Explore con un'origine/destinazione, 1/2/3 mesi, `travel_duration=1`, cache 4h e diagnostica `TRAVEL_EXPLORE`.

Test reale:

- `FCO→MAD`, ottobre 2026;
- candidato `01/10/2026→05/10/2026`;
- 95 EUR;
- migrazione Room 1→2 PASS senza perdita dati;
- cache Weekend PASS;
- **1 query osservata = 1 stimata**.

Osservazione fondamentale: Explore ha restituito **giovedì→lunedì, 4 notti**. Quindi il preset Weekend è Discovery indicativa e non garantisce il weekend breve richiesto. Questa è la motivazione della v2.2.

## 16.2 v2.2 Weekend Verifica precisa — IMPLEMENTATA, CI VERDE, DA VALIDARE SUL TELEFONO

Dopo Explore:

1. scegliere solo il candidato più economico;
2. generare al massimo due pattern precisi;
3. nuovo Account API live;
4. Google Flights per i soli pattern generati;
5. scegliere il risultato verificato più economico;
6. non enumerare tutti i weekend del mese.

Pattern:

- **A — venerdì sera → domenica sera:** date venerdì/domenica; API `outbound_times=17,23`, `return_times=17,23`;
- **B — sabato mattina → lunedì:** date sabato/lunedì; API `outbound_times=5,11`, `return_times=0,23`.

UI: card distinta `Weekend verificato ✓` con date, prezzo, compagnia/orari/scali dell'andata e fascia ritorno applicata. Il ritorno esatto via `departure_token` resta alla fase Dettaglio.

Diagnostica: `WEEKEND_VERIFY` con SUCCESS/EMPTY/ERROR e HTTP status.

Cache: verification nello stesso JSON v2, nessuna migrazione DB.

### Audit/hardening parametri orari

Il codice v2.2 originale della build `0.1.0-dev.17` usava già i valori ufficiali a ore intere. Dopo verifica documentale è stato aggiunto `SerpApiTimeFilterGuard` come difesa preventiva. Nessuna query SerpApi viene eseguita durante GitHub Actions; i run di build non consumano quota.

### CI corrente v2.2 hardening

GitHub Actions **run #19: SUCCESS**.

- versione: `0.1.0-dev.19`;
- `compileReleaseKotlin`: SUCCESS;
- `assembleRelease`: SUCCESS;
- zipalign: SUCCESS;
- apksigner verify: SUCCESS;
- fingerprint: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`;
- APK SHA-256: `71ab13c021542cb5e2e19fe545b79d7f73eb131e748d5740f4d2f5d5d84e8310`;
- Release `dev-latest` aggiornata.

Run #18 è stato cancellato da `cancel-in-progress` quando è partito #19; non è un errore di codice.

Non è stata effettuata una prova live autenticata dal connettore: per design la API key personale è solo sul telefono. La semantica dei parametri è stata verificata sulla documentazione ufficiale; il comportamento provider-runtime verrà validato nel test reale v2.2 sul telefono.

## 16.3 Prossimi sotto-step v2

Dopo validazione v2.2:

- N notti / ±X con SearchAPI.io Calendar;
- range ampi;
- fallback euristico/quota-saver.

---

# 17. v3 — Geografia avanzata

Fino a 3 origini, 3 destinazioni, Ovunque, paese/area, Explore/Deals, directory aeroporti estesa, deduplicazione.

---

# 18. v4 — Scali avanzati

Durata max scalo, esclusione paese, stessa compagnia operativa, dettaglio scali, >8h, Maps, robustezza/fallback.

---

# 19. Decisioni e motivazioni

- Kotlin + Compose: Android-only, stack ridotto.
- SerpApi primario: compatibile con Google Flights/Explore e free tier.
- SearchAPI Calendar acceleratore: riduce query senza mantenere due motori completi.
- Travel Explore solo Discovery: test reale ha mostrato intervallo Weekend troppo lungo.
- Verifica mirata max 2 query: evita brute force.
- Discovery→Verifica→Dettaglio: protezione strutturale quota.
- GitHub-only e Release `dev-latest`: requisiti espliciti.
- DataStore per API key; Room per cache+diagnostica.
- Directory IATA statica: zero rete/costo.
- Verification nel JSON esistente: evita migrazione Room inutile.
- Guard locale time-filter: prevenire consumo quota causato da parametri orari malformati.

---

# 20. Rischi noti e accettati

- SerpApi/SearchAPI.io possono cambiare JSON o avere downtime;
- ricerche ampie non sempre matematicamente esaustive;
- quota SerpApi limitata e condivisa;
- Travel Explore/SearchAPI Calendar sono indicativi;
- crediti SearchAPI.io potenzialmente one-time;
- dipendenza comune dall'ecosistema Google Flights;
- debug GitHub-only più lento;
- perdita keystore impedisce update con stessa identità;
- DataStore non cifra autonomamente le chiavi;
- directory IATA non esaustiva;
- cache key da estendere con futuri filtri/passeggeri/multi-aeroporto;
- v2.2 non scarica ancora segmento ritorno via `departure_token`;
- fasce orarie Weekend sono prima interpretazione operativa e potranno diventare configurabili.

---

# 21. Stato reale

## Chiuso e validato sul telefono

- Fase 0: 100%;
- v1 Fondamenta: 100%;
- firma persistente;
- Impostazioni/API key;
- Google Flights reale;
- IATA guard;
- Room cache;
- Diagnostica/clipboard;
- v2.1 Weekend Discovery;
- migrazione Room 1→2;
- cache Weekend;
- consumo v2.1: **1 query osservata = 1 stimata**.

## Implementato/CI verde, da testare sul telefono

- **v2.2 Weekend Verifica precisa**;
- formato SerpApi ufficiale confermato in codice;
- guard locale time-filter aggiunto;
- max 2 Google Flights sul solo candidato migliore;
- secondo controllo quota live;
- `WEEKEND_VERIFY`;
- card `Weekend verificato ✓`;
- cache verification.

Build corrente: **`0.1.0-dev.19`**, run #19 SUCCESS, firma invariata.

---

# 22. Prossimo milestone

**Validazione reale v2.2 sul telefono con build `0.1.0-dev.19`.**

Criteri:

1. installare sopra la build corrente senza disinstallare;
2. Ricerca → Weekend;
3. usare `FCO→MAD`, novembre 2026, se non già in cache;
4. 1 query Explore per il mese;
5. secondo controllo quota live;
6. max 2 `WEEKEND_VERIFY`;
7. se un pattern trova voli, card `Weekend verificato ✓`;
8. date finali venerdì→domenica oppure sabato→lunedì;
9. Pattern A: orario partenza andata restituito coerente con `17,23`; Pattern B: coerente con `5,11`;
10. Diagnostica con `TRAVEL_EXPLORE` + `WEEKEND_VERIFY`;
11. ripetizione identica entro 4h interamente da cache, 0 query provider;
12. nessun crash/schermata bianca.

Dopo conferma: chiudere v2.2 e passare a **N notti / ±X con SearchAPI.io Calendar**.
