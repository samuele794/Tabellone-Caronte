# Installare l'app smartwatch con ADB

L'app Wear OS (`Tabellone-Wear-<versione>.apk`) non passa dal telefono: un'app Android non può
installare pacchetti su un altro dispositivo, e l'APK del watch dentro quello del telefono non è più
supportato da Wear OS 2.0. Senza Play Store si installa dal computer, con ADB, collegandosi al watch
via Wi-Fi.

Le funzioni dell'app watch sono descritte nel [README](../README.md#smartwatch-wear-os) e in
[Smartwatch.md](Smartwatch.md).

## 1. Cosa serve

| Cosa       | Requisito                                                                                                               |
|------------|-------------------------------------------------------------------------------------------------------------------------|
| Smartwatch | **Wear OS 3** o successivo (API 30+): Galaxy Watch4 e successivi, Pixel Watch, ecc.                                     |
| Telefono   | Android, abbinato al watch, con l'app telefono della **stessa release** (vedi §1.1)                                     |
| Computer   | Windows, macOS o Linux con gli **Android SDK Platform-Tools** 30 o successivi (`adb pair`)                              |
| Rete       | watch e computer sulla **stessa rete Wi-Fi**, senza isolamento tra i client (reti aziendali e "ospiti" spesso lo hanno) |
| APK        | `Tabellone-Wear-<versione>.apk` dalla [GitHub Release](https://github.com/samuele794/Tabellone-Caronte/releases)        |

Wear OS 2 e i watch Tizen, Garmin, Apple Watch ecc. non sono supportati. Il watch Wear OS non si
abbina a un iPhone.

### 1.1 Stessa firma per telefono e watch

Telefono e watch si parlano (Wearable Data Layer) solo se le due app hanno lo stesso
`applicationId` e **la stessa firma**. Gli APK della stessa GitHub Release sono firmati con la
stessa chiave: installa `Tabellone-<versione>.apk` sul telefono e `Tabellone-Wear-<versione>.apk`
sul watch. Con un'app telefono firmata con un'altra chiave (una build debug, o un'altra fonte) il
watch non riceve i treni scelti e funziona solo con la propria connessione, se ce l'ha.

### 1.2 Installare ADB sul computer

```bash
brew install --cask android-platform-tools     # macOS
winget install Google.PlatformTools            # Windows
sudo apt install adb                           # Debian/Ubuntu (controlla la versione, sotto)
```

In alternativa si scarica lo zip da
[developer.android.com/tools/releases/platform-tools](https://developer.android.com/tools/releases/platform-tools)
e si usa `adb` dalla cartella estratta. Prova:

```bash
adb version     # "Android Debug Bridge version 1.0.41", "Version 30.x" o successiva
```

## 2. Preparare il watch

I nomi delle voci cambiano un po' da un produttore all'altro; quelli sotto sono del Pixel Watch, tra
parentesi quelli dei Galaxy Watch.

1. **Opzioni sviluppatore**: Impostazioni → Sistema → Informazioni → Informazioni sulla versione
   (Impostazioni → Info sull'orologio → Informazioni software) e tocca **7 volte** "Numero build"
   ("Versione software"), finché il watch dice che le opzioni sviluppatore sono attive.
2. **Debug ADB**: Impostazioni → Opzioni sviluppatore → attiva **Debug ADB**.
3. **Wi-Fi**: collega il watch alla stessa rete del computer (Impostazioni → Connettività → Wi-Fi).
   Molti watch usano il Wi-Fi solo quando il telefono non è raggiungibile via Bluetooth: se nelle
   opzioni di debug non compare un indirizzo IP, disattiva per qualche minuto il Bluetooth del
   telefono.
4. Nelle opzioni sviluppatore cerca una delle due voci:
    - **Debug wireless** (Wear OS 4 e successivi, e alcuni Wear OS 3): vai al §3.1;
    - **Debug tramite Wi-Fi** (watch più vecchi): vai al §3.2.

## 3. Collegare il computer al watch

### 3.1 Con "Debug wireless" (abbinamento con codice)

1. Sul watch: Opzioni sviluppatore → **Debug wireless** → attiva → **Abbina nuovo dispositivo**. Il
   watch mostra un **codice di 6 cifre** e un indirizzo, es. `192.168.1.50:37123`.
2. Sul computer, con quell'indirizzo:

   ```bash
   adb pair 192.168.1.50:37123
   # Enter pairing code: 482913
   # Successfully paired to 192.168.1.50:37123
   ```

3. Torna alla schermata **Debug wireless**: mostra un altro indirizzo, con una **porta diversa**
   da quella dell'abbinamento, es. `192.168.1.50:41055`. Collegati a quello:

   ```bash
   adb connect 192.168.1.50:41055
   ```

L'abbinamento si fa una volta sola per computer; le volte successive basta il passo 3 (la porta può
cambiare a ogni attivazione del debug wireless).

### 3.2 Con "Debug tramite Wi-Fi"

1. Sul watch: Opzioni sviluppatore → attiva **Debug tramite Wi-Fi**. Dopo qualche secondo compare
   l'indirizzo, es. `192.168.1.50:5555`.
2. Sul computer:

   ```bash
   adb connect 192.168.1.50:5555
   ```

3. Sul watch compare **Consentire il debug?**: conferma (spunta "Consenti sempre da questo computer"
   per non doverlo rifare).

### 3.3 Verifica

```bash
adb devices -l
# List of devices attached
# 192.168.1.50:41055     device product:... model:... device:...
```

Lo stato deve essere `device`. Con `unauthorized` accetta la richiesta sul watch; con `offline`
ripeti `adb connect`.

## 4. Installare l'app

```bash
adb -s 192.168.1.50:41055 install -r Tabellone-Wear-1.2.0.apk
# Performing Streamed Install
# Success
```

`-s` sceglie il watch se al computer sono collegati anche il telefono o un emulatore; `-r`
sostituisce un'installazione precedente mantenendo i dati.

Sul watch l'app si chiama **Tabellone**. Senza treni mostra "NESSUN TRENO": i treni si scelgono sul
telefono, in Impostazioni → **Smartwatch**. Al primo avvio il watch chiede il permesso per le
notifiche, che serve all'icona dell'attività in corso sul quadrante vicino alla partenza.

### 4.1 Aggiornare

Si ripete il §3 (basta `adb connect`) e si installa il nuovo APK con lo stesso comando. Ricorda di
aggiornare anche l'app telefono alla stessa versione.

### 4.2 Disinstallare

```bash
adb -s 192.168.1.50:41055 uninstall com.takaotech.departureboard
```

Oppure dal watch: tieni premuta l'icona dell'app → Disinstalla.

## 5. Alla fine

Il debug lasciato attivo consuma batteria e lascia il watch raggiungibile in rete:

```bash
adb disconnect 192.168.1.50:41055
```

e sul watch disattiva **Debug wireless** / **Debug tramite Wi-Fi** e **Debug ADB**. Riattiva il
Bluetooth del telefono se lo avevi spento.

## 6. Problemi frequenti

| Messaggio / sintomo                        | Causa e rimedio                                                                                                                                                    |
|--------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `failed to connect` / `Connection refused` | rete diversa o isolamento dei client; IP o porta sbagliati (la porta del debug wireless cambia); il watch è passato al Bluetooth: spegni il Bluetooth del telefono |
| `adb: unknown command pair`                | Platform-Tools troppo vecchi: aggiorna ADB (§1.2)                                                                                                                  |
| `error: more than one device/emulator`     | aggiungi `-s <indirizzo>` al comando                                                                                                                               |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE`       | sul watch c'è l'app firmata con un'altra chiave (es. una build debug): disinstallala (§4.2) e riprova; i dati dell'app sul watch si perdono                        |
| `INSTALL_FAILED_VERSION_DOWNGRADE`         | sul watch c'è una versione più nuova: disinstallala prima di installare quella vecchia                                                                             |
| `INSTALL_FAILED_OLDER_SDK`                 | il watch ha Wear OS 2 o precedente: non è supportato                                                                                                               |
| L'app si apre ma resta su "NESSUN TRENO"   | nessun treno scelto sul telefono, oppure firme diverse tra telefono e watch (§1.1)                                                                                 |
| "senza rete" fisso                         | telefono non raggiungibile e watch senza internet: controlla l'abbinamento Bluetooth con il telefono                                                               |
