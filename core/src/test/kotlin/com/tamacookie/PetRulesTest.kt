package com.tamacookie

import com.tamacookie.PetEvent.AppVisited
import com.tamacookie.PetEvent.Bathed
import com.tamacookie.PetEvent.Elapsed
import com.tamacookie.PetEvent.Fed
import com.tamacookie.PetEvent.Healed
import com.tamacookie.PetEvent.Petted
import com.tamacookie.PetEvent.PlayHit
import com.tamacookie.PetEvent.PlayStarted
import com.tamacookie.PetEvent.PoopRemoved
import com.tamacookie.feature.toy.Food
import com.tamacookie.feature.toy.Toy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Règles des jauges (caractérisation : mêmes taux, seuils et ordre que l'ancien `PetState`). */
class PetRulesTest {

    private val eps = 0.001f
    private val now = 1_700_000_000_000L

    private fun on(s: PetStats, ev: PetEvent, traits: Traits = Traits(), at: Long = now) = PetRules.apply(s, ev, at, traits)

    @Test
    fun `evolution 4a - lit, roue, jardin`() {
        val r = GameRules.DEFAULT
        // le lit : seulement endormi, plafonné comme le temps qui passe, borné
        assertEquals(40f + r.bedEnergyGainPerH * 2f, on(PetStats(energy = 40f, sleeping = true), PetEvent.Rested(2f)).stats.energy, eps)
        assertEquals(PetStats(energy = 40f), on(PetStats(energy = 40f), PetEvent.Rested(2f)).stats)
        assertEquals(100f, on(PetStats(energy = 95f, sleeping = true), PetEvent.Rested(1f)).stats.energy, eps)
        // la roue : joie modulée par le caractère comme le jeu, refusée trop fatigué
        val run = on(PetStats(energy = 50f, happiness = 50f, satiety = 50f), PetEvent.WheelRun, Traits(Personality.LAZY))
        assertEquals(50f - r.wheelEnergyCost, run.stats.energy, eps)
        assertEquals(50f + r.wheelJoy * Personality.LAZY.mod(StatMod.PLAY_JOY), run.stats.happiness, eps)
        assertEquals(50f - r.wheelSatietyCost, run.stats.satiety, eps)
        assertTrue(on(PetStats(energy = r.playMinEnergy - 1f), PetEvent.WheelRun).refused)
        // le jardin
        val g = on(PetStats(happiness = 99f, cleanliness = 2f), PetEvent.Grazed).stats
        assertEquals(100f, g.happiness, eps)
        assertEquals(0f, g.cleanliness, eps)
        // sauvegarde immédiate : la roue et le jardin, pas le lit (chaque image)
        assertTrue(PetEvent.WheelRun.savesNow && PetEvent.Grazed.savesNow)
        assertFalse(PetEvent.Rested(0.1f).savesNow)
    }

    @Test
    fun `zero heure ne change rien`() {
        val r = on(PetStats(), Elapsed(0f))
        assertEquals(PetStats(), r.stats)
        assertEquals(0, r.newPoops)
    }

    @Test
    fun `la faim augmente avec le temps`() {
        assertEquals(80f - 14f, on(PetStats(), Elapsed(2f)).stats.satiety, eps)
    }

    @Test
    fun `le rattrapage est plafonne a 48 heures`() {
        assertEquals(on(PetStats(), Elapsed(48f)).stats, on(PetStats(), Elapsed(500f)).stats)
    }

    @Test
    fun `un gourmand a faim plus vite`() {
        val normal = on(PetStats(), Elapsed(2f), Traits(Personality.CUDDLY)).stats
        val glutton = on(PetStats(), Elapsed(2f), Traits(Personality.GLUTTON)).stats
        assertTrue(glutton.satiety < normal.satiety)
    }

    @Test
    fun `en cage le bonheur baisse trois fois plus vite`() {
        val lazy = Traits(Personality.LAZY)
        val free = on(PetStats(), Elapsed(1f), lazy).stats
        val caged = on(PetStats(caged = true), Elapsed(1f), lazy).stats
        assertEquals((80f - free.happiness) * 3f, 80f - caged.happiness, eps)
    }

    @Test
    fun `les jauges restent entre 0 et 100`() {
        var s = on(PetStats(), Elapsed(48f)).stats
        for (v in listOf(s.satiety, s.happiness, s.energy, s.cleanliness, s.health)) assertTrue("valeur $v", v in 0f..100f)
        repeat(3) { s = on(s, Healed).stats }
        assertTrue(s.health <= 100f)
        assertEquals(0f, on(PetStats(happiness = 1f), PetEvent.HardLanding).stats.happiness, eps)
        assertEquals(100f, on(PetStats(happiness = 95f), PetEvent.Found).stats.happiness, eps)
    }

    @Test
    fun `le sommeil recharge l'energie et le reveille a 100`() {
        val r1 = on(PetStats(energy = 20f, sleeping = true), Elapsed(1f))
        assertEquals(45f, r1.stats.energy, eps)
        assertTrue(r1.stats.sleeping)
        val r2 = on(r1.stats, Elapsed(4f))
        assertEquals(100f, r2.stats.energy, eps)
        assertFalse(r2.stats.sleeping)
        assertTrue(PetEffect.WokeUp in r2.effects)
    }

    @Test
    fun `environ une crotte toutes les trois heures quand il a mange`() {
        val r = on(PetStats(satiety = 100f), Elapsed(3.01f))
        assertEquals(1, r.newPoops)
        assertEquals(listOf(PetEffect.PoopSpawned(1)), r.effects)
        assertEquals(1, r.stats.poops)
    }

    @Test
    fun `pas de crotte s'il a faim ou s'il dort`() {
        assertEquals(0, on(PetStats(satiety = 10f), Elapsed(6f)).newPoops)
        assertEquals(0, on(PetStats(sleeping = true, energy = 0f), Elapsed(3.5f)).newPoops)
    }

    @Test
    fun `au maximum cinq crottes`() {
        var s = PetStats()
        repeat(20) { s = on(s.copy(satiety = 100f), Elapsed(3.1f)).stats }
        assertEquals(5, s.poops)
    }

    @Test
    fun `manger rassasie, repu il refuse sauf une fraise`() {
        assertEquals(30f + Food.CARROT.satiety, on(PetStats(satiety = 30f), Fed(Food.CARROT)).stats.satiety, eps)
        val full = PetStats(satiety = 98f)
        val refused = on(full, Fed(Food.CARROT))
        assertTrue(refused.refused)
        assertEquals(full, refused.stats)
        assertFalse(on(full, Fed(Food.STRAWBERRY)).refused)
    }

    @Test
    fun `trop fatigue ou endormi pour jouer`() {
        assertTrue(on(PetStats(energy = 10f), PlayStarted).refused)
        assertTrue(on(PetStats(energy = 50f, sleeping = true), PlayStarted).refused)
        val ok = on(PetStats(energy = 50f, happiness = 50f), PlayStarted)
        assertFalse(ok.refused)
        assertEquals(56f, ok.stats.happiness, eps)
        assertEquals(46f, ok.stats.energy, eps)
    }

    @Test
    fun `le jouet prefere rapporte deux fois plus`() {
        val s = PetStats(happiness = 50f)
        assertEquals(54f, on(s, PlayHit(Toy.BALL, 2f), Traits(Personality.LAZY, Toy.BALL)).stats.happiness, eps)
        assertEquals(52f, on(s, PlayHit(Toy.BALL, 2f), Traits(Personality.LAZY, Toy.PLUSH)).stats.happiness, eps)
    }

    @Test
    fun `bain, crotte ramassee, soin`() {
        assertEquals(100f, on(PetStats(cleanliness = 5f), Bathed).stats.cleanliness, eps)
        val picked = on(PetStats(poops = 2, cleanliness = 50f), PoopRemoved).stats
        assertEquals(1, picked.poops)
        assertEquals(58f, picked.cleanliness, eps)
        assertEquals(0, on(PetStats(poops = 0), PoopRemoved).stats.poops)
        val healed = on(PetStats(health = 30f, happiness = 50f), Healed).stats
        assertEquals(70f, healed.health, eps)
        assertEquals(45f, healed.happiness, eps)
    }

    @Test
    fun `caresses repetees rapportent moins`() {
        val first = on(PetStats(happiness = 50f), Petted, Traits(Personality.LAZY)).stats
        assertEquals(56f, first.happiness, eps)
        assertEquals(now, first.lastPetAt)
        val again = on(first, Petted, Traits(Personality.LAZY), at = now + 1000).stats
        assertEquals(57.5f, again.happiness, eps)
        val later = on(again, Petted, Traits(Personality.LAZY), at = now + 1000 + 4000).stats
        assertEquals(63.5f, later.happiness, eps)
        assertEquals(50f + 6f * 1.6f, on(PetStats(happiness = 50f), Petted, Traits(Personality.CUDDLY)).stats.happiness, eps)
    }

    @Test
    fun `evenements ponctuels`() {
        val s = PetStats(happiness = 50f, sleeping = true)
        assertEquals(46f, on(s, PetEvent.HardLanding).stats.happiness, eps)
        assertEquals(PetStats(happiness = 47f), on(s, PetEvent.Woken(grumpy = true)).stats)
        assertEquals(PetStats(happiness = 50f), on(s, PetEvent.Woken(grumpy = false)).stats)
        assertTrue(on(PetStats(), PetEvent.FellAsleep).stats.sleeping)
        assertTrue(on(PetStats(), PetEvent.Caged).stats.caged)
        assertFalse(on(PetStats(caged = true), PetEvent.Released).stats.caged)
        assertEquals(65f, on(s, PetEvent.Found).stats.happiness, eps)
        assertEquals(47f, on(s, PetEvent.NotFound).stats.happiness, eps)
        val visit = on(PetStats(happiness = 50f), AppVisited, Traits(Personality.CURIOUS)).stats
        assertEquals(53f, visit.happiness, eps)
        assertEquals(1, visit.visitedApps)
        assertEquals(51f, on(PetStats(happiness = 50f), AppVisited, Traits(Personality.LAZY)).stats.happiness, eps)
    }

    @Test
    fun `humeur`() {
        assertEquals(Mood.SLEEPY, PetStats(sleeping = true).mood)
        assertEquals(Mood.SICK, PetStats(health = 10f).mood)
        assertEquals(Mood.HUNGRY, PetStats(satiety = 5f).mood)
        assertEquals(Mood.SLEEPY, PetStats(energy = 5f).mood)
        assertEquals(Mood.DIRTY, PetStats(poops = 3).mood)
        assertEquals(Mood.DIRTY, PetStats(cleanliness = 20f).mood)
        assertEquals(Mood.SAD, PetStats(happiness = 10f).mood)
        assertEquals(Mood.HAPPY, PetStats(happiness = 90f, satiety = 90f).mood)
        assertEquals(Mood.OK, PetStats(happiness = 60f).mood)
        assertTrue(PetStats(health = 34.9f).isSick)
        assertFalse(PetStats(health = 35f).isSick)
    }

    /**
     * Trace de référence : une suite d'événements tirée au hasard (graine fixe), pour chaque caractère.
     * Les valeurs attendues ont été produites par l'ancien `PetState` (tick/feed/pet/playHit…) avant sa
     * suppression : toute dérive numérique (taux, ordre des calculs, bornes) casse ce test.
     */
    @Test
    fun `trace de reference identique a l'ancien PetState`() {
        assertEquals(GOLDEN, TRACE_PERSONALITIES.joinToString("\n") { trace(it) })
    }

    @Test
    fun `ecran plein de crottes - au plus une d'avance, ramasser ne les fait pas toutes revenir`() {
        val full = PetStats(satiety = 100f, poops = 5)
        val later = on(full, Elapsed(24f)).stats
        assertEquals(5, later.poops)
        assertTrue(later.poopAccumulator <= 1f)
        var cleaned = later
        repeat(5) { cleaned = on(cleaned, PoopRemoved).stats }
        assertEquals(0, cleaned.poops)
        assertTrue(on(cleaned, Elapsed(0.01f)).stats.poops <= 1)
    }

    internal fun trace(p: Personality): String {
        val rnd = Random(p.ordinal + 7)
        val traits = Traits(p, Toy.FRISBEE)
        var s = PetStats()
        var t = now
        var h = 0L
        repeat(400) {
            t += rnd.nextLong(0, 10_000)
            s = on(s, randomEvent(rnd), traits, t).stats
            h = mix(h, s.satiety, s.happiness, s.energy, s.cleanliness, s.health, s.sleeping, s.poops, s.poopAccumulator, s.visitedApps)
        }
        return "$p $h"
    }

    internal fun randomEvent(rnd: Random): PetEvent = when (rnd.nextInt(11)) {
        0, 1, 2 -> Elapsed(rnd.nextFloat() * 6f)
        3 -> Fed(TRACE_FOODS[rnd.nextInt(TRACE_FOODS.size)])
        4 -> Petted
        5 -> PlayStarted
        6 -> PlayHit(TRACE_TOYS[rnd.nextInt(TRACE_TOYS.size)], rnd.nextFloat() * 3f)
        7 -> if (rnd.nextBoolean()) Bathed else Healed
        8 -> PoopRemoved
        9 -> AppVisited
        else -> PetEvent.FellAsleep
    }

    internal companion object {
        /**
         * Les nourritures et jouets de l'ancien `PetState` (avant l'évolution 4a) : la trace en tire un au
         * hasard ; en ajouter aux enums changerait le tirage (donc l'empreinte) sans que les règles aient changé.
         */
        val TRACE_FOODS = Food.entries.take(3)
        val TRACE_TOYS = Toy.entries.take(4)

        /** Les caractères de l'ancien `PetState` (avant l'évolution 4b), ceux de [GOLDEN]. */
        val TRACE_PERSONALITIES = Personality.entries.take(5)

        /** Empreinte de toutes les jauges, cumulée étape par étape (au bit près). */
        fun mix(h: Long, vararg v: Any): Long = v.fold(h) { acc, x ->
            acc * 1_000_003L + when (x) {
                is Float -> x.toBits().toLong()
                is Boolean -> if (x) 1L else 2L
                else -> (x as Int).toLong()
            }
        }

        const val GOLDEN = "PLAYFUL -5512679086476485874\n" +
            "GLUTTON 9188293526019001264\n" +
            "LAZY -1723763332173313826\n" +
            "CURIOUS -2533800362197862954\n" +
            "CUDDLY -1683116559252744347"
    }
}
