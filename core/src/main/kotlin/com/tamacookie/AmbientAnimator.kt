package com.tamacookie

import kotlin.math.sin
import kotlin.random.Random

/**
 * Animation ambiante d'un animal affiché (Kotlin pur, [rnd] injecté) : temps, clignement des yeux,
 * frémissement d'oreille, et particules — celles qu'il émet tout seul selon sa pose et son humeur
 * (Zzz, miettes, larmes, étoiles, bulles de saleté) et celles demandées par le jeu ([emit]).
 *
 * La géométrie (taille de la vue [w] × [h] en px, densité [d]) sert seulement à placer les particules.
 */
class AmbientAnimator(private val rnd: Random) {

    class Particle(
        val type: PType, var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float, val size: Float, val rot: Float,
    )

    /** Temps écoulé (s) : respiration, oreilles, spirales… */
    var t = 0f
        private set
    private var blinkIn = 2.5f
    private var blink = 0f
    private var earTwitchIn = 4f
    /** Frémissement d'oreille en cours (s restantes, 0 = aucun). */
    var earTwitch = 0f
        private set
    val particles = ArrayList<Particle>()

    /**
     * Animations réduites ([ReducedMotion], étape 8d) : ni frémissement d'oreille, ni particules purement
     * décoratives (miettes, étoiles du vertige, bulles de saleté, flocons et pétales de saison) ; une seule
     * particule par demande du jeu ([emit] : cœurs, notes, étincelles). Restent le clignement, les Zzz du sommeil
     * et les larmes (elles disent son état). Faux : exactement les mêmes tirages qu'avant.
     */
    var reducedMotion = false

    /** Les yeux sont fermés (clignement). */
    val blinking: Boolean get() = blink > 0f

