# SESSION_HANDOFF

**Fase attuale:** v3 — Geografia avanzata, **solo pianificazione architetturale in questo turno: nessun codice v3 implementato ancora**. Fase 0, v1 Fondamenta e l'intera v2 Date flessibili sono CHIUSE e validate sul telefono reale.

**Stato completato:** Fase 0 infrastruttura/firma persistente = PASS. v1 Fondamenta = PASS (API key locali, prima ricerca reale, IATA anti-typo, Room cache 4h, Diagnostica/clipboard). v2.1 Weekend Discovery = PASS (`FCO→MAD`, ottobre 2026, Explore 01/10→05/10, 95 EUR, 1 query, cache PASS). v2.2 Weekend Verifica precisa = PASS (`FCO→MAD`, novembre 2026, Explore 57 EUR → Pattern B Wizz Air 54 EUR, partenza 06:00 dentro `5,11`, 0 scali; 3 query iniziali, replay cache 0). v2.3 N notti ramo SerpApi = PASS (`FCO→MAD`, 3 notti, target 25/10/2026, ±5; 11 candidate, 7 valutate; Ryanair 28→31/10, 53 EUR, 06:25→09:00, 0 scali; replay cache 0). v2.3 ramo SearchAPI.io Calendar = PASS sul telefono reale, inclusa cache; con questo test l'intera **v2 è COMPLETAMENTE CHIUSA**.

**Build stabile corrente:** `0.1.0-dev.22`, GitHub Actions run #22 SUCCESS, firma v2/v3 valida, fingerprint canonico invariato `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`, APK SHA-256 `880ba101cc7c1416846a30edeb78ce306621b44e4c02070bb8e5813595131843`.

**v3 da concordare prima del codice:** multi-origine fino a 3 aeroporti, multi-destinazione fino a 3 aeroporti, destinazione Ovunque, destinazione intero paese. SerpApi Google Flights supporta origini/destinazioni multiple separandole con virgole; Google Travel Explore supporta origini multiple e `arrival_area_id` per regioni/paesi. Per `arrival_area_id` serve un KGMID del paese: il catalogo IATA→paese già presente resta utile per validazione/classificazione, ma sarà necessario un piccolo catalogo locale separato `ISO paese → KGMID`.

**Architettura da preservare:** `UI → logica ricerca → provider → cache`, con `DISCOVERY → VERIFICA → DETTAGLIO`, Account API live prima dei batch costosi, riserva minima 5 query, cache 4h e Diagnostica senza chiavi. Le modalità destinazione devono essere modellate come alternative chiare: lista aeroporti, Ovunque oppure Paese; non sommare implicitamente “lista aeroporti + paese” nello stesso input.

**Prossimo step immediato:** approvare il piano v3 a sotto-step testabili prima di modificare il codice. Dopo approvazione, implementare solo v3.1 e validarlo sul telefono prima di avanzare.

**Regola:** dopo ogni decisione/modifica/step completato aggiornare sia `PROJECT_SPEC.md` sia questo file. Nuova chat: leggere prima entrambi i file e confermare lo stato.
