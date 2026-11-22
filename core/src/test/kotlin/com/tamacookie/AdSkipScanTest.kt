package com.tamacookie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chasseur de pubs, détection : le bouton « Ignorer l'annonce » d'une publicité ([ScreenScan.findAdSkip]) sur
 * un faux arbre reproduisant le dump d'une pub YouTube relevé sur la tablette — et les pièges voisins, qui ne
 * doivent **jamais** être retenus. Puis le hub ([ScreenSensorHub]) : publication et appui gardé par le réglage.
 */
class AdSkipScanTest {

    class Node(
        val box: Box = Box(0f, 0f, 1080f, 2000f),
        val id: String? = null,
        val desc: String? = null,
        val text: String? = null,
        val clickable: Boolean = false,
        val visible: Boolean = true,
        val editable: Boolean = false,
        val children: List<Node> = emptyList(),
    ) {
        var parent: Node? = null
        init { for (c in children) c.parent = this }
    }

    object Reader : ScreenScan.NodeReader<Node>, ScreenScan.NodeFinder<Node> {
        override fun isVisible(n: Node) = n.visible
        override fun isEditable(n: Node) = n.editable
        override fun isPassword(n: Node) = false
        override fun isSelected(n: Node) = false
        override fun isClickable(n: Node) = n.clickable
        override fun description(n: Node) = n.desc
        override fun text(n: Node) = n.text
        override fun bounds(n: Node) = n.box
        override fun childCount(n: Node) = n.children.size
        override fun child(n: Node, i: Int) = n.children[i]
        override fun viewId(n: Node) = n.id
        override fun parent(n: Node) = n.parent
        override fun findByText(root: Node, text: String) = ScreenScan.collect(root, this) { n ->
            listOf(n.desc, n.text).any { it?.contains(text, ignoreCase = true) == true }
        }
        override fun findByViewId(root: Node, id: String) = ScreenScan.collect(root, this) { it.id == id }
    }

    private val yt = "com.google.android.youtube"
    private val skipBox = Box(1600f, 900f, 1832f, 996f)

    /** Le bouton tel que dans le dump : FrameLayout cliquable sans texte → conteneur décrit → texte « Ignorer ». */
    private fun skipButton(box: Box = skipBox, clickable: Boolean = true) = Node(
        box, id = "$yt:id/skip_ad_button", clickable = clickable,
        children = listOf(
            Node(box, id = "$yt:id/skip_ad_button_container", desc = "Ignorer l'annonce",
                children = listOf(Node(box, id = "$yt:id/skip_ad_button_text", text = "Ignorer"))),
        ),
    )

    /** Les pièges voisins (à ne jamais toucher). */
    private fun traps() = listOf(
        Node(Box(40f, 900f, 600f, 990f), id = "$yt:id/player_learn_more_button", clickable = true,
            text = "Consulter le site de l'annonceur"),
        Node(Box(1900f, 300f, 2200f, 380f), clickable = true, text = "Télécharger"),
        Node(Box(1900f, 400f, 2200f, 480f), clickable = true, text = "En savoir plus"),
        Node(Box(2200f, 100f, 2280f, 180f), clickable = true, desc = "Fermer le panneau des annonces"),
        Node(Box(1900f, 500f, 2200f, 580f), clickable = true, text = "Fermer"),
    )

    private fun player(vararg extra: Node) =
        Node(Box(0f, 0f, 1880f, 1060f), id = "$yt:id/watch_player", clickable = true, children = traps() + extra)

    private fun root(vararg children: Node) = Node(Box(0f, 0f, 2560f, 1600f), children = children.toList())

    // --- Détection ---

    @Test fun `pub YouTube - le bouton Ignorer l'annonce est trouve, meme dans un lecteur cliquable`() {
        val skip = skipButton()
        val r = root(player(skip))
        assertSame(skip, ScreenScan.findAdSkip(r, Reader, Reader, yt))
        assertEquals(skipBox, Reader.bounds(ScreenScan.findAdSkip(r, Reader, Reader, yt)!!))
    }

    @Test fun `pub YouTube - sans le bouton, aucun piege n'est retenu`() {
        assertNull(ScreenScan.findAdSkip(root(player()), Reader, Reader, yt))
        for (t in traps()) {
            assertFalse(t.text ?: t.desc, ScreenScan.isAdSkip(t, Reader, Reader))
            assertNull(ScreenScan.findAdSkip(root(t), Reader, Reader, yt))
        }
    }

