# API ViaggiaTreno: documentazione e confronto con quello che usiamo

Analisi delle API web di [ViaggiaTreno](http://www.viaggiatreno.it/infomobilita/index.jsp)
(Trenitalia/RFI) fatta il 19/10/2026: elenco completo degli endpoint, come si chiamano, cosa
rispondono e quali servono al tabellone.

**Come è stato ricavato l'elenco.** La pagina `index.jsp` carica `rest-jsapi`
(`http://www.viaggiatreno.it/infomobilita/rest-jsapi`), il client JavaScript che RESTEasy genera da
solo partendo dalle risorse JAX-RS del server. Contiene **tutti** gli endpoint pubblicati, con path,
parametri e `Accept`: l'elenco qui sotto è quindi completo, non indovinato. Ci sono poi due chiamate
fatte a mano da `app.js` che non passano per quel client (`soluzioniViaggioNew` e il servlet
`StampaTreno`). Ogni endpoint è stato provato dal vivo. Dove il significato di un campo non è stato
verificato c'è scritto **(stima)**.

---

## 1. Regole generali

| Aspetto              | Comportamento osservato                                                                                                                                 |
|----------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| Base URL             | `http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/` (più `…/resteasy/news/` per due endpoint)                                              |
| Protocollo           | **Solo HTTP.** `https://…` risponde `301` (Akamai) verso la stessa URL in `http://`                                                                     |
| Metodo               | Sempre `GET` (tranne il servlet `StampaTreno`, `POST`)                                                                                                  |
| Parametri            | Tutti **nel path**, codificati come segmento (`encodePathSegment`): spazi → `%20`, `+` → `%2B`                                                          |
| Autenticazione       | Nessuna. Non servono cookie, Referer né User-Agent (anche con UA vuoto risponde `200`)                                                                  |
| Cookie               | Il server ne imposta due (`dtCookie` di Dynatrace, `NSC_…` di NetScaler per la sessione sticky): si possono ignorare                                    |
| CORS                 | **Nessuna** intestazione `Access-Control-*`: dal browser serve un proxy, come per RFI e Trenord                                                         |
| Formati              | `application/json` oppure `text/plain` (righe `testo                                                                                                    |codice`, o frammenti HTML) |
| Codifica             | UTF-8; nei campi `comp*` le lingue non latine sono già entità HTML (`Versp&#228;tung`)                                                                  |
| Orari                | Epoch in **millisecondi**, ora di Roma. Le date "del giorno" (`dataPartenzaTreno`) sono la mezzanotte di Roma (`1792360800000` = 19/10/2026 00:00 CEST) |
| "Non trovato"        | Dipende dall'endpoint: `204 No Content` col corpo vuoto, `200` con `[]` oppure `200` con stringa vuota                                                  |
| Parametro malformato | `400` con corpo `Error` (testo); una rotta inesistente dà `404` HTML con `Error`                                                                        |

### Codici stazione

Il codice ViaggiaTreno è `S` + 5 cifre (`S01700` = Milano Centrale, `S08409` = Roma Termini). È lo
stesso **codice MIR** che Trenord usa per le stazioni FNM: `S01067` è Milano Domodossola in
entrambi, `S01066` Milano Cadorna. **Non** ha relazione con il `placeId` numerico di iechub.rfi.it
(Milano Centrale è `1728` su RFI e `S01700` qui; Roma Termini `2416` e `S08409`).

Confronto dei nomi (elenco RFI salvato nei test: 2434 località; ViaggiaTreno: 3351 nomi distinti):

- 2424 nomi coincidono esattamente → la corrispondenza RFI ↔ ViaggiaTreno si fa per nome.
- 10 solo RFI: posti di servizio e località minori (`PC CALDIERO`, `PM ISPRA`, `SAN GOTTARDO`,
  `TRACCIA`, `MOIMACCO`, `ORTONA`, `PORRETTA TERME`, `OLBIA TERRANOVA`…).
- 927 solo ViaggiaTreno: rete FNM/Trenord e ferrovie regionali concesse (FSE, Ferrotramviaria, …).
  Per le FNM ci sono anche i treni (vedi §3.1); per FSE (es. Alberobello) l'elenco treni è vuoto.

### Codici regione

Usati da `elencoStazioni`, `dettaglioStazione`, `datimeteo`, `news`, `elencoTratte`. Presi da
`regioniData` in `/vt_static/js/map.js` e verificati con `regione/{codice}`:

| id | Regione                           | id | Regione                     | id | Regione          |
|---:|-----------------------------------|---:|-----------------------------|---:|------------------|
|  0 | Italia (solo stazioni principali) |  8 | Emilia-Romagna              | 16 | Puglia           |
|  1 | Lombardia                         |  9 | Trentino-Alto Adige (tutto) | 17 | Calabria         |
|  2 | Liguria                           | 10 | Friuli-Venezia Giulia       | 18 | Campania         |
|  3 | Piemonte                          | 11 | Marche                      | 19 | Abruzzo          |
|  4 | Valle d'Aosta                     | 12 | Veneto                      | 20 | Sardegna         |
|  5 | Lazio                             | 13 | Toscana                     | 21 | Prov. di Trento  |
|  6 | Umbria                            | 14 | Sicilia                     | 22 | Prov. di Bolzano |
|  7 | Molise                            | 15 | Basilicata                  |    |                  |

La 9 non è nella mappa del sito ma risponde con le stazioni di Trento e Bolzano insieme (Malles,
Bressanone, Calceranica…); `regione/{codice}` restituisce la 9 per quelle stazioni, non la 21/22.

### Codici impresa (`codiceCliente`)

Osservati su ~460 righe di partenze/arrivi di 8 grandi stazioni:

| codiceCliente | Impresa (stima, da categorie e treni osservati)          |
|--------------:|----------------------------------------------------------|
|             1 | Trenitalia – Frecce / lunga percorrenza (FR, FA, FB, EC) |
|             2 | Trenitalia – Regionale                                   |
|             4 | Trenitalia – Intercity (IC, ICN)                         |
|            18 | Trenitalia TPER (regionali Emilia-Romagna)               |
|            63 | Trenord                                                  |

**Italo (NTV) non compare mai**: a Milano Centrale, 40 partenze in 1 h 45 min, zero Italo.
ViaggiaTreno mostra solo i treni Trenitalia e Trenord (più gli EC in collaborazione), non l'intera
circolazione come il monitor RFI.

---

## 2. Elenco degli endpoint

`B` = `http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno`

|  # | Endpoint                                                            | Risposta    | Stato            | Per il tabellone                 |
|---:|---------------------------------------------------------------------|-------------|------------------|----------------------------------|
|  1 | `B/partenze/{codStazione}/{orario}`                                 | JSON        | ✅               | **Da usare**                     |
|  2 | `B/arrivi/{codStazione}/{orario}`                                   | JSON        | ✅               | **Da usare**                     |
|  3 | `B/andamentoTreno/{codOrigine}/{numeroTreno}/{dataPartenza}`        | JSON        | ✅               | **Da usare** (fermate)           |
|  4 | `B/elencoStazioni/{codRegione}`                                     | JSON        | ✅               | **Da usare** (elenco in cache)   |
|  5 | `B/cercaNumeroTreno/{numeroTreno}`                                  | JSON        | ✅               | Solo di ripiego                  |
|  6 | `B/cercaNumeroTrenoTrenoAutocomplete/{numeroTreno}`                 | testo       | ✅               | Solo di ripiego                  |
|  7 | `B/statistiche/{timestamp}`                                         | JSON        | ✅               | Opzionale (cruscotto)            |
|  8 | `B/datimeteo/{codRegione}`                                          | JSON        | ✅               | Opzionale (cruscotto)            |
|  9 | `…/resteasy/news/infomobility`                                      | JSON        | ✅               | Opzionale (avvisi)               |
| 10 | `B/infomobilitaTicker`                                              | HTML        | ✅               | Opzionale (avvisi)               |
| 11 | `B/elencoStazioniCitta/{codStazione}`                               | JSON        | ✅               | Escluso: già in cache            |
| 12 | `B/autocompletaStazione/{testo}`                                    | testo       | ✅               | Escluso: già in cache            |
| 13 | `B/autocompletaStazioneImpostaViaggio/{testo}`                      | testo       | ✅               | Escluso: già in cache            |
| 14 | `B/autocompletaStazioneNTS/{testo}`                                 | testo       | ✅               | Escluso                          |
| 15 | `B/cercaStazione/{testo}`                                           | JSON        | ✅               | Escluso: già in cache            |
| 16 | `B/regione/{codStazione}`                                           | JSON        | ✅               | Escluso: ricavabile              |
| 17 | `B/dettaglioStazione/{codStazione}/{codRegione}`                    | JSON        | ✅               | Escluso: già in `elencoStazioni` |
| 18 | `B/getCoordinateStazione/{codStazione}`                             | JSON        | ✅               | Escluso: già in `elencoStazioni` |
| 19 | `B/coordinateCitta/{codStazione}`                                   | JSON        | ✅               | Escluso: già in `elencoStazioni` |
| 20 | `B/tratteCanvas/{codOrigine}/{numeroTreno}/{dataPartenza}`          | JSON        | ✅               | Escluso: doppione di 3           |
| 21 | `B/dettaglioViaggio/{codDa}/{codA}`                                 | JSON        | ✅               | Escluso                          |
| 22 | `B/elencoTratte/{reg}/{zoom}/{categorie}/{catAV}/{timestamp}`       | JSON        | ✅               | Escluso                          |
| 23 | `B/dettagliTratta/{reg}/{trattaAB}/{trattaBA}/{categorie}/{catAV}`  | JSON        | ✅               | Escluso                          |
| 24 | `B/infomobilitaRSS/{isInfoLavori}`                                  | HTML        | ✅               | Escluso: c'è 9                   |
| 25 | `B/infomobilitaRSSBox/{isInfoLavori}`                               | HTML        | ✅               | Escluso: c'è 9                   |
| 26 | `…/resteasy/news/smartcaring?…`                                     | JSON        | ✅ (vuoto)       | Escluso (per ora)                |
| 27 | `B/news/{codRegione}/{lingua}`                                      | JSON        | ⚠️ fermo al 2019 | Escluso                          |
| 28 | `B/language/{lingua}`                                               | JSON        | ✅               | Escluso                          |
| 29 | `B/property/{nome}`                                                 | testo       | ⚠️ `204`         | Escluso                          |
| 30 | `B/soluzioniViaggioNew/{da}/{a}/{data}`                             | JSON        | ❌ `404`         | Escluso: dismesso                |
| 31 | `…/infomobilita/StampaTreno` (POST) + `certificato/certificato.jsp` | JSON + HTML | non provato      | Escluso                          |

---

## 3. Endpoint utili al tabellone

### 3.1 `partenze` e `arrivi`

```
GET B/partenze/{codStazione}/{orario}
GET B/arrivi/{codStazione}/{orario}
```

| Parametro     | Formato                                                                                                                     |
|---------------|-----------------------------------------------------------------------------------------------------------------------------|
| `codStazione` | Codice `Sxxxxx`. Un codice inesistente o numerico (`1700`) dà `200 []`, non un errore                                       |
| `orario`      | Data **nel formato di `Date.toString()` di JavaScript**: il sito passa un oggetto `Date` e il client lo converte in stringa |

Formati di `orario` provati:

| Valore                                                                | Esito                                                             |
|-----------------------------------------------------------------------|-------------------------------------------------------------------|
| `Mon Oct 19 2026 20:00:00 GMT+0200`                                   | ✅                                                                |
| `Mon Oct 19 2026 20:00:00 GMT+0200 (Ora legale dell’Europa centrale)` | ✅ (la parte tra parentesi viene ignorata)                        |
| `Mon Oct 19 2026 20:00:00`                                            | ✅ (senza fuso vale l'ora di Roma)                                |
| `Mon Oct 19 2026 18:00:00 GMT+0000`                                   | ✅ stesso risultato di 20:00 `GMT+0200`: il fuso viene rispettato |
| `1792419279000` (epoch)                                               | ❌ `400 Error`                                                    |
| `2026-10-19T20:00:00` (ISO)                                           | ❌ `400 Error`                                                    |

Nomi di giorno e mese vanno in inglese (`Locale.US`); il segno `+` va codificato (`%2B`).

```
http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/partenze/S01700/Mon%20Oct%2019%202026%2016%3A13%3A55%20GMT%2B0200
```

**Finestra temporale.** Restituisce i treni da **15 minuti prima** a **circa 105 minuti dopo**
l'orario dato (con 20:00 → treni dalle 19:45 alle 21:45; con 16:13 → dalle 16:00 alle 17:45). Con
molti treni l'elenco si ferma prima (Milano Centrale: 40 partenze, 37 arrivi). Funziona anche per il
giorno dopo; un orario di ieri dà `[]`. I treni già partiti restano in elenco finché sono nella
finestra (`inStazione: true`, `compInStazionePartenza: "Partito"`).

**Risposta**: array di oggetti, ~3 kB ciascuno (Milano Centrale ≈ 120 kB). Campi principali (esempio
vero: FR 9645 alle 16:00 da Milano Centrale):

```json
{
  "numeroTreno": 9645,
  "codOrigine": "S00219",
  "dataPartenzaTreno": 1792360800000,
  "categoria": "",
  "categoriaDescrizione": " FR",
  "compNumeroTreno": " FR 9645",
  "codiceCliente": 1,
  "tipoProdotto": "100",
  "compTipologiaTreno": "nazionale",
  "origine": null,
  "destinazione": "ROMA TERMINI",
  "destinazioneEstera": null,
  "orarioPartenza": 1792418400000,
  "compOrarioPartenza": "16:00",
  "ritardo": 2,
  "binarioProgrammatoPartenzaDescrizione": "6",
  "binarioEffettivoPartenzaDescrizione": "10",
  "inStazione": true,
  "nonPartito": false,
  "circolante": true,
  "provvedimento": 0,
  "riprogrammazione": "N",
  "orientamento": "A",
  "compOrientamento": [
    "Executive in coda",
    "Executive at the rear",
    "…"
  ],
  "compRitardo": [
    "ritardo 2 min.",
    "delay 2 min.",
    "…"
  ],
  "compInStazionePartenza": [
    "Partito",
    "Departed",
    "…"
  ],
  "compImgRitardo2": "/vt_static/img/legenda/icone_legenda/regolare.png",
  "decisioneAttualizzata": "SNN--",
  "ultimoRilev": 1792419060000,
  "haCambiNumero": false,
  "subTitle": null
}
```

Significato dei campi:

| Campo                                                                                                           | Significato                                                                                                                                         |
|-----------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| `numeroTreno`                                                                                                   | Numero commerciale (intero)                                                                                                                         |
| `codOrigine`, `dataPartenzaTreno`                                                                               | Stazione d'origine e giorno di partenza (mezzanotte di Roma, ms): con `numeroTreno` sono la chiave di `andamentoTreno`                              |
| `categoriaDescrizione` / `categoria`                                                                            | Sigla: `" FR"` (con spazio davanti), `"REG"`, `"IC"`, `"EC"`. Per le Frecce `categoria` è vuota e la sigla è solo in `categoriaDescrizione`         |
| `compNumeroTreno`                                                                                               | Sigla + numero già composti (`" FR 9645"`, `"REG 2472"`): conviene fare `trim()`                                                                    |
| `tipoProdotto`                                                                                                  | `"100"` Frecciarossa, `"0"` gli altri. Il sito filtra con `100`/`101`/`102` (FR/FA/FB)                                                              |
| `compTipologiaTreno`                                                                                            | `"nazionale"` / `"regionale"`                                                                                                                       |
| `destinazione` (partenze), `origine` (arrivi)                                                                   | Nome della stazione capolinea, in maiuscolo                                                                                                         |
| `destinazioneEstera`, `origineEstera`, `oraPartenzaEstera`, `oraArrivoEstera`                                   | Capolinea e orari all'estero; il sito li preferisce a quelli italiani quando ci sono                                                                |
| `orarioPartenza` / `orarioArrivo`                                                                               | Orario programmato in questa stazione (ms)                                                                                                          |
| `compOrarioPartenza` / `compOrarioArrivo`                                                                       | Lo stesso in `HH:mm`                                                                                                                                |
| `ritardo`                                                                                                       | Minuti di ritardo (può essere negativo: anticipo). `null` se il treno non è ancora partito dall'origine                                             |
| `binarioProgrammato{Partenza,Arrivo}Descrizione`                                                                | Binario programmato (stringa: `"6"`, `"XII"`, `"1 TR"`…)                                                                                            |
| `binarioEffettivo{Partenza,Arrivo}Descrizione`                                                                  | Binario effettivo, quando assegnato; se è diverso dal programmato c'è stato un cambio                                                               |
| `inStazione`                                                                                                    | `true` = già partito (partenze) / già arrivato (arrivi). Il sito lo mostra come "Partito"/"Arrivato"                                                |
| `nonPartito`                                                                                                    | `true` = non ancora partito dall'origine (niente ritardo, icona `nonpartito.png`)                                                                   |
| `circolante`                                                                                                    | `true` = sta circolando (partito dall'origine e non ancora arrivato a destinazione)                                                                 |
| `provvedimento`                                                                                                 | `0` regolare; `1` **soppresso** (osservato con icona `cancellazione.png`); altri valori **(stima)**: soppressione parziale / variazione di percorso |
| `riprogrammazione`                                                                                              | `"N"` di norma, `null` sui soppressi; `"Y"` **(stima)** per i treni riprogrammati                                                                   |
| `haCambiNumero`, `compImgCambiNumerazione`                                                                      | Il treno cambia numero lungo il percorso                                                                                                            |
| `orientamento`, `compOrientamento[]`                                                                            | Posizione della prima classe / Executive (`"A"` = in coda, `"B"` = in testa); il testo è in 9 lingue                                                |
| `compRitardo[]`, `compRitardoAndamento[]`                                                                       | Ritardo già scritto in 9 lingue (it, en, de, fr, es, ro, ja, zh, ru)                                                                                |
| `compInStazionePartenza[]`, `compInStazioneArrivo[]`                                                            | Testi "Partito"/"Arrivato" in 9 lingue (sempre presenti, valgono solo se `inStazione`)                                                              |
| `compImgRitardo2`                                                                                               | Icona dello stato: `regolare.png`, `ritardo01.png`…, `nonpartito.png`, `cancellazione.png`                                                          |
| `compClassRitardoTxt`, `compClassRitardoLine`                                                                   | Classi CSS della gravità del ritardo (`ritardo01_txt`, `regolare_line`…)                                                                            |
| `decisioneAttualizzata`                                                                                         | Stringa di flag: `SNN--` partito, `NNN--` non partito **(stima: la prima lettera indica se ci sono rilevamenti)**                                   |
| `ritardoAttualizzato`, `ritardoPromessa`, `attivaRitardo{RR,RA,PR,NONE}`                                        | Ritardo "previsto" mostrato dal sito: `RR` reale, `RA` attualizzato, `PR` promesso **(stima)**. Sempre `RR` nei campioni                            |
| `ultimoRilev`                                                                                                   | Istante dell'ultimo rilevamento (ms)                                                                                                                |
| `corrispondenze`, `servizi`, `stazionePartenza`, `stazioneArrivo`, `statoTreno`, `materiale_label`, `iconTreno` | Sempre vuoti o `null` nei campioni                                                                                                                  |

**Cosa manca rispetto al monitor RFI** che leggiamo oggi:

- **Italo e le altre imprese non Trenitalia/Trenord** (vedi §1).
- **Fermate successive** ("FERMA A: …"): non ci sono; servono una chiamata `andamentoTreno` per
  treno (§3.2).
- **Note del blocco "Informazioni"** (composizione, gate, "VIA …", "NO-STOP") e **avvisi di
  stazione** della barra gialla: non ci sono.
- **Spia lampeggiante** "in partenza/in arrivo": non c'è un campo equivalente; si può approssimare
  con binario effettivo assegnato + `!inStazione` + orario vicino.
- **Loghi** delle imprese: solo `codiceCliente`, da mappare a mano.

In cambio ci sono dati che RFI non dà: ritardo intero sempre presente, binario programmato **e**
effettivo, chiave del treno per i dettagli, soppressione esplicita, posizione dell'Executive, treni
FNM in tempo reale.

**Stazioni FNM.** Funziona anche con i codici MIR di Trenord: `S01066` (Milano Cadorna) 31 partenze,
`S01642` (Bovisa) 96, `S01078` (Affori) 24, tutte `codiceCliente: 63`, con ritardo e binario in
tempo reale. Oggi per FNM scarichiamo l'HTML di `station-details` (solo orario programmato, ogni 5
minuti) e poi un dettaglio cifrato per ciascuno dei primi 15 treni.

### 3.2 `andamentoTreno`

```
GET B/andamentoTreno/{codOrigine}/{numeroTreno}/{dataPartenza}
```

| Parametro      | Formato                                                                                               |
|----------------|-------------------------------------------------------------------------------------------------------|
| `codOrigine`   | Codice `Sxxxxx` della stazione d'origine (da `codOrigine` di partenze/arrivi o da `cercaNumeroTreno`) |
| `numeroTreno`  | Numero commerciale                                                                                    |
| `dataPartenza` | Mezzanotte di Roma del giorno di partenza, in ms (`dataPartenzaTreno`)                                |

Combinazione sbagliata o treno inesistente → `204` con corpo vuoto.

```
http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/andamentoTreno/S00219/9645/1792360800000
```

**Risposta**: un oggetto (~9 kB per un FR con 5 fermate) con **tutti i campi della riga di
partenze/arrivi** più questi:

| Campo                                                                                      | Significato                                                                                                       |
|--------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| `fermate[]`                                                                                | Fermate del treno, in ordine (vedi sotto)                                                                         |
| `origine`, `destinazione`, `idOrigine`, `idDestinazione`                                   | Capolinea con nome e codice                                                                                       |
| `origineZero`, `destinazioneZero`, `orarioPartenzaZero`, `orarioArrivoZero`                | Capolinea e orari originari, prima di eventuali limitazioni                                                       |
| `compOrarioPartenzaZeroEffettivo`, `compOrarioArrivoZeroEffettivo`, `compDurata`           | Orari effettivi `HH:mm` e durata del viaggio                                                                      |
| `stazioneUltimoRilevamento`, `oraUltimoRilevamento`, `compOraUltimoRilevamento`            | Dove e quando è stato visto l'ultima volta (`"MILANO ROGOREDO"`, `"16:11"`)                                       |
| `arrivato`                                                                                 | Arrivato a destinazione                                                                                           |
| `fermateSoppresse[]`, `provvedimenti[]`, `anormalita[]`, `segnalazioni[]`, `cambiNumero[]` | Soppressioni di fermate, provvedimenti, anomalie, cambi numero                                                    |
| `subTitle`, `descrizioneVCO`                                                               | Testo libero sulle variazioni (es. "limitato a …") **(stima)**                                                    |
| `motivoRitardoPrevalente`                                                                  | Causa del ritardo, quando indicata                                                                                |
| `tipoTreno`                                                                                | `"PG"` programmato **(stima: `ST` soppresso totale, `PP` parzialmente soppresso, `SI` soppresso a inizio corsa)** |
| `descOrientamento[]`                                                                       | Orientamento in 9 lingue                                                                                          |

Ogni elemento di `fermate[]`:

```json
{
  "id": "S01700",
  "stazione": "MILANO CENTRALE",
  "progressivo": 20,
  "tipoFermata": "F",
  "programmata": 1792417800000,
  "arrivo_teorico": 1792417800000,
  "partenza_teorica": 1792418400000,
  "arrivoReale": 1792417920000,
  "partenzaReale": 1792418610000,
  "effettiva": 1792418610000,
  "ritardoArrivo": 2,
  "ritardoPartenza": 4,
  "ritardo": 4,
  "binarioProgrammatoArrivoDescrizione": "6",
  "binarioEffettivoArrivoDescrizione": "10",
  "binarioProgrammatoPartenzaDescrizione": "6",
  "binarioEffettivoPartenzaDescrizione": "10",
  "actualFermataType": 1,
  "orientamento": "A"
}
```

| Campo                                               | Significato                                                                                                                                                                                |
|-----------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `tipoFermata`                                       | `"P"` partenza (origine), `"F"` fermata intermedia, `"A"` arrivo (destinazione)                                                                                                            |
| `programmata`, `arrivo_teorico`, `partenza_teorica` | Orari programmati (ms)                                                                                                                                                                     |
| `arrivoReale`, `partenzaReale`, `effettiva`         | Orari rilevati (ms), `null` se non ancora passato                                                                                                                                          |
| `ritardoArrivo`, `ritardoPartenza`, `ritardo`       | Minuti                                                                                                                                                                                     |
| `actualFermataType`                                 | `1` fermata regolare (osservato); `3` soppressa **(stima: `app.js` salta le fermate con `3` per trovare l'ultima fermata vera)**; `2` straordinaria **(stima, dalle icone della legenda)** |
| `progressivo`                                       | Posizione lungo il percorso, anche non contigua (conta i punti di passaggio senza fermata)                                                                                                 |

Basta per comporre "FERMA A: …" (le fermate dopo la stazione corrente con `actualFermataType != 3`)
e per gli annunci di soppressione delle fermate.

### 3.3 `elencoStazioni`

```
GET B/elencoStazioni/{codRegione}
```

**Risposta**: array di stazioni con coordinate (Lombardia: 345 voci, 169 kB; tutte le regioni 0–22:
3360 codici distinti, ≈ 2,3 MB in tutto, 23 chiamate).

```json
{
  "codiceStazione": "S01700",
  "codReg": 1,
  "tipoStazione": 1,
  "lat": 45.486347,
  "lon": 9.204528,
  "localita": {
    "nomeLungo": "MILANO CENTRALE",
    "nomeBreve": "MILANO CENTRALE",
    "label": "Milano",
    "id": "S01700"
  },
  "nomeCitta": "Milano",
  "esterno": false,
  "dettZoomStaz": [
    {
      "zoomStartRange": 8,
      "zoomStopRange": 9,
      "pinpointVisibile": true,
      "labelVisibile": true
    }
  ]
}
```

| Campo                         | Significato                                                                                                                  |
|-------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| `localita.nomeLungo`          | Nome completo (corrisponde al nome dell'elenco RFI)                                                                          |
| `localita.nomeBreve`          | Nome abbreviato (`"MI.P.GARIBALDI"`)                                                                                         |
| `nomeCitta`, `localita.label` | Città (`label` è a volte troncata: `"Mi"`)                                                                                   |
| `tipoStazione`                | Importanza per la mappa: `1` grandi nodi (36), `2` (6), `3` ordinarie (3225), `4` (93) **(stima: 4 = confine / fuori rete)** |
| `esterno`                     | Stazione di un'altra regione mostrata ai bordi della mappa regionale                                                         |
| `codReg`                      | Regione richiesta (non quella "vera" se `esterno`)                                                                           |
| `dettZoomStaz`                | Livelli di zoom in cui la mappa mostra la stazione                                                                           |

La regione `0` contiene solo le 722 stazioni principali. Una stazione di confine compare in più
regioni: si deduplica per `codiceStazione`.

### 3.4 `cercaNumeroTreno` e `cercaNumeroTrenoTrenoAutocomplete`

Servono solo se si parte da un numero di treno senza origine e data (per esempio dall'input
dell'utente). Partendo da partenze/arrivi `codOrigine` e `dataPartenzaTreno` ci sono già.

```
GET B/cercaNumeroTreno/9645
→ {"numeroTreno":"9645","codLocOrig":"S00219","descLocOrig":"TORINO PORTA NUOVA",
   "dataPartenza":"2026-10-19","corsa":"09645A","h24":false,"tipo":"PG",
   "millisDataPartenza":"1792360800000","formatDataPartenza":null}
```

Inesistente → `204`. Restituisce un solo oggetto: se più treni hanno lo stesso numero, ne sceglie
uno **(stima: non verificato su un numero ambiguo)**.

```
GET B/cercaNumeroTrenoTrenoAutocomplete/9645
→ 9645 - TORINO PORTA NUOVA - 19/10/26|9645-S00219-1792360800000
```

Una riga per ogni treno con quel numero (`descrizione|numero-codOrigine-dataPartenzaMs`).
Inesistente → `200` vuoto. È la variante da usare quando lo stesso numero esiste con origini
diverse.

### 3.5 Opzionali: `statistiche`, `datimeteo`, `news/infomobility`, `infomobilitaTicker`

```
GET B/statistiche/{timestampMs}
→ {"treniGiorno":5891,"ultimoAggiornamento":1792419237501,"treniCircolanti":895}
```

Treni del giorno e treni in viaggio adesso su tutta la rete. Il timestamp serve solo a evitare la
cache. Potrebbe finire nel cruscotto dell'intro.

```
GET B/datimeteo/{codRegione}
→ {"S01430": {"codStazione":"S01430","oggiTemperatura":21,"oggiTemperaturaMattino":18,
     "oggiTempo":1,"oggiTempoMattino":4,…,"domaniTemperatura":18,"domaniTempo":10,…}, …}
```

Meteo di oggi e domani per alcune stazioni della regione (11 in Lombardia), indicizzato per codice.
`*Tempo` è un codice icona **(stima: 1–10 di giorno, +100 di sera/notte)**.

```
GET http://www.viaggiatreno.it/infomobilita/resteasy/news/infomobility[?region=…&trainNumber=…&evidence=…]
→ [{"title":"CIRCOLAZIONE REGOLARE","link":"https://www.trenitalia.com/…","pubDate":1792360800000,
    "description":"&lt;p&gt;…","trainTags":[],"regionTags":[],"evidenzia":true}, …]
```

Avvisi di infomobilità nazionali (24 voci, 115 kB). `description` è HTML con le entità raddoppiate;
`trainTags`/`regionTags` permettono di filtrare per treno o regione; anche i parametri query
`region`, `trainNumber`, `evidence` e l'header `correlationId` sono accettati (dal client generato).
È l'equivalente in JSON di `infomobilitaRSS`.

```
GET B/infomobilitaTicker
→ <ul><li>CIRCOLAZIONE REGOLARE</li></ul>
```

Il titolo della notizia in evidenza, in HTML: il più leggero per una riga di stato nazionale.

---

## 4. Endpoint esclusi

### Elenchi e ricerca stazioni (già nella cache locale)

Lavoriamo su un elenco stazioni scaricato una volta e tenuto un mese in `StationCache`; la ricerca
nel dialog filtra quell'elenco. Tutto ciò che segue o è già nella cache o si ricava da
`elencoStazioni`.

| Endpoint                                    | Risposta                                                                                                                    | Perché escluso                                                                                      |
|---------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `elencoStazioniCitta/S01700`                | `[{"nomeLungo":"MILANO CENTRALE","nomeBreve":"MILANO CENTRALE","label":"Milano","id":"S01700"}, …]` (13 stazioni di Milano) | Stazioni della stessa città: `id` e nome li abbiamo già, la città è `nomeCitta` di `elencoStazioni` |
| `autocompletaStazione/MILANO`               | `MILANO CENTRALE                                                                                                            | S01700\nMILANO AFFORI                                                                               |S01078\n…` (ordinate per importanza, poi alfabetico; senza distinzione maiuscole; da 1 carattere) | Ricerca lato server su dati che abbiamo in cache |
| `autocompletaStazioneImpostaViaggio/MILANO` | Identico al precedente                                                                                                      | Come sopra (usato dal form "Imposta viaggio")                                                       |
| `cercaStazione/MILANO`                      | `[{"nomeLungo":…,"nomeBreve":…,"label":…,"id":"S01700"}, …]`                                                                | Come `autocompletaStazione` ma in JSON con il nome breve                                            |
| `autocompletaStazioneNTS/MILANO`            | `MILANO CENTRALE                                                                                                            | 830001700\n…`                                                                                       | Codici **NTS/UIC** (`83` Italia + `000` + codice), usati dalla biglietteria (LeFrecce); include voci cumulative "MILANO TUTTE STAZ." |
| `regione/S01700`                            | `1` (corpo JSON: un numero). Inesistente → `204`                                                                            | Serve solo a chiamare `dettaglioStazione`; si ricava dalla regione di `elencoStazioni`              |
| `dettaglioStazione/S01700/1`                | Lo stesso oggetto di `elencoStazioni` per una stazione                                                                      | Doppione                                                                                            |
| `getCoordinateStazione/S01700`              | Come sopra ma con `codReg`, `localita`… a `null`                                                                            | Doppione                                                                                            |
| `coordinateCitta/S01700`                    | `{"latitudine":45.486347,"longitudine":9.204528}`                                                                           | Doppione (`lat`/`lon` di `elencoStazioni`)                                                          |

### Treni e percorsi

| Endpoint                                             | Risposta                                                                                                                                                        | Perché escluso                                                                                                       |
|------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| `tratteCanvas/{codOrigine}/{numero}/{data}`          | Array con un elemento per fermata: `{id, stazione, first, last, stazioneCorrente, partenzaReale, arrivoReale, orientamento[], fermata:{…come andamentoTreno…}}` | Gli stessi dati di `andamentoTreno.fermate`, impaginati per il disegno del percorso                                  |
| `dettaglioViaggio/S01700/S08409`                     | Array di righe come partenze (12 treni Milano C. → Roma T.)                                                                                                     | Treni diretti tra due stazioni nel giorno: non serve al tabellone. Potrebbe servire per un filtro "solo treni per X" |
| `soluzioniViaggioNew/{da}/{a}/{yyyy-MM-ddTHH:mm:ss}` | `404 Error` sia con `S01700` sia con `1700`                                                                                                                     | **Dismesso**: la ricerca soluzioni è passata a LeFrecce                                                              |
| `elencoTratte/{reg}/{zoom}/{categorie}/{catAV}/{ts}` | `[{"nodoA":"S01301","nodoB":"S01307","trattaAB":1,"trattaBA":83,"latitudineA":…,"occupata":true}, …]`                                                           | Segmenti di linea della mappa regionale, con `occupata` se ci sono treni                                             |
| `dettagliTratta/{reg}/{AB}/{BA}/{categorie}/{catAV}` | `[{"tratta":"Chiasso - Como","treni":[…righe come partenze…]}]`                                                                                                 | Treni su un segmento della mappa                                                                                     |

Per i due endpoint della mappa: `categorie` è un elenco separato da virgole tra `ES*`, `IC`,
`EXP`, `EC`, `EN`, `REG`, `MET`; `catAV` è `null` oppure `100`/`101`/`102`
(Frecciarossa/Argento/Bianca), come nei filtri `filtroTreni` di `map.js`.

### Contenuti del sito

| Endpoint                                                                                                                                                                          | Risposta                                                                                                     | Perché escluso                                                                                                                             |
|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| `infomobilitaRSS/{true\|false}`                                                                                                                                                   | HTML: titoli, date e testi completi (6 kB con `false` = infomobilità; 88 kB con `true` = lavori programmati) | Stesso contenuto di `news/infomobility`, ma da ripulire dall'HTML                                                                          |
| `infomobilitaRSSBox/{true\|false}`                                                                                                                                                | `<ul id="accordionGenericInfomob">` con i soli titoli                                                        | Come sopra                                                                                                                                 |
| `news/smartcaring?commercialTrainNumber=…&originCode=…&searchDate=…`                                                                                                              | `[]` senza parametri                                                                                         | Avvisi per singolo treno: interessante in futuro per gli annunci, ma il formato dei parametri non è verificabile senza un treno con avvisi |
| `news/{codRegione}/{lingua}`                                                                                                                                                      | Un'unica notizia del **dicembre 2019** con `0/it`; `[]` con altre regioni o lingue                           | Non più aggiornato                                                                                                                         |
| `language/{it\|en\|…}`                                                                                                                                                            | Oggetto di 254 etichette dell'interfaccia (`"fermate.partenzaEffettiva":"Partenza Effettiva"`)               | Testi del sito                                                                                                                             |
| `property/{nome}`                                                                                                                                                                 | `204` per ogni nome provato; nessun uso in `app.js`                                                          | Configurazione interna                                                                                                                     |
| `StampaTreno` (POST, form `numTreno`, `locArrivo`, `locArrivoDesc`, `date=dd-MM-yyyy`) → `{comunicazione, pdf, provvedimenti}`; poi `certificato/certificato.jsp?comunicazione=…` | Non provato (genera un documento)                                                                            | Attestazione di ritardo per il viaggiatore                                                                                                 |

---

## 5. Confronto con quello che usiamo oggi

Oggi il progetto **non chiama nessuna API ViaggiaTreno**: i dati vengono da due fornitori, tutti e
due tramite pagine web o endpoint del sito.

| Bisogno del tabellone              | Oggi (RFI / FNM)                                                                                                                                                              | Con ViaggiaTreno                                                                               |
|------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| Elenco stazioni                    | RFI: HTML `arrivipartenze`, `select#ElencoLocalita` (2434 località, `placeId` numerico). FNM: `/mia/v2/stazioni_v2/` (codici MIR). Entrambi in `StationCache`, validi un mese | `elencoStazioni/0…22` (3360 codici, con coordinate e città). Una voce in più in `StationCache` |
| Tabellone di stazione              | RFI: HTML `ArrivalsDepartures/Monitor` ogni 30 s, tutte le imprese                                                                                                            | `partenze`/`arrivi` ogni 30 s: JSON, ma solo Trenitalia e Trenord                              |
| Ritardo                            | RFI: testo della cella (`RRitardo`), a volte "RITARDO" o vuoto                                                                                                                | `ritardo` intero (anche negativo)                                                              |
| Binario                            | RFI: uno solo (`RBinario`)                                                                                                                                                    | Programmato **ed** effettivo                                                                   |
| Soppressione                       | RFI: testo "Cancellato"/"Soppresso" cercato nelle celle                                                                                                                       | `provvedimento == 1`, icona `cancellazione.png`                                                |
| Fermate successive                 | RFI: nel popup `RDettagli` della stessa pagina                                                                                                                                | Una chiamata `andamentoTreno` per treno                                                        |
| Note (composizione, gate, "VIA …") | RFI: blocco "Informazioni"                                                                                                                                                    | Solo `orientamento` (Executive in testa/coda)                                                  |
| Avvisi di stazione                 | RFI: barra gialla `.marqueeinfosupp`                                                                                                                                          | Nessun equivalente per stazione; solo infomobilità nazionale                                   |
| Logo dell'impresa                  | RFI: GIF in base64                                                                                                                                                            | Solo `codiceCliente`                                                                           |
| Treni FNM                          | Trenord: HTML `station-details` (solo programmato, ogni 5 min) + BFF cifrato AES per i primi 15 treni (ogni 30 s) + chiave estratta dal JS                                    | `partenze`/`arrivi` sui codici MIR, ritardo e binario in tempo reale, in **una** chiamata      |
| Italo                              | RFI sì                                                                                                                                                                        | **No**                                                                                         |

### Cosa conviene fare

1. **Elenco stazioni.** Non aggiungere chiamate di ricerca (`autocompleta*`, `cercaStazione`,
   `elencoStazioniCitta`, `regione`, `dettaglioStazione`, coordinate): tutto quello che danno è già,
   o sarebbe, nella cache locale. Se si aggiunge ViaggiaTreno come fornitore basta
   `elencoStazioni` per le regioni 0–22, deduplicato, in una nuova voce `StationSource` con la
   stessa validità di un mese. La corrispondenza con RFI si fa per nome (2424 su 2434); quella con
   FNM è diretta, perché il codice MIR è lo stesso.
2. **FNM.** È il caso in cui ViaggiaTreno conviene di più: una chiamata JSON al posto di HTML
   lento + 15 dettagli cifrati + gestione della chiave. Da valutare come sostituto (o primo
   tentativo) di `FnmBoardSource`, con `andamentoTreno` per le fermate.
3. **RFI.** Non sostituibile del tutto: mancano Italo, note, avvisi di stazione e loghi. Può servire
   come arricchimento (binario programmato/effettivo, ritardo numerico, soppressioni esplicite)
   incrociando per numero di treno, o come ripiego se iechub.rfi.it non risponde.
4. **Opzionali** per il cruscotto dell'intro: `statistiche` (treni in viaggio adesso),
   `infomobilitaTicker` o `news/infomobility` (stato della circolazione nazionale), `datimeteo`.
5. **Da ignorare**: mappa (`elencoTratte`, `dettagliTratta`), `tratteCanvas`,
   `dettaglioViaggio`, `soluzioniViaggioNew` (dismesso), `news/{reg}/{lingua}` (fermo al 2019),
   `language`, `property`, `autocompletaStazioneNTS`, `StampaTreno`.

### Cose da sistemare prima di usarlo

- **HTTP in chiaro.** Il sito non risponde in HTTPS (redirect `301` verso `http://`).
    - Android: dalla API 28 il traffico in chiaro è bloccato, e `androidApp` non ha né
      `usesCleartextTraffic` né un `networkSecurityConfig`. Serve un
      `network_security_config.xml` che lo permetta solo per `www.viaggiatreno.it`.
    - Web: non ha intestazioni CORS e la pagina servita in HTTPS non può chiamare HTTP; va aggiunta
      una rotta `/viaggiatreno/` sia in `composeApp/webpack.config.d/proxy.js` sia in
      `docker/nginx.conf` (con `proxy_pass http://www.viaggiatreno.it/`), come per `/rfi/` e
      `/trenord/`.
- **Formato di `orario`.** Va generato come `EEE MMM dd yyyy HH:mm:ss 'GMT'Z` in inglese, con il
  `+` codificato; meglio esplicitare il fuso di Roma invece di affidarsi a quello del server.
- **Finestra di 2 ore.** Per un elenco più lungo servono più chiamate con `orario` spostato (il sito
  fa passi di qualche ora con "precedente/successivo").
- **Risposte vuote.** `204` con corpo vuoto (`andamentoTreno`, `cercaNumeroTreno`, `regione`) va
  trattato come "non trovato", non come errore di parsing; `[]` su `partenze` può voler dire codice
  sbagliato.
- **Testi `comp*`.** Contengono entità HTML (`&#228;`, `&icirc;`): se si usano vanno decodificati;
  più semplice usare i campi grezzi (`ritardo`, `inStazione`, `provvedimento`).
- **API non documentata.** Nessun contratto pubblico: i nomi dei campi (con refusi come
  `pinpointVisibile`/`pinpointVisible`) possono cambiare senza preavviso. Conviene un parser
  tollerante (`ignoreUnknownKeys`, campi nullable) e test con risposte salvate, come per RFI.
