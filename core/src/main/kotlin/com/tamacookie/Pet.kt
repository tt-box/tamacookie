package com.tamacookie

import com.tamacookie.feature.mischief.MischiefState
import com.tamacookie.feature.toy.Food
import com.tamacookie.social.Engagement
import com.tamacookie.feature.toy.Toy
import com.tamacookie.persist.PetRecord
import com.tamacookie.species.Species
import kotlin.random.Random

/** Identifiant stable d'un animal : UUID créé à l'adoption (ou à la migration), clé de la persistance et des échanges. */
@JvmInline
value class PetId(val value: String)

/** D'où vient l'animal. Seul le résident existe pour l'instant (les visiteurs viendront avec l'échange). */
sealed interface Origin {
    data object Resident : Origin
}

/** Qui est l'animal (immuable ; renommer = nouvelle [Identity] de même [id]). */
data class Identity(
    val id: PetId,
    val name: String = DEFAULT_NAME,
    /** Date de naissance (heure murale, ms). */
    val bornAt: Long = 0L,
    /** Identifiant d'espèce ([Species.id], sauvegardé) ; résolu par [Species.of]. */
    val species: String = Species.DEFAULT.id,
    val origin: Origin = Origin.Resident,
) {
    companion object {
        const val DEFAULT_NAME = "Cookie"
        /** Longueur maximale d'un nom (écran d'adoption, renommage, échange). */
        const val MAX_NAME = 16
    }

    /** Âge en jours à l'heure murale [now]. */
    fun ageDays(now: Long): Int = ((now - bornAt) / 86_400_000L).toInt()
}

/**
 * Corps : position (dans [phys]), activité courante, posture et orientation.
 * Les pieds sont en ([fx], [fy]), dans les coordonnées de sa page (voir [PageTracker.bunnyShift]).
 * Le rendu en est tiré à chaque image ([PetRenderState.of]) : la vue ne lit ni n'écrit le corps.
 */
class Body(val phys: BunnyPhysics = BunnyPhysics()) {
    var fx: Float
        get() = phys.x
        set(v) { phys.x = v }
    var fy: Float
        get() = phys.y
        set(v) { phys.y = v }

    /** Activité courante : ne change que par [setAct] / [setActOrAfterAir]. */
    var act = Act.IDLE
        private set
    /** Temps passé dans l'activité courante (s). */
    var actTime = 0f
    /** Durée prévue de l'activité courante (s), interprétée par l'activité elle-même. */
    var actDuration = 3f
        private set
    /** Cible horizontale (marche, voyage, cache-cache…). */
    var targetX = 0f
    /** Activité reprise à l'atterrissage. */
    var afterAir = Act.IDLE

    var pose = Pose.IDLE
    /** 1 = vers la droite, -1 = vers la gauche. */
    var facing = 1f
    /** 0..1 : cycle de sautillement pendant la marche. */
    var walkPhase = 0f
    /** 0..1 : avancement du repas en cours (`Act.EAT`). */
    var eatProgress = 0f
    /** Ce qu'il mange (ou a mangé en dernier) : ce qu'il a dans la bouche. */
    var eatingFood = Food.CARROT

    /** Icône / bouton sous ses pattes, null = le sol. */
    var platform: Box? = null
    var platformLabel: String? = null
    /** Version des plateformes ([PlatformSet.version]) déjà vérifiée pour la plateforme courante. */
    var platVersionSeen = -1

    // --- Sa page du bureau ([PageTracker] : chaque animal a la sienne) ---
    /** Page du bureau où il se trouve (0 = pas sur le bureau). */
    var page = 0
    /** Décalage à l'écran quand sa page n'est pas (exactement) celle affichée. */
    var pageShift = 0f
    /** Quand il revient sur la page affichée (uptime ms), 0 = pas programmé. */
    var pageReturnAt = 0L
    /** Sens de son exploration de la page voisine. */
    var wanderDir = 1

    fun setAct(a: Act, duration: Float = 3f) {
        act = a; actTime = 0f; actDuration = duration
    }

    /** Comme [setAct], mais en plein vol l'activité est seulement programmée pour l'atterrissage. */
    fun setActOrAfterAir(a: Act, duration: Float = 3f) {
        if (act == Act.AIR) afterAir = a else setAct(a, duration)
    }
}

/**
 * Esprit : cerveau (décisions, selon l'espèce), son aléatoire (propre à chaque animal), expression forcée et
 * petits minuteurs de comportement.
 */
