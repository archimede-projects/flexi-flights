# SESSION_HANDOFF

**Fase attuale:** v3 — Geografia avanzata. **Fase 0, v1 Fondamenta, l'intera v2 Date flessibili, v3.1 e v3.2 sono CHIUSE e validate sul telefono reale. v3.3 Multi-aeroporto dentro N notti/±X è IMPLEMENTATA su main; CI/test telefono da chiudere.**

**Stato completato:** Fase 0 infrastruttura/firma persistente = PASS. v1 Fondamenta = PASS (API key locali, prima ricerca reale, IATA anti-typo, Room cache 4h, Diagnostica/clipboard). v2.1 Weekend Discovery = PASS. v2.2 Weekend Verifica precisa = PASS. v2.3 N notti ramo SerpApi = PASS. v2.3 ramo SearchAPI.io Calendar = PASS. **v2 COMPLETAMENTE CHIUSA.**

**v3 architettura approvata:** sequenza `v3.1 → v3.2 → v3.3 → v3.4 → v3.5 → v3.6 → v3.7`. Origine `Airports(max 3)`; destinazione alternativa esclusiva fra `Airports(max 3)`, `Anywhere`, `Country`; niente destinazioni composite. Preservare `DISCOVERY → VERIFICA → DETTAGLIO`, supporto multi-airport nativo, cache canonicalizzate, Account API live, niente prodotto cartesiano brute-force.

**v3.1 CHIUSA E VALIDATA — test reale:** `FCO + CIA + MXP → MAD`, 20/11/2026→23/11/2026: una sola Google Flights, vincitore **MXP**, **60 EUR**. UI/add-remove, anti-typo e duplicati PASS. Replay con origini riordinate `MXP + FCO + CIA` → **CACHE HIT**, 0 query, stesso prezzo/vincitore.

**v3.2 CHIUSA E VALIDATA — test reale 2026-09-10:** UI multi-destinazione PASS; duplicati destinazione bloccati PASS; sovrapposizione origine/destinazione bloccata PASS. Live `FCO + CIA → BCN + MAD + VLC` con date fisse: **1 sola query Google Flights**, vincitore **FCO→BCN**, **48 EUR**. Diagnostica ha confermato una sola richiesta provider e liste canonicalizzate. Replay riordinando ENTRAMBI gli assi → **CACHE HIT**, 0 nuove query. Canonicalizzazione origini+destinazioni validata end-to-end.

**v3.3 implementazione:** solo modalità `N notti`. UI ora accetta 1–3 origini e 1–3 destinazioni con stesso pattern add/remove della modalità Date fisse, IATA guard, duplicati separati e blocco sovrapposizioni. Repository canonicalizza entrambe le liste e le passa comma-separated a SerpApi Google Flights, SearchAPI.io Calendar e fallback SerpApi 5+2. Cache key: origini + destinazioni + N notti + target + ±X + strategia (`SERP_EXHAUSTIVE`/`SERP_SAMPLE`/`SEARCHAPI_CALENDAR`). Nessuna migrazione Room: `nights_search_cache` resta schema 3 e memorizza le liste nei campi stringa esistenti; il JSON risultato aggiunge `departureAirportId` e `arrivalAirportId` con default retrocompatibili. Il risultato mostra origine e destinazione effettive dal primo/ultimo segmento dell'andata. Calendar mantiene chunking max 14 partenze/date per blocco (14×14=196 combinazioni temporali), senza moltiplicare per gli aeroporti.

**Provider v3.3 verificati su docs ufficiali:** SerpApi Google Flights supporta `departure_id` e `arrival_id` multipli comma-separated. SearchAPI.io Google Flights Calendar supporta anch'esso più aeroporti/location comma-separated per entrambi i parametri. Quindi 2×3 aeroporti restano una query per data SerpApi o un blocco Calendar, non 6.

**Rischio Explore per v3.4/v3.5:** release notes 2026 mostrano fix recenti su Weekend `travel_duration`, `max_duration`, `stops` e, il 09/07/2026, ricerche valide che potevano tornare vuote. Per Anywhere/Paese una risposta Explore vuota/anomala non va trattata automaticamente come “nessun volo”: implementare diagnostica/error handling robusti.

**Non implementato ancora:** v3.4 Anywhere, v3.5 Country/KGMID, v3.6 verifica geografica Weekend, v3.7 integrazioni estreme. **Non avanzare a v3.4 prima del PASS reale di v3.3.**

**Regola:** dopo ogni decisione/modifica/step completato aggiornare sia `PROJECT_SPEC.md` sia questo file. Nuova chat: leggere prima entrambi e confermare lo stato.
