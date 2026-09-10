# SESSION_HANDOFF

**Fase attuale:** v3 — Geografia avanzata. **Fase 0, v1 Fondamenta, l'intera v2 Date flessibili, v3.1, v3.2 e v3.3 sono CHIUSE e validate sul telefono reale. v3.4 Anywhere Weekend Discovery è IMPLEMENTATA, CI verde e Release pubblicata; test telefono pendente.**

**Stato completato:** Fase 0 infrastruttura/firma persistente = PASS. v1 Fondamenta = PASS. v2.1 Weekend Discovery = PASS. v2.2 Weekend Verifica precisa = PASS. v2.3 N notti SerpApi = PASS. v2.3 SearchAPI Calendar = PASS. **v2 COMPLETAMENTE CHIUSA.**

**v3 architettura approvata:** sequenza `v3.1 → v3.2 → v3.3 → v3.4 → v3.5 → v3.6 → v3.7`. Origine `Airports(max 3)`; destinazione alternativa esclusiva fra `Airports(max 3)`, `Anywhere`, `Country`; niente destinazioni composite. Preservare `DISCOVERY → VERIFICA → DETTAGLIO`, supporto multi-airport nativo, cache canonicalizzate, Account API live e niente prodotto cartesiano brute-force.

**v3.1 CHIUSA E VALIDATA:** `FCO+CIA+MXP→MAD`, 20–23/11/2026, una sola Google Flights, vincitore **MXP**, **60 EUR**; replay origini riordinate → CACHE HIT, 0 query.

**v3.2 CHIUSA E VALIDATA:** `FCO+CIA→BCN+MAD+VLC`, una sola Google Flights, vincitore **FCO→BCN**, **48 EUR**; UI/duplicati/overlap PASS; replay con entrambi gli assi riordinati → CACHE HIT, 0 query.

**v3.3 CHIUSA E VALIDATA — test reale 2026-09-10:** ramo SerpApi diretto `FCO+CIA→VLC+BCN`, 3 date valutate, **3 query**, vincitore **FCO→VLC**, **40 EUR**; replay assi invertiti → CACHE HIT, 0 query. Ramo SearchAPI Calendar `FCO+CIA→MAD+BCN+VLC`, 3 notti, target 15/01/2027 ±7, 15 candidate tutte valutate, **2 Calendar + 1 SerpApi**, vincitore **FCO→VLC**, **45 EUR verificato**. Il replay cache Calendar specifico NON è stato ripetuto per proteggere quota: canonicalizzazione condivisa già validata nel ramo SerpApi e strategia inclusa nella cache key. **v3.1–v3.3 geografia nativa comma-separated completa.**

**v3.4 implementazione:** solo Weekend + Discovery Anywhere; nessuna verifica Google Flights per Anywhere. UI Weekend: 1–3 origini e selettore destinazione `Aeroporto singolo` / `Ovunque`. Il ramo Aeroporto continua a usare il repository v2.2 già validato. `AnywhereWeekendSearchRepository` usa Travel Explore con `departure_id` canonicalizzato comma-separated e omette completamente `arrival_id`/`arrival_area_id`. Risultati: città, paese, aeroporto IATA/nome, date indicative, prezzo indicativo. Cache riusa `weekend_search_cache`, nessuna migrazione Room, chiave `WEEKEND|<origini canonicalizzate>|ANYWHERE|<periodo>`, TTL 4h. Account API live + quota guard; una query Explore per mese selezionato.

**Robustezza Explore v3.4:** endpoint raw JSON per distinguere campo assente da lista vuota. Diagnostica `TRAVEL_EXPLORE`: `HTTP_ERROR`, `PROVIDER_ERROR`, `BODY_MISSING`, `STRUCTURE_ANOMALY`, `SURPRISING_EMPTY`, `NO_OPPORTUNITIES`, `SUCCESS`, `NETWORK_ERROR`. `destinations=[]` su Anywhere = `SURPRISING_EMPTY`, non prova di “nessun volo”. Dati con segnali di offerta ma campi essenziali invalidi = `STRUCTURE_ANOMALY`; struttura valida senza offerte weekend prezzate = `NO_OPPORTUNITIES`.

**CI v3.4:** run autorevole **#28 = SUCCESS**, build **`0.1.0-dev.28`**, commit applicativo `6aef440e7b397f6f543539535fcd6d36d1edfc57`, `BUILD SUCCESSFUL in 2m 14s`, 49 task. Firma v2/v3 valida, 1 signer, fingerprint canonico invariato `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`. APK SHA-256 `9ea540c368fd077906b630cf9d3b41925261384ef549242cb335eb88a9b3c40a`, size 9,916,803 byte. `dev-latest` verificato su commit/run corretti. Durante preparazione è stato accidentalmente creato e subito rimosso un file root `NOOP`: i run #26/#27 sono transitori/non autorevoli e non hanno consumato query provider.

**Test v3.4 raccomandato:** installare `0.1.0-dev.28` senza disinstallare; `Weekend → Ovunque`; origini `FCO + CIA`; singolo mese **dicembre 2026**; atteso 1 Account API gratuita + **1 Travel Explore**, 0 Google Flights, 0 SearchAPI. Se `SUCCESS`, lista non verificata ordinata per prezzo con città/paese/aeroporto/date/prezzo; Diagnostica = `SERPAPI_ACCOUNT SUCCESS` + un `TRAVEL_EXPLORE SUCCESS`. Replay con origini invertite `CIA + FCO`, stesso mese → `CACHE HIT`, 0 query. Se `SURPRISING_EMPTY`, `STRUCTURE_ANOMALY` o `BODY_MISSING`, NON ripetere alla cieca: usare `Copia diagnostica` e analizzare prima.

**Non implementato ancora:** v3.5 Country/KGMID, v3.6 verifica geografica Weekend, v3.7 integrazioni estreme. **Non avanzare a v3.5 prima del PASS reale di v3.4.**

**Regola:** dopo ogni decisione/modifica/step completato aggiornare sia `PROJECT_SPEC.md` sia questo file. Nuova chat: leggere prima entrambi e confermare lo stato.
