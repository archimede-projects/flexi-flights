# SESSION_HANDOFF

**Fase attuale:** v1 — Fondamenta. Fase 0 completata al 100%; primo step v1 = firma APK stabile persistente.

**Ultimo step completato con successo:** Fase 0 confermata end-to-end sul telefono: `VolaFlex-dev.apk` installato, schermata `VolaFlex - Build OK` visibile e versione `0.1.0-dev.3` corretta. Tutti gli 8 criteri della Fase 0 sono soddisfatti.

**Stato firma stabile:** workflow già aggiornato nella repository per `assembleRelease` + `zipalign` + `apksigner`, usando i Secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Il keystore viene ricostruito solo nel runner e cancellato a fine job.

**Prossimo step immediato:** il proprietario deve generare personalmente il keystore JKS in Codespaces, salvarne un backup esterno a GitHub, creare i quattro GitHub Actions Secrets e poi avviare manualmente `Android Build` con `workflow_dispatch`.

**Problemi/rischi aperti:** la build firmata stabile non è ancora stata eseguita perché i Secrets non sono ancora configurati. Il primo APK con la nuova firma NON può aggiornare `0.1.0-dev.3`: servirà una sola disinstallazione/reinstallazione. Se il keystore stabile viene perso, in futuro non sarà possibile aggiornare l'app già installata con la stessa identità di firma.

**Regola:** dopo ogni decisione, modifica o step completato aggiornare sia `PROJECT_SPEC.md` sia questo file.

**Nuova chat:** leggi `SESSION_HANDOFF.md` e `PROJECT_SPEC.md` dalla repository, poi conferma di aver capito lo stato prima di procedere.

**Promemoria:** leggi `PROJECT_SPEC.md` per il contesto completo prima di rispondere o prendere decisioni tecniche.
