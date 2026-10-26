#!/usr/bin/env kotlin

// Genera release.yaml: `kotlin .github/workflows/release.main.kts` (o lancia lo script direttamente).
// Parte dal push di un tag `v<app-version>` (o `v<app-version>-qualcosa` per una prerelease di prova):
// GitHub Release con APK e jar e immagine su ghcr.io.

@file:Repository("https://repo.maven.apache.org/maven2/")
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:4.0.0")

@file:Repository("https://bindings.krzeminski.it")
@file:DependsOn("actions:checkout:v7")
@file:DependsOn("actions:setup-java:v6")
@file:DependsOn("actions:upload-artifact:v7")
@file:DependsOn("actions:download-artifact:v8")
@file:DependsOn("actions:attest-build-provenance:v4")
@file:DependsOn("gradle:actions__setup-gradle:v6")
@file:DependsOn("docker:login-action:v4")
@file:DependsOn("docker:metadata-action:v6")
@file:DependsOn("docker:setup-qemu-action:v4")
@file:DependsOn("docker:setup-buildx-action:v4")
@file:DependsOn("docker:build-push-action:v7")
@file:DependsOn("softprops:action-gh-release:v3")

import io.github.typesafegithub.workflows.actions.actions.AttestBuildProvenance_Untyped
import io.github.typesafegithub.workflows.actions.actions.Checkout
import io.github.typesafegithub.workflows.actions.actions.DownloadArtifact
import io.github.typesafegithub.workflows.actions.actions.SetupJava
import io.github.typesafegithub.workflows.actions.actions.UploadArtifact
import io.github.typesafegithub.workflows.actions.docker.BuildPushAction
import io.github.typesafegithub.workflows.actions.docker.LoginAction
import io.github.typesafegithub.workflows.actions.docker.MetadataAction
import io.github.typesafegithub.workflows.actions.docker.SetupBuildxAction
import io.github.typesafegithub.workflows.actions.docker.SetupQemuAction
import io.github.typesafegithub.workflows.actions.gradle.ActionsSetupGradle
import io.github.typesafegithub.workflows.actions.softprops.ActionGhRelease
import io.github.typesafegithub.workflows.domain.JobOutputs
import io.github.typesafegithub.workflows.domain.Mode
import io.github.typesafegithub.workflows.domain.Permission
import io.github.typesafegithub.workflows.domain.RunnerType.UbuntuLatest
import io.github.typesafegithub.workflows.domain.triggers.Push
import io.github.typesafegithub.workflows.dsl.JobBuilder
import io.github.typesafegithub.workflows.dsl.expressions.expr
import io.github.typesafegithub.workflows.dsl.workflow

fun JobBuilder<*>.setUpGradle() {
    uses(name = "Checkout", action = Checkout())
    uses(
        name = "Java 21",
        action = SetupJava(distribution = SetupJava.Distribution.Temurin, javaVersion = "21"),
    )
    uses(name = "Gradle", action = ActionsSetupGradle())
}

