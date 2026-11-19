package com.tamacookie

import com.tamacookie.feature.toy.BalloonToy
import com.tamacookie.feature.toy.Food
import com.tamacookie.feature.toy.FrisbeeToy
import com.tamacookie.feature.toy.Launch
import com.tamacookie.feature.toy.Poop
import com.tamacookie.feature.toy.RopeToy
import com.tamacookie.feature.toy.TunnelToy
import com.tamacookie.feature.toy.YarnToy
import com.tamacookie.feature.toy.Toy
import com.tamacookie.feature.toy.ToyCtx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jouets et nourriture en plugins (`feature/toy/`) : exhaustivité, identité sauvegardée, propriétés. */
class ToySpecTest {

    @Test
    fun `chaque jouet a un spec qui le designe`() {
        for (t in Toy.entries) {
            val s = t.spec
            assertSame(t, s.toy)
            assertNull("$t", s.food)
            assertTrue("$t", s.wDp > 0 && s.hDp > 0)
            assertTrue("$t : on le lance au doigt", s.touchable)
            assertTrue("$t", s.startLines.isNotEmpty())
        }
        // Un spec par jouet (pas de copier-coller d'un objet sur deux entrées)
        assertEquals(Toy.entries.size, Toy.entries.map { it.spec }.toSet().size)
    }

    @Test
    fun `chaque nourriture a un spec qui la designe`() {
        for (f in Food.entries) {
            assertSame(f, f.spec.food)
            assertNull("$f", f.spec.toy)
            assertFalse("$f : elle tombe, on ne la touche pas", f.spec.touchable)
            assertTrue("$f", f.spec.lines.isNotEmpty())
            assertEquals(38, f.spec.wDp); assertEquals(38, f.spec.hDp)
        }
        assertEquals(Food.entries.size, Food.entries.map { it.spec }.toSet().size)
        // La carotte se couche une fois au sol
        assertEquals(90f, Food.CARROT.spec.restSpin, 0f)
        assertEquals(0f, Food.LETTUCE.spec.restSpin, 0f)
    }

    @Test
    fun `la crotte n'est ni un jouet ni de la nourriture`() {
        assertNull(Poop.toy); assertNull(Poop.food)
        assertTrue(Poop.touchable)
    }

    @Test
    fun `noms sauvegardes inchanges`() {
        // favoriteToy est sauvegardé par nom : ne jamais renommer (on ajoute à la fin)
        assertEquals(listOf("BALL", "BALLOON", "FRISBEE", "PLUSH"), Toy.entries.take(4).map { it.name })
        assertEquals(listOf("CARROT", "LETTUCE", "STRAWBERRY"), Food.entries.take(3).map { it.name })
    }

    @Test
    fun `proprietes reprises de l'ancien code`() {
        val old = Toy.entries.take(4)                    // les jouets d'avant l'étape 3b
        // tailles des fenêtres (dp)
        assertEquals(listOf(42 to 42, 52 to 86, 52 to 30, 40 to 44), old.map { it.spec.wDp to it.spec.hDp })
        // teinte au hasard : ballon et frisbee
        assertEquals(setOf(Toy.BALLOON, Toy.FRISBEE), old.filter { it.spec.randomTint }.toSet())
        // il proteste quand on les lui prend : doudou et frisbee
        assertEquals(setOf(Toy.PLUSH, Toy.FRISBEE), old.filter { it.spec.complainsWhenTaken }.toSet())
        // lancers
        val def = Toy.BALL.spec.launch
        assertSame(Launch.DEFAULT, def)
        assertSame(Launch.DEFAULT, Toy.PLUSH.spec.launch)
        assertEquals(listOf(120f, 250f, -500f, -950f), listOf(def.aboveDp, def.vxDp, def.vyDp, def.chestVyDp))
        val b = BalloonToy.launch
        assertEquals(listOf(220f, 60f, -120f, -250f), listOf(b.aboveDp, b.vxDp, b.vyDp, b.chestVyDp))
        val f = FrisbeeToy.launch
        assertEquals(listOf(120f, 650f, -380f, -950f), listOf(f.aboveDp, f.vxDp, f.vyDp, f.chestVyDp))
    }

    /** Faux contexte des jouets : la façade de test + l'état de la partie. */
    private class FakeToyCtx(val base: FakePetCtx = FakePetCtx()) : ToyCtx, PetCtx by base {
        override val userHolds = false
        override var holdTime = 0f
        override var catchCooldown = 0f
        override var groundSaidAt = 0L
    }

