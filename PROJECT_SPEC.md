# VolaFlex — PROJECT_SPEC

**Repository:** `archimede-projects/flexi-flights` (privata)  
**Nome app:** VolaFlex  
**Application ID / namespace:** `com.archimedeprojects.volaflex`  
**Ruolo:** fonte di verità persistente del progetto  
**Ultimo aggiornamento:** 2026-09-14

## Regola di manutenzione
Aggiornare questo file dopo ogni requisito/decisione/modifica/step completato. `SESSION_HANDOFF.md` è il riepilogo operativo sintetico.

# 1. Vincoli permanenti
Uso personale/APK sideload; zero costi e nessuna carta; niente backend a pagamento; GitHub-only; niente Android Studio locale; CI/build via Actions; test su telefono; mai segreti in repo/chat.

# 2. Requisiti
Weekend flessibili; N notti ±X; range ampi; max 3 origini; destinazione esclusiva fra max 3 aeroporti / Ovunque / Paese; futuri filtri su scali/compagnia/durata e Maps Intent. Niente destinazioni composite.

# 3. Architettura
`UI → logica ricerca → provider → cache/database` e **DISCOVERY → VERIFICA → DETTAGLIO**. Evitare prodotti cartesiani: multi-airport nativo, Explore per geografia, Calendar per date, pochi candidati verificati, Account API live, cache TTL 4h, diagnostica.

# 4. Provider
## SerpApi
`google_flights` exact/verifica; `google_travel_explore` discovery; Account API quota live. Google Flights supporta liste comma-separated in `departure_id`/`arrival_id`. Quota condivisa con altro progetto, riserva minima 5.

## SearchAPI.io Calendar
Acceleratore N notti; multi-airport comma-separated; chunk 14×14=196; ≤10 date Serp exact, >10 con SearchAPI Calendar→1 Serp verify, senza SearchAPI sampling 5+2.

## Travel Explore
`travel_duration=1` Weekend; multi-origin `departure_id`; aeroporto specifico=`arrival_id`; Ovunque=nessun arrival; Paese=`arrival_area_id=<KGMID>` senza `arrival_id`. SerpApi documenta i KGMID country/area come Freebase IDs `/m/` o `/g/`; Francia `/m/0f8l9c` è esempio ufficiale.

### Robustezza Explore
Release notes 2026: fix Weekend, `max_duration`, `stops`, e 09/07/2026 “most valid searches returning empty results”. `TravelExploreRawParser` condiviso da Anywhere/Country classifica `BODY_MISSING`, `PROVIDER_ERROR`, `STRUCTURE_ANOMALY`, `SURPRISING_EMPTY`, `NO_OPPORTUNITIES`, `SUCCESS`; i repository aggiungono `HTTP_ERROR`/`NETWORK_ERROR`. `destinations=[]` non è silenziosamente “nessun volo”.

# 5. Sicurezza
SerpApi/SearchAPI.io in DataStore locale, mai repo/chat; backup Android disabilitato. SearchAPI.io configurata sul telefono.

# 6. Geografia locale
`AirportDirectory`: IATA→aeroporto→città→ISO country, circa 180–200 aeroporti, guard anti-typo e base futuri filtri scalo.

`CountryAreaCatalog` v3.5: catalogo statico zero-rete `nome italiano → ISO2 → KGMID`, **33 paesi**: Algeria, Austria, Belgio, Brasile, Bulgaria, Canada, Croazia, Danimarca, Egitto, Finlandia, Francia, Germania, Giappone, Grecia, Irlanda, Italia, Malta, Marocco, Messico, Paesi Bassi, Polonia, Portogallo, Regno Unito, Repubblica Ceca, Romania, Spagna, Stati Uniti, Svezia, Svizzera, Thailandia, Tunisia, Turchia, Ungheria. Curato volutamente per non sprecare quota con ID non verificati.

