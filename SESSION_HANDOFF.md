# SESSION_HANDOFF

**Ultimo aggiornamento:** 2026-09-16

**Fase attuale:** v3 — Geografia avanzata. **Fase 0, v1 Fondamenta, l'intera v2 Date flessibili, v3.1, v3.2, v3.3, v3.4, v3.5 e v3.6 sono CHIUSE e validate sul telefono/device reale. Il blocker UI post-v3.5 è CHIUSO E VALIDATO. v3.7-B Maps Intent è IMPLEMENTATA, CI verde e Release pubblicata; test device Maps pendente. v3.7-A filtri post-verifica è il prossimo step pianificato ma NON iniziato.**

**v3 architettura:** `v3.1 → v3.2 → v3.3 → v3.4 → v3.5 → v3.6 → v3.7`. Origine `Airports(max 3)`; destinazione esclusiva fra `Airports(max 3)`, `Anywhere`, `Country`; no destinazioni composite. Pattern `DISCOVERY → VERIFICA → DETTAGLIO`, cache canonicalizzate, Account API live, niente brute force cartesiano.

**Milestone chiuse:** Fase 0 PASS; v1 PASS; v2 COMPLETAMENTE CHIUSA; v3.1 PASS (`FCO+CIA+MXP→MAD`, MXP 60 EUR, 1 query, replay 0); v3.2 PASS (`FCO+CIA→BCN+MAD+VLC`, FCO→BCN 48 EUR, 1 query, replay entrambi assi 0); v3.3 PASS (Serp multi-list FCO→VLC 40 EUR; Calendar multi-list FCO→VLC 45 EUR, 2 Calendar +1 Serp).

**v3.4 CHIUSA E VALIDATA:** `FCO+CIA → Ovunque`, dicembre 2026: 1 Travel Explore `SUCCESS`, candidati Bari 34 EUR, Alicante 42 EUR, Varsavia ecc.; replay `CIA+FCO` cache hit, 0 query.

**v3.5 CHIUSA E VALIDATA SUL TELEFONO REALE:** test `FCO+CIA → Francia`, gennaio 2027: Lourdes/LDE, 72 EUR, 08/01→11/01/2027; candidato realmente in Francia; diagnostica `SERPAPI_ACCOUNT` + 1 `TRAVEL_EXPLORE`, nessuna Google Flights/SearchAPI; replay `CIA+FCO` 0 query. Build validata `0.1.0-dev.29`.

**Fix UI post-v3.5 CHIUSO E VALIDATO:** build `0.1.0-dev.31`; Date fisse PASS, Weekend/Aeroporto PASS con `Aeroporto` su una riga, N notti raggiungibile da Weekend PASS. Weekend/Ovunque e Weekend/Paese non riverificati con screenshot dedicati; rischio residuo accettato basso per natura strutturale del fix.

**v3.6 implementazione:** nuovo `WeekendVerificationEngine` condiviso anche dal percorso aeroporto specifico. Per Ovunque/Paese la Discovery resta Travel Explore; i candidati sono ordinati per prezzo e si verifica **un solo candidato più economico eleggibile**, senza fallback automatico. Elegibilità: IATA a 3 lettere + date interpretabili + almeno un pattern weekend valido. Per Paese è obbligatorio anche `AirportDirectory.find(IATA)?.countryCode == selectedCountry.iso2`; candidati sconosciuti/mismatch vengono saltati localmente senza query.

**Verifica v3.6:** Google Flights riceve `departure_id=<origini canonicalizzate>` e `arrival_id=<IATA candidato>`. Pattern max 2: venerdì sera→domenica sera; sabato mattina→lunedì. Se entrambi danno risultati si sceglie il prezzo verificato più basso. Se il candidato non verifica, STOP: niente secondo candidato automatico; UI mantiene Discovery, mostra candidato tentato + messaggio. Quando riesce, card `Weekend verificato ✓` sopra Discovery.

**Cache Verifica v3.6:** nessuna migrazione Room, schema 3. Discovery e Verifica sono entry indipendenti in `weekend_search_cache`. Key: `WEEKEND_VERIFY|<origini>|<scope ANYWHERE/COUNTRY:XX/AIRPORT:IATA>|<periodo>|<IATA>|<monthKey>|<Explore outbound>:<Explore return>|<SUCCESS|NO_MATCH>`. Si cacheano solo outcome terminali `SUCCESS` e `NO_MATCH`; HTTP/provider/network/structure/quota errors non vengono cacheati. Replay con entrambe fresche = 0 query. Discovery e Verifica possono scadere indipendentemente; `forceRefresh` bypassa entrambe.

