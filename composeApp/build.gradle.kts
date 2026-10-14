import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)

    jvm()

    android {
        namespace = "departureboard.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources { enable = true }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "departureBoard"
        browser {
            commonWebpackConfig { outputFileName = "departureBoard.js" }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.material3)
            implementation(libs.compose.components.resources)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.ktor.client.core)
            implementation(libs.ksoup)
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.datastore.core.okio)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.cio)
            implementation(libs.jlayer)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.kotlinx.browser)
            // Fusi orari per kotlinx-datetime (TimeZone.of("Europe/Rome")).
            implementation(npm("@js-joda/timezone", "2.22.0"))
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.resources {
    packageOfResClass = "departureboard.resources"
}

compose.desktop {
    application {
        mainClass = "MainKt"
        jvmArgs("-Dskiko.fps.enabled=true", "-Dskiko.fps.count=200")
    }
}
