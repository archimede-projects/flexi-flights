# VolaFlex — PROJECT_SPEC

**Repository:** `archimede-projects/flexi-flights` (privata)  
**Nome app:** VolaFlex  
**Application ID / namespace:** `com.archimedeprojects.volaflex`  
**Ruolo:** fonte di verità persistente del progetto  
**Ultimo aggiornamento:** 2026-09-14

## Regola di manutenzione

Aggiornare questo file dopo ogni requisito/decisione/modifica/step completato. `SESSION_HANDOFF.md` contiene il riepilogo operativo sintetico per riprendere il lavoro in una nuova chat.

---

# 1. Obiettivo e vincoli permanenti

VolaFlex è un'app Android personale per trovare voli economici con date flessibili, weekend, multi-aeroporto, geografia avanzata e futuri filtri sugli scali.

Vincoli permanenti: uso personale/APK sideload; **zero costi e nessuna carta**; niente backend a pagamento; sviluppo GitHub-only; niente Android Studio locale; CI/build via GitHub Actions; test runtime su telefono reale; nessuna API key/keystore/password in repo/chat; workflow semplice e verificabile.

---

# 2. Requisiti funzionali

1. Weekend flessibili: venerdì sera o sabato mattina → domenica sera o lunedì.
2. N notti: numero esatto di notti, target ±X, miglior periodo.
3. Range ampi senza brute force.
4. Multi-origine max 3.
5. Destinazione alternativa esclusiva fra: aeroporto/i max 3, Ovunque, Paese.
6. Esclusione paese di scalo.
7. Stessa compagnia operativa quando richiesto.
8. Durata massima scalo.
9. Dettaglio scalo aeroporto/città/paese/durata/overnight.
10. Scali >8h: Maps via Intent, niente Maps SDK.

Niente destinazioni composite (es. aeroporti + paese insieme).

---

# 3. Architettura

`UI → logica ricerca → provider → cache/database`

Pattern: **DISCOVERY → VERIFICA → DETTAGLIO**.

Protezione anti-esplosione: preferire parametri multi-airport nativi, Explore per ridurre geografia, Calendar per ridurre date, verifica di pochi candidati, Account API live, cache TTL 4h e diagnostica; mai prodotto cartesiano ingenuo `origini × destinazioni × date × pattern`.

v3.1–v3.3 hanno validato end-to-end la geografia nativa comma-separated su Google Flights e SearchAPI Calendar. v3.4 ha validato la Discovery geografica `Anywhere` con Explore e cache canonicalizzata.

---

# 4. Provider dati

## 4.1 SerpApi

Ruoli:
- `google_flights`: exact/verifica;
- `google_travel_explore`: Discovery Weekend/Ovunque/Paese;
- Account API: quota live autorevole.

Google Flights: `departure_id`/`arrival_id` supportano liste comma-separated; `outbound_times`/`return_times` sono 2 o 4 ore intere 0–23 separate da virgola; dettaglio ritorno esatto richiede `departure_token`.

Quota condivisa con altro progetto: Account API live autorevole; riserva minima 5; mai batch automatici massivi. Cache e sampling sono obbligatori per proteggere quota.

## 4.2 SearchAPI.io Calendar

Acceleratore N notti. Liste multi-airport comma-separated; limite ~200 combinazioni date round-trip; chunk 14×14=196; ≤10 date SerpApi esaustivo; >10 + SearchAPI Calendar → 1 verifica SerpApi; >10 senza SearchAPI sampling 5+2.

## 4.3 Google Travel Explore

- `engine=google_travel_explore`;
- `travel_duration=1` Weekend;
- `departure_id` supporta origini multiple comma-separated;
- `arrival_id` per aeroporto/città specifica;
- **Ovunque:** omettere `arrival_id` e `arrival_area_id`;
- **Paese:** omettere `arrival_id`, usare `arrival_area_id=<KGMID>`;
- KGMID paese = location Freebase ID `/m/...` o `/g/...`, come documentato da SerpApi.

### Robustezza Explore obbligatoria

Release notes ufficiali 2026 hanno corretto regressioni su Weekend, `max_duration`, `stops` e il 09/07/2026 il caso “most valid searches returning empty results”. Da v3.4 il parser raw difensivo classifica:
`HTTP_ERROR`, `PROVIDER_ERROR`, `BODY_MISSING`, `STRUCTURE_ANOMALY`, `SURPRISING_EMPTY`, `NO_OPPORTUNITIES`, `SUCCESS`, `NETWORK_ERROR`.

