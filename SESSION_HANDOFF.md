# SESSION_HANDOFF

**Fase attuale:** Fase 0 — infrastruttura GitHub-only quasi completata; CI e Release validate, manca solo il test sul telefono.

**Ultimo step completato con successo:** workflow `Android Build` verde su `main`. Android SDK 36 viene installato con `android-actions/setup-android@v4`; Gradle compila l'APK e GitHub pubblica/aggiorna la Release `VolaFlex - Development latest` con asset `VolaFlex-dev.apk`. Il workflow ignora modifiche solo a `PROJECT_SPEC.md` e `SESSION_HANDOFF.md` per non sprecare build/minuti CI.

**Prossimo step immediato:** scaricare `VolaFlex-dev.apk` dalla Release `dev-latest`, installarlo sul telefono Android e confermare che compaiano `VolaFlex - Build OK` e la versione dell'app.

**Problemi aperti:** test/installazione su telefono non ancora confermati; firma debug stabile da aggiungere dopo la chiusura della Fase 0.

**Regola:** dopo ogni decisione, modifica o step completato aggiornare sia `PROJECT_SPEC.md` sia questo file.

**Nuova chat:** leggi `SESSION_HANDOFF.md` e `PROJECT_SPEC.md` dalla repository, poi conferma di aver capito lo stato prima di procedere.

**Promemoria:** leggi `PROJECT_SPEC.md` per il contesto completo prima di rispondere o prendere decisioni tecniche.
