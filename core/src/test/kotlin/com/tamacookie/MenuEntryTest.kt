package com.tamacookie

import com.tamacookie.species.rabbit.RabbitLines
import com.tamacookie.feature.menu.MenuCtx
import com.tamacookie.feature.menu.MenuEntries
import com.tamacookie.feature.menu.MenuEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Menu rapide en liste d'entrées (`feature/menu/`) : chaque entrée a une action et un texte. */
class MenuEntryTest {

    /** Faux contexte du menu : la façade de test + les panneaux / jeux pilotés par le toucher. */
    private class FakeMenuCtx(val base: FakePetCtx = FakePetCtx()) : MenuCtx, PetCtx by base {
        var stationsPanels = 0
        var hideSeeks = 0
        val travels = ArrayList<PetId>()
        override fun toggleStationsPanel() { stationsPanels++ }
        override fun startHideSeek(): Boolean { hideSeeks++; return true }
        override fun startTravel(pet: Pet) { travels += pet.id }
    }

    /** Tout ce qu'une entrée peut avoir fait, résumé (pour vérifier qu'elle fait quelque chose). */
    private fun effects(c: FakeMenuCtx) = listOf(
        c.stationsPanels, c.hideSeeks, c.travels.size, c.base.hideRequests, c.base.sleepRequests, c.base.said.size,
    )

    @Test
    fun `chaque entree a un bouton et une action qui fait quelque chose`() {
        assertEquals(listOf("Dodo", "Objets", "Cache-cache", "Ranger"), MenuEntries.ALL.take(4).map { it.label })
        assertEquals(MenuEntries.ALL.size, MenuEntries.ALL.map { it.emoji }.toSet().size)
        for (e in MenuEntries.ALL) {
            val c = FakeMenuCtx()
            c.pet.stats = c.pet.stats.copy(energy = 50f)
            assertTrue(e.text(c.pet.stats).startsWith(e.emoji))
            val before = effects(c)
            e.run(c, c.pet)
            assertTrue("${e.label} ne fait rien", effects(c) != before)
        }
    }

    @Test
    fun `chaque action est la sienne`() {
        fun ran(e: MenuEntry) = FakeMenuCtx().also { it.pet.stats = it.pet.stats.copy(energy = 50f); e.run(it, it.pet) }
        assertEquals(1, ran(MenuEntries.STATIONS).stationsPanels)
        assertEquals(1, ran(MenuEntries.HIDE_SEEK).hideSeeks)
        assertEquals(1, ran(MenuEntries.HIDE).base.hideRequests)
        assertEquals(1, ran(MenuEntries.SLEEP).base.sleepRequests)
        val t = ran(MenuEntries.TRAVEL)
        assertEquals(listOf(t.pet.id), t.travels)
    }

    @Test
    fun `dodo - pas fatigue, il refuse`() {
        val c = FakeMenuCtx()
        c.pet.stats = c.pet.stats.copy(energy = 95f)
        MenuEntries.SLEEP.run(c, c.pet)
        assertEquals(0, c.base.sleepRequests)
        assertTrue(c.base.said.single() in RabbitLines.NOT_TIRED)
    }

    @Test
    fun `dodo - s'il dort, le bouton le reveille`() {
        val c = FakeMenuCtx()
        assertEquals("💤\nDodo", MenuEntries.SLEEP.text(c.pet.stats))
        c.pet.stats = c.pet.stats.copy(sleeping = true)
        c.pet.setAct(Act.SLEEP)
        assertTrue(MenuEntries.SLEEP.text(c.pet.stats).endsWith("Réveil"))
        MenuEntries.SLEEP.run(c, c.pet)
        assertFalse(c.pet.stats.sleeping)
        assertEquals(listOf(c.pet.id), c.base.saved)       // réveil sauvegardé tout de suite
        assertEquals(Act.IDLE, c.pet.act)
        assertEquals(Expr.SLEEPY, c.pet.mind.exprOverride)
        assertEquals(listOf(RabbitLines.GETTING_UP), c.base.said)
    }

    @Test
    fun `seul le dodo change de texte`() {
        assertEquals(listOf(MenuEntries.SLEEP), MenuEntries.ALL.filter { it.dynamic })
        for (e in MenuEntries.ALL.filter { !it.dynamic }) assertEquals("${e.emoji}\n${e.label}", e.text(FakePetCtx().pet.stats))
    }

    // ------------------------------------------------------------------ Évolution 4a

    @Test
    fun `nouvelles entrees a la fin - chanter, brosser`() {
        assertEquals(listOf("Chanter", "Brosser"), MenuEntries.ALL.drop(4).take(2).map { it.label })
    }

    @Test
    fun `voyager - seul sur la derniere rangee, en pleine largeur`() {
        assertEquals(MenuEntries.TRAVEL, MenuEntries.ALL.last())
        assertEquals(listOf(MenuEntries.TRAVEL), MenuEntries.ALL.chunked(MenuEntries.PER_ROW).last())
    }

    @Test
    fun `chanter - des notes et une danse, une berceuse s'il dort`() {
        val c = FakeMenuCtx()
        MenuEntries.SING.run(c, c.pet)
        assertEquals(4, c.base.emitted.count { it == PType.NOTE })
        assertEquals(Act.DANCE, c.pet.act)
        assertEquals(0f, c.pet.mind.danceTime, 0f)
        assertEquals(Expr.HAPPY, c.pet.mind.exprOverride)
        assertEquals(1, c.base.said.size)
        // endormi : il ne se réveille pas
        val s = FakeMenuCtx()
        s.pet.stats = s.pet.stats.copy(sleeping = true)
        s.pet.setAct(Act.SLEEP)
        MenuEntries.SING.run(s, s.pet)
        assertTrue(s.pet.stats.sleeping)
        assertEquals(Act.SLEEP, s.pet.act)
        assertTrue(s.base.said.single().contains("Dodo") || s.base.said.single().contains("rêves"))
        // occupé (une partie) : il chante sans s'arrêter de jouer
        val p = FakeMenuCtx()
        p.pet.setAct(Act.PLAY)
        MenuEntries.SING.run(p, p.pet)
        assertEquals(Act.PLAY, p.pet.act)
    }

    @Test
    fun `brosser - plus propre et content, sauvegarde, pas pendant son sommeil`() {
        val c = FakeMenuCtx()
        c.pet.stats = c.pet.stats.copy(cleanliness = 50f, happiness = 50f)
        MenuEntries.BRUSH.run(c, c.pet)
        val r = GameRules.DEFAULT
        assertEquals(50f + r.brushCleanliness, c.pet.stats.cleanliness, 0.01f)
        assertTrue(c.pet.stats.happiness > 50f)
        assertEquals(listOf(c.pet.id), c.base.saved)
        assertEquals(3, c.base.emitted.count { it == PType.SPARKLE })
        val s = FakeMenuCtx()
        s.pet.stats = s.pet.stats.copy(sleeping = true, cleanliness = 50f)
        MenuEntries.BRUSH.run(s, s.pet)
        assertEquals(50f, s.pet.stats.cleanliness, 0f)
        assertTrue(s.base.said.single().contains("dort"))
    }
}
