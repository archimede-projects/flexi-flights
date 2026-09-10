# SESSION_HANDOFF

**Fase attuale:** v3 — Geografia avanzata. **Fase 0, v1 Fondamenta, l'intera v2 Date flessibili, v3.1 e v3.2 sono CHIUSE e validate sul telefono reale. v3.3 Multi-aeroporto dentro N notti/±X è IMPLEMENTATA, CI verde e Release pubblicata; manca la validazione sul telefono reale.**

**Stato completato:** Fase 0 infrastruttura/firma persistente = PASS. v1 Fondamenta = PASS (API key locali, prima ricerca reale, IATA anti-typo, Room cache 4h, Diagnostica/clipboard). v2.1 Weekend Discovery = PASS. v2.2 Weekend Verifica precisa = PASS. v2.3 N notti ramo SerpApi = PASS. v2.3 ramo SearchAPI.io Calendar = PASS. **v2 COMPLETAMENTE CHIUSA.**

**v3 architettura approvata:** sequenza `v3.1 → v3.2 → v3.3 → v3.4 → v3.5 → v3.6 → v3.7`. Origine `Airports(max 3)`; destinazione alternativa esclusiva fra `Airports(max 3)`, `Anywhere`, `Country`; niente destinazioni composite. Preservare `DISCOVERY → VERIFICA → DETTAGLIO`, supporto multi-airport nativo, cache canonicalizzate, Account API live, niente prodotto cartesiano brute-force.

**v3.1 CHIUSA E VALIDATA — test reale:** `FCO + CIA + MXP → MAD`, 20/11/2026→23/11/2026: una sola Google Flights, vincitore **MXP**, **60 EUR**. UI/add-remove, anti-typo e duplicati PASS. Replay con origini riordinate `MXP + FCO + CIA` → **CACHE HIT**, 0 query, stesso prezzo/vincitore.

**v3.2 CHIUSA E VALIDATA — test reale 2026-09-10:** UI multi-destinazione PASS; duplicati destinazione bloccati PASS; sovrapposizione origine/destinazione bloccata PASS. Live `FCO + CIA → BCN + MAD + VLC` con date fisse: **1 sola query Google Flights**, vincitore **FCO→BCN**, **48 EUR**. Diagnostica ha confermato una sola richiesta provider e liste canonicalizzate. Replay riordinando ENTRAMBI gli assi → **CACHE HIT**, 0 nuove query. Canonicalizzazione origini+destinazioni validata end-to-end.

**v3.3 implementazione:** solo modalità `N notti`. UI ora accetta 1–3 origini e 1–3 destinazioni con stesso pattern add/remove della modalità Date fisse, IATA guard, duplicati separati e blocco sovrapposizioni. Repository canonicalizza entrambe le liste e le passa comma-separated a SerpApi Google Flights, SearchAPI.io Calendar e fallback SerpApi 5+2. Cache key: origini + destinazioni + N notti + target + ±X + strategia (`SERP_EXHAUSTIVE`/`SERP_SAMPLE`/`SEARCHAPI_CALENDAR`). Nessuna migrazione Room: `nights_search_cache` resta schema 3 e memorizza le liste nei campi stringa esistenti; il JSON risultato aggiunge `departureAirportId` e `arrivalAirportId` con default retrocompatibili. Il risultato mostra origine e destinazione effettive dal primo/ultimo segmento dell'andata. Calendar mantiene chunking max 14 partenze/date per blocco (14×14=196 combinazioni temporali), senza moltiplicare per gli aeroporti.

**CI v3.3:** GitHub Actions run **#25 = SUCCESS**, build **`0.1.0-dev.25`**, commit applicativo `8ebfa88085d8db9a9240fe91b919df02adfa137a`. `BUILD SUCCESSFUL in 2m 13s`; KSP/compile/lint/assembleRelease, zipalign, apksigner e pubblicazione `dev-latest` tutti verdi. Firma v2/v3 valida, 1 signer, fingerprint canonico invariato `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`. APK SHA-256 `12ca144a1fdfcb5adfbf30d36b16e3fdf8a402d17762b078b44cb93be2c76084`, size 9,884,035 byte.

**Provider v3.3 verificati su docs ufficiali:** SerpApi Google Flights supporta `departure_id` e `arrival_id` multipli comma-separated. SearchAPI.io Google Flights Calendar supporta anch'esso più aeroporti/location comma-separated per entrambi i parametri. Quindi 2×3 aeroporti restano una query per data SerpApi o un blocco Calendar, non 6.

**Test telefono v3.3 pianificati:** (A) ramo SerpApi esaustivo con `FCO+CIA → BCN+VLC`, 3 notti, target 10/12/2026, ±1 = 3 candidate: massimo 3 SerpApi; replay con entrambi gli assi riordinati deve essere CACHE HIT 0. (B) ramo Calendar con `FCO+CIA → MAD+BCN+VLC`, 3 notti, target 15/01/2027, ±7 = 15 candidate: attesi 2 SearchAPI Calendar + 1 SerpApi finale; replay riordinato deve essere CACHE HIT 0. Verificare sempre origine/destinazione effettive e Diagnostica. Il fallback multi-aeroporto `SERP_SAMPLE` con SearchAPI assente riusa la stessa funzione exact-date già esercitata dal ramo esaustivo e l'algoritmo 5+2 già validato in v2; non spendere altre 5–7 query solo per duplicare il test salvo anomalia.

**Rischio Explore per v3.4/v3.5:** release notes 2026 mostrano fix recenti su Weekend `travel_duration`, `max_duration`, `stops` e, il 09/07/2026, ricerche valide che potevano tornare vuote. Per Anywhere/Paese una risposta Explore vuota/anomala non va trattata automaticamente come “nessun volo”: implementare diagnostica/error handling robusti.

**Non implementato ancora:** v3.4 Anywhere, v3.5 Country/KGMID, v3.6 verifica geografica Weekend, v3.7 integrazioni estreme. **Non avanzare a v3.4 prima del PASS reale di v3.3.**

**Regola:** dopo ogni decisione/modifica/step completato aggiornare sia `PROJECT_SPEC.md` sia questo file. Nuova chat: leggere prima entrambi e confermare lo stato.
