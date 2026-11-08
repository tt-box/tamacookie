package com.tamacookie

import com.tamacookie.feature.toy.Food
import com.tamacookie.feature.toy.Toy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [ToyController] en JVM pur : ses fenêtres passent par le faux [OverlayHost]. */
class ToyControllerTest {
    private lateinit var ctx: FakePetCtx
    private lateinit var toys: ToyController
    private val overlay get() = ctx.overlay
    private val be get() = ctx.overlay.backend
    private val pet get() = ctx.pet
    private val dt = 1f / 60f

    @Before fun setUp() {
        ctx = FakePetCtx()
        toys = ToyController(ctx)
        toys.registerActs(ctx.acts)
    }

    /** Une image : physique des objets puis application des déplacements. */
    private fun frame() { toys.update(dt); overlay.flush() }

    @Test fun `nourrir affiche la nourriture, qui tombe avec au plus un deplacement par image`() {
        pet.stats = pet.stats.copy(satiety = 50f)
        toys.feed(Food.CARROT)
        val c = ctx.world.props.single()
        assertTrue(overlay.isAttached(c.windowId))
        assertEquals(Act.GOTO_FOOD, pet.act)
        var moves = be.count("move")
        repeat(120) {
            frame()
            val now = be.count("move")
            assertTrue(now - moves <= 1)
            moves = now
        }
        assertTrue(c.onGround)
        assertEquals(c.left to c.top, overlay.shownAt(c.windowId))
        // Posée : plus aucun déplacement ni redessin
        val ops = be.ops.size
        repeat(10) { frame() }
        assertEquals(ops, be.ops.size)
    }

    @Test fun `la rotation vit dans le modele et ne redessine que si elle change`() {
        pet.stats = pet.stats.copy(satiety = 50f)
        toys.feed(Food.CARROT)
        val c = ctx.world.props.single()
        frame()
        assertTrue(c.spin != 0f)
        assertTrue(be.count("redraw") > 0)
    }

    @Test fun `cache, l'objet n'a pas de fenetre et reapparait a Montrer`() {
        pet.stats = pet.stats.copy(satiety = 50f)
        ctx.hidden = true
        toys.feed(Food.CARROT)
        val c = ctx.world.props.single()
        assertFalse(overlay.isAttached(c.windowId))
        repeat(5) { frame() }
        assertEquals(0, be.ops.count { it.op != "redraw" })
        ctx.hidden = false
        toys.reattach()
        assertTrue(overlay.isAttached(c.windowId))
        toys.reattach()
        assertEquals(1, be.count("add"))
    }

    @Test fun `il mange, la fenetre de la nourriture est liberee et le corps sait ce qu'il mange`() {
        pet.stats = pet.stats.copy(satiety = 50f)
        toys.feed(Food.STRAWBERRY)
        val c = ctx.world.props.single()
        c.onGround = true; c.x = pet.body.fx + 10f; c.y = ctx.arena.groundY
        ctx.acts.handlerFor(pet)!!.update(pet, dt)
        assertEquals(Act.EAT, pet.act)
        assertEquals(Food.STRAWBERRY, pet.body.eatingFood)
        assertTrue(ctx.world.props.isEmpty())
        assertFalse(overlay.isAttached(c.windowId))
        assertNull(overlay.window(c.windowId))
        // 3 s plus tard : repas fini
        pet.body.actTime = 3f
        ctx.acts.handlerFor(pet)!!.update(pet, dt)
        assertEquals(Act.IDLE, pet.act)
        assertEquals(3, ctx.emitted.count { it == PType.HEART })
    }

    @Test fun `la nourriture sort meme s'il dort, il ne bouge pas et ne parle pas`() {
        pet.stats = pet.stats.copy(satiety = 50f, sleeping = true)
        pet.setAct(Act.SLEEP)
        ctx.said.clear()
        toys.feed(Food.LETTUCE)
        val c = ctx.world.props.single()
        assertEquals(Food.LETTUCE, c.food)
        assertTrue(overlay.isAttached(c.windowId))
        assertEquals(Act.SLEEP, pet.act)
        assertTrue(ctx.said.isEmpty())
    }

    @Test fun `la nourriture sort meme s'il est repu, il y va quand meme`() {
        pet.stats = pet.stats.copy(satiety = 100f)
        toys.feed(Food.CARROT)
        assertEquals(1, ctx.world.props.count { it.food != null })
        assertEquals(Act.GOTO_FOOD, pet.act)
    }

