package com.tamacookie.social

import com.tamacookie.Act
import com.tamacookie.Neighbor
import com.tamacookie.Pet
import com.tamacookie.PetCtx
import com.tamacookie.PetId
import com.tamacookie.SpeechPriority
import com.tamacookie.feature.ActHandler
import com.tamacookie.feature.ActRegistry
import com.tamacookie.feature.Travel
import kotlin.math.abs

/**
 * Une interaction à deux participants (plugin, sur le modèle des fonctionnalités) : un `object` par
 * interaction dans `social/`, enregistré dans [Socials.ALL]. Sans état (il est dans l'[Engagement] de
 * chaque participant). Deux façons de commencer :
 * - **le cerveau** d'un animal décide d'aller vers un autre (`Decision.Socialize`) : la cascade
 *   [Socials.ALL] (ordre, [chance]) choisit la première possible ([canStart]) et la lance ([start]) ;
 * - **le monde** la déclenche ([observe], chaque image pour chaque paire d'animaux affichés : jouer
 *   ensemble avec la même balle) ou l'arbitre ([SocialSystem.startDispute] : la carotte disputée).
 */
interface SocialInteraction {
    /** Ce que fait l'animal engagé si l'utilisateur change d'application. */
    val travel: Travel get() = Travel.BUSY

    /** Probabilité d'être retenue dans la cascade (1 = sans tirage, 0 = jamais par le cerveau). */
    val chance: Float get() = 0f

    /** Possible maintenant entre [a] (qui la propose) et [b] ? Ne consomme pas d'aléatoire. */
    fun canStart(ctx: PetCtx, a: Pet, b: Pet): Boolean = false

    /** Lance l'interaction : engage [a] (et [b] si elle est mutuelle) — [SocialSystem.engage]. */
    fun start(sys: SocialSystem, a: Pet, b: Pet) {}

    /** Chaque image pour un participant engagé ([e]) ; false = c'est fini pour lui (il repasse au repos). */
    fun update(sys: SocialSystem, self: Pet, partner: Pet?, e: Engagement, dt: Float): Boolean = false

    /** Chaque image pour chaque paire d'animaux affichés, engagés ou non (interactions déclenchées par le monde). */
    fun observe(sys: SocialSystem, a: Pet, b: Pet, dt: Float) {}
}

/** Ce qu'un animal fait avec un autre (porté par son esprit : `Mind.social`). */
class Engagement(val interaction: SocialInteraction, val partner: PetId, val duration: Float, val leads: Boolean) {
    /** Temps écoulé (s). */
    var time = 0f
    /** Étape de l'interaction (libre pour elle). */
    var phase = 0
}

/** Les interactions enregistrées. */
object Socials {
    /** Dans l'ordre de la cascade (quand le cerveau va vers un autre). */
    val ALL: List<SocialInteraction> = listOf(HugSocial, FollowSocial, PlayBallSocial, CarrotDispute)
}

/**
 * Le système social (Kotlin pur, testé en JVM) : ce que chaque animal perçoit des autres ([nearest] →
 * `BrainInput.nearest`), le lancement des interactions ([tryStart], [startDispute]), l'activité
 * [Act.SOCIAL] (enregistrée dans [ActRegistry]) et les interactions déclenchées par le monde ([update]).
 * Les différends (la nourriture) sont tranchés par le `Director` ; ce système ne fait que les mettre en scène.
 */
class SocialSystem(val ctx: PetCtx, private val all: List<SocialInteraction> = Socials.ALL) {
    private val buf = ArrayList<Pet>()
    private val near = ArrayList<Pet>()

    /** [Act.SOCIAL] : l'interaction en cours de l'animal (une caresse l'interrompt : caresse ordinaire). */
    private val engaged = object : ActHandler {
        override val travel: Travel get() = Travel.BUSY
        override fun update(pet: Pet, dt: Float) = step(pet, dt)
        override fun onPet(pet: Pet): Boolean { end(pet); return false }
    }

    fun registerActs(acts: ActRegistry) {
        acts.register(Act.SOCIAL) { p -> if (p.mind.social != null) engaged else null }
    }