    @Test
    fun `toucher le jouet fait plaisir, deux fois plus avec le prefere`() {
        val ctx = FakeToyCtx()
        val pet = ctx.pet
        pet.traits = pet.traits.copy(favoriteToy = Toy.PLUSH)
        pet.stats = pet.stats.copy(happiness = 50f)
        ctx.hit(pet, Toy.BALL)
        val once = pet.stats.happiness - 50f
        assertTrue(once > 0f)
        assertEquals(1, ctx.base.emitted.size)
        assertTrue(ctx.base.emitted[0] == PType.HEART || ctx.base.emitted[0] == PType.NOTE)
        assertEquals(Expr.HAPPY, pet.mind.exprOverride)
        pet.stats = pet.stats.copy(happiness = 50f)
        ctx.hit(pet, Toy.PLUSH)
        assertTrue(pet.stats.happiness - 50f > once)
    }

    // ------------------------------------------------------------------ Évolution 4a : nouveaux objets

    @Test
    fun `nouveaux jouets et nourritures ajoutes a la fin`() {
        assertEquals(listOf("ROPE", "YARN", "TUNNEL"), Toy.entries.drop(4).map { it.name })
        assertEquals(listOf("APPLE", "HAY", "TREAT"), Food.entries.drop(3).map { it.name })
        assertTrue(RopeToy.complainsWhenTaken)
        assertTrue(YarnToy.randomTint)
        assertFalse(TunnelToy.randomTint || TunnelToy.complainsWhenTaken)
    }

    @Test
    fun `la friandise - beaucoup de joie, peu de satiete, toujours acceptee`() {
        val treat = Food.TREAT
        for (f in Food.entries) if (f != treat) {
            assertTrue("$f", treat.happiness > f.happiness)
            assertTrue("$f", treat.satiety < f.satiety)
        }
        val full = PetStats(satiety = 99f)
        assertFalse(PetRules.apply(full, PetEvent.Fed(treat), 0L).refused)
        assertTrue(PetRules.apply(full, PetEvent.Fed(Food.APPLE), 0L).refused)
        assertTrue(PetRules.apply(full, PetEvent.Fed(Food.HAY), 0L).refused)
        // le foin nourrit et fait du bien, sans grande fête
        assertTrue(Food.HAY.satiety > Food.APPLE.satiety && Food.HAY.health > 0f && Food.HAY.happiness < Food.APPLE.happiness)
    }

    /** Une balle d'un spec, posée en [x] au-dessus du sol. */
    private fun prop(ctx: FakeToyCtx, t: Toy, x: Float, above: Float = 0f) =
        Prop(ctx.world.newPropId(), t.spec, t.spec.wDp, t.spec.hDp).also {
            it.x = x; it.y = ctx.arena.groundY - above; it.onGround = above == 0f
            ctx.world.props.add(it)
        }

    /** Hauteur du premier rebond d'un jouet lâché de 400 px. */
    private fun firstBounce(t: Toy): Float {
        val ctx = FakeToyCtx()
        val p = prop(ctx, t, 500f, above = 400f)
        val g = ctx.arena.groundY
        var landed = false
        var apex = g
        repeat(600) {
            t.spec.step(ctx, ctx.pet, p, 1f / 60f)
            if (!landed && p.y >= g - 1f) landed = true
            else if (landed) apex = minOf(apex, p.y)
        }
        return g - apex
    }

    @Test
    fun `la pelote rebondit bien moins que la balle et roule loin`() {
        val ball = firstBounce(Toy.BALL)
        val yarn = firstBounce(Toy.YARN)
        assertTrue("balle $ball, pelote $yarn", yarn < ball / 4f)
        // un coup de patte : elle part au ras du sol, il ne saute pas
        val ctx = FakeToyCtx()
        val pet = ctx.pet
        val p = prop(ctx, Toy.YARN, pet.body.fx + 10f)
        YarnToy.play(ctx, pet, p, 1f / 60f)
        assertTrue(p.vx != 0f && p.vy < 0f && p.vy > -300f)
        assertEquals(Act.IDLE, pet.act)
        assertTrue(pet.mind.kickCooldown > 0f)
    }

    @Test
    fun `corde - il l'attrape, la machouille puis la relance`() {
        val ctx = FakeToyCtx()
        val pet = ctx.pet
        pet.stats = pet.stats.copy(happiness = 50f)
        val p = prop(ctx, Toy.ROPE, pet.body.fx + 100f)
        var t = 0f
        while (!p.held) { RopeToy.play(ctx, pet, p, 0.05f); t += 0.05f; assertTrue("jamais attrapée", t < 5f) }
        val grabbed = pet.stats.happiness
        assertTrue(grabbed > 50f)
        // il la mâchouille : un peu de joie à chaque coup de dents, la corde reste dans sa gueule
        repeat(40) { RopeToy.play(ctx, pet, p, 0.05f); RopeToy.step(ctx, pet, p, 0.05f) }
        assertTrue(p.held)
        assertTrue(pet.stats.happiness > grabbed)
        assertEquals(pet.body.fy - 18f, p.y, 0.01f)
        // au bout de CHEW_SECONDS, il la relance et ne la rattrape pas tout de suite
        while (p.held) { RopeToy.play(ctx, pet, p, 0.05f); assertTrue(ctx.holdTime < RopeToy.CHEW_SECONDS + 1f) }
        assertTrue(p.vy < 0f && !p.onGround)
        assertTrue(ctx.catchCooldown > 0f)
        assertTrue(ctx.base.said.size >= 2)
    }

