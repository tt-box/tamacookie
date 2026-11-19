package com.tamacookie.feature.toy

import com.tamacookie.PetEvent
import com.tamacookie.Act
import com.tamacookie.Expr
import com.tamacookie.PType
import com.tamacookie.Pose
import com.tamacookie.Pet
import com.tamacookie.Prop
import kotlin.math.hypot

/** Doudou : câlins, promenade, lancers ; il s'endort avec s'il est fatigué. */
object PlushToy : ToySpec {
    /** Fatigué, il s'endort avec son doudou. */
    const val NAP = "Dodo avec mon doudou… 💤"

    override val toy get() = Toy.PLUSH
    override val wDp = 40
    override val hDp = 44
    override val complainsWhenTaken = true
    override val startLines = arrayOf("Un doudou ! 🧸", "Oh, un nouveau copain !")
    private val GRABBED = arrayOf("Mon doudou ! 🧸", "Je t'ai !", "Viens là toi !")
    private val THROWN = arrayOf("Wiii, vole doudou !", "Hop !", "Il vole !")

    override fun play(ctx: ToyCtx, pet: Pet, p: Prop, dt: Float) {
        val a = ctx.arena
        val d = a.d
        val rnd = ctx.rnd
        if (p.holder == null) {
            if (!p.onGround) { pet.body.pose = Pose.IDLE; ctx.actor.faceToward(pet, p.x); return }
            if (ctx.actor.walkToward(pet, p.x, 150 * d, dt, stopDist = 22 * d)) {
                p.holder = pet.id; ctx.holdTime = 0f
                ctx.hit(pet, Toy.PLUSH, 3f)
                ctx.say(ctx.speech.pickFrom(GRABBED), 1.6f)
            }
            return
        }
        // Un autre le tient : lui seul le relance
        if (p.holder != pet.id) { pet.body.pose = Pose.IDLE; ctx.actor.faceToward(pet, p.x); return }
        val before = ctx.holdTime
        ctx.holdTime += dt
        val hug = ctx.holdTime
        when {
            hug < 4f -> {
                pet.body.pose = Pose.IDLE
                pet.express(Expr.HAPPY, 0.2f)
                if (rnd.nextFloat() < dt * 2.5f) { ctx.emit(pet, PType.HEART); ctx.on(pet, PetEvent.PlayHit(Toy.PLUSH, 0.6f)) }
            }
            hug < 10f -> {
                if (before < 4f) pet.body.targetX = (pet.body.fx + (rnd.nextFloat() * 2f - 1f) * a.screenW * 0.3f).coerceIn(a.minX, a.maxX)
                if (ctx.actor.walkToward(pet, pet.body.targetX, 80 * d, dt)) pet.body.pose = Pose.IDLE
            }
            else -> {
                if (pet.stats.energy < ctx.rules.plushNapEnergyBelow) { ctx.say(NAP); ctx.startSleep(pet); return }
                p.holder = null; p.onGround = false
                p.vy = -(650 + rnd.nextFloat() * 250) * d
                p.vx = pet.body.facing * rnd.nextFloat() * 120 * d
                ctx.catchCooldown = 0.5f
                ctx.holdTime = 0f
                ctx.say(ctx.speech.pickFrom(THROWN), 1.4f)
            }
        }
    }

    override fun step(ctx: ToyCtx, pet: Pet, p: Prop, dt: Float) {
        val d = ctx.arena.d
        val fx = pet.body.fx; val fy = pet.body.fy
        val act = pet.body.act
        if (p.held) {
            val sleepingNow = act == Act.SLEEP
            p.x = fx + pet.body.facing * (if (sleepingNow) 30 else 16) * d
            p.y = fy - (if (sleepingNow) 0 else 6) * d
            p.vx = 0f; p.vy = 0f; p.spin = if (sleepingNow) -70f else 0f
        } else {
            ctx.stepToy(pet, p, dt, 1f, 0.3f, 0.6f, 6f)
            if (!p.onGround) p.spin += p.vx / d * dt * 3f + 120f * dt
            else p.spin *= (1f - 6f * dt)
            if (ctx.catchCooldown <= 0f && act == Act.PLAY && !p.onGround &&
                hypot(p.x - fx, (p.y - p.h / 2f) - (fy - 40 * d)) < 40 * d
            ) {
                p.holder = pet.id; ctx.holdTime = 0f
                ctx.hit(pet, Toy.PLUSH, 3f)
            }
        }
    }
}
