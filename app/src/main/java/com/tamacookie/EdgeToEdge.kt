package com.tamacookie

import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * Affichage « bord à bord » (imposé à partir de la cible 35) : le contenu des écrans de l'app passe sous la barre
 * d'état et la barre de navigation. On garde le fond crème jusqu'aux bords, mais le contenu ([content]) est décalé
 * de la taille des barres système, de la découpe de la caméra et du clavier ; les icônes des barres restent foncées
 * sur le fond clair.
 */
object EdgeToEdge {
    fun apply(window: Window, root: View, content: View) {
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(light, light)
        val l = content.paddingLeft; val t = content.paddingTop
        val r = content.paddingRight; val b = content.paddingBottom
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
            )
            content.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
            WindowInsets.CONSUMED
        }
        root.requestApplyInsets()
    }
}
