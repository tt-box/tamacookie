package com.tamacookie

import com.tamacookie.feature.mischief.Mischiefs
import com.tamacookie.feature.station.StationKind
import java.util.EnumSet
import kotlin.math.abs

/** Importance d'une réplique quand plusieurs animaux veulent parler ([Director.BubbleQueue]). */
enum class SpeechPriority {
    /** Bavardage, échanges entre animaux : abandonné s'il doit trop attendre. */
    LOW,
    /** Ce que fait l'animal (repas, chute, voyage…). */
    NORMAL,
    /** Réponse à l'utilisateur (toucher, menu, commande) : passe devant. */
    HIGH,
}

/**
 * L'arbitre global quand plusieurs animaux vivent ensemble (Kotlin pur, testé en JVM) : ce qui ne peut
 * arriver qu'**une fois à la fois** sur l'écran.
 *
 * - **Bêtises** : un seul animal en fait une ([mayStartMischief], [mischiefOwner]) ;
 * - **coffre**, **gamelle** : un seul animal va s'y servir de lui-même ([mayHeadTo]) ; les stations de
 *   capacité 1 sont gardées par l'occupation du monde (`Occupancy.canEnter`) ;
 * - **nourriture** : quand deux animaux courent vers la même, le plus proche l'emporte ([foodWinner]),
 *   l'autre renonce à celle-ci ([foodTarget]) ;
 * - **bulles** : une seule réplique à l'écran à la fois, les autres attendent par priorité ([bubbles]) ;
 * - **notifications** : un titre, une ligne et des alertes qui résument tous les animaux ([title], [alerts]).
 *
 * Tout est **déduit du monde** (activités, occupation) plutôt que réservé : rien à libérer, rien qui fuie
 * quand un animal part. Avec un seul animal, chaque question répond « oui » sans tirage : comportement
 * identique à avant.
 */
class Director(private val world: World) {

    // =====================================================================
    //  Bêtises : une à la fois
    // =====================================================================
    /** L'animal en pleine bêtise (squat, toc-toc, cache-cache…), null = aucun. */
    fun mischiefOwner(): Pet? {
        for (p in world.pets.values) if (inMischief(p)) return p
        return null
    }

    /** [p] peut commencer une bêtise : aucun autre animal n'en fait une. */
    fun mayStartMischief(p: Pet): Boolean {
        for (q in world.pets.values) if (q !== p && inMischief(q)) return false
        return true
    }

    private fun inMischief(p: Pet) = p.act in MISCHIEF_ACTS || p.mind.mischief.hidingView != null

    // =====================================================================
    //  Coffre, gamelle : un seul animal à la fois
    // =====================================================================
    /**
     * [p] peut aller de lui-même à la station [k] (se servir au coffre, manger à la gamelle) : aucun autre
     * animal n'est en route ([GOING_TO]) ni dedans. Pas de course à deux : le premier qui décide part, les
     * autres ne la voient plus (le cerveau ne tire même pas au sort).
     */
    fun mayHeadTo(p: Pet, k: StationKind): Boolean {
        val going = GOING_TO[k]
        val occ = world.station(k)
        for (q in world.pets.values) {
            if (q === p) continue
            if ((going != null && q.act == going) || occ.has(q.id)) return false
        }
        return true
    }

    // =====================================================================
    //  Chasseur de pubs : un seul animal appuie ([adHunt])
    // =====================================================================
    val adHunt = AdHunt(world)

    /** Le bouton « Ignorer l'annonce » d'une publicité : un seul animal y va, le plus proche. */
    class AdHunt(private val world: World) {
        /** L'animal parti appuyer (en course, en plein saut vers le bouton, dessus), null = aucun. */
        fun hunter(): Pet? = world.pets.values.firstOrNull { huntsAd(it) }

        /**
         * Qui part appuyer sur le bouton [target] : personne si un animal y est déjà ([hunter]) ; sinon, parmi
         * ceux qui le peuvent ([eligible]), le plus proche (distance horizontale) ; à égalité, l'ordre du monde.
         */
        fun hunterFor(target: Box, eligible: (Pet) -> Boolean): Pet? {
            if (hunter() != null) return null
            return world.pets.values.filter(eligible).minByOrNull { abs(it.body.fx - target.centerX) }
        }
    }

