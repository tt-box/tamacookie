package com.tamacookie

import com.tamacookie.feature.station.StationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registre des fenêtres ([WindowTable]) : idempotence, regroupement des déplacements, ids par entité. */
class OverlayHostTest {
    private val host = FakeOverlayHost()
    private val be get() = host.backend
    private val petA = WindowId.Pet(PetId("a"))
    private val petB = WindowId.Pet(PetId("b"))

    @Test fun `les ids sont types et propres a chaque entite`() {
        assertEquals(WindowId.Prop(3), WindowId.Prop(3))
        assertTrue(WindowId.Prop(3) != WindowId.Prop(4))
        assertTrue(petA != petB)
        assertTrue(WindowId.Bubble(PetId("a")) != WindowId.Bubble(PetId("b")))
        assertTrue(WindowId.Station(StationKind.BATH) != WindowId.Station(StationKind.CAGE))
        host.attach(petA, 0, 0); host.attach(petB, 10, 0)
        assertTrue(host.isAttached(petA) && host.isAttached(petB))
        host.detach(petA)
        assertFalse(host.isAttached(petA)); assertTrue(host.isAttached(petB))
    }

    @Test fun `attacher deux fois ne rajoute pas la fenetre`() {
        host.attach(petA, 5, 6)
        host.attach(petA, 5, 6)
        host.flush()
        assertEquals(1, be.count("add"))
        assertEquals(0, be.count("move"))
        host.attach(petA, 9, 6)          // déjà là : simple déplacement
        host.flush()
        assertEquals(1, be.count("add"))
        assertEquals(WinOp("move", petA, 9, 6), be.ops.last())
    }

    @Test fun `detacher une fenetre absente ne fait rien`() {
        host.detach(WindowId.Dock)
        host.release(WindowId.Prop(1))
        host.attach(petA, 0, 0)
        host.detach(petA)
        host.detach(petA)
        assertEquals(1, be.count("remove"))
        host.move(WindowId.Menu, 1, 2)
        host.redraw(WindowId.Menu)
        host.flush()
        assertEquals(listOf("add", "remove"), be.ops.map { it.op })
    }

    @Test fun `au plus un updateViewLayout par fenetre et par image`() {
        host.attach(petA, 0, 0); host.attach(WindowId.Prop(1), 0, 0)
        for (i in 1..5) { host.move(petA, i, i); host.move(WindowId.Prop(1), 100 + i, 0) }
        assertEquals(0, be.count("move"))       // rien avant la fin de l'image
        host.flush()
        assertEquals(2, be.count("move"))
        assertEquals(5 to 5, host.shownAt(petA))
        assertEquals(105 to 0, host.shownAt(WindowId.Prop(1)))
        host.flush()
        assertEquals(2, be.count("move"))
        assertEquals(2L, host.moves)
    }

    @Test fun `une position inchangee n'envoie rien`() {
        host.attach(petA, 3, 4)
        host.move(petA, 3, 4)
        host.flush()
        host.move(petA, 8, 8); host.move(petA, 3, 4)      // aller-retour dans la même image
        host.flush()
        assertEquals(0, be.count("move"))
    }

    @Test fun `une fenetre non affichee garde la position sans appel`() {
        host.attach(petA, 0, 0)
        host.detach(petA)
        host.move(petA, 7, 7)
        host.flush()
        assertEquals(0, be.count("move"))
        assertFalse(host.isAttached(petA))
    }

    @Test fun `retirer une fenetre annule son deplacement en attente`() {
        host.attach(petA, 0, 0)
        host.move(petA, 50, 50)
        host.detach(petA)
        host.flush()
        assertEquals(0, be.count("move"))
        host.attach(petA, 1, 1)
        assertEquals(WinOp("add", petA, 1, 1), be.ops.last())
    }

    @Test fun `detachAll retire tout, et chaque fenetre peut revenir`() {
        host.attach(petA, 0, 0); host.attach(WindowId.Dock, 0, 0); host.attach(WindowId.Station(StationKind.VET), 0, 0)
        host.move(WindowId.Dock, 9, 9)
        val before = be.count("remove")          // le dock est repassé au-dessus de la station (WindowLayer)
        host.detachAll()
        host.flush()
        assertEquals(before + 3, be.count("remove"))
        assertEquals(0, be.count("move"))
        assertTrue(be.shown.isEmpty())
        host.detachAll()
        assertEquals(before + 3, be.count("remove"))
        host.attach(WindowId.Dock, 2, 2)
        assertTrue(host.isAttached(WindowId.Dock))
    }

