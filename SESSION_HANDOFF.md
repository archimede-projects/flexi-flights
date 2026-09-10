# SESSION_HANDOFF

**Fase attuale:** v2 — Date flessibili. **v1 “Fondamenta” è CHIUSA e validata al 100% sul telefono reale. v2.1 Weekend Discovery è CHIUSA e validata sul telefono reale.**

**Ultimo successo reale:** v2.1 `FCO → MAD`, ottobre 2026: Travel Explore ha restituito candidato `01/10/2026 → 05/10/2026` a 95 EUR; Room migration 1→2 PASS senza perdita dati; cache/diagnostica v1 preservate; cache Weekend PASS; consumo osservato **1 query**, uguale alla stima. Osservazione chiave: `travel_duration=1` può restituire un intervallo lungo (nel test giovedì→lunedì, 4 notti), quindi Explore è solo Discovery e non garantisce il weekend breve richiesto.

**Implementato ora — v2.2 Weekend Verifica precisa:** dopo la Discovery si sceglie solo il candidato Explore più economico e si verificano al massimo 2 pattern Google Flights: `venerdì 17:00–23:59 → domenica 17:00–23:59` e `sabato 05:00–11:59 → lunedì`. Prima della Verifica viene rifatta Account API live e viene protetta la riserva minima di 5 query. Nuovo evento diagnostico `WEEKEND_VERIFY`. La UI mostra una card distinta `Weekend verificato ✓` con date finali, prezzo verificato, compagnia/orari esatti dell'andata, scali andata e fascia ritorno applicata. Il dettaglio esatto del volo di ritorno resta rinviato perché richiede `departure_token` e una query aggiuntiva.

**Cache v2.2:** nessuna migrazione Room 2→3. `VerifiedWeekendResult` viene salvato nel JSON già presente in `weekend_search_cache`, con campi retrocompatibili. Cache v2.2 verificata entro 4h = 0 query; cache v2.1 fresca senza verifica = Explore saltato, si esegue solo la Verifica.

**Ultimo successo CI:** GitHub Actions run #17 = **SUCCESS**, build `0.1.0-dev.17`; `compileReleaseKotlin`, `assembleRelease`, zipalign, apksigner e Release tutti verdi. Fingerprint invariato: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`. APK SHA-256: `f5999c86a80615a7bc80dedfe79e283c6f60cfc14aee6077af499e7b39984821`.

**Quota v2.2 su cache miss completa:** 1 mese = max 1 Explore + 2 Google Flights = **3 query**; 2 mesi = max 4; 3 mesi = max 5. Account API gratuita. Se la quota per la Verifica è insufficiente/non leggibile, mostrare comunque il candidato Explore indicativo e non consumare le query di verifica.

**Prossimo step immediato:** installare `0.1.0-dev.17` sopra la build corrente senza disinstallare e testare `FCO → MAD`, modalità Weekend, **novembre 2026** (mese diverso da ottobre per forzare Discovery + Verifica completa). Atteso: card `Weekend verificato ✓` con date finali venerdì→domenica oppure sabato→lunedì; Diagnostica con `TRAVEL_EXPLORE` + `WEEKEND_VERIFY`; ripetizione identica entro 4h interamente da cache, 0 query.

**Problemi aperti:** v2.2 è compilata/firmata/pubblicata ma non ancora validata sul telefono. Il ritorno esatto via `departure_token`, N notti/±X con SearchAPI.io Calendar, range ampi, multi-aeroporto e filtri avanzati restano successivi.

**Regola:** dopo ogni decisione, modifica o step completato aggiornare sia `PROJECT_SPEC.md` sia questo file.

**Nuova chat:** leggi `SESSION_HANDOFF.md` e `PROJECT_SPEC.md` dalla repository, poi conferma di aver capito lo stato prima di procedere.

**Promemoria:** leggi `PROJECT_SPEC.md` per il contesto completo prima di rispondere o prendere decisioni tecniche.