    // =====================================================================
    //  Nourriture : le plus proche l'emporte
    // =====================================================================
    /** Animal → nourritures ([Prop.id]) qu'il a perdues au profit d'un autre (il n'y retourne pas). */
    private val lostFood = HashMap<PetId, MutableSet<Int>>()

    /**
     * Parmi les animaux qui courent vers [food] (`GOTO_FOOD`, et c'est la nourriture la plus proche d'eux qu'ils
     * n'ont pas perdue), celui qui l'aura : le plus proche ; à égalité, le plus affamé ; puis l'ordre du monde (le
     * résident d'abord).
     */
    fun foodWinner(food: Prop): Pet? {
        var best: Pet? = null
        for (p in world.pets.values) {
            if (p.act != Act.GOTO_FOOD || nearestFood(world, p, lostFood[p.id]) !== food) continue
            val b = best
            if (b == null) { best = p; continue }
            if (closer(p, b, food)) best = p
        }
        return best
    }

    /** Un différend tranché : [loser] a perdu la nourriture au profit de [winner] (mis en scène par `SocialSystem`). */
    data class Dispute(val loser: PetId, val winner: PetId)
    private val disputes = ArrayList<Dispute>()

    /**
     * [p] a perdu [food] au profit de [winner] : il n'y retourne pas (il peut viser une autre nourriture), et le
     * différend est noté pour [takeDisputes].
     */
    fun loseFood(p: Pet, food: Prop, winner: Pet? = null) {
        lostFood.getOrPut(p.id) { HashSet() } += food.id
        if (winner != null) disputes += Dispute(p.id, winner.id)
    }

    /** Les différends tranchés depuis le dernier appel (la file est vidée). */
    fun takeDisputes(): List<Dispute> {
        if (disputes.isEmpty()) return emptyList()
        return disputes.toList().also { disputes.clear() }
    }

    /**
     * La nourriture vers laquelle [p] court, ou null : la plus proche de lui parmi celles posées. Plusieurs peuvent
     * être au sol ; aucune n'est réservée, le plus proche l'emporte ([foodWinner]). Une nourriture qu'il a perdue
     * redevient possible s'il est **devenu** le plus proche (on l'a déplacé, ou elle a bougé) — et l'ancien gagnant
     * renonce à son tour.
     */
    fun foodTarget(p: Pet): Prop? {
        val lost = lostFood[p.id]
        if (lost != null) {
            lost.retainAll { id -> world.props.any { it.id == id && it.food != null } }   // disparues : oubliées
            for (f in world.props) {
                if (f.food == null || f.id !in lost) continue
                val winner = foodWinner(f)
                if (winner == null || closer(p, winner, f)) lost -= f.id
            }
            if (lost.isEmpty()) lostFood.remove(p.id)
        }
        return nearestFood(world, p, lostFood[p.id])
    }

    // =====================================================================
    //  Bulles : une à la fois, file par priorité
    // =====================================================================
    val bubbles = BubbleQueue()

    /**
     * Une seule bulle « qui parle » à la fois. Un animal remplace toujours sa propre bulle (un seul animal :
     * comportement d'avant) ; un autre passe devant s'il est plus prioritaire, sinon il attend son tour
     * (au plus [MAX_QUEUE], les répliques [SpeechPriority.LOW] trop anciennes sont abandonnées).
     */
    class BubbleQueue {
        data class Line(val pet: PetId, val text: String, val seconds: Float, val priority: SpeechPriority) {
            internal var waited = 0f
        }

        /** Réplique à l'écran (null = personne ne parle). */
        var current: Line? = null
            private set
        private var remaining = 0f
        private val queue = ArrayList<Line>()
        val waiting: List<Line> get() = queue

        /** Demande de parler : true = à afficher maintenant (sinon en attente ou abandonnée). */
        fun request(l: Line): Boolean {
            val c = current
            if (c == null || remaining <= 0f || c.pet == l.pet || l.priority > c.priority) {
                show(l); return true
            }
            queue.removeAll { it.pet == l.pet }            // une seule réplique en attente par animal
            if (queue.size >= MAX_QUEUE) {
                val worst = queue.minByOrNull { it.priority } ?: return false
                if (worst.priority >= l.priority) return false
                queue.remove(worst)
            }
            queue += l
            return false
        }

