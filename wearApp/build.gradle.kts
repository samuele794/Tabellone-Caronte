import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
}

// Stessa firma dell'app telefono (signing/credential.properties): il Wearable Data Layer collega
// solo app con lo stesso applicationId e lo stesso certificato. Senza il file la release esce
// non firmata.
val signingDir = rootProject.file("signing")
val signingProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("signing/credential.properties"))
        .asText.orNull?.let { load(it.reader()) }
}
val releaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .associateWith { signingProperties.getProperty(it).orEmpty() }
    .takeIf { values -> values.values.all { it.isNotBlank() } }

android {
    namespace = "com.takaotech.departureboard.wear"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Uguale al telefono: è quello che il Data Layer confronta.
        applicationId = "com.takaotech.departureboard"
        minSdk = libs.versions.android.wear.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Sul Play Store telefono e watch condividono l'applicationId: il watch ha codici a parte.
        versionCode = 10_000 + libs.versions.app.versionCode.get().toInt()
        versionName = libs.versions.app.version.get()
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = signingDir.resolve(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.complications.data.source)
    implementation(libs.wear.remote.interactions)
    implementation(libs.wear)
    implementation(libs.concurrent.futures.ktx)
    testImplementation(libs.kotlin.test.junit)
}