    @Test fun `repu, il mange sans effet et le dit`() {
        pet.stats = pet.stats.copy(satiety = 100f)
        toys.feed(Food.CARROT)
        val c = ctx.world.props.single()
        c.onGround = true; c.x = pet.body.fx + 10f; c.y = ctx.arena.groundY
        ctx.acts.handlerFor(pet)!!.update(pet, dt)
        assertEquals(Act.EAT, pet.act)
        val before = pet.stats
        ctx.said.clear()
        pet.body.actTime = 3f
        ctx.acts.handlerFor(pet)!!.update(pet, dt)
        assertEquals(before.satiety, pet.stats.satiety)
        assertEquals(0, ctx.emitted.count { it == PType.HEART })
        assertEquals(1, ctx.said.size)
    }

    @Test fun `une nouvelle nourriture remplace celle qui est au sol`() {
        pet.stats = pet.stats.copy(satiety = 50f)
        toys.feed(Food.CARROT)
        val a = ctx.world.props.single()
        toys.feed(Food.APPLE)
        val b = ctx.world.props.single()
        assertEquals(Food.APPLE, b.food)
        assertFalse(overlay.isAttached(a.windowId))
        assertTrue(overlay.isAttached(b.windowId))
    }

    @Test fun `on lui prend le jouet qu'il tient, il proteste`() {
        toys.startPlay(Toy.PLUSH)
        val t = ctx.world.currentToy()!!
        assertTrue(overlay.isAttached(t.windowId))
        t.held = true
        ctx.said.clear()
        toys.grab(t)
        assertFalse(t.held)
        assertTrue(toys.anyMoving())
        assertEquals(Expr.ANGRY, pet.mind.exprOverride)
        assertEquals(1, ctx.said.size)
        // tenu par l'utilisateur : le jouet ne bouge pas tout seul
        toys.drag(t, 300f, 400f)
        frame()
        assertEquals(300f, t.x)
        assertEquals(400f + t.h / 2f, t.y)
    }

    @Test fun `lancer au doigt, la vitesse est bornee`() {
        toys.startPlay(Toy.BALL)
        val t = ctx.world.currentToy()!!
        toys.grab(t)
        toys.release(t, 1e6f, -1e6f)
        val max = Tuning.TOY_THROW_MAX_DP * ctx.arena.d
        assertEquals(max, t.vx)
        assertEquals(-max, t.vy)
        assertFalse(t.onGround)
    }

    @Test fun `un nouveau jouet remplace l'ancien et sa fenetre`() {
        toys.startPlay(Toy.BALL)
        val a = ctx.world.currentToy()!!
        pet.setAct(Act.IDLE)
        toys.startPlay(Toy.BALLOON)
        val b = ctx.world.currentToy()!!
        assertTrue(a !== b && a.id != b.id)
        assertFalse(overlay.isAttached(a.windowId))
        assertTrue(overlay.isAttached(b.windowId))
        assertEquals(1, ctx.world.props.size)
    }

    @Test fun `ramasser une crotte`() {
        pet.stats = pet.stats.copy(poops = 1)
        toys.spawnPoop(300f)
        val p = ctx.world.props.single()
        assertTrue(p.isPoop)
        assertTrue(overlay.isAttached(p.windowId))
        toys.pickUp(p)
        assertTrue(ctx.world.props.isEmpty())
        assertFalse(overlay.isAttached(p.windowId))
        assertEquals(4, ctx.emitted.count { it == PType.SPARKLE })
        toys.pickUp(p)          // deuxième toucher : rien
        assertEquals(4, ctx.emitted.count { it == PType.SPARKLE })
    }

    @Test fun `une crotte reste sur sa vue`() {
        ctx.pages.currentView = "com.app.a"
        toys.spawnPoop(300f)
        val p = ctx.world.props.single()
        assertEquals("com.app.a", p.anchor)
        toys.layoutPoops()
        assertTrue(overlay.isAttached(p.windowId))
        ctx.pages.currentView = "com.app.b"
        toys.layoutPoops()
        assertFalse(overlay.isAttached(p.windowId))
        assertNotNull(overlay.window(p.windowId))     // vue gardée pour le retour
        ctx.pages.currentView = "com.app.a"
        toys.layoutPoops()
        assertTrue(overlay.isAttached(p.windowId))
    }
}
