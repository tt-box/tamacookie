package com.tamacookie

import com.tamacookie.Director.BubbleQueue
import com.tamacookie.Director.BubbleQueue.Line
import com.tamacookie.feature.station.StationKind
import com.tamacookie.feature.toy.Food
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** L'arbitre global : une bêtise, un animal au coffre, une nourriture, une bulle à la fois ; notifications. */
class DirectorTest {
    private val ctx = FakePetCtx()
    private val a get() = ctx.first
    private val dir get() = ctx.director

    // --- Bêtises ---
    @Test fun uneSeuleBetiseALaFois() {
        val b = ctx.addPet("b")
        assertTrue(dir.mayStartMischief(a)); assertTrue(dir.mayStartMischief(b))
        assertNull(dir.mischiefOwner())
        a.setAct(Act.KNOCK)
        assertSame(a, dir.mischiefOwner())
        assertTrue(dir.mayStartMischief(a))
        assertFalse(dir.mayStartMischief(b))
        a.setAct(Act.IDLE)
        b.mind.mischief.hidingView = "launcher#p3"          // cache-cache (même entre deux activités)
        assertSame(b, dir.mischiefOwner())
        assertFalse(dir.mayStartMischief(a))
    }

    @Test fun seulAvecLuiMeme_toujoursPermis() {
        for (act in Director.MISCHIEF_ACTS) { a.setAct(act); assertTrue(dir.mayStartMischief(a)) }
        assertTrue(dir.mayHeadTo(a, StationKind.CHEST))
    }

    /** Le compagnon ignoré ne lance rien (et ne tire pas au sort) tant que le résident fait une bêtise. */
    @Test fun controleur_refuseUneDeuxiemeBetise() {
        val b = ctx.addPet("b", seed = 9)
        val mis = MischiefController(ctx)
        mis.registerActs(ctx.acts)
        a.setAct(Act.SQUAT)
        ctx.lastInteraction = ctx.clock.wallMs() - 3_600_000L
        val probe = kotlin.random.Random(9)                   // même graine que le compagnon
        val refused = ctx.world.asPet(b) { mis.maybeSeekAttention() }
        assertFalse(refused)
        assertTrue(ctx.said.isEmpty())
        assertEquals(probe.nextInt(), b.mind.rnd.nextInt())  // aucun tirage consommé par le refus
        assertFalse(b.act in Director.MISCHIEF_ACTS)
    }

    @Test fun cacheCacheDepuisLeMenu_refuseSiUnAutreJoue() {
        val b = ctx.addPet("b")
        val mis = MischiefController(ctx)
        a.setAct(Act.HIDDEN)
        val ok = ctx.world.asPet(b) { mis.startHideSeek(fromMenu = true) }
        assertFalse(ok)
        assertTrue(ctx.said.single().contains("une à la fois"))
    }

    // --- Coffre et stations de capacité 1 ---
    @Test fun unSeulAnimalAuCoffre() {
        val b = ctx.addPet("b")
        a.setAct(Act.GOTO_CHEST)
        assertFalse(dir.mayHeadTo(b, StationKind.CHEST))
        assertTrue(dir.mayHeadTo(a, StationKind.CHEST))
        a.setAct(Act.IDLE)
        assertTrue(dir.mayHeadTo(b, StationKind.CHEST))
    }

    @Test fun stationDeCapacite1_unSeulOccupant() {
        val b = ctx.addPet("b")
        val toys = ToyController(ctx)
        val sc = StationController(ctx, toys, FakeStationPanels())
        sc.registerActs(ctx.acts)
        sc.create()
        ctx.pages.currentView = "com.app"
        sc.toggleStation(StationKind.BATH)
        assertEquals(1, StationKind.BATH.behavior.capacity)
        ctx.world.asPet(a) { sc.enterStation(StationKind.BATH) }
        ctx.world.asPet(b) { sc.enterStation(StationKind.BATH) }
        assertEquals(StationKind.BATH, ctx.world.stationOf(a))
        assertNull(ctx.world.stationOf(b))
        assertEquals(Act.IN_STATION, a.act)
        assertFalse(b.act == Act.IN_STATION)
        assertSame(a, sc.occupantOf(sc[StationKind.BATH]))
        // le premier sort : le second peut entrer
        sc.leaveStation(a, hopOut = false)
        ctx.world.asPet(b) { sc.enterStation(StationKind.BATH) }
        assertEquals(StationKind.BATH, ctx.world.stationOf(b))
        assertSame(b, sc.occupantOf(sc[StationKind.BATH]))
    }

