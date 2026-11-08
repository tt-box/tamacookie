package com.tamacookie

import com.tamacookie.feature.ActHandler
import com.tamacookie.feature.ActRegistry
import com.tamacookie.feature.Travel
import com.tamacookie.feature.station.BathStation
import com.tamacookie.feature.station.BedStation
import com.tamacookie.feature.station.BowlStation
import com.tamacookie.feature.station.CageStation
import com.tamacookie.feature.station.ChestStation
import com.tamacookie.feature.station.Occupancy
import com.tamacookie.feature.station.StationCtx
import com.tamacookie.feature.station.StationKind
import com.tamacookie.feature.station.VetStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/** Faux contrôleur des stations : enregistre les sorties et les panneaux demandés par les comportements. */
class FakeStationCtx(val fake: FakePetCtx = FakePetCtx()) : StationCtx, PetCtx by fake {
    val leaves = ArrayList<Boolean>()
    var vetPanelOpen = false
    var chestOpened = 0

    override fun leaveStation(pet: Pet, hopOut: Boolean) {
        world.stationOf(pet)?.let { world.station(it).leave(pet.id) }
        leaves += hopOut
    }
    override fun openVetPanel(pet: Pet) { vetPanelOpen = true }
    override fun closeVetPanel() { vetPanelOpen = false }
    override fun openChest() { chestOpened++ }
}

/** Comportements de station (feature/station) : propriétés, entrée / séjour / sortie, occupation. */
class StationBehaviorTest {

    private lateinit var ctx: FakeStationCtx
    private val fake get() = ctx.fake
    private val pet get() = ctx.pet

    @Before fun setUp() {
        ctx = FakeStationCtx()
    }

    /** Comme `StationController.enterStation`, sans les fenêtres. */
    private fun enter(k: StationKind, announce: Boolean = true, p: Pet = pet) {
        assertTrue(ctx.world.station(k).enter(p.id))
        p.setAct(Act.IN_STATION)
        k.behavior.onEnter(ctx, p, announce)
    }

    private fun otherPet(): Pet {
        return testPet(BunnyPhysics(1f), Random(1), id = "seconde").also { ctx.world.add(it) }
    }

    // ------------------------------------------------------------------ Exhaustivité et propriétés

    @Test
    fun `chaque station a un comportement et un nom`() {
        val behaviors = StationKind.entries.map { it.behavior }
        assertEquals("un comportement distinct par station", behaviors.size, behaviors.toSet().size)
        val names = behaviors.map { it.name }
        for (n in names) assertTrue(n, n.isNotBlank())
        assertEquals("noms distincts", names.size, names.toSet().size)
        for (k in StationKind.entries) {
            val b = k.behavior
            if (b.acceptsPet) {
                assertTrue("$k : capacité", b.capacity >= 1)
                assertTrue("$k : explication au tap", b.tapHint.isNotBlank())
                assertTrue("$k : réplique occupé", b.busyLine(ctx).isNotBlank())
            }
        }
    }

    @Test
    fun `ordre et noms des stations inchanges (cles de preferences)`() {
        assertEquals(listOf("BATH", "VET", "CAGE", "CHEST"), StationKind.entries.take(4).map { it.name })
        // évolution 4a : ajoutées à la fin
        assertEquals(listOf("BED", "BOWL", "WHEEL", "GARDEN"), StationKind.entries.drop(4).map { it.name })
        assertEquals("st_CAGE_anchor", Prefs.stationAnchor(StationKind.CAGE))
    }

