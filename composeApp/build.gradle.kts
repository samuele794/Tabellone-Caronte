import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.nucleus)
}

kotlin {
    // MapLibre Compose sul desktop usa la Foreign Function API e chiede Java 25.
    jvmToolchain(25)

    jvm()

    android {
        namespace = "com.takaotech.departureboard.shared"
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
            implementation(libs.navigation3.ui)
            implementation(libs.ktor.client.core)
            implementation(libs.ksoup)
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.datastore.core.okio)
        }
        // Mappa delle stazioni: MapLibre Compose c'è per Android e desktop, non per wasmJs.
        val mapMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.maplibre.compose)
            }
        }
        jvmMain.get().dependsOn(mapMain)
        androidMain.get().dependsOn(mapMain)
        jvmMain.dependencies {
            // Native Skiko di tutte le piattaforme: il jar desktop è uno solo per Windows, macOS e
            // Linux. Quelle di Nucleus sono già tutte dentro i suoi jar.
            implementation(compose.desktop.macos_arm64)
            implementation(compose.desktop.macos_x64)
            implementation(compose.desktop.windows_x64)
            implementation(compose.desktop.windows_arm64)
            implementation(compose.desktop.linux_x64)
            implementation(compose.desktop.linux_arm64)
            implementation(libs.nucleus.application)
            implementation(libs.nucleus.decorated.window.tao)
            implementation(libs.ktor.client.cio)
            implementation(libs.jlayer)
            // Motore nativo della mappa: Metal su macOS (solo Apple Silicon), Vulkan altrove.
            runtimeOnly(libs.maplibre.compose.runtime.metal.macos.arm64)
            runtimeOnly(libs.maplibre.compose.runtime.vulkan.windows.x64)
            runtimeOnly(libs.maplibre.compose.runtime.vulkan.windows.arm64)
            runtimeOnly(libs.maplibre.compose.runtime.vulkan.linux.x64)
            runtimeOnly(libs.maplibre.compose.runtime.vulkan.linux.arm64)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            runtimeOnly(libs.maplibre.compose.runtime.opengl.android)
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
    packageOfResClass = "com.takaotech.departureboard.resources"
}

nucleus.application {
    mainClass = "com.takaotech.departureboard.MainKt"
    jvmArgs(
        "-Dskiko.fps.enabled=true",
        "-Dskiko.fps.count=200",
        // MapLibre chiama il motore nativo con la Foreign Function API.
        "--enable-native-access=ALL-UNNAMED",
    )

    nativeDistributions {
        packageName = "DepartureBoard"
        packageVersion = libs.versions.app.version.get()
    }

    buildTypes.release.proguard {
        isEnabled = true
        optimize = true
        // Skiko e Nucleus chiamano il codice Kotlin da JNI: i nomi devono restare quelli.
        obfuscate = false
        joinOutputJars = true
        configurationFiles.from(project.file("proguard-desktop.pro"))
    }
}

// Jar unico per Windows, macOS e Linux (x64 e arm64): `java -jar DepartureBoard-<versione>.jar`.
// L'uber jar di Compose porta nel nome l'OS della build, ma dentro ha le native di tutti; qui
// si riscrive con il nome giusto e con Enable-Native-Access, che toglie l'avviso di JNI su Java 22+.
interface ArchiveOperationsHolder {
    @get:Inject
    val archives: ArchiveOperations
}

val packageUniversalJar by tasks.registering(Jar::class) {
    group = "distribution"
    description = "Jar desktop universale con ProGuard, in build/dist."
    val archives = objects.newInstance<ArchiveOperationsHolder>().archives
    val uberJar = tasks.named<Zip>("packageReleaseUberJarForCurrentOS").flatMap { it.archiveFile }
    from(uberJar.map { archives.zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
    destinationDirectory = layout.buildDirectory.dir("dist")
    archiveFileName = "DepartureBoard-${libs.versions.app.version.get()}.jar"
    manifest.attributes(
        "Main-Class" to "com.takaotech.departureboard.MainKt",
        "Multi-Release" to "true",
        "Enable-Native-Access" to "ALL-UNNAMED",
    )
}