    // --- Nourriture ---
    @Test fun nourriture_lePlusProcheLEmporte_lAutreRenonce() {
        val b = ctx.addPet("b", x = 900f, seed = 3)
        val toys = ToyController(ctx)
        toys.registerActs(ctx.acts)
        a.body.fx = 200f
        toys.feed(Food.CARROT)
        val food = ctx.world.props.single { it.food != null }
        food.x = 850f; food.y = ctx.arena.groundY; food.onGround = true; food.vy = 0f
        a.setAct(Act.GOTO_FOOD); b.setAct(Act.GOTO_FOOD)
        assertSame(b, dir.foodWinner(food))
        ctx.said.clear(); ctx.speakers.clear()
        ctx.world.asPet(a) { ctx.acts.handlerFor(a)!!.update(a, 0.05f) }
        assertEquals(Act.IDLE, a.act)                           // il renonce…
        // … et le différend est noté pour le système social (qui met la dispute en scène)
        assertEquals(listOf(Director.Dispute(a.id, b.id)), dir.takeDisputes())
        assertTrue(dir.takeDisputes().isEmpty())
        assertFalse((dir.foodTarget(a) != null))
        assertTrue((dir.foodTarget(b) != null))
        // le gagnant mange
        repeat(200) { if (b.act == Act.GOTO_FOOD) ctx.world.asPet(b) { ctx.acts.handlerFor(b)!!.update(b, 0.05f) } }
        assertEquals(Act.EAT, b.act)
        // la nourriture a disparu : il pourra courir vers la prochaine
        assertNull(dir.foodTarget(a))
        toys.feed(Food.APPLE)
        assertSame(ctx.world.props.single { it.food != null }, dir.foodTarget(a))
    }

    @Test fun nourriture_deuxNourritures_chacunLaSienneSansDispute() {
        val b = ctx.addPet("b", x = 900f, seed = 3)
        val toys = ToyController(ctx)
        toys.registerActs(ctx.acts)
        a.body.fx = 200f
        toys.feed(Food.CARROT); toys.feed(Food.LETTUCE)
        val (f1, f2) = ctx.world.props.filter { it.food != null }
        for (f in listOf(f1, f2)) { f.y = ctx.arena.groundY; f.onGround = true; f.vx = 0f; f.vy = 0f }
        f1.x = 250f; f2.x = 850f
        a.setAct(Act.GOTO_FOOD); b.setAct(Act.GOTO_FOOD)
        assertSame(f1, dir.foodTarget(a)); assertSame(f2, dir.foodTarget(b))
        assertSame(a, dir.foodWinner(f1)); assertSame(b, dir.foodWinner(f2))
        ctx.world.asPet(a) { ctx.acts.handlerFor(a)!!.update(a, 0.05f) }
        ctx.world.asPet(b) { ctx.acts.handlerFor(b)!!.update(b, 0.05f) }
        assertTrue(dir.takeDisputes().isEmpty())
    }

    @Test fun nourriture_personneNEstServi_leperdantRapprocheLaReprend() {
        val b = ctx.addPet("b", x = 900f, seed = 3)
        val toys = ToyController(ctx)
        toys.registerActs(ctx.acts)
        a.body.fx = 200f
        toys.feed(Food.CARROT)                                   // donnée au premier…
        val food = ctx.world.props.single { it.food != null }
        food.x = 850f; food.y = ctx.arena.groundY; food.onGround = true; food.vy = 0f
        a.setAct(Act.GOTO_FOOD); b.setAct(Act.GOTO_FOOD)
        ctx.world.asPet(a) { ctx.acts.handlerFor(a)!!.update(a, 0.05f) }
        assertEquals(Act.IDLE, a.act)                            // … mais le compagnon est plus près : a renonce
        assertFalse((dir.foodTarget(a) != null))
        // on déplace a tout contre la nourriture : il y retourne, et b renonce à son tour
        a.body.fx = 860f
        assertTrue((dir.foodTarget(a) != null))
        a.setAct(Act.GOTO_FOOD)
        assertSame(a, dir.foodWinner(food))
        dir.takeDisputes()
        ctx.world.asPet(b) { ctx.acts.handlerFor(b)!!.update(b, 0.05f) }
        assertEquals(Act.IDLE, b.act)
        assertEquals(listOf(Director.Dispute(b.id, a.id)), dir.takeDisputes())
    }

    @Test fun nourriture_unCompagnonQuiMarcheOuEstAssisLaRemarque() {
        val b = ctx.addPet("b", x = 820f, seed = 3)
        val toys = ToyController(ctx)
        toys.registerActs(ctx.acts)
        val behavior = PetBehavior(ctx, object : PetBehavior.Hooks {
            override fun chestVisible(p: Pet) = false
            override val lastSelfServe = 0L
            override fun goToChest(p: Pet, now: Long) {}
            override fun seekAttention(p: Pet) = false
            override fun startPageWander(p: Pet) {}
            override fun hour() = 12
        })
        for (act in listOf(Act.WALK, Act.SIT)) {
            ctx.world.props.toList().forEach { toys.removeProp(it) }
            toys.feed(Food.CARROT)
            b.setAct(act, 30f)
            ctx.world.asPet(b) { behavior.update(b, 0.05f) }
            assertEquals(act.name, Act.GOTO_FOOD, b.act)
        }
    }

