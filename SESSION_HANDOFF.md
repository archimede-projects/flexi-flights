# SESSION_HANDOFF

**Fase attuale:** v2 — Date flessibili. **v1 Fondamenta, v2.1 Weekend Discovery e v2.2 Weekend Verifica precisa sono CHIUSE e validate sul telefono reale.** v2.3 N notti / ±X è implementata, compilata, firmata e pubblicata; manca la validazione runtime sul telefono.

**Ultimo successo reale — v2.2 chiusa:** test `FCO → MAD`, novembre 2026. Travel Explore Discovery: **57 EUR indicativi**, range **26/11/2026 → 30/11/2026**. Verifica Google Flights: **Pattern B sabato→lunedì**, Wizz Air, **54 EUR round-trip**, partenza andata **06:00**, correttamente dentro `outbound_times=5,11`, **0 scali**. Consumo iniziale **3 query SerpApi**, esattamente il budget previsto. Ripetizione identica entro TTL: Discovery + Verifica completamente da cache, **0 nuove query**. v2.2 è CHIUSA.

**v2.3 N notti / ±X — implementazione:** terza modalità `N notti` nella schermata Ricerca. Input: partenza e destinazione singole, 1–30 notti, data target, flessibilità ±0–60 giorni. Strategia doppio binario: fino a 10 partenze candidate → SerpApi Google Flights preciso ed esaustivo; oltre 10 con SearchAPI.io configurata → `google_flights_calendar` in blocchi massimi di 14 partenze (`14×14=196` combinazioni), filtro locale `ritorno = partenza + N`, poi 1 verifica SerpApi precisa; oltre 10 senza SearchAPI.io → modalità risparmio quota con 5 date distribuite + fino a 2 vicine alla migliore, massimo 7 query SerpApi. Le chiamate Serp del ramo diretto/campionato sono già Google Flights precise e non vengono duplicate inutilmente.

**SearchAPI.io:** chiave opzionale nelle Impostazioni/DataStore; getter runtime implementato. Endpoint Calendar `/api/v1/search?engine=google_flights_calendar`, autenticazione `api_key`, EUR. Limite round-trip 200 combinazioni rispettato con chunk da 14. Nuovo diagnostico: `SEARCHAPI_CALENDAR`.

**Cache/Room v2.3:** nuovo `nights_search_cache`, TTL 4h. Schema Room **2→3** con migrazione additiva; cache/diagnostica precedenti devono restare intatte. La cache include la strategia nella chiave. Cache hit identico: 0 Account API, 0 Calendar, 0 Google Flights.

**Quota v2.3:** Account API SerpApi prima dei batch live e riserva minima 5. Range piccolo: `5 + D`; campionamento: `5 + max 7`; Calendar: `5 + 1` SerpApi finale. Le chiamate Calendar usano il pool SearchAPI.io.

**CI v2.3:** GitHub Actions run **#22 = SUCCESS**, build **`0.1.0-dev.22`**, commit `b4e632da7723fe201a7d16be2ab66ceb382005e8`. `kspReleaseKotlin`, `compileReleaseKotlin`, `assembleRelease`, zipalign, apksigner e pubblicazione `dev-latest` tutti verdi. Firma v2/v3 valida, fingerprint invariato: `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`. APK SHA-256: `880ba101cc7c1416846a30edeb78ce306621b44e4c02070bb8e5813595131843`. I due commit tecnici temporanei precedenti sono superseduti e non sono la build da testare.

**Prossimo step immediato:** installare `0.1.0-dev.22` sopra la build corrente **senza disinstallare**. Prima verificare che Impostazioni e Diagnostica pregresse siano ancora presenti, validando Room 2→3. Primo test v2.3 **senza configurare SearchAPI.io**: `FCO → MAD`, **3 notti**, data target **15/12/2026**, **±5 giorni**. Sono 11 partenze candidate, quindi deve apparire la strategia SerpApi `modalità risparmio quota`; attese 5–7 query SerpApi Google Flights, 0 SearchAPI.io. Il ritorno vincente deve essere esattamente 3 giorni dopo l'andata. Ripetizione identica entro 4h: interamente da cache, 0 query provider. Diagnostica: `SERPAPI_ACCOUNT` + `GOOGLE_FLIGHTS`, nessun `SEARCHAPI_CALENDAR`; al replay `CACHE HIT`.

**Secondo test futuro:** solo dopo il primo PASS, configurare la chiave SearchAPI.io e usare un nuovo set di parametri con >10 partenze (es. ±7) per validare Calendar + 1 verifica SerpApi precisa.

**Regola:** dopo ogni decisione/modifica/step completato aggiornare sia `PROJECT_SPEC.md` sia questo file. Nuova chat: leggere prima entrambi i file e confermare lo stato.
