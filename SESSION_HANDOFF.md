# SESSION_HANDOFF

**Fase attuale:** v1 — Fondamenta. Fase 0 completata al 100%; firma APK stabile verificata in CI su due build consecutive. Siamo in attesa della prova finale di aggiornamento sul telefono.

**Ultimo step completato con successo:** GitHub Actions run #5 ha prodotto `VolaFlex-dev.apk` versione `0.1.0-dev.5`. `assembleRelease`, `zipalign`, `apksigner` e `apksigner verify` sono tutti riusciti. Il fingerprint SHA-256 del certificato è identico alla build precedente: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`. La Release `VolaFlex - Development latest` è stata aggiornata correttamente.

**Stato telefono:** `0.1.0-dev.4` è installata e funzionante dopo la reinstallazione una tantum necessaria per passare dalla vecchia firma debug alla nuova firma stabile.

**Prossimo step immediato:** scaricare `0.1.0-dev.5` e installarla DIRETTAMENTE sopra `0.1.0-dev.4`, senza disinstallare. Se Android accetta l'aggiornamento e l'app mostra `Versione: 0.1.0-dev.5`, la firma persistente è validata end-to-end.

**Problemi/rischi aperti:** manca solo la conferma reale dell'aggiornamento sopra la versione precedente senza disinstallazione. Se il keystore stabile viene perso, non sarà più possibile aggiornare installazioni firmate con questo certificato senza disinstallazione/reinstallazione. Il keystore ha doppio backup personale esterno a GitHub; password conservata separatamente su carta.

**Nota operativa Codespaces/GitHub:** `gh secret set` nel Codespace ha restituito `403 Resource not accessible by integration` per permessi insufficienti del token; i 4 Secrets sono stati creati con successo via interfaccia web GitHub. Se ricapita un 403 simile, non assumere che il comando CLI abbia permessi di amministrazione repository/secrets.

**Regola:** dopo ogni decisione, modifica o step completato aggiornare sia `PROJECT_SPEC.md` sia questo file.

**Nuova chat:** leggi `SESSION_HANDOFF.md` e `PROJECT_SPEC.md` dalla repository, poi conferma di aver capito lo stato prima di procedere.

**Promemoria:** leggi `PROJECT_SPEC.md` per il contesto completo prima di rispondere o prendere decisioni tecniche.
