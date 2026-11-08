package com.tamacookie

import com.tamacookie.feature.toy.Food
import com.tamacookie.feature.toy.Toy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test fun `plusieurs nourritures a la suite, aucune ne disparait en vol`() {
        pet.stats = pet.stats.copy(satiety = 50f)
        toys.feed(Food.CARROT)
        val a = ctx.world.props.single()
        toys.feed(Food.APPLE)
        toys.feed(Food.LETTUCE)
        assertEquals(3, ctx.world.props.count { it.food != null })
        assertTrue(ctx.world.props.all { overlay.isAttached(it.windowId) })
        assertTrue(a in ctx.world.props)
        repeat(10) { frame() }
        assertEquals(3, ctx.world.props.count { it.food != null })
    }

    @Test fun `au-dela du plafond, la plus ancienne posee laisse la place`() {
        pet.stats = pet.stats.copy(satiety = 50f, sleeping = true)
        repeat(8) { toys.feed(Food.CARROT) }
        val first = ctx.world.props.first()
        repeat(200) { frame() }        // toutes posées
        toys.feed(Food.APPLE)
        assertEquals(8, ctx.world.props.count { it.food != null })
        assertFalse(first in ctx.world.props)
    }

    @Test fun `du coffre, le lancer part au hasard et pas vers lui`() {
        val chest = Station(com.tamacookie.feature.station.StationKind.CHEST, 100, 100)
        val speeds = HashSet<Float>()
        repeat(6) {
            toys.feed(Food.CARROT, from = chest)
            speeds += ctx.world.props.last().vx
        }
        assertTrue(speeds.size > 1)
        assertTrue(speeds.any { it < 0f } && speeds.any { it > 0f })
    }

    @Test fun `il court vers la nourriture la plus proche`() {
        pet.stats = pet.stats.copy(satiety = 50f)
        toys.feed(Food.CARROT)
        toys.feed(Food.APPLE)
        val (a, b) = ctx.world.props.filter { it.food != null }
        for (c in listOf(a, b)) { c.onGround = true; c.y = ctx.arena.groundY; c.vx = 0f; c.vy = 0f }
        a.x = pet.body.fx + 400f; b.x = pet.body.fx - 50f
        assertSame(b, ctx.director.foodTarget(pet))
        ctx.acts.handlerFor(pet)!!.update(pet, dt)
        assertTrue(pet.body.facing < 0f)
    }

    @Test fun `le jouet sort meme s'il dort, il ne bouge pas`() {
        pet.stats = pet.stats.copy(sleeping = true)
        pet.setAct(Act.SLEEP)
        val chest = Station(com.tamacookie.feature.station.StationKind.CHEST, 100, 100)
        toys.startPlay(Toy.BALLOON, from = chest)
        val t = ctx.world.currentToy()!!
        assertEquals(Toy.BALLOON, t.toy)
        assertTrue(overlay.isAttached(t.windowId))
        assertEquals(Act.SLEEP, pet.act)
    }

    @Test fun `le ballon rebondit sur la tete du compagnon qui joue, meme si le residant dort`() {
        val b = ctx.addPet("b", x = 900f, seed = 3)
        pet.stats = pet.stats.copy(sleeping = true); pet.setAct(Act.SLEEP); pet.body.fx = 200f
        toys.startPlay(Toy.BALLOON)
        val t = ctx.world.currentToy()!!
        b.setAct(Act.PLAY)
        val d = ctx.arena.d
        t.x = b.body.fx; t.vx = 0f; t.vy = 0f
        t.y = (b.body.fy - 85 * d) + t.h - t.w / 2f      // centre du ballon sur sa tête
        frame()
        assertTrue(t.vy < -400 * d)
    }

    @Test fun `du coffre, le jouet part au hasard`() {
        val chest = Station(com.tamacookie.feature.station.StationKind.CHEST, 100, 100)
        val speeds = HashSet<Float>()
        repeat(6) {
            pet.setAct(Act.IDLE)
            toys.startPlay(Toy.BALL, from = chest)
            speeds += ctx.world.currentToy()!!.vx
        }
        assertTrue(speeds.any { it < 0f } && speeds.any { it > 0f })
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
