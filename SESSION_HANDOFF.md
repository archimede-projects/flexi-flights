# SESSION_HANDOFF

**Fase attuale:** v2 — Date flessibili. **v1 “Fondamenta” è CHIUSA e validata al 100% sul telefono reale.** Anti-typo IATA, Room cache 4h e Diagnostica hanno superato tutti i test; l'intero round finale ha consumato 1 sola query SerpApi, esattamente coerente con la stima.

**Ultimo step completato:** v2.1 “Weekend flessibile — Discovery con SerpApi Google Travel Explore” implementato e validato in CI. GitHub Actions run #16 = SUCCESS, build `0.1.0-dev.16`; KSP/Kotlin/assembleRelease/firma/Release riusciti. Fingerprint invariato: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`.

**Implementato v2.1:** modalità `Ricerca → Weekend`; un'origine + una destinazione; scelta singolo mese tra i prossimi 6 oppure `Prossimi 2 mesi`/`Prossimi 3 mesi`; `engine=google_travel_explore`, `travel_duration=1` Weekend; 1 query Explore per mese; Account API live prima del batch; riserva minima 5 query; candidati con date/prezzo/destinazione; Diagnostica `TRAVEL_EXPLORE`; Room cache 4h con chiave origine+destinazione+periodo e `Aggiorna comunque`; database Room v1→v2 con migrazione esplicita che aggiunge `weekend_search_cache`.

**Prossimo step immediato:** installare `0.1.0-dev.16` sopra la build attuale senza disinstallare e testare `FCO → MAD`, modalità Weekend, **ottobre 2026**. Atteso: un candidato Discovery con andata/ritorno e prezzo indicativo; Diagnostica `SERPAPI_ACCOUNT` + `TRAVEL_EXPLORE`; ripetizione identica entro 4h da cache.

**Consumo test previsto:** prima ricerca su un solo mese = Account API gratuita + **1 query Travel Explore**; ripetizione identica da cache = **0 query**. Totale atteso del round: **1 query SerpApi**. `Prossimi 2 mesi` costa fino a 2 query Explore; `Prossimi 3 mesi` fino a 3.

**Limite intenzionale:** v2.1 è solo Discovery. Non verifica ancora venerdì sera/sabato mattina e domenica sera/lunedì con Google Flights; questo è il prossimo raffinamento dopo il test reale. SearchAPI.io Calendar non entra ancora: sarà usato nel sotto-step N notti/±X.

**Problemi aperti:** nessun problema noto CI/firma. Da validare sul telefono: migrazione Room 1→2 e risposta reale Travel Explore. Se compare un errore, usare Impostazioni → Diagnostica → `Copia diagnostica` e incollare il testo in chat senza API key.

**Regola:** dopo ogni decisione, modifica o step completato aggiornare sia `PROJECT_SPEC.md` sia questo file.

**Nuova chat:** leggi `SESSION_HANDOFF.md` e `PROJECT_SPEC.md` dalla repository, poi conferma di aver capito lo stato prima di procedere.

**Promemoria:** leggi `PROJECT_SPEC.md` per il contesto completo prima di rispondere o prendere decisioni tecniche.
