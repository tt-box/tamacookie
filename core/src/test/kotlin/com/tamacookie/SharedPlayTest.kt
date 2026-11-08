package com.tamacookie

import com.tamacookie.feature.toy.Toy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Partie partagée : chacun s'arrête de son côté, le jouet reste tant que quelqu'un joue. */
class SharedPlayTest {
    private val ctx = FakePetCtx()
    private val toys = ToyController(ctx).also { it.registerActs(ctx.acts) }
    private val behavior = PetBehavior(ctx, object : PetBehavior.Hooks {
        override fun chestVisible(p: Pet) = false
        override val lastSelfServe = 0L
        override fun goToChest(p: Pet, now: Long) {}
        override fun seekAttention(p: Pet) = false
        override fun startPageWander(p: Pet) {}
        override fun hour() = 12
    })
    private val a get() = ctx.pet
    private val dt = 1f / 60f

    private fun play(p: Pet) = ctx.world.asPet(p) { ctx.acts.handlerFor(p)!!.update(p, dt) }

    @Test fun `celui qui a assez joue s'arrete seul, le jouet reste aux autres`() {
        val b = ctx.addPet("b", x = 900f, seed = 3)
        toys.startPlay(Toy.BALL)
        val toy = ctx.world.currentToy()!!
        a.setAct(Act.PLAY); b.setAct(Act.PLAY)
        a.mind.playTime = 1000f
        play(a)
        assertEquals(Act.IDLE, a.act)
        assertEquals(toy.id, a.mind.playedOut)
        assertNotNull(ctx.world.currentToy())
        // il ne revient pas à ce jouet
        a.body.actTime = 0f
        ctx.world.asPet(a) { behavior.update(a, dt) }
        assertEquals(Act.IDLE, a.act)
        // le dernier joueur qui s'arrête range le jouet
        b.mind.playTime = 1000f
        play(b)
        assertNull(ctx.world.currentToy())
    }

    @Test fun `un compagnon se joint a la partie avec un temps de jeu remis a zero`() {
        val b = ctx.addPet("b", x = 900f, seed = 3)
        a.stats = a.stats.copy(sleeping = true); a.setAct(Act.SLEEP)
        toys.startPlay(Toy.BALLOON)
        b.mind.playTime = 1000f            // reste d'une partie précédente
        b.setAct(Act.IDLE)
        ctx.world.asPet(b) { behavior.update(b, dt) }
        assertEquals(Act.PLAY, b.act)
        assertEquals(0f, b.mind.playTime)
        play(b)
        assertNotNull(ctx.world.currentToy())   // le ballon ne disparaît pas aussitôt
        assertEquals(Act.PLAY, b.act)
    }

    @Test fun `trop fatigue, il ne se joint pas et le jouet reste`() {
        val b = ctx.addPet("b", x = 900f, seed = 3)
        a.stats = a.stats.copy(sleeping = true); a.setAct(Act.SLEEP)
        toys.startPlay(Toy.BALL)
        b.stats = b.stats.copy(energy = 0f)
        b.setAct(Act.IDLE)
        ctx.world.asPet(b) { behavior.update(b, dt) }
        assertFalse(b.act == Act.PLAY)
        assertTrue(ctx.world.hasToy())
    }
}
