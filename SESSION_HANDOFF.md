# SESSION_HANDOFF

**Fase attuale:** v1 — Fondamenta. Fase 0, firma persistente, Impostazioni API key e prima ricerca reale SerpApi sono confermate nel mondo reale. Gli ultimi tre elementi v1 (IATA guard, Room cache 4h, Diagnostica) sono implementati e CI verde; manca solo il test sul telefono.

**Ultimo successo reale:** build `0.1.0-dev.13`, ricerca `FCO → MAD`, 16–19 ottobre 2026: Ryanair 104 EUR round-trip, 0 scali andata, quota live 131/250 prima della ricerca. Nessun crash; firma invariata.

**Ultimo successo CI:** GitHub Actions run #15 = SUCCESS, build `0.1.0-dev.15`. Room 2.8.4 + KSP 2.3.11 compilano correttamente; `assembleRelease`, firma e Release riusciti. Fingerprint invariato: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`.

**Implementato ora:** directory locale strutturata di circa 180–200 aeroporti (`IATA/nome/città/ISO country`); warning per IATA sconosciuto con `Correggi` / `Cerca comunque`; Room cache chiave `origine|destinazione|andata|ritorno`, TTL 4h; cache hit salta Account API e Google Flights; `Aggiorna comunque` forza refresh; Diagnostica persistente ultimi 20 eventi (`SERPAPI_ACCOUNT`, `GOOGLE_FLIGHTS`, `CACHE`, `QUOTA_GUARD`) con timestamp/status HTTP/messaggio e pulsante `Copia diagnostica`. Nessuna API key viene registrata.

**Prossimo step immediato:** installare `0.1.0-dev.15` sopra la versione corrente e validare: (1) `FC0 → MAD` mostra warning senza query; premere `Correggi`; (2) fare `FCO → MAD` con date fisse una prima volta; (3) ripetere identico entro 4h e verificare `Risultato da cache — aggiornato alle HH:MM`; (4) Impostazioni → Diagnostica, verificare gli eventi e `Copia diagnostica`.

**Consumo test previsto:** warning `FC0` = 0 query; prima ricerca valida = 1 Google Flights + Account API gratuita; seconda identica da cache = 0 query. Non premere `Aggiorna comunque` salvo test esplicito, perché aggiunge una nuova query voli.

**Nota CI:** run #14 è stato cancellato automaticamente da `cancel-in-progress` quando è partito il run #15 sul commit funzionale; non era un errore di codice.

**Problemi aperti:** nessun problema noto di build/firma. Da verificare solo comportamento reale di IATA warning, cache e clipboard diagnostica sul telefono. Il dettaglio ritorno via `departure_token`, filtri avanzati, weekend/date flessibili e multi-aeroporto restano successivi.

**Regola:** dopo ogni decisione, modifica o step completato aggiornare sia `PROJECT_SPEC.md` sia questo file.

**Nuova chat:** leggi `SESSION_HANDOFF.md` e `PROJECT_SPEC.md` dalla repository, poi conferma di aver capito lo stato prima di procedere.

**Promemoria:** leggi `PROJECT_SPEC.md` per il contesto completo prima di rispondere o prendere decisioni tecniche.
