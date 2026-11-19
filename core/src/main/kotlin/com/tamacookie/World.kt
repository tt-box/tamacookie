package com.tamacookie

import com.tamacookie.feature.station.Occupancy
import com.tamacookie.feature.station.StationKind

/**
 * Géométrie de l'écran partagée par tous les animaux (pixels), recalculée par le service
 * à chaque changement de taille / d'orientation.
 */
class Arena {
    /** Densité (px par dp). */
    var d = 1f
    /** Taille de la fenêtre d'un lapin. */
    var bw = 0
    var bh = 0
    /** Marge sous les pieds dans la fenêtre du lapin. */
    var feetPad = 0f
    var screenW = 0f
    var groundY = 0f
    var topY = 0f
    var minX = 0f
    var maxX = 0f
}

/**
 * Le monde : les animaux et ce qu'ils partagent (objets posés, occupation des stations).
 * Le résident (celui de ce téléphone) et ses compagnons ; rien ne suppose qu'il est unique.
 *
 * **Animal courant** ([current]) : celui dont la boucle, le toucher ou une commande s'occupe en ce moment
 * ([asPet]) — le résident par défaut. C'est lui que les fonctionnalités voient en `PetCtx.pet` (et dont
 * la bulle, le `Random` et la page sont utilisés) ; elles reçoivent toujours le `Pet` quand elles agissent.
 */
class World {
    val pets = LinkedHashMap<PetId, Pet>()

    /** Le résident ([resident]) ; null = le premier ajouté. */
    var residentId: PetId? = null
    private var focusId: PetId? = null

    /** Nourriture, crottes et jouets posés à l'écran (gérés par [ToyController]). */
    val props = ArrayList<Prop>()
    private var lastPropId = 0

    /** Identifiant d'un nouvel objet (clé de sa fenêtre : [WindowId.Prop]). */
    fun newPropId(): Int = ++lastPropId

    /** Qui occupe chaque station, dans l'ordre de [StationKind] (leur état affiché est dans `StationController`). */
    private val stations: Array<Occupancy> = Array(StationKind.entries.size) { Occupancy(StationKind.entries[it]) }

    /** Ajoute [pet] ; [resident] : il devient le résident. */
    fun add(pet: Pet, resident: Boolean = false) {
        pets[pet.id] = pet
        if (resident) residentId = pet.id
    }

    /** Retire l'animal [id] du monde (et de sa station). */
    fun remove(id: PetId): Pet? {
        for (s in stations) s.leave(id)
        if (focusId == id) focusId = null
        if (residentId == id) residentId = null
        return pets.remove(id)
    }

    fun station(k: StationKind): Occupancy = stations[k.ordinal]

    /** Station où se trouve [pet] (null = dehors). Sans allocation : appelé à chaque image. */
    fun stationOf(pet: Pet): StationKind? {
        val id = pet.id
        for (s in stations) if (s.has(id)) return s.kind
        return null
    }

    /** Occupé (cache-cache ou dans une station) : on ne peut ni le nourrir ni jouer avec lui. */
    fun busy(pet: Pet): Boolean = pet.isHiding || stationOf(pet) != null

    /** L'animal résident (celui de [residentId], sinon le premier ajouté). */
    val resident: Pet get() = residentId?.let { pets[it] } ?: pets.values.first()

    /** L'animal dont on s'occupe en ce moment ([asPet]), sinon le résident. */
    val current: Pet get() = focusId?.let { pets[it] } ?: resident

    /** Exécute [block] avec [p] pour animal courant ([current]), puis rétablit le précédent. */
    inline fun <T> asPet(p: Pet, block: () -> T): T {
        val prev = swapFocus(p.id)
        try { return block() } finally { swapFocus(prev) }
    }

    @PublishedApi internal fun swapFocus(id: PetId?): PetId? { val prev = focusId; focusId = id; return prev }

    /**
     * Les animaux affichés, résident en tête, au plus [Tuning.MAX_PETS_SHOWN] (coût IPC des fenêtres) :
     * les autres « dorment hors écran » (jauges seulement). [into] est vidée puis remplie (pas d'allocation).
     */
    fun shown(into: ArrayList<Pet>): ArrayList<Pet> {
        into.clear()
        if (pets.isEmpty()) return into
        val r = resident
        into.add(r)
        for (p in pets.values) {
            if (into.size >= Tuning.MAX_PETS_SHOWN) break
            if (p !== r) into.add(p)
        }
        return into
    }

    fun isShown(p: Pet): Boolean {
        if (p === resident) return true
        var i = 1
        for (q in pets.values) {
            if (q === resident) continue
            if (i >= Tuning.MAX_PETS_SHOWN) return false
            if (q === p) return true
            i++
        }
        return false
    }

    /** Les autres animaux affichés que [p] (perception sociale). */
    fun othersShown(p: Pet, into: ArrayList<Pet>): ArrayList<Pet> {
        shown(into)
        into.remove(p)
        return into
    }

    fun currentToy(): Prop? = props.firstOrNull { it.toy != null }
    fun hasToy(): Boolean = props.any { it.toy != null }
    fun hasFood(): Boolean = props.any { it.food != null }
    fun hasToyOrFood(): Boolean = props.any { it.toy != null || it.food != null }

    /** [pet] est attrapé par l'utilisateur : il lâche son jouet (s'il le tient, pas celui d'un autre). */
    fun dropHeldToy(pet: PetId) {
        currentToy()?.let { if (it.holder == pet) { it.holder = null; it.onGround = false } }
    }
}
