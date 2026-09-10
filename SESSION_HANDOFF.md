# SESSION_HANDOFF

**Fase attuale:** v3 — Geografia avanzata. **Fase 0, v1 Fondamenta, l'intera v2 Date flessibili, v3.1, v3.2 e v3.3 sono CHIUSE e validate sul telefono reale. v3.4 Anywhere Weekend Discovery è IMPLEMENTATA su main; CI #28/test telefono da chiudere.**

**Stato completato:** Fase 0 infrastruttura/firma persistente = PASS. v1 Fondamenta = PASS (API key locali, prima ricerca reale, IATA anti-typo, Room cache 4h, Diagnostica/clipboard). v2.1 Weekend Discovery = PASS. v2.2 Weekend Verifica precisa = PASS. v2.3 N notti ramo SerpApi = PASS. v2.3 ramo SearchAPI.io Calendar = PASS. **v2 COMPLETAMENTE CHIUSA.**

**v3 architettura approvata:** sequenza `v3.1 → v3.2 → v3.3 → v3.4 → v3.5 → v3.6 → v3.7`. Origine `Airports(max 3)`; destinazione alternativa esclusiva fra `Airports(max 3)`, `Anywhere`, `Country`; niente destinazioni composite. Preservare `DISCOVERY → VERIFICA → DETTAGLIO`, supporto multi-airport nativo, cache canonicalizzate, Account API live, niente prodotto cartesiano brute-force.

**v3.1 CHIUSA E VALIDATA:** `FCO + CIA + MXP → MAD`, 20/11/2026→23/11/2026: una sola Google Flights, vincitore **MXP**, **60 EUR**. UI/add-remove, anti-typo e duplicati PASS. Replay con origini riordinate → **CACHE HIT**, 0 query.

**v3.2 CHIUSA E VALIDATA:** UI multi-destinazione/duplicati/overlap PASS. Live `FCO + CIA → BCN + MAD + VLC`: **1 sola Google Flights**, vincitore **FCO→BCN**, **48 EUR**. Replay riordinando entrambi gli assi → **CACHE HIT**, 0 nuove query.

**v3.3 CHIUSA E VALIDATA — test reale 2026-09-10:** ramo SerpApi diretto `FCO+CIA → VLC+BCN`, 3 date candidate/valutate, **3 query**, vincitore **FCO→VLC**, **40 EUR**; replay con entrambi gli assi invertiti → **CACHE HIT**, 0 nuove query, stesso risultato. Ramo SearchAPI.io Calendar `FCO+CIA → MAD+BCN+VLC`, 3 notti, target 15/01/2027 ±7: **15 candidate tutte valutate**, strategia `SearchAPI.io Calendar → verifica SerpApi` attivata automaticamente, **2 richieste Calendar + 1 SerpApi**, vincitore **FCO→VLC**, **45 EUR verificato**. Il replay cache specifico del ramo Calendar NON è stato ripetuto intenzionalmente: la canonicalizzazione è la stessa funzione già validata end-to-end nel ramo SerpApi e la cache key include la strategia (`SERP_EXHAUSTIVE`/`SERP_SAMPLE`/`SEARCHAPI_CALENDAR`) come separatore; evitare query ridondanti protegge la quota condivisa. **Conclusione: tutta la geografia nativa comma-separated v3.1–v3.3 è completa.**

**v3.4 implementazione:** solo modalità Weekend e solo Discovery Anywhere; nessuna verifica Google Flights per Anywhere. UI Weekend ora permette 1–3 origini e scelta destinazione `Aeroporto singolo` oppure `Ovunque`. Il ramo Aeroporto continua a usare il repository v2.2 già validato; il nuovo `AnywhereWeekendSearchRepository` usa Travel Explore con `departure_id` canonicalizzato comma-separated e omette completamente `arrival_id`/`arrival_area_id`. Risultati Anywhere: città, paese, aeroporto IATA/nome, date indicative, prezzo indicativo. Cache riusa `weekend_search_cache` senza migrazione Room, con chiave `WEEKEND|<origini canonicalizzate>|ANYWHERE|<periodo>`, TTL 4h.

**Robustezza Explore v3.4:** il nuovo endpoint restituisce JSON raw per distinguere campo assente da lista vuota. Diagnostica `TRAVEL_EXPLORE` distingue `HTTP_ERROR`, `PROVIDER_ERROR`, `BODY_MISSING`, `STRUCTURE_ANOMALY`, `SURPRISING_EMPTY`, `NO_OPPORTUNITIES`, `SUCCESS` e `NETWORK_ERROR`. `destinations=[]` su Anywhere viene trattato come `SURPRISING_EMPTY`, non come prova di “nessun volo”. Una lista non vuota ma senza offerte prezzate complete può essere `NO_OPPORTUNITIES`; dati con segnali di offerta ma campi essenziali invalidi diventano `STRUCTURE_ANOMALY`.

**Provider v3.4 verificato su docs ufficiali:** Google Travel Explore richiede `departure_id`, supporta origini multiple comma-separated; `arrival_id` è opzionale. Per ricerca destinazioni senza arrivo specificato la risposta documentata usa `destinations[]` con `name`, `country`, `destination_airport.code`, `start_date`, `end_date`, `flight_price`. Release notes 2026 confermano regressioni/fix recenti, incluso il fix del 09/07/2026 sulle ricerche valide che tornavano vuote.

**Nota operativa CI:** durante la preparazione v3.4 è stato creato per errore un file root `NOOP` su main e subito rimosso; non ha toccato codice, dati o provider e non consuma query API. I run transitori #26/#27 non sono build applicative autorevoli. **Run autorevole v3.4: #28 sul commit `6aef440e7b397f6f543539535fcd6d36d1edfc57`; stato da aggiornare a chiusura CI.**

**Non implementato ancora:** v3.5 Country/KGMID, v3.6 verifica geografica Weekend, v3.7 integrazioni estreme. **Non avanzare a v3.5 prima del PASS reale di v3.4.**

**Regola:** dopo ogni decisione/modifica/step completato aggiornare sia `PROJECT_SPEC.md` sia questo file. Nuova chat: leggere prima entrambi e confermare lo stato.
