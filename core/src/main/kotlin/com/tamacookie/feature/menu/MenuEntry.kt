package com.tamacookie.feature.menu

import com.tamacookie.Act
import com.tamacookie.CommonLines
import com.tamacookie.Expr
import com.tamacookie.PType
import com.tamacookie.Pet
import com.tamacookie.PetCtx
import com.tamacookie.PetEvent
import com.tamacookie.PetStats

/** Ce que les entrées du menu peuvent demander, en plus de [PetCtx] (panneaux et jeux pilotés par le toucher). */
interface MenuCtx : PetCtx {
    /** Panneau 🏠 (installer / ranger les objets sur la vue courante). */
    fun toggleStationsPanel()
    /** Cache-cache lancé par l'utilisateur : il explique pourquoi s'il ne peut pas. */
    fun startHideSeek(): Boolean
    /** Voyage vers un autre téléphone (contact NFC puis Bluetooth) : ouvre l'écran d'envoi de [pet]. */
    fun startTravel(pet: Pet)
}

/**
 * Une entrée du menu rapide (double toucher / appui long sur l'animal) : un bouton « emoji + libellé »
 * et son action. [refresh] donne le texte du bouton selon l'état de l'animal (relu chaque seconde) ;
 * null = texte fixe. [run] est appelé une fois le menu fermé.
 */
class MenuEntry(
    val emoji: String,
    val label: String,
    private val refresh: ((PetStats) -> String)? = null,
    val run: (ctx: MenuCtx, pet: Pet) -> Unit,
) {
    private val fixed = "$emoji\n$label"

    /** Le texte change selon l'état de l'animal. */
    val dynamic: Boolean get() = refresh != null

    /** Texte du bouton (emoji, retour à la ligne, libellé). */
    fun text(st: PetStats): String = refresh?.invoke(st) ?: fixed
}

/** Les entrées du menu, dans l'ordre d'affichage (deux par rangée). */
object MenuEntries {
    val SLEEP = MenuEntry("💤", "Dodo", refresh = { if (it.sleeping) "☀️\nRéveil" else "💤\nDodo" }) { ctx, pet ->
        val st = pet.stats
        if (st.sleeping) {
            ctx.on(pet, PetEvent.Woken(grumpy = false))
            pet.express(Expr.SLEEPY, 2f)
            ctx.say(pet.species.lines.GETTING_UP)
            pet.setActOrAfterAir(Act.IDLE, 3f)
        } else if (st.energy > ctx.rules.notTiredAbove) {
            ctx.speech.sayOneOf(pet.species.lines.NOT_TIRED)
        } else ctx.startSleep(pet)
    }

    val STATIONS = MenuEntry("🏠", "Objets") { ctx, _ -> ctx.toggleStationsPanel() }

    val HIDE_SEEK = MenuEntry("🫣", "Cache-cache") { ctx, _ -> ctx.startHideSeek() }

    val HIDE = MenuEntry("🙈", "Ranger") { ctx, _ -> ctx.hide() }

    // --- Évolution 4a ---
    private val LULLABIES = arrayOf("🎵 Dodo, l'enfant do… 🎶", "🎶 Fais de beaux rêves… 🎵")
    private val BRUSHED = arrayOf("Ooh, ça fait du bien ! 🪥", "Tout doux, tout beau ✨", "Mon pelage brille !")

    /** Chanter : il chante (des notes) et danse s'il est libre ; endormi, c'est une berceuse (il ne se réveille pas). */
    val SING = MenuEntry("🎵", "Chanter") { ctx, pet ->
        if (pet.stats.sleeping) {
            ctx.emit(pet, PType.NOTE, 2)
            ctx.speech.sayOneOf(LULLABIES)
            return@MenuEntry
        }
        ctx.emit(pet, PType.NOTE, 4)
        pet.express(Expr.HAPPY, 2f)
        ctx.speech.sayOneOf(pet.species.lines.SONGS)
        if (pet.body.act in Act.FREE) {
            // comme `PetBehavior.startDance` (la danse dure 9 s, un saut toutes les 0,55 s)
            pet.mind.danceTime = 0f; pet.mind.danceHopIn = 0f
            pet.setAct(Act.DANCE)
        }
    }

    /** Brosser : un peu plus propre et content (`PetEvent.Brushed`) ; pas pendant son sommeil. */
    val BRUSH = MenuEntry("🪥", "Brosser") { ctx, pet ->
        if (pet.stats.sleeping) { ctx.say(CommonLines.ASLEEP); return@MenuEntry }
        ctx.on(pet, PetEvent.Brushed)
        ctx.emit(pet, PType.SPARKLE, 3)
        pet.express(Expr.HAPPY, 1.5f)
        ctx.speech.sayOneOf(BRUSHED)
    }

    /** Voyager : il part sur le téléphone d'un ami (l'écran d'envoi explique le contact NFC). */
    val TRAVEL = MenuEntry("🧳", "Voyager") { ctx, pet -> ctx.startTravel(pet) }

    val ALL: List<MenuEntry> = listOf(SLEEP, STATIONS, HIDE_SEEK, HIDE, SING, BRUSH, TRAVEL)

    /** Boutons par rangée. */
    const val PER_ROW = 2
}