class Mind(val rnd: Random, species: Species = Species.DEFAULT) {
    val brain = BunnyBrain(rnd, species)

    var exprOverride: Expr? = null
    var exprTimer = 0f

    /** Prochain bavardage spontané (s). */
    var chatterIn = 25f
    /** Durée de la partie de jeu en cours (s). */
    var playTime = 0f
    /** Jouet ([Prop.id]) dont il a fini sa partie : il n'y revient pas (les autres peuvent y jouer) ; -1 = aucun. */
    var playedOut = -1
    /** Délai avant un nouveau coup de patte / de tête dans un jouet (s). */
    var kickCooldown = 0f
    var danceTime = 0f
    var danceHopIn = 0f
    /** Voyage d'app en app : réplique à l'arrivée et activité ensuite. */
    var travelComment: String? = null
    var travelThen = Act.IDLE
    /** Dernier voyage (heure murale, ms). */
    var lastTravelAt = 0L
    /** État de sa bêtise en cours (une seule à la fois dans le monde : `Director`). */
    val mischief = MischiefState()
    /** Interaction en cours avec un autre animal ([Act.SOCIAL], `SocialSystem`), null = aucune. */
    var social: Engagement? = null

    fun express(e: Expr, seconds: Float) { exprOverride = e; exprTimer = seconds }

    /** Fait avancer les minuteurs propres à l'esprit. */
    fun tick(dt: Float) {
        if (exprTimer > 0f) { exprTimer -= dt; if (exprTimer <= 0f) exprOverride = null }
        if (kickCooldown > 0f) kickCooldown -= dt
    }
}

/**
 * Un animal : une entité faite de composants. Jamais de singleton « le lapin » :
 * toute logique qui concerne un animal reçoit son [Pet].
 *
 * Ses jauges ([stats], immuables) ne changent que par [on] (règles [PetRules]) ; sa sauvegarde passe par
 * le port [com.tamacookie.persist.PetStore] ([toRecord] / [fromRecord]).
 */
class Pet(
    identity: Identity,
    var traits: Traits,
    var look: Look,
    var stats: PetStats,
    val body: Body,
    val mind: Mind,
    /** L'écran d'adoption a été validé. */
    var adopted: Boolean = true,
) {
    var identity: Identity = identity
        private set
    val id: PetId = identity.id
    /** Son espèce (plugin) : dessin, gabarit, influence du caractère, répliques. */
    val species: Species = Species.of(identity.species)
    val name: String get() = identity.name
    val personality: Personality get() = traits.personality
    val favoriteToy: Toy get() = traits.favoriteToy

    val act: Act get() = body.act
    /** En train de jouer à cache-cache (course, caché, ou tête qui dépasse). */
    val isHiding: Boolean get() = body.act in Act.HIDING

    fun rename(name: String) { identity = identity.copy(name = name) }

    /** Applique un événement à ses jauges ([PetRules]) et renvoie le résultat (effets à mettre en scène). */
    fun on(event: PetEvent, now: Long, rules: GameRules = GameRules.DEFAULT): PetRules.Result =
        PetRules.apply(stats, event, now, traits, rules).also { stats = it.stats }

    fun setAct(a: Act, duration: Float = 3f) = body.setAct(a, duration)
    fun setActOrAfterAir(a: Act, duration: Float = 3f) = body.setActOrAfterAir(a, duration)
    fun express(e: Expr, seconds: Float) = mind.express(e, seconds)

    /** Il refuserait [event] (repu, trop fatigué) — sans rien changer. */
    fun wouldRefuse(event: PetEvent, now: Long, rules: GameRules = GameRules.DEFAULT): Boolean =
        PetRules.apply(stats, event, now, traits, rules).refused

    /** Ce qui se sauvegarde de lui ([PetRecord.lastUpdate] est horodaté par le magasin). */
    fun toRecord(): PetRecord = PetRecord(identity, traits, look, stats, adopted)

    companion object {
        /** Un animal chargé (sans rattrapage hors-ligne : à l'appelant d'appliquer [PetEvent.Elapsed]). */
        fun fromRecord(r: PetRecord, phys: BunnyPhysics, rnd: Random): Pet =
            Pet(r.identity, r.traits, r.look, r.stats, Body(phys), Mind(rnd, Species.of(r.identity.species)), r.adopted)
    }
}
