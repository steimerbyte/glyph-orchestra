package com.steimerbyte.glyphorchestra.device

import android.os.Build

/**
 * Was auf diesem Geraet moeglich ist.
 *
 * Das GDK kennt zwei Bauarten: einzelne Segmente (Kanalzahl, `setFrameColors`)
 * und Matrix-Geraete (Pixelgitter, `setAppMatrixFrame`). Diese App steuert
 * Segmente; ein Matrix-Geraet wird deshalb erkannt und benannt, aber nicht
 * angesprochen -- ein Kanalindex auf einer Matrix waere keine LED, sondern
 * eine Zahl.
 */
sealed interface Detection {

    data class Supported(val spec: DeviceSpec) : Detection

    data class Matrix(val model: String, val name: String) : Detection

    data class Unknown(val model: String) : Detection
}

/**
 * Welches Geraet ist das?
 *
 * Das GDK fragt `Build.MODEL` gegen seine `DEVICE_*`-Konstanten -- dieselbe
 * Auskunft, nur ohne den Dienst zu binden. Der Test setzt [model], damit sich
 * jedes Geraet ohne Geraet durchspielen laesst.
 */
object DeviceDetector {

    var model: () -> String = { Build.MODEL }

    fun reset() {
        model = { Build.MODEL }
    }

    fun detect(): Detection = of(model())

    fun of(name: String): Detection {
        // Vor forModel: "A069P" faengt sonst beim Praefix von "A069" ab.
        DeviceCatalog.matrixName(name)?.let { return Detection.Matrix(name, it) }
        DeviceCatalog.forModel(name)?.let { return Detection.Supported(it) }
        return Detection.Unknown(name)
    }
}
