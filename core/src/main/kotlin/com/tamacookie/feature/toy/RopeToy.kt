package com.tamacookie.feature.toy

import com.tamacookie.Expr
import com.tamacookie.PType
import com.tamacookie.Pet
import com.tamacookie.PetEvent
import com.tamacookie.Pose
import com.tamacookie.Prop
import kotlin.math.sin

/**
 * Corde : il l'attrape dans la gueule, la mâchouille en secouant la tête, puis la relance d'un coup
 * de tête. Il proteste si on la lui prend (on « tire à la corde » avec lui).
 */
object RopeToy : ToySpec {
    /** Durée d'une séance de mâchouillage avant qu'il la relance (s). */
    const val CHEW_SECONDS = 5f
    /** Un coup de dents toutes les [BITE_SECONDS] : un peu de joie. */
    const val BITE_SECONDS = 0.6f

    override val toy get() = Toy.ROPE
    override val wDp = 58
    override val hDp = 24
    override val complainsWhenTaken = true
    override val launch = Launch(aboveDp = 110f, vxDp = 200f, vyDp = -420f)
    override val startLines = arrayOf("Une corde ! 🪢", "On tire ? Je suis fort !", "Grrr… à moi la corde !")
    private val GRABBED = arrayOf("Grrr ! 😼", "Je la tiens !", "Tire si tu l'oses !")
    private val TOSSED = arrayOf("Et hop !", "Rattrape-la !", "Ouf, pause…")

    override fun play(ctx: ToyCtx, pet: Pet, p: Prop, dt: Float) {
        val d = ctx.arena.d
        val rnd = ctx.rnd
        if (p.holder != pet.id) { approach(ctx, pet, p, dt); return }
        val before = ctx.holdTime
        ctx.holdTime += dt
        pet.body.pose = Pose.IDLE                  // il mâchouille (la corde secouée : voir step)
        pet.express(Expr.HAPPY, 0.2f)
        if ((before / BITE_SECONDS).toInt() != (ctx.holdTime / BITE_SECONDS).toInt()) {
            ctx.on(pet, PetEvent.PlayHit(Toy.ROPE, 0.8f))
            if (rnd.nextFloat() < 0.4f) ctx.emit(pet, PType.NOTE)
        }
        if (ctx.holdTime >= CHEW_SECONDS) {
            p.holder = null; p.onGround = false
            p.vx = -pet.body.facing * (150 + rnd.nextFloat() * 200) * d
            p.vy = -(380 + rnd.nextFloat() * 220) * d
            ctx.catchCooldown = 1f
            ctx.holdTime = 0f
            pet.body.pose = Pose.IDLE
            ctx.say(ctx.speech.pickFrom(TOSSED), 1.4f)
        }
    }

    /**
     * La corde n'est pas dans sa gueule : il va la prendre si elle est libre et posée ; en vol, juste relancée
     * ou tenue par un autre (lui seul la relance), il la regarde.
     */
    private fun approach(ctx: ToyCtx, pet: Pet, p: Prop, dt: Float) {
        if (p.held || !p.onGround || ctx.catchCooldown > 0f) {
            pet.body.pose = Pose.IDLE; ctx.actor.faceToward(pet, p.x); return
        }
        if (ctx.actor.walkToward(pet, p.x, 160 * ctx.arena.d, dt, stopDist = 24 * ctx.arena.d)) {
            p.holder = pet.id; ctx.holdTime = 0f
            ctx.hit(pet, Toy.ROPE, 2.5f)
            ctx.say(ctx.speech.pickFrom(GRABBED), 1.4f)
        }
    }

    override fun step(ctx: ToyCtx, pet: Pet, p: Prop, dt: Float) {
        val d = ctx.arena.d
        if (p.held) {
            val shake = sin(ctx.holdTime * 14f)
            p.x = pet.body.fx + pet.body.facing * 26 * d + shake * 4 * d
            p.y = pet.body.fy - 18 * d
            p.vx = 0f; p.vy = 0f; p.spin = shake * 18f
        } else {
            ctx.stepToy(pet, p, dt, 1f, 0.2f, 0.5f, 5f)
            if (!p.onGround) p.spin += p.vx / d * dt * 2f
            else p.spin *= (1f - 8f * dt)
        }
    }
}