    @Test
    fun `proprietes lues sur le comportement`() {
        assertSame(BathStation, StationKind.BATH.behavior)
        assertFalse(ChestStation.acceptsPet)
        // cadence : seule la cage est économe (comme l'ancien Act.CAGED dans Act.SLOW)
        assertTrue(CageStation.slowFrames)
        assertFalse(BathStation.slowFrames || VetStation.slowFrames)
        // voyage : au bain il commente, chez le véto et en cage il se tait
        assertEquals(Travel.BUSY, BathStation.travel)
        assertEquals(Travel.IGNORES, VetStation.travel)
        assertEquals(Travel.IGNORES, CageStation.travel)
        for (k in StationKind.entries) if (k.behavior.acceptsPet) assertTrue("$k", k.behavior.blocksTravel)
        // la cage et le lit gardent l'animal d'une vue à l'autre ; décor animé : baignoire et roue (4a)
        assertEquals(listOf(StationKind.CAGE, StationKind.BED), StationKind.entries.filter { it.behavior.holdsPet })
        assertEquals(listOf(StationKind.BATH, StationKind.WHEEL), StationKind.entries.filter { it.behavior.animated })
        // il s'endort dans la cage (qui le garde) et dans le lit
        assertEquals(listOf(StationKind.CAGE, StationKind.BED), StationKind.entries.filter { it.behavior.sleepsInside })
        // seul le lit a deux places
        assertEquals(listOf(StationKind.BED), StationKind.entries.filter { it.behavior.capacity > 1 })
        // places (dp) : celles de l'ancien positionInStation
        assertEquals(12f, VetStation.seat.dxDp); assertEquals(26f, VetStation.seat.aboveBottomDp)
        assertEquals(0f, BathStation.seat.dxDp); assertEquals(14f, BathStation.seat.aboveBottomDp)
        assertEquals(16f, CageStation.seat.aboveBottomDp)
    }

    // ------------------------------------------------------------------ Occupation et capacité

    @Test
    fun `une place par station - le coffre n'accueille personne`() {
        val other = otherPet()
        val bath = ctx.world.station(StationKind.BATH)
        assertEquals(1, bath.capacity)
        assertTrue(bath.enter(pet.id))
        assertFalse("déjà dedans", bath.enter(pet.id))
        assertFalse("pleine", bath.canEnter(other.id))
        assertEquals(listOf(pet.id), bath.occupants)
        assertTrue(bath.isFull)
        assertFalse(ctx.world.station(StationKind.CHEST).canEnter(pet.id))
        assertTrue(bath.leave(pet.id))
        assertFalse(bath.leave(pet.id))
        assertTrue(bath.isEmpty && bath.canEnter(other.id))
    }

    @Test
    fun `capacite superieure - plusieurs occupants`() {
        val other = otherPet()
        val bed = Occupancy(StationKind.BATH, capacity = 2)
        assertTrue(bed.enter(pet.id))
        assertTrue(bed.enter(other.id))
        assertTrue(bed.isFull)
        assertTrue(bed.has(pet.id) && bed.has(other.id))
        bed.leave(pet.id)
        assertEquals(listOf(other.id), bed.occupants)
    }

    @Test
    fun `le monde sait ou est chaque animal`() {
        val other = otherPet()
        assertNull(ctx.world.stationOf(pet))
        assertFalse(ctx.world.busy(pet))
        ctx.world.station(StationKind.VET).enter(pet.id)
        assertEquals(StationKind.VET, ctx.world.stationOf(pet))
        assertTrue(ctx.world.busy(pet))
        assertNull(ctx.world.stationOf(other))
        assertFalse(ctx.world.busy(other))
    }

    @Test
    fun `sayIfBusy - replique de la station occupee`() {
        assertFalse(ctx.sayIfBusy(pet))
        assertTrue(fake.said.isEmpty())
        ctx.world.station(StationKind.BATH).enter(pet.id)
        assertTrue(ctx.sayIfBusy(pet))
        assertEquals(BathStation.busyLine(ctx), fake.said.last())
        ctx.world.station(StationKind.BATH).leave(pet.id)
        pet.setAct(Act.HIDDEN)
        assertTrue(ctx.sayIfBusy(pet))
        assertTrue(fake.said.last().contains("cache-cache"))
    }

    // ------------------------------------------------------------------ Dispatch Act → fonctionnalité

    @Test
    fun `registre - l'activite courante choisit le gestionnaire`() {
        val reg = ActRegistry()
        val h = object : ActHandler {
            override val slowFrames = true
            override fun update(pet: Pet, dt: Float) {}
        }
        reg.register(Act.IN_STATION) { p -> if (ctx.world.stationOf(p) != null) h else null }
        assertNull("autre activité", reg.handlerFor(pet))
        pet.setAct(Act.IN_STATION)
        assertNull("station introuvable", reg.handlerFor(pet))
        ctx.world.station(StationKind.CAGE).enter(pet.id)
        assertSame(h, reg.handlerFor(pet))
        assertTrue(reg.handlerFor(pet)!!.slowFrames)
        assertTrue(h.blocksTravel.not())
        try { reg.register(Act.IN_STATION) { null }; throw AssertionError("double enregistrement accepté") }
        catch (_: IllegalStateException) {}
    }

