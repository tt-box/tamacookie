package com.tamacookie

import com.tamacookie.feature.toy.Food
import com.tamacookie.feature.toy.Poop
import com.tamacookie.feature.toy.PropSpec
import com.tamacookie.feature.toy.Toy
import kotlin.math.roundToInt

/**
 * Objet posé à l'écran (nourriture, crotte, jouet) : sa petite physique et ce qu'affiche sa fenêtre
 * ([spin], [tint]). Kotlin pur : la fenêtre ([PropView]) est fabriquée par l'adaptateur et lit cet état.
 * [w] × [h] = taille de la fenêtre (px) ; les pieds de l'objet sont en ([x], [y]) (centre bas).
 */
class Prop(val id: Int, val spec: PropSpec, val w: Int, val h: Int) : PropBody {
    override var x = 0f
    override var y = 0f
    override var vx = 0f
    override var vy = 0f
    override var onGround = false
    var age = 0f
    /** L'animal qui le tient (gueule, pattes) ; null = posé ou en vol. Lui seul le relance (jeu partagé). */
    var holder: PetId? = null
    val held: Boolean get() = holder != null
    var anchor = ""           // vue (app / page du bureau) où l'objet est posé
    /** Crotte : l'animal qui l'a faite (ramassée = ses jauges), null = objet partagé. */
    var owner: PetId? = null
    /** Rotation du dessin (degrés), animée par la physique de l'objet. */
    var spin = 0f
    /** Teinte (jouets à couleur aléatoire). */
    var tint = Palette.BALL

    val windowId = WindowId.Prop(id)
    val toy: Toy? get() = spec.toy
    val food: Food? get() = spec.food
    val isPoop: Boolean get() = spec === Poop

    /** Position de la fenêtre (coin haut gauche, coordonnées de sa page). */
    val left: Int get() = (x - w / 2f).roundToInt()
    val top: Int get() = (y - h).roundToInt()
}
