package com.tamacookie.feature.toy

import com.tamacookie.Act
import com.tamacookie.BunnyPhysics
import com.tamacookie.Pose
import com.tamacookie.Pet
import com.tamacookie.Prop
import kotlin.math.abs
import kotlin.math.hypot

/** Frisbee : il l'attrape au vol et le relance. */
object FrisbeeToy : ToySpec {
    override val toy get() = Toy.FRISBEE
    override val wDp = 52
    override val hDp = 30
    override val randomTint = true
    override val launch = Launch(vxDp = 650f, vyDp = -380f)
    override val complainsWhenTaken = true
    override val startLines = arrayOf("Un frisbee ! 🥏 Lance-le, je l'attrape !", "Je suis un champion du frisbee !")
    private val THROWN = arrayOf("À toi ! 🥏", "Attrape !", "Super lancer !")
    private val CAUGHT = arrayOf("Attrapé ! 🥏", "Trop fort !", "Dans les dents !")

    override fun play(ctx: ToyCtx, pet: Pet, p: Prop, dt: Float) {
        val d = ctx.arena.d
        if (p.held) {
            // Lui seul relance le frisbee qu'il a dans la gueule ; un autre attend son lancer
            if (p.holder == pet.id) holdThenThrow(ctx, pet, p, dt)
            else { pet.body.pose = Pose.IDLE; ctx.actor.faceToward(pet, p.x) }
            return
        }
        val airborne = !p.onGround
        val tx = if (airborne) p.x + p.vx * 0.25f else p.x
        if (abs(tx - pet.body.fx) > 24 * d) ctx.actor.walkToward(pet, tx, 230 * d, dt, stopDist = 24 * d) else pet.body.pose = Pose.IDLE
        if (pet.body.act != Act.PLAY) return
        val dx = p.x - pet.body.fx
        val h = pet.body.fy - (p.y - p.h / 2f)
        if (airborne && abs(dx) < 90 * d && h > 60 * d && h < 240 * d && pet.mind.kickCooldown <= 0f && !ctx.userHolds) {
            pet.mind.kickCooldown = 0.6f
            ctx.actor.jump(pet, BunnyPhysics.impulseForHeight(pet.body.phys.gravity, (h - 30 * d).coerceAtLeast(10 * d)), dx * 2.5f, then = Act.PLAY)
        }
    }

    /** [pet] tient le frisbee : il le garde un instant puis le lance vers le milieu de l'écran. */
    private fun holdThenThrow(ctx: ToyCtx, pet: Pet, p: Prop, dt: Float) {
        val a = ctx.arena
        val d = a.d
        val rnd = ctx.rnd
        ctx.holdTime += dt
        pet.body.pose = Pose.IDLE
        if (ctx.holdTime <= 1.3f) return
        val dir = if (pet.body.fx < a.screenW / 2) 1f else -1f
        pet.body.facing = dir
        p.holder = null; p.onGround = false
        p.x = pet.body.fx + dir * 30 * d; p.y = pet.body.fy - 40 * d
        p.vx = dir * (750 + rnd.nextFloat() * 450) * d
        p.vy = -(250 + rnd.nextFloat() * 250) * d
        ctx.catchCooldown = 0.9f
        if (rnd.nextFloat() < 0.35f) ctx.say(ctx.speech.pickFrom(THROWN), 1.4f)
    }

    override fun step(ctx: ToyCtx, pet: Pet, p: Prop, dt: Float) {
        val d = ctx.arena.d
        val fy = pet.body.fy
        val act = pet.body.act
        val mouthX = pet.body.fx + pet.body.facing * 18 * d
        if (p.held) {
            p.x = mouthX + pet.body.facing * 8 * d
            p.y = fy - 52 * d + p.h / 2f
            p.vx = 0f; p.vy = 0f
        } else {
            val lift = (abs(p.vx) / (900 * d)).coerceAtMost(1f) * 0.85f
            p.vx *= (1f - 0.35f * dt)
            ctx.stepToy(pet, p, dt, 1f - lift, 0.15f, 0.6f, 5f)
            if (!p.onGround) p.spin += 900f * dt
            // attrapé ?
            val cy = p.y - p.h / 2f
            if (ctx.catchCooldown <= 0f && (act == Act.PLAY || act == Act.AIR) &&
                hypot(p.x - mouthX, cy - (fy - 55 * d)) < 45 * d
            ) {
                val wasFlying = !p.onGround
                p.holder = pet.id; ctx.holdTime = 0f
                ctx.hit(pet, Toy.FRISBEE, if (wasFlying) 4f else 1f)
                if (wasFlying && ctx.rnd.nextFloat() < 0.5f) ctx.say(ctx.speech.pickFrom(CAUGHT), 1.5f)
            }
        }
    }
}