**Diagnostica WEEKEND_VERIFY v3.6:** classificazioni `HTTP_ERROR`, `BODY_MISSING`, `PROVIDER_ERROR`, `STRUCTURE_ANOMALY`, `NO_OPPORTUNITIES`, `NETWORK_ERROR`, `SUCCESS`; inoltre `CANDIDATE_SELECTED`/`CANDIDATE_SKIPPED`. Account API resta guard quota gratuita. Riserva 5.

**Budget v3.6:** singolo mese/cache miss = 1 Travel Explore + max 2 Google Flights = **max 3 query SerpApi di ricerca**. N mesi = N Explore + max 2 Google Flights complessive. Nessuna scansione di più destinazioni.

**File applicativi v3.6:** `AnywhereWeekendSearchRepository.kt`, `CountryWeekendSearchRepository.kt`, `WeekendSearchRepository.kt`, nuovo `WeekendVerificationEngine.kt`, `WeekendSearchScreen.kt`. Nessuna modifica a `SerpApiService`, DTO network, Room schema/DAO o SearchAPI.

**CI v3.6:** run #36 fallita in compile per `NON_LOCAL_SUSPENSION_POINT`: `diagnostics.log` suspend dentro `Sequence.mapNotNull` della guard Country. Fix con ciclo `for` suspend-safe, comportamento invariato. Run autorevole **#37 = SUCCESS**, build **`0.1.0-dev.37`**, commit applicativo/finale di build `86b3c5aac180c335c7513485896a876d198e4b0e`; `BUILD SUCCESSFUL in 2m 19s`, 49 task. Firma v2/v3 valida, 1 signer, fingerprint canonico `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`. APK SHA-256 `4471b0adf32ee74cba53a864c7d21f6a9f0886eba433e9a67f63f12c08121634`, size 9,949,571 byte. Release `dev-latest` verificata su version/commit/run corretti.

**v3.6 CHIUSA E VALIDATA SU DEVICE REALE — 2026-09-16:** build `0.1.0-dev.37`. Tre verifiche reali PASS: 1) **Weekend → Ovunque:** confermato il flusso Discovery Explore → verifica di un singolo candidato e, in caso di verifica fallita, **nessun fallback automatico** verso il candidato successivo; 2) **Weekend → Paese:** verifica Google Flights riuscita e replay confermato a **0 nuove query**; 3) **canonicalizzazione cache origini:** test incrociato `MXP/BGY` vs `BGY/MXP` conferma cache hit con ordine origini invertito e **0 nuove query**. Questi test chiudono i blocker funzionali previsti per v3.6.

**v3.7-B Maps Intent — IMPLEMENTATA, CI/RELEASE PASS; TEST DEVICE PENDENTE:** commit applicativo `741af21c6a675eb1ce2d2144703429c5d3337bb5`. Nuovo helper UI `MapsIntent.kt`; pulsante `Apri in Maps` sulle card `Weekend verificato ✓` (Aeroporto/Ovunque/Paese, entrambi i pattern) e `N notti verificato ✓`. Destinazione costruita da IATA + `AirportDirectory`; se l'IATA verificato non è catalogato usa `<IATA> airport`. Primo tentativo: `ACTION_VIEW` su URI `geo:0,0?q=...` con package Google Maps. Se Maps non è installata: fallback `ACTION_VIEW` a `https://www.google.com/maps/search/?api=1&query=...`. Nessuna nuova dipendenza, nessun Maps SDK/Places API, nessun Manifest change, nessuna modifica Discovery/Verifica/cache/provider: **0 nuove query e rischio quota nullo**. Nessun unit test aggiunto perché la repo non ha `app/src/test`/dipendenze test e la spec non impone test UI per questa feature.

**CI v3.7-B:** run **#38 = SUCCESS**, build **`0.1.0-dev.38`**, commit `741af21c6a675eb1ce2d2144703429c5d3337bb5`; `BUILD SUCCESSFUL in 2m 2s`, 49 task. Firma v2/v3 valida, 1 signer, fingerprint canonico invariato `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`. APK SHA-256 `65ddf4e90092b7781eaeea99a681d7dc7a1b888e580eb593f9974edda8fd13c5`, size 9,965,955 byte. Release `dev-latest` verificata su version `0.1.0-dev.38`, commit e run #38.

**Stato:** v3.6 **CHIUSA E VALIDATA su device reale**. v3.7-B **implementata + CI/Release PASS**, da validare sul device reale per apertura Maps/fallback. **v3.7-A filtri post-verifica (scali/compagnia/durata) è il prossimo step pianificato ma NON iniziato**; deve privilegiare il riuso dei dati già verificati senza moltiplicare le query.

**Regola:** dopo ogni decisione/modifica/step completato aggiornare `PROJECT_SPEC.md` e `SESSION_HANDOFF.md`. Nuova chat: leggere entrambi prima di procedere.