    @Test
    fun `IN_STATION n'est dans aucun ensemble d'Act`() {
        for (set in listOf(Act.MOVING, Act.SLOW, Act.NO_PAGE_FOLLOW, Act.CAN_COME_BACK_OR_PAGE_OUT,
            Act.RUSH_BACK, Act.BUSY_FOR_TRAVEL, Act.HIDING, Act.FREE)) assertFalse(Act.IN_STATION in set)
    }

    // ------------------------------------------------------------------ Baignoire

    @Test
    fun `bain - il se reveille, mousse puis ressort propre`() {
        pet.stats = pet.stats.copy(sleeping = true)
        pet.stats = pet.stats.copy(cleanliness = 10f)
        enter(StationKind.BATH)
        assertFalse(pet.stats.sleeping)
        assertEquals(1, fake.said.size)
        // pendant le bain
        repeat(30) { pet.body.actTime += 0.1f; BathStation.update(ctx, pet, 0.1f) }
        assertEquals(Pose.IDLE, pet.body.pose)
        assertEquals(Expr.HAPPY, pet.mind.exprOverride)
        assertTrue(fake.emitted.any { it == PType.SPARKLE || it == PType.BUBBLE })
        assertTrue(ctx.leaves.isEmpty())
        assertEquals(10f, pet.stats.cleanliness)
        // fin du bain
        pet.body.actTime = BathStation.BATH_SECONDS + 0.1f
        BathStation.update(ctx, pet, 0.1f)
        assertEquals(100f, pet.stats.cleanliness)
        assertEquals(listOf(true), ctx.leaves)          // il saute dehors
        assertNull(ctx.world.stationOf(pet))
        assertEquals(2, fake.said.size)
    }

    @Test
    fun `bain sans annonce - silencieux`() {
        enter(StationKind.BATH, announce = false)
        assertTrue(fake.said.isEmpty())
    }

    // ------------------------------------------------------------------ Véto

    @Test
    fun `veto - fiche ouverte, assis tourne vers le centre, fiche fermee a la sortie`() {
        pet.body.fx = 100f; pet.body.facing = -1f
        enter(StationKind.VET)
        assertTrue(ctx.vetPanelOpen)
        VetStation.update(ctx, pet, 0.016f)
        assertEquals(Pose.SIT, pet.body.pose)
        assertEquals(1f, pet.body.facing)
        ctx.world.station(StationKind.VET).leave(pet.id)
        VetStation.onLeave(ctx, pet)
        assertFalse(ctx.vetPanelOpen)
    }

    // ------------------------------------------------------------------ Cage

    @Test
    fun `cage - enferme, sauvegarde, se plaint, liberte a la sortie`() {
        enter(StationKind.CAGE)
        assertTrue(pet.stats.caged)
        assertTrue(CageStation.restores(pet))
        assertEquals(Expr.SAD, pet.mind.exprOverride)
        assertEquals(1, fake.notifications)
        assertEquals(1, fake.said.size)
        // il se plaint au bout de 20 s
        repeat(19) { CageStation.update(ctx, pet, 1f) }
        assertEquals(1, fake.said.size)
        assertEquals(Pose.SIT, pet.body.pose)
        repeat(2) { CageStation.update(ctx, pet, 1f) }
        assertEquals(2, fake.said.size)
        assertTrue(pet.mind.chatterIn in 24f..45f)
        // endormi : il dort sans rien dire
        pet.stats = pet.stats.copy(sleeping = true)
        repeat(60) { CageStation.update(ctx, pet, 1f) }
        assertEquals(Pose.SLEEP, pet.body.pose)
        assertEquals(2, fake.said.size)
        // sortie
        ctx.world.station(StationKind.CAGE).leave(pet.id)
        CageStation.onLeave(ctx, pet)
        assertFalse(pet.stats.caged)
        assertFalse(CageStation.restores(pet))
        assertEquals(3, fake.emitted.count { it == PType.HEART })
        assertEquals(Expr.HAPPY, pet.mind.exprOverride)
        assertEquals(2, fake.notifications)
    }