workflow(
    name = "Release",
    on = listOf(Push(tags = listOf("v*"))),
    sourceFile = __FILE__,
    permissions = mapOf(Permission.Contents to Mode.Read),
) {
    // Il tag deve corrispondere ad app-version (gradle/libs.versions.toml): è la versione di APK,
    // jar e immagine. Con un suffisso (v1.2.0-rc.1) la release è una prerelease.
    val version = job(
        id = "version",
        name = "Versione",
        runsOn = UbuntuLatest,
        outputs = object : JobOutputs() {
            var version by output()
            var prerelease by output()
        },
    ) {
        uses(name = "Checkout", action = Checkout())
        val check = run(
            name = "Controllo tag",
            command = """
                VERSION="${'$'}{GITHUB_REF_NAME#v}"
                APP_VERSION=$(sed -nE 's/^app-version = "(.*)"$/\1/p' gradle/libs.versions.toml)
                if [ "${'$'}{VERSION%%-*}" != "${'$'}APP_VERSION" ]; then
                  echo "::error::Il tag ${'$'}GITHUB_REF_NAME non corrisponde ad app-version ${'$'}APP_VERSION (gradle/libs.versions.toml)"
                  exit 1
                fi
                case "${'$'}VERSION" in *-*) PRERELEASE=true ;; *) PRERELEASE=false ;; esac
                echo "version=${'$'}VERSION" >> "${'$'}GITHUB_OUTPUT"
                echo "prerelease=${'$'}PRERELEASE" >> "${'$'}GITHUB_OUTPUT"
            """.trimIndent(),
        )
        jobOutputs.version = check.outputs["version"]
        jobOutputs.prerelease = check.outputs["prerelease"]
    }

    // APK firmato: keystore e credenziali arrivano dai secret e finiscono in signing/, come in locale.
    val android = job(id = "android", name = "APK Android", runsOn = UbuntuLatest, needs = listOf(version)) {
        setUpGradle()
        run(
            name = "Keystore",
            env = mapOf(
                "KEYSTORE_BASE64" to expr("secrets.ANDROID_KEYSTORE_BASE64"),
                "KEYSTORE_PASSWORD" to expr("secrets.ANDROID_KEYSTORE_PASSWORD"),
                "KEY_ALIAS" to expr("secrets.ANDROID_KEY_ALIAS"),
                "KEY_PASSWORD" to expr("secrets.ANDROID_KEY_PASSWORD"),
            ),
            command = """
                mkdir -p signing
                echo "${'$'}KEYSTORE_BASE64" | base64 --decode > signing/release.jks
                {
                  echo "storeFile=release.jks"
                  echo "storePassword=${'$'}KEYSTORE_PASSWORD"
                  echo "keyAlias=${'$'}KEY_ALIAS"
                  echo "keyPassword=${'$'}KEY_PASSWORD"
                } > signing/credential.properties
            """.trimIndent(),
        )
        // Telefono e smartwatch: stessa firma, che il Wearable Data Layer richiede.
        run(name = "Build", command = "./gradlew :androidApp:assembleRelease :wearApp:assembleRelease")
        run(
            name = "APK",
            env = mapOf("VERSION" to expr(version.outputs.version)),
            command = """
                APK=androidApp/build/outputs/apk/release/androidApp-release.apk
                WEAR=wearApp/build/outputs/apk/release/wearApp-release.apk
                if [ ! -f "${'$'}APK" ] || [ ! -f "${'$'}WEAR" ]; then
                  echo "::error::Manca l'APK firmato: controlla i secret ANDROID_*"
                  exit 1
                fi
                mv "${'$'}APK" "Tabellone-${'$'}VERSION.apk"
                mv "${'$'}WEAR" "Tabellone-Wear-${'$'}VERSION.apk"
            """.trimIndent(),
        )
        uses(name = "Upload", action = UploadArtifact(name = "android", path = listOf("Tabellone-*.apk")))
    }

    // Jar universale (Windows/macOS/Linux, x64 e arm64) con ProGuard.
    val desktop = job(id = "desktop", name = "Jar desktop", runsOn = UbuntuLatest, needs = listOf(version)) {
        setUpGradle()
        run(name = "Build", command = "./gradlew :composeApp:packageUniversalJar")
        uses(
            name = "Upload",
            action = UploadArtifact(name = "desktop", path = listOf("composeApp/build/dist/DepartureBoard-*.jar")),
        )
    }

    // Immagine web per amd64 e arm64 su ghcr.io, con attestazione di provenienza.
    // Tag: 1.2.0, 1.2 e latest; per una prerelease solo 1.2.0-rc.1.
    job(
        id = "docker",
        name = "Immagine Docker",
        runsOn = UbuntuLatest,
        needs = listOf(version),
        permissions = mapOf(
            Permission.Contents to Mode.Read,
            Permission.Packages to Mode.Write,
            Permission.Custom("attestations") to Mode.Write,
            Permission.IdToken to Mode.Write,
        ),
    ) {
        // ghcr.io vuole il nome in minuscolo, il repository è Tabellone-Caronte.
        val image = run(
            name = "Nome immagine",
            command = "echo \"name=ghcr.io/\${GITHUB_REPOSITORY,,}\" >> \"\$GITHUB_OUTPUT\"",
        )
        uses(
            name = "Login",
            action = LoginAction(
                registry = "ghcr.io",
                username = expr { github.actor },
                password = expr { secrets.GITHUB_TOKEN },
            ),
        )
        val meta = uses(
            name = "Metadata",
            action = MetadataAction(
                images = listOf(expr(image.outputs["name"])),
                tags = listOf(
                    "type=semver,pattern={{version}}",
                    "type=semver,pattern={{major}}.{{minor}}",
                ),
            ),
        )
        uses(name = "QEMU", action = SetupQemuAction())
        uses(name = "Buildx", action = SetupBuildxAction())
        val build = uses(
            name = "Build e push",
            action = BuildPushAction(
                push = true,
                platforms = listOf("linux/amd64", "linux/arm64"),
                tags_Untyped = expr(meta.outputs.tags),
                labels_Untyped = expr(meta.outputs.labels),
                cacheFrom = listOf("type=gha"),
                cacheTo = listOf("type=gha,mode=max"),
            ),
        )
        uses(
            name = "Attestazione",
            action = AttestBuildProvenance_Untyped(
                subjectName_Untyped = expr(image.outputs["name"]),
                subjectDigest_Untyped = expr(build.outputs.digest),
                pushToRegistry_Untyped = "true",
            ),
        )
    }

    job(
        id = "release",
        name = "GitHub Release",
        runsOn = UbuntuLatest,
        needs = listOf(version, android, desktop),
        permissions = mapOf(Permission.Contents to Mode.Write),
    ) {
        uses(name = "Download", action = DownloadArtifact(path = "dist", mergeMultiple = true))
        uses(
            name = "Release",
            action = ActionGhRelease(
                files = listOf("dist/*"),
                generateReleaseNotes = true,
                prerelease_Untyped = expr(version.outputs.prerelease),
            ),
        )
    }
}