    @Test fun `identifiant seul - retenu sur YouTube, pas un autre identifiant`() {
        val bare = Node(skipBox, id = "$yt:id/skip_ad_button", clickable = true)
        assertSame(bare, ScreenScan.findAdSkip(root(bare), Reader, Reader, yt))
        val other = Node(skipBox, id = "$yt:id/skip_ad_button_container", clickable = true)
        assertNull(ScreenScan.findAdSkip(root(other), Reader, Reader, yt))
    }

    @Test fun `Ignorer seul hors YouTube - jamais`() {
        val ignore = Node(skipBox, clickable = true, text = "Ignorer")
        assertNull(ScreenScan.findAdSkip(root(ignore), Reader, Reader, "com.example.news"))
        assertNull(ScreenScan.findAdSkip(root(ignore), Reader, Reader, yt))   // sans son identifiant non plus
        val wrapped = Node(skipBox, clickable = true, children = listOf(Node(skipBox, text = "Ignorer")))
        assertNull(ScreenScan.findAdSkip(root(wrapped), Reader, Reader, "com.example.news"))
    }

    @Test fun `noeud non cliquable, invisible ou vide - jamais`() {
        assertNull(ScreenScan.findAdSkip(root(skipButton(clickable = false)), Reader, Reader, yt))
        val text = Node(skipBox, desc = "Ignorer l'annonce")
        val plain = Node(Box(0f, 0f, 500f, 500f), children = listOf(text))
        assertNull(ScreenScan.findAdSkip(root(plain), Reader, Reader, yt))
        val hidden = Node(skipBox, clickable = true, visible = false, text = "Skip ad")
        assertNull(ScreenScan.findAdSkip(root(hidden), Reader, Reader, "com.example"))
        val empty = Node(Box(10f, 10f, 10f, 10f), clickable = true, text = "Skip ad")
        assertNull(ScreenScan.findAdSkip(root(empty), Reader, Reader, "com.example"))
        val field = Node(skipBox, clickable = true, editable = true, text = "Skip ad")
        assertNull(ScreenScan.findAdSkip(root(field), Reader, Reader, "com.example"))
    }

    @Test fun `libelles exacts dans une autre app - le bouton ou le parent cliquable de son unique enfant`() {
        val skipAd = Node(skipBox, clickable = true, text = "Skip Ad")
        assertSame(skipAd, ScreenScan.findAdSkip(root(skipAd), Reader, Reader, "com.android.chrome"))
        val wrapped = Node(skipBox, clickable = true,
            children = listOf(Node(skipBox, text = "Passer l’annonce")))
        assertSame(wrapped, ScreenScan.findAdSkip(root(wrapped), Reader, Reader, "com.example"))
        // deux enfants : ce n'est plus « le » bouton de l'annonce
        val two = Node(skipBox, clickable = true,
            children = listOf(Node(skipBox, text = "Passer l'annonce"), Node(skipBox, text = "Acheter")))
        assertNull(ScreenScan.findAdSkip(root(two), Reader, Reader, "com.example"))
    }

    @Test fun `liste blanche des libelles`() {
        val yes = listOf("Ignorer l'annonce", "Passer l'annonce", "Skip ad", "Skip Ad", "SKIP AD",
            "Ignorer les annonces", "  Ignorer  l’annonce ")
        for (s in yes) assertTrue(s, ScreenScan.isAdSkipLabel(s))
        val no = listOf(null, "", "Ignorer", "Fermer", "Passer", "Skip", "Skip ads", "Skip ad 5", "Ignorer l'annonce ?",
            "Consulter le site de l'annonceur", "En savoir plus", "Télécharger", "Fermer le panneau des annonces",
            "Ignorer l'annonce dans 5 secondes")
        for (s in no) assertFalse("$s", ScreenScan.isAdSkipLabel(s))
    }

    @Test fun `au moment d'appuyer - seulement un bouton qui recoupe encore la cible`() {
        val skip = skipButton()
        val r = root(player(skip))
        assertSame(skip, ScreenScan.findAdSkip(r, Reader, Reader, yt, near = Box(1700f, 950f, 1710f, 960f)))
        assertNull(ScreenScan.findAdSkip(r, Reader, Reader, yt, near = Box(100f, 100f, 300f, 200f)))
    }

