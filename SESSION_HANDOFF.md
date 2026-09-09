# SESSION_HANDOFF

**Fase attuale:** Fase 0 — infrastruttura GitHub-only; prima build CI in avvio/verifica.

**Stato:** il progetto Compose minimo VolaFlex e il workflow `.github/workflows/android-build.yml` sono presenti su `main` nella repo privata `archimede-projects/flexi-flights`.

**Ultimo step completato:** installato il workflow `Android Build` con Android SDK configurato tramite `android-actions/setup-android@v4`, build Gradle 9.5.0 e pubblicazione su Release `dev-latest`.

**Prossimo step immediato:** verificare il run di GitHub Actions, leggere i log e correggere finché il job è verde e la Release `VolaFlex - Development latest` contiene `VolaFlex-dev.apk`.

**Problemi aperti:** prima build non ancora confermata verde; Release/APK non ancora verificati; APK non ancora installato sul telefono; firma debug stabile da aggiungere dopo la validazione della pipeline.

**Regola:** dopo ogni decisione, modifica o step completato aggiornare sia `PROJECT_SPEC.md` sia questo file.

**Nuova chat:** leggi `SESSION_HANDOFF.md` e `PROJECT_SPEC.md` dalla repository, poi conferma di aver capito lo stato prima di procedere.

**Promemoria:** leggi `PROJECT_SPEC.md` per il contesto completo prima di rispondere o prendere decisioni tecniche.
