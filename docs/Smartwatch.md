# Smartwatch (Wear OS): specifiche funzionali

Il watch non mostra il tabellone intero: mostra **un solo treno alla volta**, tra quelli che
l'utente segue, con la grafica dei monitor di binario RFI

## 1. Scelta dei treni (telefono)

- Si scelgono sul telefono, in Impostazioni → **Smartwatch**: la pagina elenca i treni seguiti
  (etichetta, stazione, orario), ognuno con il tasto per toglierlo, e il pulsante **Aggiungi un
  treno**, che chiede:
    1. la **stazione**, indipendente da quella del tabellone e da quella degli altri treni;
    2. uno dei treni in partenza da lì, oppure il **numero** di un treno scritto a mano (anche uno
       che oggi non è ancora in tabellone, o quando le partenze non si leggono). Il numero scritto
       filtra l'elenco; se il treno c'è, vale come sceglierlo dall'elenco. I treni già seguiti sono
       evidenziati, e lo stesso treno non si aggiunge due volte.
- I treni sono **ricorrenti**: si seguono gli stessi numeri ogni giorno, finché non li si toglie.
- Ogni treno porta con sé l' **orario programmato**. Così il watch sa quando controllarlo anche
  prima di averlo visto in tabellone.
- Un treno scelto solo per numero non ha ancora destinazione né orario: l'etichetta è "Treno 91831"
  e la pagina dice "orario quando comparirà in tabellone". La **prima volta** che compare in
  tabellone, etichetta e orario si prendono dal monitor e si salvano, sul telefono (impostazioni)
  e sul watch; da lì in poi non cambiano più. Vale anche se a trovarlo è il watch da solo: il
  telefono si allinea al suo prossimo controllo. Riscrivere il numero di un treno già trovato non ne
  perde i dati.
- Se nessuno smartwatch è collegato, la pagina lo segnala.
- Sul watch non si sceglie nulla. Senza treni l'app mostra "NESSUN TRENO" e un pulsante che apre
  l'app sul telefono.
- I telefoni con l'app di prima avevano un treno solo: diventa il primo dell'elenco.

### Ordine di osservazione e treno mostrato

- I treni si osservano **in ordine di partenza**, comunque li abbia aggiunti l'utente. La giornata
  dei treni va dalle 03:00 alle 03:00: un treno delle 00:20 viene dopo quello delle 23:50.
- Un treno scelto solo per numero, finché non ha un orario, resta nel posto in cui l'ha messo
  l'utente (in fondo, aggiungendolo). Alla **prima volta** che compare in tabellone prende il suo
  posto nell'ordine. Per trovarlo presto, a ogni controllo si legge anche il monitor della sua
  stazione, non solo quello del treno mostrato.
- Il watch mostra **un solo treno**: il primo, nell'ordine, che oggi non è ancora partito. Resta
  quello finché non parte.
- **Partito** vuol dire: era in tabellone e ne è **sparito**. Allora il watch passa subito al
  successivo, e nello stesso controllo ne legge il monitor, anche di un'altra stazione.
- Un treno che non si è visto sparire (watch spento, nessuna rete) si dà per partito 20 minuti dopo
  l'orario, ritardo compreso; se all'ultimo controllo era ancora in tabellone, 20 minuti dopo quel
  controllo.
- Partiti tutti, il watch mostra il primo, che è il prossimo del giorno dopo. Alle 03:00 si riparte
  dal primo.
- Il watch dice al telefono quale treno mostra e quali sono già partiti, insieme alla richiesta di
  controllo (§3): così il telefono segue la stessa sequenza anche se il watch ha controllato da sé.

## 2. Cosa mostra il watch

### App a schermo intero

Disposizione del monitor di Bologna, adattata al quadrante rotondo. Le etichette sono solo in
italiano, per lasciare spazio ai LED.

| Zona             | Contenuto                                                                                              |
|------------------|--------------------------------------------------------------------------------------------------------|
| In alto          | stato dell'aggiornamento: "aggiornato 3 min fa", "12 min fa · senza rete", "aggiornamento…"            |
| Colonna sinistra | binario in grande, bianco e corsivo (il resto, es. "ovest", più piccolo); sotto, l'orologio analogico  |
| Centro, riga 1   | **destinazione** (LED ambra, scorre se non entra), con la spia "in partenza" lampeggiante              |
| Centro, riga 2   | **partenza**: orario, con il ritardo in fondo al modulo (`5'`, `220'`, `CANC`) e l'etichetta "ritardo" |
| Colonna destra   | **treno**: logo o nome dell'impresa in alto, categoria e numero attaccati in basso (`R91831`)          |
| In basso         | **informazioni** (fermate e note), larghe quanto centro e destra; scorrono se non entrano              |
| Fondo            | nome della stazione                                                                                    |

- Il pannello è **nero**; i moduli LED sono antracite, come sui monitor veri.
- **Aggiornamento manuale**: il monitor riempie lo schermo e un tocco sopra non fa nulla, così non
  si aggiorna per sbaglio. Scorrendo verso il basso, con il dito o con la corona/ghiera, compare il
  pulsante **Aggiorna**.
    - L'indicatore di scorrimento sul bordo segnala che sotto c'è altro.
    - Toccando il pulsante l'aggiornamento parte subito e la schermata torna sul monitor, dove la
      riga in alto mostra "aggiornamento…".
    - Mentre l'aggiornamento è in corso il pulsante è disattivato.
    - In ambient la schermata torna in cima e non scorre.
