#!/usr/bin/env kotlin

// Genera ci.yaml: `kotlin .github/workflows/ci.main.kts` (o lancia lo script direttamente).
// Il YAML va committato insieme allo script: il job di consistency-check lo rigenera e fallisce
// se i due non coincidono.

@file:Repository("https://repo.maven.apache.org/maven2/")
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:4.0.0")

@file:Repository("https://bindings.krzeminski.it")
@file:DependsOn("actions:checkout:v7")
@file:DependsOn("actions:setup-java:v6")
@file:DependsOn("gradle:actions__setup-gradle:v6")
@file:DependsOn("docker:setup-buildx-action:v4")
@file:DependsOn("docker:build-push-action:v7")

import io.github.typesafegithub.workflows.actions.actions.Checkout
import io.github.typesafegithub.workflows.actions.actions.SetupJava
import io.github.typesafegithub.workflows.actions.docker.BuildPushAction
import io.github.typesafegithub.workflows.actions.docker.SetupBuildxAction
import io.github.typesafegithub.workflows.actions.gradle.ActionsSetupGradle
import io.github.typesafegithub.workflows.domain.RunnerType.UbuntuLatest
import io.github.typesafegithub.workflows.domain.triggers.PullRequest
import io.github.typesafegithub.workflows.domain.triggers.Push
import io.github.typesafegithub.workflows.dsl.workflow

workflow(
    name = "CI",
    on = listOf(
        Push(branches = listOf("main")),
        PullRequest(),
    ),
    sourceFile = __FILE__,
) {
    // Test JVM e build di tutti i target. L'APK esce non firmato: in CI non ci sono i segreti.
    job(id = "build", name = "Test e build", runsOn = UbuntuLatest) {
        uses(name = "Checkout", action = Checkout())
        uses(
            name = "Java 21",
            action = SetupJava(distribution = SetupJava.Distribution.Temurin, javaVersion = "21"),
        )
        uses(name = "Gradle", action = ActionsSetupGradle())
        // I test in uno step a sé: se si rompono lo si vede subito, senza cercarli tra R8 e ProGuard.
        run(name = "Test", command = "./gradlew :composeApp:jvmTest")
        run(
            name = "Build",
            command = "./gradlew :androidApp:assembleRelease " +
                ":composeApp:packageUniversalJar :composeApp:wasmJsBrowserDistribution",
        )
    }

    // L'immagine si compila ma non si pubblica: quella vera esce solo dai tag (release.yaml).
    job(id = "docker", name = "Immagine Docker", runsOn = UbuntuLatest) {
        uses(name = "Buildx", action = SetupBuildxAction())
        uses(
            name = "Build",
            action = BuildPushAction(
                push = false,
                cacheFrom = listOf("type=gha"),
                cacheTo = listOf("type=gha,mode=max"),
            ),
        )
    }
}