# 7. Room/cache/diagnostica
`volaflex.db`, schema 3: `flight_search_cache`, `weekend_search_cache`, `nights_search_cache`, `diagnostic_events`; TTL 4h; migrazioni 1→2 e 2→3 validate.

Date fisse key: origini+destinazioni canonicalizzate+date. N notti key: origini+destinazioni+N+target+±X+strategia. Anywhere key: `WEEKEND|<origini>|ANYWHERE|<periodo>`. Country key: `WEEKEND|<origini>|COUNTRY:<ISO2>|<periodo>` con `arrivalId=COUNTRY:<ISO2>`. Nessuna migrazione Room v3.5.

# 8. Stack
Kotlin/Compose; AGP 9.3.1; Kotlin 2.4.20; Gradle 9.5; Compose BOM 2026.06.00; Navigation 2.9.8; DataStore 1.2.1; Retrofit 3.0.0; OkHttp 4.12.0; kotlinx.serialization 1.11.0; Room 2.8.4; KSP 2.3.11; SDK36/min26; JDK17.

# 9. Firma/Release
`dev-latest` con APK `VolaFlex-dev.apk`. Firma canonica SHA-256 `a1f432f512e3d1867ee4b4535fb06a83fae5413ee700113b34e8b926a2767df3`, signer `CN=VolaFlex, OU=Personal, O=archimede-projects`, RSA4096.

**v3.5 CI:** run **#29 SUCCESS**, build **`0.1.0-dev.29`**, commit `2b34d73d80b7bb0e385efdb8265ec124a7f91edc`, `BUILD SUCCESSFUL in 2m 5s`, 49 task. Firma v2/v3 valida, 1 signer, fingerprint canonico invariato. APK SHA-256 `0ae786a35bf48acf38c6f5cdcadd0267bd77ea7ee5cdca2505b54bc153855273`, asset size 9,933,187 byte.

**Fix UI selettore principale:** run **#31 SUCCESS**, build **`0.1.0-dev.31`**, commit applicativo `4dddf1195311aa8546eeb26c11e8431f1fd0f30d`; `BUILD SUCCESSFUL in 2m 15s`, 49 task. Firma v2/v3 valida, 1 signer, fingerprint canonico invariato. APK SHA-256 `8fb7cb81ed0575e1addd5cdd0b2aebd447fc0e1b96d376efc8bef03f10044d65`, asset size 9,933,187 byte. Release `dev-latest` aggiornata sul commit/run corretti.

# 10. Stato e test reali
Fase 0 CHIUSA. v1 CHIUSA. v2 COMPLETAMENTE CHIUSA.

v3.1 CHIUSA: `FCO+CIA+MXP→MAD`, MXP 60 EUR, 1 query; replay 0.
v3.2 CHIUSA: `FCO+CIA→BCN+MAD+VLC`, FCO→BCN 48 EUR, 1 query; replay entrambi assi 0.
v3.3 CHIUSA: Serp multi-list FCO→VLC 40 EUR; Calendar multi-list FCO→VLC 45 EUR (2 Calendar+1 Serp). Replay Calendar specifico omesso intenzionalmente; canonicalizzazione condivisa e strategy key già validate.

## v3.4 — CHIUSA E VALIDATA
UI Aeroporto/Ovunque PASS; anti-typo PASS; `FCO+CIA→Ovunque`, dicembre 2026: 1 Travel Explore, `SUCCESS`; candidati reali Bari 34 EUR, Alicante 42 EUR, Varsavia ecc. con città/paese/aeroporto/date/prezzo; nessuna Google Flights. Replay `CIA+FCO`: cache hit, 0 query, quota invariata (105 osservata).