    @Test fun nourriture_seul_ilGagneToujours() {
        val toys = ToyController(ctx)
        toys.feed(Food.CARROT)
        val food = ctx.world.props.single { it.food != null }
        a.setAct(Act.GOTO_FOOD)
        assertSame(a, dir.foodWinner(food))
        assertTrue((dir.foodTarget(a) != null))
    }

    // --- Bulles ---
    private fun line(p: String, t: String, s: Float = 3f, pr: SpeechPriority = SpeechPriority.NORMAL) =
        Line(PetId(p), t, s, pr)

    @Test fun bulles_unAnimalRemplaceToujoursLaSienne() {
        val q = BubbleQueue()
        assertTrue(q.request(line("a", "1")))
        assertTrue(q.request(line("a", "2", pr = SpeechPriority.LOW)))
        assertEquals("2", q.current!!.text)
        assertTrue(q.waiting.isEmpty())
    }

    @Test fun bulles_unAutreAttendSonTour_parPriorite() {
        val q = BubbleQueue()
        assertTrue(q.request(line("a", "a1", s = 2f)))
        assertFalse(q.request(line("b", "b1", pr = SpeechPriority.LOW)))
        assertFalse(q.request(line("c", "c1", s = 1f, pr = SpeechPriority.NORMAL)))
        assertEquals(2, q.waiting.size)
        assertNull(q.tick(1f))
        val next = q.tick(1.1f)
        assertEquals("c1", next!!.text)                        // plus prioritaire d'abord
        assertEquals("b1", q.tick(1.1f)!!.text)
        assertNull(q.tick(10f))
        assertNull(q.current)
    }

    @Test fun bulles_plusPrioritairePasseDevant_basseAbandonnee() {
        val q = BubbleQueue()
        q.request(line("a", "bavardage", s = 20f, pr = SpeechPriority.LOW))
        assertTrue(q.request(line("b", "aïe !", pr = SpeechPriority.HIGH)))
        assertEquals("aïe !", q.current!!.text)
        assertFalse(q.request(line("a", "bla", pr = SpeechPriority.LOW)))
        q.tick(BubbleQueue.LOW_MAX_WAIT_S + 0.5f)                // a attendu trop longtemps : abandonnée
        assertTrue(q.waiting.isEmpty())
    }

    @Test fun bulles_fileBornee_etOubli() {
        val q = BubbleQueue()
        q.request(line("a", "a", s = 30f, pr = SpeechPriority.HIGH))
        for (i in 1..BubbleQueue.MAX_QUEUE) q.request(line("p$i", "x$i", pr = SpeechPriority.LOW))
        assertEquals(BubbleQueue.MAX_QUEUE, q.waiting.size)
        assertFalse(q.request(line("z", "z", pr = SpeechPriority.LOW)))          // pleine : pas mieux
        assertFalse(q.request(line("y", "y", pr = SpeechPriority.NORMAL)))       // remplace une LOW
        assertTrue(q.waiting.any { it.text == "y" })
        assertEquals(BubbleQueue.MAX_QUEUE, q.waiting.size)
        q.forget(PetId("y")); q.forget(PetId("a"))
        assertNull(q.current)
        assertFalse(q.waiting.any { it.pet == PetId("y") })
    }

    // --- Notifications ---
    @Test fun notifications_seul_commeAvant() {
        assertEquals("${a.name} se promène", dir.title { "se promène" })
        assertEquals("L", dir.statusLine { "L" })
        a.stats = a.stats.copy(satiety = 5f)
        assertEquals(NotifierTexts.alertText(a.name, a.stats.mood), dir.alerts { NotifierTexts.alertText(it.name, it.stats.mood) })
        assertNull(dir.alerts { NotifierTexts.alertText(it.name, it.stats.mood) })   // même humeur : pas de nouvelle alerte
    }

    @Test fun notifications_agregees() {
        val b = ctx.addPet("b")
        b.rename("Noisette")
        ctx.addPet("c").rename("Praline")
        assertEquals("${a.name} dort · +2 compagnons", dir.title { "dort" })
        assertTrue(dir.statusLine { "L" }.startsWith("L  ·  Noisette "))
        assertTrue(dir.statusLine { "L" }.contains("Praline"))
        dir.alerts { null }                                       // humeurs de départ
        a.stats = a.stats.copy(satiety = 5f)
        b.stats = b.stats.copy(satiety = 5f)
        val txt = dir.alerts { NotifierTexts.alertText(it.name, it.stats.mood) }!!
        assertTrue(txt, txt.contains(a.name) && txt.contains("Noisette") && " · " in txt)
    }
}
