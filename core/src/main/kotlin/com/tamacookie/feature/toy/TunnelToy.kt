package com.tamacookie.feature.toy

import com.tamacookie.Pet
import com.tamacookie.Pose
import com.tamacookie.Prop
import kotlin.math.abs

/**
 * Tunnel : posé au sol, il y entre d'un côté et ressort de l'autre, encore et encore. Les fenêtres des
 * objets sont au-dessus de celle de l'animal (`WindowLayer`) : le tunnel le cache pendant la traversée.
 *
 * Phase dans `Mind.crossing` (faux = il va à l'entrée la plus proche, vrai = il traverse), bout visé dans
 * `Body.targetX`, pause entre deux traversées dans `Mind.kickCooldown` : tout est à l'animal, pas à la partie
 * (à deux, l'un ne ressort pas de la traversée de l'autre).
 */
object TunnelToy : ToySpec {
    override val toy get() = Toy.TUNNEL
    override val wDp = 130
    override val hDp = 56
    override val launch = Launch(aboveDp = 80f, vxDp = 0f, vyDp = -150f, chestVyDp = -700f)
    override val startLines = arrayOf("Un tunnel ! 🕳️", "Je parie que je ressors de l'autre côté !", "Une cachette !")
    private val OUT = arrayOf("Coucou ! 👋", "Me revoilà !", "Encore !", "Tadaaa !")

    /** Les deux bouts du tunnel (pieds de l'animal juste dehors), bornés à l'écran. */
    fun ends(ctx: ToyCtx, p: Prop): Pair<Float, Float> {
        val a = ctx.arena
        val half = p.w / 2f + 24 * a.d
        return (p.x - half).coerceIn(a.minX, a.maxX) to (p.x + half).coerceIn(a.minX, a.maxX)
    }

    override fun play(ctx: ToyCtx, pet: Pet, p: Prop, dt: Float) {
        val d = ctx.arena.d
        val b = pet.body
        if (!p.onGround || ctx.userHolds || pet.mind.kickCooldown > 0f) {
            b.pose = Pose.IDLE; ctx.actor.faceToward(pet, p.x); return
        }
        val (left, right) = ends(ctx, p)
        val near = 2 * d
        if (abs(b.targetX - left) > near && abs(b.targetX - right) > near) {
            // première fois (ou tunnel déplacé) : vers l'entrée la plus proche
            b.targetX = if (abs(b.fx - left) <= abs(b.fx - right)) left else right
            pet.mind.crossing = false
        }
        if (!ctx.actor.walkToward(pet, b.targetX, 190 * d, dt, stopDist = near)) return
        if (pet.mind.crossing) {
            // il vient de traverser
            ctx.hit(pet, Toy.TUNNEL, 3f)
            if (ctx.rnd.nextFloat() < 0.4f) ctx.say(ctx.speech.pickFrom(OUT), 1.4f)
        }
        pet.mind.crossing = true
        b.targetX = if (abs(b.targetX - left) <= near) right else left
        pet.mind.kickCooldown = 0.5f + ctx.rnd.nextFloat() * 0.8f
    }

    override fun step(ctx: ToyCtx, pet: Pet, p: Prop, dt: Float) {
        ctx.stepToy(pet, p, dt, 1f, 0f, 0.2f, 8f)
        p.spin = 0f
    }
}