        private fun show(l: Line) {
            current = l; remaining = l.seconds
            queue.removeAll { it.pet == l.pet }
        }

        /** Fait vieillir la bulle courante ; renvoie la réplique suivante à afficher (null = rien). */
        fun tick(dt: Float): Line? {
            for (l in queue) l.waited += dt
            queue.removeAll { it.priority == SpeechPriority.LOW && it.waited > LOW_MAX_WAIT_S }
            if (current == null) return null
            remaining -= dt
            if (remaining > 0f) return null
            current = null
            val next = queue.maxWithOrNull(compareBy<Line> { it.priority }.thenByDescending { it.waited }) ?: return null
            show(next)
            return next
        }

        /** L'animal [pet] se tait (fenêtre retirée, parti) : sa bulle et ses répliques en attente sont oubliées. */
        fun forget(pet: PetId) {
            queue.removeAll { it.pet == pet }
            if (current?.pet == pet) { current = null; remaining = 0f }
        }

        companion object {
            const val MAX_QUEUE = 3
            const val LOW_MAX_WAIT_S = 5f
        }
    }

    // =====================================================================
    //  Notifications agrégées
    // =====================================================================
    /** Titre de la notification permanente : le résident, et le nombre de compagnons. */
    fun title(moodText: (Pet) -> String): String {
        val r = world.resident
        val n = world.pets.size - 1
        return NotifierTexts.title(r.name, moodText(r), companions = n)
    }

    /** Ligne de la notification : jauges du résident, puis l'humeur de chaque compagnon. */
    fun statusLine(line: (Pet) -> String): String {
        val r = world.resident
        val others = world.pets.values.filter { it !== r }
        if (others.isEmpty()) return line(r)
        return NotifierTexts.withCompanions(line(r), others.map { it.name to it.stats.mood })
    }

    private val lastMood = HashMap<PetId, Mood>()

    /**
     * Alertes : l'humeur de chaque animal qui vient de basculer vers un besoin ([text] : null = pas
     * d'alerte), réunies en une seule notification (null = rien de neuf).
     */
    fun alerts(text: (Pet) -> String?): String? {
        val out = ArrayList<String>()
        for (p in world.pets.values) {
            val m = p.stats.mood
            if (lastMood[p.id] == m) continue
            lastMood[p.id] = m
            text(p)?.let { out += it }
        }
        lastMood.keys.retainAll(world.pets.keys)
        return if (out.isEmpty()) null else NotifierTexts.joinAlerts(out)
    }

    companion object {
        /** Station → activité « il y va de lui-même » ([mayHeadTo]). */
        private val GOING_TO = mapOf(StationKind.CHEST to Act.GOTO_CHEST, StationKind.BOWL to Act.GOTO_BOWL)

        /** [p] chasse une pub : sur le bouton ou en route, y compris en plein saut vers lui. */
        fun huntsAd(p: Pet): Boolean =
            p.act == Act.SKIP_AD || (p.act == Act.AIR && p.body.afterAir == Act.SKIP_AD)

        /** Activités d'une bêtise (celles de [Mischiefs.ALL]). */
        val MISCHIEF_ACTS: Set<Act> = EnumSet.noneOf(Act::class.java).apply { for (m in Mischiefs.ALL) addAll(m.acts) }
    }
}

/** La nourriture posée la plus proche de [p], hors [lost]. */
private fun nearestFood(world: World, p: Pet, lost: Set<Int>?): Prop? {
    var best: Prop? = null
    for (f in world.props) {
        if (f.food == null || (lost != null && f.id in lost)) continue
        val b = best
        if (b == null || abs(p.body.fx - f.x) < abs(p.body.fx - b.x)) best = f
    }
    return best
}

/** [p] l'emporte sur [q] pour [food] : plus près, ou à égalité plus affamé (la règle de `Director.foodWinner`). */
private fun closer(p: Pet, q: Pet, food: Prop): Boolean {
    val dp = abs(p.body.fx - food.x); val dq = abs(q.body.fx - food.x)
    return dp < dq || (dp == dq && p.stats.satiety < q.stats.satiety)
}