    @Test
    fun `cage restauree au demarrage - sans annonce`() {
        enter(StationKind.CAGE, announce = false)
        assertTrue(fake.said.isEmpty())
        assertNull(pet.mind.exprOverride)
        assertEquals(1, fake.notifications)
    }

    @Test
    fun `caresses - un coeur, et un calin a travers les barreaux`() {
        enter(StationKind.BATH, announce = false)
        BathStation.onPet(ctx, pet)
        assertEquals(listOf(PType.HEART), fake.emitted)
        ctx.world.station(StationKind.BATH).leave(pet.id)
        enter(StationKind.CAGE, announce = false)
        pet.stats = pet.stats.copy(happiness = 50f)
        CageStation.onPet(ctx, pet)
        assertTrue(pet.stats.happiness > 50f)
        assertEquals(2, fake.emitted.count { it == PType.HEART })
        assertTrue(fake.said.last() in CageStation.PETTED)
    }

    @Test
    fun `tap - explication, ou ouverture du coffre`() {
        BathStation.onTap(ctx)
        assertTrue(fake.said.last().startsWith(BathStation.tapHint))
        ChestStation.onTap(ctx)
        assertEquals(1, ctx.chestOpened)
    }

    @Test
    fun `retour de la cage sur la vue - il ne parle que si elle est a l'ecran`() {
        CageStation.onShownAgain(ctx, pet, onScreen = false)
        assertTrue(fake.said.isEmpty())
        CageStation.onShownAgain(ctx, pet, onScreen = true)
        assertEquals(1, fake.said.size)
        assertNotNull(fake.said.first())
    }

    // ------------------------------------------------------------------ Évolution 4a : lit, gamelle, roue, jardin

    /** [n] images de [dt] dans la station [k] (le temps de l'activité avance, comme dans la boucle). */
    private fun stay(k: StationKind, n: Int, dt: Float = 0.1f, p: Pet = pet) {
        repeat(n) {
            if (ctx.world.stationOf(p) != k) return
            p.body.actTime += dt
            k.behavior.update(ctx, p, dt)
        }
    }

    @Test
    fun `lit - deux places distinctes, il s'endort et recupere plus vite`() {
        val other = otherPet()
        val bed = ctx.world.station(StationKind.BED)
        assertEquals(2, bed.capacity)
        assertTrue(BedStation.seatFor(0).dxDp != BedStation.seatFor(1).dxDp)
        pet.stats = pet.stats.copy(energy = 30f)
        other.stats = other.stats.copy(energy = 30f)
        enter(StationKind.BED)
        enter(StationKind.BED, p = other)
        assertTrue(bed.isFull)
        assertTrue(pet.stats.sleeping && other.stats.sleeping)
        assertTrue(fake.emitted.contains(PType.ZZZ))
        // une heure de lit : l'énergie du lit en plus (le temps qui passe est compté ailleurs)
        stay(StationKind.BED, 36, dt = 100f)
        assertEquals(Pose.SLEEP, pet.body.pose)
        assertEquals(30f + GameRules.DEFAULT.bedEnergyGainPerH, pet.stats.energy, 0.01f)
        assertTrue(ctx.leaves.isEmpty())
    }

    @Test
    fun `lit - une caresse le reveille en douceur, il sort un peu apres`() {
        pet.stats = pet.stats.copy(energy = 30f, happiness = 50f)
        enter(StationKind.BED)
        BedStation.onPet(ctx, pet)
        assertFalse(pet.stats.sleeping)
        assertEquals("réveil sans mauvaise humeur", 50f, pet.stats.happiness, 0f)
        stay(StationKind.BED, 10)
        assertEquals(Pose.SIT, pet.body.pose)
        assertTrue(ctx.leaves.isEmpty())
        stay(StationKind.BED, 30)
        assertEquals(listOf(true), ctx.leaves)
    }