Da v3.5 Anywhere e Country usano **lo stesso `TravelExploreRawParser`**, così un payload identico ha la stessa semantica diagnostica. `destinations=[]` è prudenzialmente `SURPRISING_EMPTY`, non “nessun volo”.

---

# 5. Credenziali e sicurezza

SerpApi e SearchAPI.io salvate localmente in DataStore, mai repo/chat; `android:allowBackup=false`. SearchAPI.io è opzionale architetturalmente e configurata sul telefono. DataStore non cifra autonomamente a riposo: rischio accettato per app personale nello storage privato Android.

---

# 6. Geografia locale

## AirportDirectory

`IATA → aeroporto → città → ISO country`, circa 180–200 aeroporti. Usato per guard anti-typo, classificazione e futuri filtri scali.

## CountryAreaCatalog — v3.5

Catalogo statico zero-rete:

`nome italiano → ISO2 → KGMID/Freebase ID`

Copertura iniziale **33 paesi**, volutamente curata per evitare KGMID non verificati che potrebbero sprecare quota:
Algeria, Austria, Belgio, Brasile, Bulgaria, Canada, Croazia, Danimarca, Egitto, Finlandia, Francia, Germania, Giappone, Grecia, Irlanda, Italia, Malta, Marocco, Messico, Paesi Bassi, Polonia, Portogallo, Regno Unito, Repubblica Ceca, Romania, Spagna, Stati Uniti, Svezia, Svizzera, Thailandia, Tunisia, Turchia, Ungheria.

Primo test v3.5 raccomandato: **Francia (`FR`, `/m/0f8l9c`)**, perché SerpApi usa esplicitamente questo KGMID come esempio ufficiale di `arrival_area_id` paese.

Modello v3:
- `OriginSelection.Airports(max3)`;
- `DestinationSelection.Airports(max3)`;
- `DestinationSelection.Anywhere`;
- `DestinationSelection.Country(ISO2/KGMID)`.

---

# 7. Cache Room e Diagnostica

Database `volaflex.db`, schema **3**. Tabelle: `flight_search_cache`, `weekend_search_cache`, `nights_search_cache`, `diagnostic_events`. TTL 4h. Migrazioni 1→2 e 2→3 validate.

Date fisse cache: origini/destinazioni canonicalizzate + date.
N notti cache: origini/destinazioni + N + target + ±X + strategia.

### Anywhere Weekend v3.4
`WEEKEND|<origini>|ANYWHERE|<periodo>`.

### Country Weekend v3.5
`WEEKEND|<origini>|COUNTRY:<ISO2>|<periodo>`.
Il record riusa `weekend_search_cache`, con `arrivalId=COUNTRY:<ISO2>`. Nessuna migrazione Room necessaria.

Diagnostica conserva ultimi 20 eventi e non registra chiavi API.

---

# 8. Modelli dati

- `SimpleFlightResult`: Date fisse + origine/destinazione effettive.
- `WeekendCandidate` / `VerifiedWeekendResult`: Weekend aeroporto.
- `AnywhereWeekendCandidate`: Discovery Anywhere.
- `CountryWeekendCandidate`: Discovery Paese, stessa struttura dati essenziale (città, paese, aeroporto, date indicative, prezzo, valuta, mese).
- `NightsSearchResult`: N notti + strategia/provider/cache + origine/destinazione effettive.
- futuri: `FlightItinerary`, `FlightSegment`, `Layover`.

---

# 9. Stack

Kotlin/Compose; AGP 9.3.1; Kotlin 2.4.20; Gradle 9.5.0; Compose BOM 2026.06.00; Activity Compose 1.11.0; Navigation Compose 2.9.8; DataStore 1.2.1; Retrofit 3.0.0; OkHttp 4.12.0; kotlinx.serialization 1.11.0; Room 2.8.4; KSP 2.3.11; SDK36/min26; JDK17.

---

# 10. CI, Release e firma

Pipeline: GitHub Actions → assembleRelease → zipalign → apksigner → `dev-latest`.
Firma canonica SHA-256:
`a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`
Signer `CN=VolaFlex, OU=Personal, O=archimede-projects`, RSA4096.