- **Treno non in tabellone**: il watch mostra "in attesa" e, dell'ultima volta che l'ha visto, solo
  orario e destinazione. Binario, ritardo e spia valgono solo per il treno attuale.

### Tile

- Mostra treno, destinazione, partenza con il ritardo e binario.
- Riporta l'ora assoluta dell'ultimo aggiornamento ("agg. 19:12"), con il motivo se il watch è
  offline.
- Un tocco apre l'app.

### Complication

- **Breve**: orario, con `+5'`, `OK` o `CANC`.
- **Lunga**: `R 91831 VIGNOLA 19:18 +5' BIN 7`.
- Un tocco apre l'app.

### Ambient (always-on)

- Pannello e moduli neri, senza LED spenti; LED accesi attenuati e senza alone.
- Etichette senza riquadro, orologio a solo contorno.
- Nulla scorre o lampeggia; l'orologio avanza una volta al minuto.

## 3. Da dove arrivano i dati

Non tutti i watch hanno una connessione propria, quindi i dati arrivano in modo **ibrido**:

1. **Dal telefono** (Wearable Data Layer):
    - il watch manda il messaggio `/watch/refresh` con il suo stato (treno mostrato, treni partiti);
    - il telefono legge i monitor (§1) e risponde con il DataItem `/watch/train`, il nuovo stato;
    - i treni scelti arrivano al watch con il DataItem `/watch/selection`;
    - funziona anche con l'app del telefono chiusa;
    - il telefono lavora solo quando il watch lo chiede, e riusa per 10 s un controllo appena fatto.
2. **Dal watch**, solo se il telefono non è raggiungibile e il watch ha **davvero** internet:
    - una radio Wi-Fi o LTE accesa non basta;
    - la rete deve essere validata dal sistema e non deve chiedere un accesso (captive portal);
    - deve rispondere a una richiesta di prova verso il fornitore;
    - il download passa poi dalla stessa rete, che viene rilasciata subito dopo.
3. **Senza nessuna delle due** resta l'ultimo dato, con il motivo: "senza rete" (né telefono né
   Wi-Fi/LTE) oppure "senza internet" (c'è una rete, ma internet non risponde).

## 4. Quando si aggiorna (risparmio della batteria)

Il dato di un treno cambia solo attorno alla sua partenza. Fuori da quel periodo il watch non chiede
nulla al telefono e non accende la radio. Le regole valgono per il treno mostrato (§1); quando un
treno parte, si controlla subito il successivo.

- **Fascia attiva**: da 90 minuti prima a 20 minuti dopo la partenza, ritardo compreso.
- Finché il treno resta in tabellone la fascia si allunga.
- Gli orari sono quelli italiani (`Europe/Rome`), qualunque sia il fuso del watch.
- I treni a cavallo della mezzanotte sono gestiti.

| Momento                             | Background (tile, complication)                          | App aperta  |
|-------------------------------------|----------------------------------------------------------|-------------|
| Da 90 a 30 min prima della partenza | ogni 10 min                                              | ogni 3 min  |
| Ultimi 30 min e fino alla partenza  | ogni 3 min                                               | ogni minuto |
| Fuori fascia                        | nessun aggiornamento; si riparte all'inizio della fascia | ogni 10 min |
| Orario non ancora noto              | ogni 30 min                                              | ogni 30 min |

- **Treno partito**: se sparisce dal tabellone si passa al successivo; partiti tutti, per oggi basta
  e si riprende il giorno dopo.
- **Senza connessione** l'attesa tra due tentativi raddoppia (pari al tempo passato dall'ultimo dato
  buono), fino a 40 minuti.
- **Errore del monitor**: si aspettano almeno 10 minuti.
- **Wi-Fi/LTE del watch**: si provano al massimo ogni 5 minuti, perché costano molto più del
  Bluetooth del telefono.
- **Pulsante Aggiorna**: aggiorna sempre, ignorando tutte queste regole.
- **Background solo se serve**: si aggiorna in background (WorkManager) solo se sul watch c'è un
  tile o una complication del treno. Senza, l'app si aggiorna da sola quando è aperta.
- **Tile e complication** non chiedono dati a intervalli: si ridisegnano solo quando quello che
  mostrano cambia.
- **Riaprire l'app** non forza un aggiornamento se il dato è ancora fresco.
- **Schermo**:
    - il ticker dei LED si ferma in ambient;
    - i testi scorrono solo se non entrano nel modulo;
    - durante l'aggiornamento non c'è un'animazione continua, solo la scritta "aggiornamento…".

## 5. Requisiti tecnici

- **Identità**: telefono e watch hanno lo stesso `applicationId` (`com.takaotech.departureboard`) e
  la stessa firma, come richiede il Data Layer.
- **Versione**: il `versionCode` del watch è quello del telefono più 10000.
- **Sistema**: Wear OS con API 30 o successive. L'app è `standalone`, cioè funziona anche senza
  telefono, se il watch ha rete.