    @Test
    fun `lit - pas fatigue il ne dort pas, et le sortir le reveille`() {
        pet.stats = pet.stats.copy(energy = 99f)
        enter(StationKind.BED)
        assertFalse(pet.stats.sleeping)
        stay(StationKind.BED, 40)
        assertEquals(listOf(true), ctx.leaves)
        // endormi puis sorti (glisser, changement de vue) : réveillé
        pet.stats = pet.stats.copy(energy = 20f)
        enter(StationKind.BED, announce = false)
        assertTrue(pet.stats.sleeping)
        ctx.world.station(StationKind.BED).leave(pet.id)
        BedStation.onLeave(ctx, pet)
        assertFalse(pet.stats.sleeping)
        // l'événement du lit n'agit que sur un animal endormi
        assertEquals(20f, PetRules.apply(PetStats(energy = 20f), PetEvent.Rested(1f), 0L).stats.energy, 0f)
    }

    @Test
    fun `gamelle - il renifle puis mange s'il a faim`() {
        pet.stats = pet.stats.copy(satiety = 30f)
        enter(StationKind.BOWL)
        stay(StationKind.BOWL, 5)
        assertEquals(Pose.IDLE, pet.body.pose)
        stay(StationKind.BOWL, 10)
        assertEquals(Pose.EAT, pet.body.pose)
        assertEquals(BowlStation.FOOD, pet.body.eatingFood)
        assertTrue(pet.body.eatProgress > 0f)
        assertEquals(30f, pet.stats.satiety, 0f)
        stay(StationKind.BOWL, 30)
        assertEquals(30f + BowlStation.FOOD.satiety, pet.stats.satiety, 0.01f)
        assertEquals(listOf(true), ctx.leaves)
        assertEquals(0f, pet.body.eatProgress, 0f)
        assertEquals(listOf(pet.id), fake.saved)          // repas sauvegardé tout de suite
    }

    @Test
    fun `gamelle - pas faim, il ressort sans manger`() {
        pet.stats = pet.stats.copy(satiety = 85f)
        enter(StationKind.BOWL)
        stay(StationKind.BOWL, 15)
        assertEquals(listOf(true), ctx.leaves)
        assertEquals(85f, pet.stats.satiety, 0f)
        assertEquals(2, fake.said.size)
    }

    @Test
    fun `roue - il court, se fatigue et s'amuse`() {
        pet.stats = pet.stats.copy(energy = 60f, happiness = 50f)
        enter(StationKind.WHEEL)
        stay(StationKind.WHEEL, 19)
        assertEquals(Pose.WALK, pet.body.pose)
        assertTrue(pet.body.walkPhase > 0f)
        assertTrue(ctx.leaves.isEmpty())
        stay(StationKind.WHEEL, 50)
        assertEquals(listOf(true), ctx.leaves)
        val r = GameRules.DEFAULT
        assertEquals(60f - r.wheelEnergyCost, pet.stats.energy, 0.01f)
        assertTrue(pet.stats.happiness > 50f)
    }

    @Test
    fun `roue - trop fatigue, il refuse et ressort aussitot`() {
        pet.stats = pet.stats.copy(energy = 5f, happiness = 50f)
        enter(StationKind.WHEEL)
        assertEquals(listOf(true), ctx.leaves)
        assertEquals(5f, pet.stats.energy, 0f)
        assertEquals(1, fake.said.size)
        assertTrue(PetRules.apply(PetStats(sleeping = true), PetEvent.WheelRun, 0L).refused)
    }

    @Test
    fun `jardin - il grignote, se roule, un peu de joie et de terre`() {
        pet.stats = pet.stats.copy(happiness = 50f, satiety = 50f, cleanliness = 80f)
        enter(StationKind.GARDEN)
        stay(StationKind.GARDEN, 10)
        assertEquals(Pose.EAT, pet.body.pose)
        stay(StationKind.GARDEN, 20)
        assertEquals(Pose.SIT, pet.body.pose)
        val facings = HashSet<Float>()
        repeat(15) { stay(StationKind.GARDEN, 1); facings += pet.body.facing }
        assertEquals("il se roule d'un côté à l'autre", 2, facings.size)
        stay(StationKind.GARDEN, 20)
        assertEquals(listOf(true), ctx.leaves)
        val r = GameRules.DEFAULT
        assertEquals(50f + r.gardenJoy, pet.stats.happiness, 0.01f)
        assertEquals(50f + r.gardenSatiety, pet.stats.satiety, 0.01f)
        assertEquals(80f - r.gardenCleanlinessCost, pet.stats.cleanliness, 0.01f)
    }
}
