# Release: cosa fare

Guida passo passo per attivare le pipeline GitHub Actions (`.github/workflows/`) e pubblicare una
versione. La panoramica sta nel README principale, sezione [Release e CI](../../README.md#release-e-ci).

## 1. Configurazione una tantum

### 1.1 Firma in locale: `signing/credential.properties`

La cartella `signing/` è in `.gitignore` e contiene il keystore e le sue credenziali:

```
signing/
├── DepartureBoardKeyStore.jks
└── credential.properties
```

`signing/credential.properties` ha quattro chiavi, tutte obbligatorie:

| Chiave          | Valore                                                                  |
|-----------------|-------------------------------------------------------------------------|
| `storeFile`     | il keystore, relativo a `signing/` (o percorso assoluto)                |
| `storePassword` | password del keystore                                                   |
| `keyAlias`      | alias della chiave dentro il keystore (`keytool -list -keystore …`)     |
| `keyPassword`   | password della chiave (in un keystore PKCS12 coincide con `storePassword`) |

```properties
storeFile=DepartureBoardKeyStore.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Le password non devono contenere `\`: in un file `.properties` è un carattere di escape. Prova:
`./gradlew :androidApp:assembleRelease` deve produrre
`androidApp/build/outputs/apk/release/androidApp-release.apk`; se esce
`androidApp-release-unsigned.apk` manca il file o uno dei valori è vuoto.

### 1.2 Secret della firma Android

In CI il workflow Release ricrea `signing/` dai secret: il keystore in `signing/release.jks` e
`signing/credential.properties` con `storeFile=release.jks`. Da Settings → Secrets and variables →
Actions → New repository secret:

| Secret                      | Valore                                    |
|-----------------------------|-------------------------------------------|
| `ANDROID_KEYSTORE_BASE64`   | il keystore codificato in base64 (sotto)  |
| `ANDROID_KEYSTORE_PASSWORD` | `storePassword`                           |
| `ANDROID_KEY_ALIAS`         | `keyAlias`                                |
| `ANDROID_KEY_PASSWORD`      | `keyPassword`                             |

```bash
base64 -i signing/DepartureBoardKeyStore.jks | pbcopy     # macOS: copia negli appunti
```

Con [GitHub CLI](https://cli.github.com) si fa tutto da terminale:

```bash
base64 -i signing/DepartureBoardKeyStore.jks | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD     # chiede il valore
gh secret set ANDROID_KEY_ALIAS
gh secret set ANDROID_KEY_PASSWORD
```

### 1.3 Permessi di Actions

Settings → Actions → General → Workflow permissions: va bene il default (read). I job che
pubblicano chiedono da sé i permessi in scrittura (`contents`, `packages`, `id-token`,
`attestations`).

## 2. Primo giro di prova

1. Commit e push di tutto su `main`. Il workflow **CI** deve diventare verde, compreso il job
   *Check YAML consistency*.
2. Prerelease di prova (il numero prima del suffisso deve essere `app-version`):

   ```bash
   git tag v1.2.0-rc.1 && git push origin v1.2.0-rc.1
   ```

   Controllare in Actions → Release:
   - Releases: prerelease `v1.2.0-rc.1` con `Tabellone-1.2.0-rc.1.apk` e `DepartureBoard-1.2.0.jar`;
   - Packages: `tabellone-caronte` con il tag `1.2.0-rc.1` (amd64 e arm64).
3. Se l'APK manca o il job Android fallisce con "Manca l'APK firmato", ricontrollare i secret
   `ANDROID_*`.
4. Cancellare la prerelease di prova (Release e tag):

   ```bash
   gh release delete v1.2.0-rc.1 --cleanup-tag --yes
   ```

## 3. Pubblicare una versione

1. In `gradle/libs.versions.toml` aggiornare `app-version` e **incrementare** `app-versionCode`
   (Android rifiuta un aggiornamento con versionCode uguale o minore).
2. Commit e push su `main`.
3. Tag:

   ```bash
   git tag v1.2.0 && git push origin v1.2.0
   ```

Se il tag non corrisponde ad `app-version` la release si ferma al primo job: cancellare il tag
(`git push --delete origin v1.2.0 && git tag -d v1.2.0`), correggere e ripetere.

## 4. Dopo la prima release

- **Immagine Docker pubblica**: il package ghcr.io nasce privato. Profilo → Packages →
  `tabellone-caronte` → Package settings → Change visibility → Public. Da lì si può anche collegare
  il package al repository, se non lo è già.

## 5. Modificare le pipeline

Si cambia lo script `.main.kts`, si rigenera il YAML dalla root del repository e si committano
entrambi:

```bash
kotlin .github/workflows/ci.main.kts
kotlin .github/workflows/release.main.kts
```

Serve Kotlin installato (`brew install kotlin`). Se nel YAML il job `check_yaml_consistency` lancia
`release.main.kts` senza `.github/workflows/` davanti, è colpa della cache di `main.kts`:

```bash
KOTLIN_MAIN_KTS_COMPILED_SCRIPTS_CACHE_DIR= kotlin .github/workflows/release.main.kts
```

## Riepilogo

- [ ] `signing/credential.properties` compilato, `assembleRelease` firmato in locale
- [ ] Secret `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
- [ ] Push su `main`, CI verde
- [ ] Prerelease `v1.2.0-rc.1` verificata e cancellata
- [ ] Tag `v1.2.0`
- [ ] Package ghcr.io reso pubblico