    /**
     * L'animal affiché le plus proche de [p] sur la même page, disponible (ni caché, ni ailleurs, ni dans
     * une station) ; null = il est seul.
     */
    fun nearest(p: Pet): Neighbor? {
        var best: Pet? = null
        var bestD = Float.MAX_VALUE
        for (q in ctx.world.othersShown(p, near)) {
            if (!available(q) || q.body.page != p.body.page) continue
            val d = abs(q.body.fx - p.body.fx)
            if (d < bestD) { bestD = d; best = q }
        }
        val b = best ?: return null
        return Neighbor(b.id, b.act, bestD)
    }

    private fun available(q: Pet) = !ctx.isAway(q) && !q.isHiding && ctx.world.stationOf(q) == null

    /** Le cerveau de [a] va vers [n] : la première interaction possible de la cascade (tirage avec son aléatoire). */
    fun tryStart(a: Pet, n: Neighbor): Boolean {
        val b = ctx.world.pets[n.id] ?: return false
        if (a.mind.social != null || b.mind.social != null) return false
        val rnd = a.mind.rnd
        for (s in all) {
            val c = s.chance
            if (c <= 0f || !s.canStart(ctx, a, b)) continue
            if (c < 1f && rnd.nextFloat() >= c) continue
            s.start(this, a, b)
            return a.mind.social != null
        }
        return false
    }

    /** Engage [p] dans [s] avec [partner] pendant [duration] s ([leads] : c'est lui qui l'a proposée). */
    fun engage(p: Pet, s: SocialInteraction, partner: Pet, duration: Float, leads: Boolean): Engagement {
        val e = Engagement(s, partner.id, duration, leads)
        p.mind.social = e
        val b = p.body
        if (b.platform != null && b.act != Act.AIR) {
            // Perché sur une icône : il en descend (chute, sans tirage) avant l'interaction, au lieu de flotter
            // puis de se retrouver d'un coup au sol
            b.platform = null
            b.phys.stop()
            b.afterAir = Act.SOCIAL
            p.setAct(Act.AIR)
        } else {
            b.platform = null
            p.setActOrAfterAir(Act.SOCIAL)
        }
        return e
    }

    /** Fin de l'interaction de [p] (son partenaire continue, la sienne s'arrête aussi s'il l'attendait). */
    fun end(p: Pet, idle: Float = 2f) {
        p.mind.social = null
        if (p.act == Act.SOCIAL) p.setAct(Act.IDLE, idle)
    }

    private fun step(p: Pet, dt: Float) {
        val e = p.mind.social ?: run { p.setAct(Act.IDLE, 2f); return }
        e.time += dt
        val partner = ctx.world.pets[e.partner]?.takeIf { available(it) && ctx.world.isShown(it) }
        if (!e.interaction.update(this, p, partner, e, dt)) end(p)
    }

    /** Le `Director` a tranché une nourriture : [loser] la dispute à [winner] (mise en scène). */
    fun startDispute(loser: Pet, winner: Pet) {
        if (loser.mind.social != null) return
        CarrotDispute.start(this, loser, winner)
    }

    /**
     * Une fois par image, après le comportement de chaque animal : différends tranchés par l'arbitre, puis
     * interactions déclenchées par le monde pour chaque paire d'animaux affichés.
     */
    fun update(dt: Float) {
        // Une interaction interrompue (attrapé, voyage, dodo…) : son engagement est oublié
        for (p in ctx.world.pets.values) {
            if (p.mind.social == null) continue
            if (p.act != Act.SOCIAL && !(p.act == Act.AIR && p.body.afterAir == Act.SOCIAL)) p.mind.social = null
        }
        for (d in ctx.director.takeDisputes()) {
            val l = ctx.world.pets[d.loser] ?: continue
            val w = ctx.world.pets[d.winner] ?: continue
            ctx.world.asPet(l) { startDispute(l, w) }
        }
        val pets = ctx.world.shown(buf)
        if (pets.size < 2) return
        for (i in pets.indices) for (j in i + 1 until pets.size) {
            for (s in all) s.observe(this, pets[i], pets[j], dt)
        }
    }

    /** [p] dit [text] (bulle basse priorité : un échange entre animaux ne coupe pas la parole à l'utilisateur). */
    fun say(p: Pet, text: String, seconds: Float = 2.2f) =
        ctx.world.asPet(p) { ctx.speech.at(SpeechPriority.LOW) { ctx.say(text, seconds) } }

    /** Tirage dans [lines] avec l'aléatoire de [p]. */
    fun pick(p: Pet, lines: Array<String>): String = lines[p.mind.rnd.nextInt(lines.size)]
}
