# SESSION_HANDOFF

**Fase attuale:** v3 — Geografia avanzata. **Fase 0, v1 Fondamenta, l'intera v2 Date flessibili, v3.1, v3.2, v3.3, v3.4 e v3.5 sono CHIUSE e validate sul telefono reale.** Prima di v3.6 c'è un blocco UI ad alta priorità: il selettore principale in Weekend mostra solo `Date fisse | Weekend` e omette `N notti`.

**v3 architettura:** `v3.1 → v3.2 → v3.3 → v3.4 → v3.5 → v3.6 → v3.7`. Origine `Airports(max 3)`; destinazione esclusiva fra `Airports(max 3)`, `Anywhere`, `Country`; no destinazioni composite. Pattern `DISCOVERY → VERIFICA → DETTAGLIO`, cache canonicalizzate, Account API live, niente brute force cartesiano.

**Milestone chiuse:** Fase 0 PASS; v1 PASS; v2 COMPLETAMENTE CHIUSA; v3.1 PASS (`FCO+CIA+MXP→MAD`, MXP 60 EUR, 1 query, replay 0); v3.2 PASS (`FCO+CIA→BCN+MAD+VLC`, FCO→BCN 48 EUR, 1 query, replay entrambi assi 0); v3.3 PASS (Serp multi-list FCO→VLC 40 EUR; Calendar multi-list FCO→VLC 45 EUR, 2 Calendar +1 Serp).

**v3.4 CHIUSA E VALIDATA — test reale:** UI `Aeroporto singolo/Ovunque` PASS; anti-typo origine aggiuntiva PASS. Live `FCO+CIA → Ovunque`, dicembre 2026: **1 sola Travel Explore**, `SUCCESS`, candidati Bari **34 EUR**, Alicante **42 EUR**, Varsavia ecc. con città/paese/aeroporto/date/prezzo; nessuna Google Flights verification. Replay `CIA+FCO` → `Risultato da cache`, 0 nuove query e quota invariata (105 osservata). Robust parser/cache Anywhere validati.

**v3.5 CHIUSA E VALIDATA SUL TELEFONO REALE:** Weekend aggiunge terza scelta `Paese`. 1–3 origini. `CountryAreaCatalog` locale `nome/ISO2/KGMID` con **33 paesi**. Provider: Travel Explore con `departure_id=<origini canonicalizzate>`, `arrival_area_id=<country.kgmid>`, `month`, `travel_duration=1`, Economy/EUR/it; **nessun `arrival_id`**; nessun Google Flights/SearchAPI in Discovery Country. Cache `WEEKEND|<origini>|COUNTRY:<ISO2>|<periodo>`, `arrivalId=COUNTRY:<ISO2>`, TTL 4h, schema Room 3 invariato.

**Test reale v3.5 PASS:** build `0.1.0-dev.29`; `FCO+CIA → Francia`, gennaio 2027. Risultato: **Lourdes**, Paese **Francia**, aeroporto **LDE**, **72 EUR**, periodo Explore **08/01→11/01/2027**. Controllo anti-KGMID-sbagliato superato: il candidato appartiene realmente alla Francia. Diagnostica: `SERPAPI_ACCOUNT` + **1 solo `TRAVEL_EXPLORE`**, nessuna Google Flights/Weekend Verify/SearchAPI Calendar. Replay `CIA+FCO` → `Risultato da cache`, **0 nuove query**. Consumo reale: **1 sola query SerpApi**, come previsto.

**Robustezza condivisa:** v3.4 Anywhere e v3.5 Country usano lo stesso `TravelExploreRawParser`. Classificazioni: `HTTP_ERROR`, `PROVIDER_ERROR`, `BODY_MISSING`, `STRUCTURE_ANOMALY`, `SURPRISING_EMPTY`, `NO_OPPORTUNITIES`, `SUCCESS`, `NETWORK_ERROR`. Release notes Explore 2026 includono regressione/fix del 09/07/2026 sulle ricerche valide vuote: non interpretare `destinations=[]` come normale assenza voli.

**CI v3.5:** run autorevole **#29 = SUCCESS**, build **`0.1.0-dev.29`**, commit applicativo/documentale di build `2b34d73d80b7bb0e385efdb8265ec124a7f91edc`; `BUILD SUCCESSFUL in 2m 5s`, 49 task. Firma v2/v3 valida, 1 signer, fingerprint canonico invariato `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`. APK SHA-256 `0ae786a35bf48acf38c6f5cdcadd0267bd77ea7ee5cdca2505b54bc153855273`, size 9,933,187 byte. `dev-latest` verificato su build/commit/run corretti.

**BLOCCO UI prima di v3.6:** in `WeekendSearchScreen` il selettore principale mostra solo `Date fisse | Weekend`; manca `N notti`, mentre `SearchScreen` e `NightsSearchScreen` mostrano correttamente tutte e tre le modalità. Investigare e correggere esclusivamente layout/navigazione UI. Il selettore secondario Weekend `Aeroporto | Ovunque | Paese` deve restare leggibile, con `Aeroporto` su una riga. Nessun file repository/provider/rete/cache/query deve essere modificato.

**Non implementato:** v3.6 verifica geografica Weekend; v3.7 integrazione/hardening. Non avanzare a v3.6 finché il fix UI non è buildato, pubblicato e validato sul telefono.

**Regola:** dopo ogni decisione/modifica/step completato aggiornare `PROJECT_SPEC.md` e `SESSION_HANDOFF.md`. Nuova chat: leggere entrambi prima di procedere.
