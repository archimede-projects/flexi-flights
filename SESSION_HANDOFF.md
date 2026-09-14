# SESSION_HANDOFF

**Fase attuale:** v3 — Geografia avanzata. **Fase 0, v1 Fondamenta, l'intera v2 Date flessibili, v3.1, v3.2, v3.3 e v3.4 sono CHIUSE e validate sul telefono reale. v3.5 Country Weekend Discovery è in implementazione sul branch `feature/v3-5-country-discovery`.**

**Stato completato:** Fase 0 infrastruttura/firma persistente = PASS. v1 Fondamenta = PASS. v2.1 Weekend Discovery = PASS. v2.2 Weekend Verifica precisa = PASS. v2.3 N notti SerpApi = PASS. v2.3 SearchAPI Calendar = PASS. **v2 COMPLETAMENTE CHIUSA.**

**v3 architettura approvata:** sequenza `v3.1 → v3.2 → v3.3 → v3.4 → v3.5 → v3.6 → v3.7`. Origine `Airports(max 3)`; destinazione alternativa esclusiva fra `Airports(max 3)`, `Anywhere`, `Country`; niente destinazioni composite. Preservare `DISCOVERY → VERIFICA → DETTAGLIO`, supporto multi-airport nativo, cache canonicalizzate, Account API live e niente prodotto cartesiano brute-force.

**v3.1 CHIUSA E VALIDATA:** `FCO+CIA+MXP→MAD`, 20–23/11/2026, una sola Google Flights, vincitore **MXP**, **60 EUR**; replay origini riordinate → CACHE HIT, 0 query.

**v3.2 CHIUSA E VALIDATA:** `FCO+CIA→BCN+MAD+VLC`, una sola Google Flights, vincitore **FCO→BCN**, **48 EUR**; UI/duplicati/overlap PASS; replay con entrambi gli assi riordinati → CACHE HIT, 0 query.

**v3.3 CHIUSA E VALIDATA:** ramo SerpApi diretto `FCO+CIA→VLC+BCN`, 3 date valutate, **3 query**, vincitore **FCO→VLC**, **40 EUR**; replay assi invertiti → CACHE HIT, 0 query. Ramo SearchAPI Calendar `FCO+CIA→MAD+BCN+VLC`, 3 notti, target 15/01/2027 ±7, 15 candidate tutte valutate, **2 Calendar + 1 SerpApi**, vincitore **FCO→VLC**, **45 EUR verificato**. Replay Calendar specifico non ripetuto per proteggere quota: canonicalizzazione condivisa già validata nel ramo SerpApi e strategia inclusa nella cache key.

**v3.4 CHIUSA E VALIDATA — test reale:** UI `Aeroporto singolo/Ovunque` PASS; anti-typo su origine aggiuntiva PASS. Live `FCO+CIA → Ovunque`, dicembre 2026: **1 sola Travel Explore**, Diagnostica `SUCCESS`, quota osservata 105 prima del replay; candidati ricevuti con città/paese/aeroporto/date/prezzo (es. Bari **34 EUR**, Alicante **42 EUR**, Varsavia e altri). Nessuna Google Flights verification, come da specifica. Replay con origini invertite `CIA+FCO`, stesso periodo → **Risultato da cache**, 0 nuove query e quota invariata. Conclusione: cache canonicalizzata e robust parser v3.4 validati sul telefono.

**v3.5 scope in implementazione:** solo Weekend + Discovery Paese, nessuna Google Flights verification. UI destinazione `Aeroporto | Ovunque | Paese`; 1–3 origini. `CountryAreaCatalog` statico locale con nome/ISO2/KGMID; query Travel Explore usa `departure_id` canonicalizzato + `arrival_area_id=<KGMID>` e omette `arrival_id`. Cache prevista `WEEKEND|<origini>|COUNTRY:<ISO2>|<periodo>`, TTL 4h, riuso `weekend_search_cache`, nessuna migrazione Room. Country e Anywhere condividono lo stesso `TravelExploreRawParser` e le classificazioni `HTTP_ERROR`, `PROVIDER_ERROR`, `BODY_MISSING`, `STRUCTURE_ANOMALY`, `SURPRISING_EMPTY`, `NO_OPPORTUNITIES`, `SUCCESS`, `NETWORK_ERROR`.

**Catalogo Country v3.5:** 33 paesi iniziali verificati/curati: Algeria, Austria, Belgio, Brasile, Bulgaria, Canada, Croazia, Danimarca, Egitto, Finlandia, Francia, Germania, Giappone, Grecia, Irlanda, Italia, Malta, Marocco, Messico, Paesi Bassi, Polonia, Portogallo, Regno Unito, Repubblica Ceca, Romania, Spagna, Stati Uniti, Svezia, Svizzera, Thailandia, Tunisia, Turchia, Ungheria. Primo test raccomandato: **Francia**, perché SerpApi documenta esplicitamente `arrival_area_id=/m/0f8l9c`.

**Rischio Explore:** release notes ufficiali 2026 includono fix a `travel_duration`, `max_duration`, `stops` e il 09/07/2026 al problema delle ricerche valide vuote. Non interpretare risposte vuote come normale assenza voli senza classificazione Diagnostica.

**Ultima Release validata prima di v3.5:** `0.1.0-dev.28`, run #28 SUCCESS, firma canonica invariata. La build v3.5 deve ancora essere chiusa in CI prima del test telefono.

**Non implementato ancora:** v3.6 verifica geografica Weekend, v3.7 integrazioni estreme. **Non avanzare a v3.6 prima del PASS reale di v3.5.**

**Regola:** dopo ogni decisione/modifica/step completato aggiornare sia `PROJECT_SPEC.md` sia questo file. Nuova chat: leggere prima entrambi e confermare lo stato.
