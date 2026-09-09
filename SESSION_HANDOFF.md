# SESSION_HANDOFF

**Fase attuale:** v1 — Fondamenta. Fase 0 e firma persistente chiuse; Impostazioni API key completate e testate sul telefono. Step v1.3 “prima ricerca reale SerpApi a date fisse” implementato e validato in CI; manca il primo test reale della chiamata dal telefono.

**Ultimo step completato con successo:** GitHub Actions run #13 ha compilato, firmato e pubblicato `VolaFlex-dev.apk` versione `0.1.0-dev.13`. `assembleRelease`, `zipalign` e `apksigner verify` sono riusciti. Fingerprint SHA-256 invariato: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`. Implementati permesso INTERNET, Retrofit/OkHttp/Kotlin Serialization, route Ricerca, input IATA testuale, date picker, Account API quota live, blocco con saldo <=5, una chiamata `google_flights` round-trip, loading, parsing del primo risultato ed error handling leggibile.

**Decisione quota:** la chiave SerpApi è condivisa con un altro progetto. La Account API live è la fonte di verità. In questo primo step di ricerca viene controllata prima di OGNI query voli e la ricerca viene bloccata se il saldo è <=5 o non verificabile.

**Prossimo step immediato:** installare `0.1.0-dev.13` sopra la versione corrente senza disinstallare e testare `FCO → MAD` con due date future. Atteso: quota live >5, una sola query Google Flights, card con prezzo round-trip più compagnia/orari/scali dell'andata. Se compare un errore, riportare esattamente il messaggio mostrato dalla UI.

**Nota round-trip:** il dettaglio del ritorno richiede una seconda chiamata con `departure_token`; non viene ancora eseguita per mantenere questo test a una sola query voli.

**Problemi aperti:** nessun problema CI o firma. Il comportamento reale SerpApi dal telefono non è ancora stato verificato. Non sono ancora implementati Room/cache, filtri avanzati, weekend/date flessibili o multi-aeroporto.

**Regola:** dopo ogni decisione, modifica o step completato aggiornare sia `PROJECT_SPEC.md` sia questo file.

**Nuova chat:** leggi `SESSION_HANDOFF.md` e `PROJECT_SPEC.md` dalla repository, poi conferma di aver capito lo stato prima di procedere.

**Promemoria:** leggi `PROJECT_SPEC.md` per il contesto completo prima di rispondere o prendere decisioni tecniche.
