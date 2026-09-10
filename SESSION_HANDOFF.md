# SESSION_HANDOFF

**Fase attuale:** v2 — Date flessibili. **v1 Fondamenta, v2.1 Weekend Discovery e v2.2 Weekend Verifica precisa sono CHIUSE e validate sul telefono reale.** v2.3 N notti / ±X è implementata sul branch tecnico e attende CI + test reale.

**Ultimo successo reale — v2.2 chiusa:** test `FCO → MAD`, novembre 2026. Travel Explore Discovery: **57 EUR indicativi**, range **26/11/2026 → 30/11/2026**. Verifica Google Flights: **Pattern B sabato→lunedì**, Wizz Air, **54 EUR round-trip**, partenza andata **06:00**, correttamente dentro `outbound_times=5,11`, **0 scali**. Consumo iniziale **3 query SerpApi**, esattamente il budget previsto. Ripetizione identica entro TTL: Discovery + Verifica completamente da cache, **0 nuove query**. v2.2 è quindi CHIUSA.

**v2.3 N notti / ±X — implementazione:** terza modalità `N notti` nella schermata Ricerca. Input: partenza e destinazione singole, 1–30 notti, data target, flessibilità ±0–60 giorni. Strategia doppio binario: fino a 10 partenze candidate → SerpApi Google Flights preciso ed esaustivo; oltre 10 con SearchAPI.io configurata → `google_flights_calendar` in blocchi massimi di 14 partenze (`14×14=196` combinazioni), filtro locale `ritorno = partenza + N`, poi 1 verifica SerpApi precisa; oltre 10 senza SearchAPI.io → modalità risparmio quota con 5 date distribuite + fino a 2 vicine alla migliore, massimo 7 query SerpApi. Le chiamate Serp del ramo diretto/campionato sono già verifiche Google Flights precise e non vengono duplicate inutilmente.

**SearchAPI.io:** la chiave resta opzionale ed è già prevista nelle Impostazioni/DataStore; ora esiste anche il getter runtime. Endpoint Calendar ufficiale `/api/v1/search?engine=google_flights_calendar`, autenticazione `api_key`, valuta EUR. Il limite round-trip di 200 combinazioni è rispettato usando chunk da 14. Nuovo diagnostico: `SEARCHAPI_CALENDAR`.

**Cache/Room v2.3:** nuovo `nights_search_cache`, TTL 4h. Schema Room passa **2→3** con migrazione additiva; cache/diagnostica precedenti devono restare intatte. La chiave cache include anche la strategia, così una futura configurazione SearchAPI.io non riusa per errore una vecchia cache euristica. Cache hit identico deve fare 0 Account API, 0 Calendar, 0 Google Flights.

**Quota v2.3:** prima dei batch live viene letta Account API SerpApi e si preserva riserva 5. Range piccolo: serve `5 + D`; campionamento: `5 + max 7`; Calendar: `5 + 1` SerpApi finale. Le chiamate Calendar consumano il pool SearchAPI.io, non la quota SerpApi.

**Ultimo CI pubblicato prima di v2.3:** run #19 SUCCESS, build `0.1.0-dev.19`, fingerprint firma invariato `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`. I due commit tecnici temporanei successivi non contengono feature e saranno superseduti dalla build v2.3 finale.

**Prossimo step immediato:** portare il branch v2.3 su `main`, verificare CI/firma/Release. Poi primo test telefono **senza configurare SearchAPI.io**, per validare il ramo SerpApi campionato e la migrazione Room 2→3; ripetizione identica entro 4h deve consumare 0 query. In un secondo test futuro, configurare SearchAPI.io e validare il ramo Calendar.

**Regola:** dopo ogni decisione/modifica/step completato aggiornare sia `PROJECT_SPEC.md` sia questo file. Nuova chat: leggere prima entrambi i file e confermare lo stato.
