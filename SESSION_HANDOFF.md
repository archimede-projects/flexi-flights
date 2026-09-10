# SESSION_HANDOFF

**Fase attuale:** v2 — Date flessibili. **v1 Fondamenta, v2.1 Weekend Discovery, v2.2 Weekend Verifica precisa e il ramo SerpApi di v2.3 N notti/±X sono CHIUSI e validati sul telefono reale.** Resta da validare il ramo SearchAPI.io Calendar di v2.3.

**Ultimo successo reale — v2.3 ramo SerpApi:** `FCO → MAD`, **3 notti**, target **25/10/2026**, **±5 giorni**. 11 date candidate, 7 valutate con strategia `SERP_SAMPLE` (5 iniziali + 2 vicine). Vincitore: **28/10/2026 → 31/10/2026**, Ryanair, **53 EUR round-trip**, andata **06:25 → 09:00**, **0 scali**. Ripetizione identica entro 4h: Discovery + Verifica completamente da cache, **0 nuove query**. Il ramo SerpApi di v2.3 è quindi CHIUSO E VALIDATO.

**v2.3 doppio binario già presente in `0.1.0-dev.22`:** `chooseStrategy()` usa SerpApi diretto se `candidateCount <= 10`; se `candidateCount > 10` e `getSearchApiKey()` restituisce una chiave non vuota usa `SEARCHAPI_CALENDAR`; altrimenti usa `SERP_SAMPLE`. La UI legge davvero la chiave SearchAPI.io dal DataStore e la passa al repository. La cache include la strategia nella chiave, quindi una vecchia cache `SERP_SAMPLE` non può mascherare un nuovo test `SEARCHAPI_CALENDAR`.

**SearchAPI.io ora configurata sul telefono:** chiave salvata localmente nelle Impostazioni; non è nel repository/chat. Calendar usa `google_flights_calendar`, chunk da massimo 14 partenze (`14×14=196`, sotto il limite 200), filtro locale `return = departure + N`, quindi 1 verifica SerpApi Google Flights precisa sul candidato migliore. Nuovo diagnostico `SEARCHAPI_CALENDAR`.

**Cache/Room:** schema 3 con `nights_search_cache`, TTL 4h. Il test v2.3 SerpApi ha esercitato con successo la build aggiornata e la cache N notti. Cache key include `SERP_EXHAUSTIVE`, `SERP_SAMPLE` o `SEARCHAPI_CALENDAR`.

**CI autorevole:** GitHub Actions run **#22 = SUCCESS**, build **`0.1.0-dev.22`**, commit `b4e632da7723fe201a7d16be2ab66ceb382005e8`; firma v2/v3 valida, fingerprint invariato `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`; APK SHA-256 `880ba101cc7c1416846a30edeb78ce306621b44e4c02070bb8e5813595131843`.

**Prossimo test immediato — ramo Calendar, nessuna nuova build necessaria:** `FCO → MAD`, **3 notti**, target **15/11/2026**, **±7 giorni**. Sono 15 partenze candidate, quindi con SearchAPI.io configurata la UI deve prevedere **2 blocchi Calendar + 1 verifica SerpApi**. Atteso risultato: `Strategia: SearchAPI.io Calendar → verifica SerpApi`; richiesta live: circa **2 SearchAPI.io Calendar + 1 SerpApi Google Flights**; Account API SerpApi gratuita. Diagnostica: `SERPAPI_ACCOUNT`, due eventi `SEARCHAPI_CALENDAR` (SUCCESS/EMPTY a seconda dei blocchi), poi un `GOOGLE_FLIGHTS` di verifica; non devono comparire 7 Google Flights come nel fallback campionato. Replay identico entro 4h: `CACHE HIT`, **0 query provider**.

**Regola:** dopo ogni decisione/modifica/step completato aggiornare sia `PROJECT_SPEC.md` sia questo file. Nuova chat: leggere prima entrambi i file e confermare lo stato.
