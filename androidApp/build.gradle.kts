import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
}

// Firma della release da signing/credential.properties (la cartella è fuori da git): storeFile
// (relativo a signing/), storePassword, keyAlias, keyPassword. Senza il file o con un valore vuoto
// la release esce non firmata.
val signingDir = rootProject.file("signing")
val signingProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("signing/credential.properties"))
        .asText.orNull?.let { load(it.reader()) }
}
val releaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .associateWith { signingProperties.getProperty(it).orEmpty() }
    .takeIf { values -> values.values.all { it.isNotBlank() } }

android {
    namespace = "com.takaotech.departureboard.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.takaotech.departureboard"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = libs.versions.app.versionCode.get().toInt()
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
}
