package com.tamacookie

import com.tamacookie.feature.ActRegistry
import com.tamacookie.feature.mischief.HideSeekMischief
import kotlin.random.Random

/**
 * Façade étroite que reçoivent les fonctionnalités (contrôleurs) au lieu du service entier.
 * Implémentée par [BunnyService] ; `FakePetCtx` en test.
 *
 * Une fonctionnalité agit sur l'animal qu'on lui passe (ou [pet], l'animal courant) et interroge le
 * monde ; elle n'appelle pas directement un autre contrôleur (sauf exceptions documentées dans docs/historique.md).
 */
interface PetCtx {
    // --- Monde ---
    val world: World
    /** L'animal courant ([World.current]) : celui dont on s'occupe (boucle, toucher, commande), sinon le résident. */
    val pet: Pet get() = world.current
    val arena: Arena
    val actor: BunnyActor
    val clock: Clock
    /** Aléatoire de l'animal courant ([Mind.rnd] : répliques, objets, décisions) — chaque animal a le sien. */
    val rnd: Random
    /** Réglages (port [SettingsStore] : SharedPreferences du fichier [Prefs.FILE] en jeu). */
    val prefs: SettingsStore
    /** Vues / pages du bureau et défilement (où se trouve chaque chose). */
    val pages: PageTracker
    /** Ce que voit le service d'accessibilité : plateformes, clavier, pages du bureau, signaux de l'utilisateur. */
    val screen: ScreenSensor
    /** Quelle fonctionnalité prend en charge l'activité courante d'un animal (stations…). */
    val acts: ActRegistry
    /** L'arbitre global entre animaux (une bêtise à la fois, le coffre, la nourriture, les bulles). */
    val director: Director
    /** Thème saisonnier du moment (évolution 4b ; [Season.NONE] si coupé dans les réglages ou hors saison). */
    val season: Season get() = Season.NONE

    // --- Jauges et sauvegarde (l'hôte est la seule autorité sur l'état des animaux) ---
    /**
     * Applique [event] aux jauges de [pet] ([PetRules]) ; s'il compte ([PetEvent.savesNow]) et n'est pas
     * refusé, l'animal est sauvegardé tout de suite. Seul chemin par lequel une fonctionnalité change les jauges.
     */
    fun on(pet: Pet, event: PetEvent): PetRules.Result {
        val r = pet.on(event, clock.wallMs(), rules)
        if (event.savesNow && !r.refused) save(pet)
        return r
    }
    /** Règles du jeu (taux, seuils) : [GameRules.DEFAULT] en jeu. */
    val rules: GameRules get() = GameRules.DEFAULT
    /** Sauvegarde [pet] (port `PetStore`). */
    fun save(pet: Pet)

    // --- État global ---
    /** Tout est masqué (« Cacher ») : aucune fenêtre. */
    val hidden: Boolean
    fun foregroundPkg(): String?
    /** Nom lisible d'une application (null si inconnue). */
    fun appLabel(pkg: String): String?

    // --- Paroles ---
    val speech: Speech
    fun say(text: String, seconds: Float = Speech.DEFAULT_SECONDS)
    fun pick(vararg s: String): String

    // --- Effets ponctuels (particules, retour haptique) : envoyés au rendu de l'animal ---
    fun emit(pet: Pet, type: PType, n: Int = 1)
    fun haptic(pet: Pet, feedback: Int)
    fun vibrate(pattern: LongArray)

    // --- Interactions entre fonctionnalités (questions au monde plutôt qu'appels de contrôleur) ---
    /** Dernière interaction de l'utilisateur (heure murale, ms) : les bêtises attendent qu'il l'oublie. */
    var lastInteraction: Long
    fun noteInteraction() { lastInteraction = clock.wallMs() }
    /**
     * Vrai (et il le dit) s'il est occupé : à cache-cache ou dans une station ([World.busy]).
     * La réplique vient du comportement de la station ([com.tamacookie.feature.station.StationBehavior.busyLine]).
     */
    fun sayIfBusy(pet: Pet = this.pet): Boolean {
        if (pet.isHiding) { say(HideSeekMischief.BUSY_LINE); return true }
        val k = world.stationOf(pet) ?: return false
        say(k.behavior.busyLine(this))
        return true
    }
    fun startSleep(pet: Pet)

    // --- Boucle, notification, menu ---
    fun requestFrames()
    /** Un doigt touche une de nos fenêtres : l'écran est donc allumé et utilisable (réveille la boucle au besoin). */
    fun userTouched() = requestFrames()
    fun updateNotification(force: Boolean)
    fun closeMenu()
    fun hide()

    // --- Fenêtres : toutes passent par [overlay] (ids par entité) ---
    /** La seule autorité sur les fenêtres overlay (`OverlayManager` ; faux hôte en test). */
    val overlay: OverlayHost
    fun applyView()
    fun layoutAnchored()
    /** Fenêtre de [pet] retirée (en cage sur une autre page, caché). */
    fun isAway(pet: Pet): Boolean
    /** Retire ([away]) ou réaffiche la fenêtre de [pet] ([fadeIn] : petite apparition en fondu). */
    fun setAway(pet: Pet, away: Boolean, fadeIn: Boolean = false)
    /** Fenêtre de [pet] à sa position (regroupé : au plus un déplacement par image). */
    fun place(pet: Pet)
    /** Raccourcis sur l'animal courant ([pet]). */
    val bunnyAway: Boolean get() = isAway(pet)
    fun setBunnyAway(away: Boolean, fadeIn: Boolean = false) = setAway(pet, away, fadeIn)
    fun placeBunny() = place(pet)
    fun setDockHiding(hiding: Boolean)
}