    @Test
    fun `frisbee a deux - seul celui qui l'a attrape le relance, de sa gueule`() {
        val ctx = FakeToyCtx()
        val a = ctx.pet
        val b = testPet(id = "copain")
        b.body.fx = a.body.fx + 300f
        val p = prop(ctx, Toy.FRISBEE, a.body.fx, above = 50f)
        p.holder = a.id
        // l'autre attend : bien plus que le temps de garde (1,3 s), il ne le lance pas
        repeat(60) { FrisbeeToy.play(ctx, b, p, 0.05f) }
        assertEquals(a.id, p.holder)
        assertEquals(0f, ctx.holdTime, 0f)
        // le jouet suit la gueule de celui qui le tient, même si l'autre est passé devant
        FrisbeeToy.step(ctx, a, p, 0.05f)
        assertEquals(a.body.fx + a.body.facing * 26f, p.x, 0.01f)
        // c'est lui qui le lance, depuis sa position
        var t = 0f
        while (p.held) { FrisbeeToy.play(ctx, a, p, 0.05f); t += 0.05f; assertTrue("jamais lancé", t < 2f) }
        assertEquals(a.body.fx, p.x - p.vx / kotlin.math.abs(p.vx) * 30f, 0.01f)
    }

    @Test
    fun `corde a deux - l'autre ne la prend pas ni ne la relance`() {
        val ctx = FakeToyCtx()
        val a = ctx.pet
        val b = testPet(id = "copain")
        val p = prop(ctx, Toy.ROPE, a.body.fx)
        p.holder = a.id
        b.body.fx = p.x                       // juste à côté : il pourrait l'attraper si elle était libre
        repeat((RopeToy.CHEW_SECONDS / 0.05f).toInt() + 20) { RopeToy.play(ctx, b, p, 0.05f) }
        assertEquals(a.id, p.holder)
        assertEquals(0f, ctx.holdTime, 0f)
    }

    @Test
    fun `tunnel - il entre d'un cote et ressort de l'autre, encore et encore`() {
        val ctx = FakeToyCtx()
        val pet = ctx.pet
        pet.stats = pet.stats.copy(happiness = 40f)
        pet.body.fx = 200f
        val p = prop(ctx, Toy.TUNNEL, 600f)
        val (left, right) = TunnelToy.ends(ctx, p)
        assertTrue(left < p.x - p.w / 2f && right > p.x + p.w / 2f)
        var crossings = 0
        var side = 0               // -1 à gauche du tunnel, 1 à droite
        repeat(60 * 30) {
            TunnelToy.play(ctx, pet, p, 1f / 60f)
            pet.mind.tick(1f / 60f)
            val now = when { pet.body.fx <= left + 3f -> -1; pet.body.fx >= right - 3f -> 1; else -> side }
            if (side != 0 && now != side) crossings++
            side = now
        }
        assertTrue("traversées : $crossings", crossings >= 4)
        assertTrue(pet.stats.happiness > 40f + crossings)        // chaque traversée fait plaisir
        assertEquals(0f, p.spin, 0f)
    }

    @Test
    fun `tunnel a deux - chacun sa traversee, l'arrivee de l'autre ne compte pas`() {
        val ctx = FakeToyCtx()
        val a = ctx.pet
        val b = testPet(id = "copain")
        val p = prop(ctx, Toy.TUNNEL, 600f)
        val (left, right) = TunnelToy.ends(ctx, p)
        a.body.fx = left - 40f; b.body.fx = right + 300f
        // a arrive à son entrée : il va traverser
        var t = 0f
        while (!a.mind.crossing) { TunnelToy.play(ctx, a, p, 1f / 60f); t += 1f / 60f; assertTrue(t < 3f) }
        // b arrive à la sienne sans avoir traversé : ni joie ni « Coucou ! »
        val happy = b.stats.happiness
        val said = ctx.base.said.size
        t = 0f
        while (!b.mind.crossing) { TunnelToy.play(ctx, b, p, 1f / 60f); t += 1f / 60f; assertTrue(t < 5f) }
        assertEquals(happy, b.stats.happiness, 0f)
        assertEquals(said, ctx.base.said.size)
        // et l'entrée de b n'a pas effacé la traversée de a : a ressort de l'autre côté, content
        val before = a.stats.happiness
        a.mind.kickCooldown = 0f
        t = 0f
        while (a.mind.kickCooldown <= 0f) { TunnelToy.play(ctx, a, p, 1f / 60f); t += 1f / 60f; assertTrue(t < 5f) }
        assertEquals(right, a.body.fx, 2f)
        assertTrue(a.stats.happiness > before)
    }
}