    // --- Le hub : publication et appui ---

    private fun scan(adSkip: Box?) = ScanResult(ime = null, pkg = yt, adSkip = adSkip)

    @Test fun `hub - publie le bouton seulement si le chasseur est voulu`() {
        val hub = ScreenSensorHub()
        hub.onScan(scan(skipBox), hub.epoch, 0L)
        assertNull(hub.snapshot.adSkip)
        hub.onAdProbe(skipBox)
        assertNull(hub.snapshot.adSkip)
        hub.adSkipWanted = true
        hub.onScan(scan(skipBox), hub.epoch, 0L)
        assertEquals(skipBox, hub.snapshot.adSkip)
        hub.onAdProbe(null)
        assertNull(hub.snapshot.adSkip)
        hub.onAdProbe(skipBox)
        assertEquals(skipBox, hub.snapshot.adSkip)
        // un défilement ne l'efface pas (le scan suivant dira s'il est toujours là)
        hub.onViewScrolled(yt, 0, 10, 0, 0L)
        assertEquals(skipBox, hub.snapshot.adSkip)
        // réglage coupé : oublié tout de suite
        hub.adSkipWanted = false
        assertNull(hub.snapshot.adSkip)
    }

    @Test fun `hub - ecran eteint, deconnexion ou autre fenetre - plus de bouton`() {
        val hub = ScreenSensorHub().apply { adSkipWanted = true }
        hub.onAdProbe(skipBox)
        hub.onScreenOff()
        assertNull(hub.snapshot.adSkip)
        hub.onAdProbe(skipBox)                    // écran éteint : ignoré
        assertNull(hub.snapshot.adSkip)
        hub.onScreenOn()
        hub.onAdProbe(skipBox)
        hub.onScan(ScanResult(ime = null, pkg = null), hub.epoch, 0L)
        assertNull(hub.snapshot.adSkip)
        hub.onAdProbe(skipBox)
        hub.onDisconnected()
        assertNull(hub.snapshot.adSkip)
    }

    @Test fun `relais - l'appui n'est transmis qu'avec une cible qui recoupe le bouton connu`() {
        val hub = ScreenSensorHub()
        val relay = AdSkipRelay(hub)
        val pressed = ArrayList<Box>()
        relay.presser = { pressed += it; true }
        assertFalse(relay.pressAdSkip(skipBox))     // chasseur non voulu
        hub.adSkipWanted = true
        assertFalse(relay.pressAdSkip(skipBox))     // aucun bouton connu
        hub.onAdProbe(skipBox)
        assertFalse(relay.pressAdSkip(Box(0f, 0f, 100f, 100f)))   // ailleurs
        assertTrue(pressed.isEmpty())
        assertTrue(relay.pressAdSkip(skipBox))
        assertEquals(listOf(skipBox), pressed)
        relay.presser = null                        // service d'accessibilité parti
        assertFalse(relay.pressAdSkip(skipBox))
        assertEquals(1, pressed.size)
    }

    @Test
    fun `toucher simule - YouTube seulement, rogne a la fenetre active`() {
        val yt = "com.google.android.youtube"
        val window = Box(0f, 0f, 1000f, 600f)
        // Bouton dans sa fenêtre : son centre
        assertEquals(950f to 500f, ScreenScan.tapPoint(yt, Box(900f, 450f, 1000f, 550f), window))
        // Une autre application qui refuse le clic : jamais de vrai toucher
        assertNull(ScreenScan.tapPoint("com.malveillante", Box(900f, 450f, 1000f, 550f), window))
        assertNull(ScreenScan.tapPoint(null, Box(900f, 450f, 1000f, 550f), window))
        // Bornes débordant de la fenêtre (vers une autre app en écran partagé) : rognées à la fenêtre
        assertEquals(950f to 550f, ScreenScan.tapPoint(yt, Box(900f, 500f, 1000f, 1400f), window))
        // Entièrement hors de sa fenêtre : rien
        assertNull(ScreenScan.tapPoint(yt, Box(900f, 700f, 1000f, 800f), window))
        assertNull(ScreenScan.tapPoint(yt, null, window))
        assertNull(ScreenScan.tapPoint(yt, Box(900f, 450f, 1000f, 550f), null))
    }
}
