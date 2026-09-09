# SESSION_HANDOFF

**Fase attuale:** v1 — Fondamenta. Fase 0 completata al 100%; firma APK stabile validata end-to-end nel mondo reale. Primo step funzionale v1 in corso: schermata Impostazioni API key.

**Ultimo step completato con successo:** il proprietario ha installato `0.1.0-dev.5` direttamente sopra `0.1.0-dev.4` usando “Aggiorna”, senza disinstallare. L'app si apre correttamente e mostra `Versione: 0.1.0-dev.5`. La firma persistente è quindi chiusa e validata sia in CI sia sul telefono. Fingerprint SHA-256 di riferimento: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`.

**Step in corso:** implementare Home + schermata `Impostazioni`, salvataggio locale delle API key con DataStore Preferences e navigazione Compose. Nessuna chiamata di rete in questo step.

**Prossimo step immediato:** completare implementazione, build CI firmata, pubblicazione `VolaFlex-dev.apk`, poi testare sul telefono navigazione, salvataggio e indicatori “configurata”.

**Problemi/rischi aperti:** nessun problema sulla firma. Le API key saranno salvate in DataStore nello storage privato dell'app; non saranno nel codice/repository. DataStore Preferences non è cifratura del dato a riposo: il rischio su dispositivo compromesso/rootato resta accettato per l'APK personale. SearchAPI.io resta opzionale.

**Nota operativa Codespaces/GitHub:** `gh secret set` nel Codespace ha restituito `403 Resource not accessible by integration` per permessi insufficienti del token; i 4 Secrets sono stati creati con successo via interfaccia web GitHub. Se ricapita un 403 simile, non assumere che il comando CLI abbia permessi di amministrazione repository/secrets.

**Regola:** dopo ogni decisione, modifica o step completato aggiornare sia `PROJECT_SPEC.md` sia questo file.

**Nuova chat:** leggi `SESSION_HANDOFF.md` e `PROJECT_SPEC.md` dalla repository, poi conferma di aver capito lo stato prima di procedere.

**Promemoria:** leggi `PROJECT_SPEC.md` per il contesto completo prima di rispondere o prendere decisioni tecniche.
