# Smartwatch (Wear OS): specifiche funzionali

Il watch non mostra il tabellone intero: mostra **un solo treno**, quello che l'utente segue, con la
grafica dei monitor di binario RFI

## 1. Scelta del treno (telefono)

- Si sceglie sul telefono, in Impostazioni → **Smartwatch**:
    1. la **stazione**, indipendente da quella del tabellone;
    2. uno dei treni in partenza da lì, oppure "Nessuno".
- Il treno è **ricorrente**: si segue il suo numero ogni giorno, finché non se ne sceglie un altro.
- La selezione porta con sé l' **orario programmato**. Così il watch sa quando controllare il treno
  anche prima di averlo visto in tabellone.
- Se nessuno smartwatch è collegato, la pagina lo segnala.
- Sul watch non si sceglie nulla. Senza un treno scelto l'app mostra "NESSUN TRENO" e un pulsante
  che apre l'app sul telefono.

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
    - il watch manda il messaggio `/watch/refresh`;
    - il telefono legge il monitor e risponde con il DataItem `/watch/train`;
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
nulla al telefono e non accende la radio.

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

- **Treno partito**: se sparisce dal tabellone dopo l'orario (partito o soppresso), per oggi basta e
  si riprende il giorno dopo.
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
