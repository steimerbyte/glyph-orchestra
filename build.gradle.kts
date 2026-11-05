plugins {
    // 8.11.1 ist die erste Linie, die compileSdk 36 ohne Warnung baut -- und
    // targetSdk 36 ist hier kein Zierwert: ab Android 16 prueft Nothing OS
    // den API-Schluessel des GDK nicht mehr.
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