## v3.5 — CHIUSA E VALIDATA SUL TELEFONO REALE
Weekend/Discovery Country validata end-to-end su build `0.1.0-dev.29`. Test reale `FCO+CIA → Francia`, gennaio 2027: candidato **Lourdes**, Paese **Francia**, aeroporto **LDE**, **72 EUR**, periodo Explore **08/01→11/01/2027**. Controllo geografico anti-KGMID-sbagliato superato: il candidato appartiene realmente alla Francia. Diagnostica: `SERPAPI_ACCOUNT` + **1 solo `TRAVEL_EXPLORE`**, nessun Google Flights/Weekend Verify/SearchAPI Calendar. Replay con origini invertite `CIA+FCO`: `Risultato da cache`, **0 nuove query**. Consumo reale: **1 sola query SerpApi**, come previsto. UI `Aeroporto | Ovunque | Paese`, 1–3 origini, CountryAreaCatalog 33 paesi, cache `COUNTRY:ISO2`, schema Room invariato: PASS.

## Fix regressione UI post-v3.5 — CHIUSO E VALIDATO SUL TELEFONO REALE
Causa esatta: `WeekendSearchScreen.kt` aveva il selettore principale hard-coded con soli `Date fisse | Weekend` e non esponeva alcun callback `onOpenNights`; non era un overflow causato dal selettore secondario. Fix: aggiunto `N notti` con peso uguale e callback di navigazione cablato in `VolaFlexApp.kt`; spacing principale uniformato a 6 dp. Nel selettore secondario `Aeroporto | Ovunque | Paese` il padding orizzontale interno è ridotto a 8 dp e le label sono forzate su una sola riga (`maxLines=1`, `softWrap=false`) per evitare il wrap di `Aeroporto`. Diff applicativo limitato a `WeekendSearchScreen.kt` e `VolaFlexApp.kt`; nessun file repository/provider/rete/cache/query modificato.

Validazione reale build `0.1.0-dev.31`: **Test A Date fisse PASS** — tutte e tre le modalità visibili; **Test B Weekend/Aeroporto PASS** — tutte e tre le modalità visibili e `Aeroporto` su una sola riga; **Test E parziale PASS** — `N notti` raggiunta da Weekend, tutte e tre le modalità visibili con `N notti` attiva. Weekend/Ovunque e Weekend/Paese **non sono stati riverificati con screenshot dedicati dopo il fix**; rischio residuo accettato come basso perché la riga principale in `WeekendSearchScreen` è indipendente da `destinationChoice`, mentre le tre opzioni secondarie usano lo stesso `DestinationChoiceButton`. Il blocker UI è quindi **CHIUSO E VALIDATO** e il blocco su v3.6 è rimosso.

# 11. Rischi
Provider mutevoli; quota condivisa; AirportDirectory/catalogo KGMID non universali; Explore ha avuto regressioni; empty response classificata prudenzialmente; combinazioni estreme sempre protette da quota/cache.

# 12. Prossimo step — v3.6 (PIANIFICATA, NON IMPLEMENTATA)
Estendere ai risultati Weekend `Ovunque` e `Paese` lo stesso pattern **Discovery → Verifica** già usato dal Weekend aeroporto specifico. Dopo la Discovery, ordinare i candidati validi per prezzo e scegliere **un solo candidato più economico eleggibile** (IATA/date valide e almeno un pattern weekend ricavabile); la verifica Google Flights userà le origini canonicalizzate e come `arrival_id` esclusivamente l'IATA di quel candidato. Per il candidato scelto controllare al massimo i due pattern già esistenti: venerdì sera→domenica sera e sabato mattina→lunedì, scegliendo poi il più economico fra gli esiti verificati. **Non fare fallback automatico su seconda/terza destinazione se il primo candidato non verifica**: mostrare invece la Discovery indicativa e il messaggio di verifica non riuscita, così la geografia ampia non diventa una scansione. Costo tipico su un mese/cache miss: **1 Travel Explore + massimo 2 Google Flights = massimo 3 query SerpApi di ricerca**, più Account API per quota; su più mesi: `N Explore + max 2 Google Flights`. Replay con Discovery+verifica in cache: 0 query. Prima di implementare, riusare quanto più possibile la logica `buildVerificationPatterns`/quota/cache/diagnostica del Weekend esistente senza moltiplicare le destinazioni verificate.
