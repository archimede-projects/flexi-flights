# SESSION_HANDOFF

**Fase attuale:** v1 — Fondamenta. Fase 0 completata al 100%; firma APK stabile tecnicamente completata e verificata in CI.

**Ultimo step completato con successo:** GitHub Actions run #4 ha compilato `assembleRelease`, eseguito `zipalign`, firmato con `apksigner` usando il keystore persistente ricostruito dai 4 GitHub Actions Secrets e verificato positivamente la firma. Release `VolaFlex - Development latest` aggiornata con `VolaFlex-dev.apk` versione `0.1.0-dev.4`.

**Fingerprint firma stabile:** SHA-256 certificato `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`.

**Prossimo step immediato:** migrazione una tantum sul telefono: disinstallare la vecchia `0.1.0-dev.3` firmata con la precedente debug key, installare `0.1.0-dev.4` firmata stabilmente, poi produrre una build successiva e verificare che si installi sopra senza disinstallazione.

**Problemi/rischi aperti:** la compatibilità di aggiornamento con la nuova firma non è ancora stata verificata sul telefono. Se il keystore stabile viene perso, non sarà più possibile aggiornare installazioni firmate con questo certificato senza disinstallazione/reinstallazione. Il keystore ha doppio backup personale esterno a GitHub; password conservata separatamente su carta.

**Nota operativa Codespaces/GitHub:** `gh secret set` nel Codespace ha restituito `403 Resource not accessible by integration` per permessi insufficienti del token; i 4 Secrets sono stati creati con successo via interfaccia web GitHub. Se ricapita un 403 simile, non assumere che il comando CLI abbia permessi di amministrazione repository/secrets.

**Regola:** dopo ogni decisione, modifica o step completato aggiornare sia `PROJECT_SPEC.md` sia questo file.

**Nuova chat:** leggi `SESSION_HANDOFF.md` e `PROJECT_SPEC.md` dalla repository, poi conferma di aver capito lo stato prima di procedere.

**Promemoria:** leggi `PROJECT_SPEC.md` per il contesto completo prima di rispondere o prendere decisioni tecniche.
