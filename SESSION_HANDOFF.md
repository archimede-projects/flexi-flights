# SESSION_HANDOFF

**Fase attuale:** v3 — Geografia avanzata. **Fase 0, v1 Fondamenta, l'intera v2 Date flessibili, v3.1, v3.2, v3.3 e v3.4 sono CHIUSE e validate sul telefono reale. v3.5 Country Weekend Discovery è IMPLEMENTATA, CI verde e Release pubblicata; test telefono pendente.**

**v3 architettura:** `v3.1 → v3.2 → v3.3 → v3.4 → v3.5 → v3.6 → v3.7`. Origine `Airports(max 3)`; destinazione esclusiva fra `Airports(max 3)`, `Anywhere`, `Country`; no destinazioni composite. Pattern `DISCOVERY → VERIFICA → DETTAGLIO`, cache canonicalizzate, Account API live, niente brute force cartesiano.

**Milestone chiuse:** Fase 0 PASS; v1 PASS; v2 COMPLETAMENTE CHIUSA; v3.1 PASS (`FCO+CIA+MXP→MAD`, MXP 60 EUR, 1 query, replay 0); v3.2 PASS (`FCO+CIA→BCN+MAD+VLC`, FCO→BCN 48 EUR, 1 query, replay entrambi assi 0); v3.3 PASS (Serp multi-list FCO→VLC 40 EUR; Calendar multi-list FCO→VLC 45 EUR, 2 Calendar +1 Serp).

**v3.4 CHIUSA E VALIDATA — test reale:** UI `Aeroporto singolo/Ovunque` PASS; anti-typo origine aggiuntiva PASS. Live `FCO+CIA → Ovunque`, dicembre 2026: **1 sola Travel Explore**, `SUCCESS`, candidati Bari **34 EUR**, Alicante **42 EUR**, Varsavia ecc. con città/paese/aeroporto/date/prezzo; nessuna Google Flights verification. Replay `CIA+FCO` → `Risultato da cache`, 0 nuove query e quota invariata (105 osservata). Robust parser/cache Anywhere validati.

**v3.5 IMPLEMENTATA + CI VERDE:** Weekend aggiunge terza scelta `Paese`. 1–3 origini. `CountryAreaCatalog` locale `nome/ISO2/KGMID` con **33 paesi**: Algeria, Austria, Belgio, Brasile, Bulgaria, Canada, Croazia, Danimarca, Egitto, Finlandia, Francia, Germania, Giappone, Grecia, Irlanda, Italia, Malta, Marocco, Messico, Paesi Bassi, Polonia, Portogallo, Regno Unito, Repubblica Ceca, Romania, Spagna, Stati Uniti, Svezia, Svizzera, Thailandia, Tunisia, Turchia, Ungheria. Catalogo curato per non inserire KGMID non verificati. Francia = `FR`, `/m/0f8l9c`, esempio ufficiale SerpApi.

**Provider v3.5:** Travel Explore usa `departure_id=<origini canonicalizzate>`, `arrival_area_id=<country.kgmid>`, `month`, `travel_duration=1`, Economy/EUR/it; **nessun `arrival_id`**. Discovery pura: nessun Google Flights/SearchAPI. Risultati: città, paese, aeroporto, date indicative, prezzo indicativo e label esplicita del paese selezionato.

**Robustezza condivisa:** v3.4 Anywhere e v3.5 Country usano lo stesso `TravelExploreRawParser`. Classificazioni: `HTTP_ERROR`, `PROVIDER_ERROR`, `BODY_MISSING`, `STRUCTURE_ANOMALY`, `SURPRISING_EMPTY`, `NO_OPPORTUNITIES`, `SUCCESS`, `NETWORK_ERROR`. Release notes Explore 2026 includono regressione/fix del 09/07/2026 sulle ricerche valide vuote: non interpretare `destinations=[]` come normale assenza voli.

**Cache v3.5:** nessuna migrazione Room, schema resta 3. Riuso `weekend_search_cache`; chiave `WEEKEND|<origini>|COUNTRY:<ISO2>|<periodo>`, `arrivalId=COUNTRY:<ISO2>`, TTL 4h. Separata da `ANYWHERE` e dalle destinazioni aeroporto.

**CI v3.5:** run autorevole **#29 = SUCCESS**, build **`0.1.0-dev.29`**, commit applicativo/documentale di build `2b34d73d80b7bb0e385efdb8265ec124a7f91edc`; `BUILD SUCCESSFUL in 2m 5s`, 49 task. Firma v2/v3 valida, 1 signer, fingerprint canonico invariato `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`. APK SHA-256 `0ae786a35bf48acf38c6f5cdcadd0267bd77ea7ee5cdca2505b54bc153855273`, size 9,933,187 byte. `dev-latest` verificato su build/commit/run corretti.

**Test raccomandato:** installare `0.1.0-dev.29`; Weekend → Paese; origini `FCO+CIA`; paese **Francia**; periodo **gennaio 2027** finché presente nei prossimi 6 mesi. Atteso: 1 Account API gratuita + **1 Travel Explore**, 0 Google Flights, 0 SearchAPI; candidati limitati alla Francia; Diagnostica `TRAVEL_EXPLORE SUCCESS` con `Country FR`; replay `CIA+FCO`, stessi paese/periodo → CACHE HIT, 0 query. Se `SURPRISING_EMPTY`, `STRUCTURE_ANOMALY` o `BODY_MISSING`, non ripetere alla cieca: copiare Diagnostica.

**Non implementato:** v3.6 verifica geografica Weekend; v3.7 integrazione/hardening. Non avanzare a v3.6 prima del PASS v3.5.

**Regola:** dopo ogni decisione/modifica/step completato aggiornare `PROJECT_SPEC.md` e `SESSION_HANDOFF.md`. Nuova chat: leggere entrambi prima di procedere.