Ultima build validata prima di v3.5: v3.4 run #28 SUCCESS, `0.1.0-dev.28`, commit `6aef440e7b397f6f543539535fcd6d36d1edfc57`, APK SHA-256 `9ea540c368fd077906b630cf9d3b41925261384ef549242cb335eb88a9b3c40a`.

---

# 11. Stato roadmap e test reali

## Fase 0
**CHIUSA E VALIDATA.** CI, firma persistente, Release e upgrade senza disinstallazione PASS.

## v1 Fondamenta
**CHIUSA E VALIDATA.** Prima ricerca reale FCO→MAD 16–19/10/2026, Ryanair 104 EUR; IATA/cache/diagnostica PASS.

## v2 Date flessibili
**COMPLETAMENTE CHIUSA E VALIDATA.** Weekend Discovery/Verify e N notti SerpApi/Calendar tutti PASS con cache e consumi coerenti alle stime.

## v3.1
**CHIUSA:** `FCO+CIA+MXP→MAD`, vincitore MXP 60 EUR, 1 query; replay riordinato 0.

## v3.2
**CHIUSA:** `FCO+CIA→BCN+MAD+VLC`, vincitore FCO→BCN 48 EUR, 1 query; replay entrambi assi riordinati 0.

## v3.3
**CHIUSA:** Serp direct multi-list (3 query, FCO→VLC 40 EUR) + Calendar multi-list (2 Calendar +1 Serp, FCO→VLC 45 EUR). Replay Calendar specifico non ripetuto per protezione quota; canonicalizzazione condivisa e strategia nella cache key già validate.

## v3.4 — CHIUSA E VALIDATA SUL TELEFONO

Test reale:
- UI `Aeroporto singolo/Ovunque`: PASS;
- anti-typo origine aggiuntiva: PASS;
- `FCO+CIA → Ovunque`, dicembre 2026;
- **1 sola Travel Explore**;
- Diagnostica `SUCCESS`, nessuna anomalia;
- candidati reali ricevuti: Bari **34 EUR**, Alicante **42 EUR**, Varsavia e altri, con città/paese/aeroporto/date/prezzo;
- nessuna Google Flights verification, correttamente;
- replay `CIA+FCO`, stesso periodo: **Risultato da cache**, 0 query, quota osservata invariata (105 prima/dopo replay).

Conclusione: v3.4 e robust parser/cache Anywhere validati.

## v3.5 — IMPLEMENTATA SU BRANCH, CI DA CHIUDERE

Scope:
- Weekend soltanto;
- terza scelta destinazione `Paese`;
- catalogo locale 33 paesi;
- 1–3 origini canonicalizzate;
- Travel Explore con `arrival_area_id` KGMID e senza `arrival_id`;
- Discovery pura, nessuna Google Flights verification;
- parser raw condiviso con Anywhere;
- cache `COUNTRY:<ISO2>` nella tabella Weekend esistente;
- nessuna migrazione Room;
- Country risultati limitati chiaramente al paese selezionato.

**Non avanzare a v3.6 prima del PASS reale di v3.5.**

---

# 12. Rischi noti

Provider possono cambiare JSON/parametri; quota SerpApi condivisa; SearchAPI pool finito; AirportDirectory non esaustiva; catalogo KGMID volutamente curato/non universale; Explore ha avuto regressioni 2026; `destinations=[]` è anomalia prudenziale; dettaglio ritorno resta on-demand; combinazioni estreme devono restare protette da cache/quota.

---

# 13. Prossimo milestone operativo

1. Chiudere CI v3.5 e verificare firma/Release.
2. Installare build v3.5 sopra quella corrente.
3. Weekend → Paese → **Francia**; origini `FCO+CIA`; un solo mese nuovo.
4. Atteso 1 Account API gratuita + **1 Travel Explore**, nessun Google Flights/SearchAPI.
5. Verificare che tutti i candidati appartengano al paese selezionato e Diagnostica `TRAVEL_EXPLORE SUCCESS` riporti `Country FR`.
6. Replay origini invertite, stesso paese/periodo → cache hit 0 query.
7. In caso `SURPRISING_EMPTY`/`STRUCTURE_ANOMALY`/`BODY_MISSING`, non ripetere alla cieca: copiare Diagnostica e analizzare.
8. Se PASS, chiudere v3.5 e passare a v3.6.