    @Test fun `release oublie la fenetre de l'entite`() {
        var made = 0
        host.factory = { made++; it }
        host.attach(WindowId.Prop(1), 0, 0)
        host.detach(WindowId.Prop(1))
        host.attach(WindowId.Prop(1), 0, 0)
        assertEquals(1, made)                    // vue gardée tant que l'entité existe
        host.release(WindowId.Prop(1))
        assertNull(host.window(WindowId.Prop(1)))
        host.attach(WindowId.Prop(1), 0, 0)
        assertEquals(2, made)
    }

    @Test fun `sans fabrique pour l'entite, rien n'est affiche`() {
        host.factory = { null }
        host.attach(WindowId.Prop(9), 0, 0)
        assertFalse(host.isAttached(WindowId.Prop(9)))
        assertTrue(be.ops.isEmpty())
    }

    @Test fun `une autre fenetre sous le meme id remplace l'ancienne`() {
        val be2 = RecordingBackend()
        val table = WindowTable(be2)
        table.attach(WindowId.Menu, WindowId.Panel("ancien"), 0, 0)
        table.attach(WindowId.Menu, WindowId.Panel("nouveau"), 0, 0)
        assertEquals(listOf("add", "remove", "add"), be2.ops.map { it.op })
        assertEquals(WindowId.Panel("nouveau"), be2.ops.last().id)
        assertTrue(table.isAttached(WindowId.Menu))
    }

    @Test fun `relayout applique tout de suite`() {
        host.attach(WindowId.Panel("vet"), 0, 0)
        host.move(WindowId.Panel("vet"), 5, 5)
        host.relayout(WindowId.Panel("vet"), 20, 30)
        host.flush()
        assertEquals(1, be.count("move"))
        assertEquals(20 to 30, host.shownAt(WindowId.Panel("vet")))
    }

    @Test fun `fenetre ancree a une vue`() {
        val id = WindowId.Station(StationKind.CHEST)
        assertNull(host.placeAnchored(id, 100f, 50, Float.NaN))
        assertFalse(host.isAttached(id))
        assertEquals(100, host.placeAnchored(id, 100f, 50, 0f, fadeIn = true))
        assertEquals(WinOp("add+fade", id, 100, 50), be.ops.last())
        assertEquals(-200, host.placeAnchored(id, 100f, 50, -300f))   // la page défile
        host.flush()
        assertEquals(-200 to 50, host.shownAt(id))
        assertNull(host.placeAnchored(id, 100f, 50, Float.NaN))      // page quittée
        assertFalse(host.isAttached(id))
    }

    @Test fun `le premier deplacement en attente previent l'adaptateur`() {
        var posts = 0
        val table = object : WindowTable<WindowId>(RecordingBackend()) {
            override fun onPending() { posts++ }
        }
        table.factory = { it }
        table.attach(petA, 0, 0); table.attach(petB, 0, 0)
        table.move(petA, 1, 1); table.move(petB, 1, 1); table.move(petA, 2, 2)
        assertEquals(1, posts)
        table.flush()
        table.move(petA, 3, 3)
        assertEquals(2, posts)
    }

    @Test
    fun `ajout refuse (permission retiree) - signale, fenetre non affichee, pas d'exception`() {
        val refusing = object : WindowBackend<WindowId> by RecordingBackend() {
            override fun add(w: WindowId, x: Int, y: Int, fadeIn: Boolean) = throw IllegalStateException("BadToken")
        }
        val t = WindowTable(refusing)
        val failed = ArrayList<WindowId>()
        t.onAddFailed = { id, _ -> failed += id }
        t.attach(petA, petA, 10, 20)
        assertEquals(listOf(petA), failed)
        assertFalse(t.isAttached(petA))
        // Sans gestionnaire : l'exception remonte (comportement d'avant)
        val bare = WindowTable(refusing)
        var thrown = false
        try { bare.attach(petA, petA, 10, 20) } catch (_: IllegalStateException) { thrown = true }
        assertTrue(thrown)
    }
}
