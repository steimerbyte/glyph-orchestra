import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signatur aus keystore.properties im Projekt-Root. Die Datei steht in
// .gitignore und wird nicht committet.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}

// API-Schluessel des Glyph Developer Kit. Ab Android 16 fragt Nothing OS ihn
// nicht mehr ab; auf aelteren Nothing-OS-Staenden gilt nur ein eigener
// Schluessel oder der GDK-Testschluessel "test" zusammen mit
//   adb shell settings put global nt_glyph_interface_debug_enable 1
// Ein eigener Schluessel kommt per -PnothingKey=... oder aus
// ~/.gradle/gradle.properties.
val nothingKey = (project.findProperty("nothingKey") as String?) ?: "test"

val gdkAar = file("libs/glyph-matrix-sdk-2.0.aar")
if (!gdkAar.exists()) {
    throw GradleException(
        "Glyph Developer Kit fehlt: ${gdkAar.path}\n" +
            "Einmalig ausfuehren: ./scripts/fetch-gdk.sh"
    )
}

android {
    namespace = "com.steimerbyte.glyphorchestra"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.steimerbyte.glyphorchestra"
        // Das GDK laeuft laut README nur ab Android 14, das 3a Pro startet
        // nicht darunter.
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        manifestPlaceholders["nothingKey"] = nothingKey
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile") ?: "glyphorchestra.keystore")
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Die About-Sektion nennt die Version aus BuildConfig.
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Analyse im Hintergrund; Dispatchers.Main kommt aus dem Android-Artefakt.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Das offizielle SDK: com.nothing.ketchum (Glyph, GlyphFrame, GlyphManager).
    implementation(files("libs/glyph-matrix-sdk-2.0.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Ein von Hand getipptes `assembleRelease` darf die Tests nicht ueberspringen.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn("testDebugUnitTest")
}