    /**
     * Avance de [dt] secondes pour l'état [s]. Renvoie vrai si l'image affichée change
     * (sinon la vue n'a pas besoin d'être redessinée).
     */
    fun update(dt: Float, s: PetRenderState, w: Float, h: Float, d: Float): Boolean {
        val active = blink > 0f || earTwitch > 0f || particles.isNotEmpty()
        // Ramené dans [0, 2π·100) : sin(3t), sin(2t) inchangés, et la précision reste celle des premières secondes
        t = (t + dt) % T_PERIOD
        // clignement
        if (blink > 0f) blink -= dt
        else {
            blinkIn -= dt
            if (blinkIn <= 0f) { blink = 0.14f; blinkIn = 2f + rnd.nextFloat() * 4f }
        }
        // petit mouvement d'oreille (décoratif : aucun en animations réduites)
        if (earTwitch > 0f) earTwitch -= dt
        else if (!reducedMotion) {
            earTwitchIn -= dt
            if (earTwitchIn <= 0f) { earTwitch = 0.35f; earTwitchIn = 3f + rnd.nextFloat() * 6f }
        }
        // particules selon la pose et l'humeur
        if (s.pose == Pose.SLEEP && rnd.nextFloat() < dt * 0.9f) emit(PType.ZZZ, 1, w, h, d, s.facing)
        val deco = !reducedMotion
        if (deco && s.pose == Pose.EAT && rnd.nextFloat() < dt * 4f) emit(PType.CRUMB, 1, w, h, d, s.facing)
        if (s.expr == Expr.SAD && rnd.nextFloat() < dt * 0.4f) emit(PType.DROP, 1, w, h, d, s.facing)
        if (s.expr == Expr.SICK && rnd.nextFloat() < dt * 0.3f) emit(PType.DROP, 1, w, h, d, s.facing)
        if (deco && s.expr == Expr.DIZZY && rnd.nextFloat() < dt * 5f) emit(PType.STAR, 1, w, h, d, s.facing)
        if (deco && s.veryDirty && rnd.nextFloat() < dt * 0.6f) emit(PType.BUBBLE, 1, w, h, d, s.facing)
        // saison (évolution 4b) : après les autres, et aucun tirage hors saison (mêmes tirages qu'avant)
        val seasonal = s.season.particle
        if (deco && seasonal != null && rnd.nextFloat() < dt * SEASON_PARTICLES_PER_S) {
            emit(seasonal, 1, w, h, d, s.facing)
        }

        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) { it.remove(); continue }
            p.x += p.vx * dt; p.y += p.vy * dt
            if (p.type == PType.CRUMB || p.type == PType.DROP) p.vy += 300f * d * dt
            if (p.type == PType.ZZZ || p.type == PType.NOTE) p.x += sin(t * 3f + p.rot) * 12f * d * dt
            if (p.type == PType.SNOW || p.type == PType.PETAL) p.x += sin(t * 2f + p.rot) * 16f * d * dt
        }
        return active || blink > 0f || earTwitch > 0f || particles.isNotEmpty() || movesWithTime(s)
    }

    /**
     * Une image de la vue : avance de [dt] pour l'état [s] (le précédent était [old]) et renvoie s'il faut
     * redessiner (état différent ou animation ambiante).
     */
    fun frame(dt: Float, old: PetRenderState, s: PetRenderState, w: Float, h: Float, d: Float): Boolean =
        update(dt, s, w, h, d) || s != old

    /** Ajoute [count] particules au-dessus de la tête (vue [w] × [h], densité [d], regard [facing]). */
    fun emit(type: PType, count: Int, w: Float, h: Float, d: Float, facing: Float) {
        repeat(if (reducedMotion) count.coerceAtMost(1) else count) {
            val cx = w / 2f
            val headY = h - 6f * d - 60f * d * UNIT
            val p = when (type) {
                PType.HEART -> Particle(type, cx + rnd.nextFloat(-25f, 25f) * d, headY,
                    rnd.nextFloat(-20f, 20f) * d, -55f * d, 1.4f, 1.4f, rnd.nextFloat(9f, 14f) * d, 0f)
                PType.ZZZ -> Particle(type, cx + 20f * d * facing, headY + 6f * d, 8f * d * facing,
                    -28f * d, 2.2f, 2.2f, rnd.nextFloat(11f, 16f) * d, rnd.nextFloat() * 6f)
                PType.NOTE -> Particle(type, cx + rnd.nextFloat(-30f, 30f) * d, headY,
                    rnd.nextFloat(-10f, 10f) * d, -45f * d, 1.6f, 1.6f, rnd.nextFloat(13f, 18f) * d, rnd.nextFloat() * 6f)
                PType.SPARKLE -> Particle(type, cx + rnd.nextFloat(-40f, 40f) * d,
                    h - rnd.nextFloat(10f, 80f) * d, 0f, -15f * d, 0.8f, 0.8f, rnd.nextFloat(5f, 9f) * d, 0f)
                PType.DROP -> Particle(type, cx + 16f * d * facing, headY + 8f * d, 6f * d * facing,
                    -20f * d, 0.9f, 0.9f, 4f * d, 0f)
                PType.CRUMB -> Particle(type, cx + 24f * d * facing, h - 50f * d, rnd.nextFloat(-30f, 30f) * d,
                    -40f * d, 0.7f, 0.7f, 2.2f * d, 0f)
                PType.STAR -> Particle(type, cx, headY - 4f * d, 0f, 0f, 0.6f, 0.6f, 5f * d, rnd.nextFloat() * 6.28f)
                PType.BUBBLE -> Particle(type, cx + rnd.nextFloat(-20f, 20f) * d, h - 40f * d,
                    rnd.nextFloat(-6f, 6f) * d, -25f * d, 1.5f, 1.5f, rnd.nextFloat(3f, 6f) * d, 0f)
                // flocons et pétales : tombent doucement du haut de la fenêtre en se balançant
                PType.SNOW -> Particle(type, cx + rnd.nextFloat(-55f, 55f) * d, h - 130f * d,
                    0f, 24f * d, 4.5f, 4.5f, rnd.nextFloat(2.5f, 4.5f) * d, rnd.nextFloat() * 6.28f)
                PType.PETAL -> Particle(type, cx + rnd.nextFloat(-55f, 55f) * d, h - 130f * d,
                    0f, 18f * d, 5.5f, 5.5f, rnd.nextFloat(3f, 5f) * d, rnd.nextFloat() * 6.28f)
            }
            particles.add(p)
        }
    }

    private fun Random.nextFloat(a: Float, b: Float) = a + nextFloat() * (b - a)

    companion object {
        /** Échelle du lapin : 1 unité de dessin = UNIT dp. */
        const val UNIT = 0.95f

        /** Particules de saison émises par seconde (en moyenne). */
        const val SEASON_PARTICLES_PER_S = 0.4f

        /**
         * Le dessin de [s] dépend du temps (respiration au repos, au sommeil, en mangeant, tenu en l'air,
         * oreilles joyeuses, yeux en spirale, écharpe…). La marche et le vol dépendent seulement de
         * l'état ([PetRenderState.walkPhase], [PetRenderState.airVy]).
         */
        fun movesWithTime(s: PetRenderState): Boolean =
            (s.pose != Pose.WALK && s.pose != Pose.AIR) ||
                s.expr == Expr.HAPPY || s.expr == Expr.DIZZY ||
                s.look.accessory == Accessory.SCARF ||
                (s.pose == Pose.AIR && s.look.ears == Ears.LOP)
    }
}

/** Période de [AmbientAnimator.t] : multiple de 2π (les balancements en sin(2t) et sin(3t) restent continus). */
private const val T_PERIOD = (2 * Math.PI * 100).toFloat()
