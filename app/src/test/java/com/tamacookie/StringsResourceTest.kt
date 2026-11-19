package com.tamacookie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Étape 8c : `res/values/strings.xml` lu comme un fichier XML (pas de ressources Android dans les tests JVM).
 * Le compilateur garantit déjà que chaque `R.string.x` existe ; ici : le personnage « lapin » n'apparaît que dans
 * les textes listés (le personnage de l'app, voir docs/textes-et-accessibilite.md « Ce qui reste lapin »), et les
 * formats sont prêts à traduire (arguments numérotés, pluriels complets pour le français).
 */
class StringsResourceTest {

    /** Textes qui désignent le personnage de l'app : l'adoption ne crée que des lapins (pas de choix d'espèce). */
    private val speciesWordsAllowed = setOf(
        "main_tagline", "main_name_hint", "main_a11y_dialog_message", "main_reset", "main_reset_title",
        "main_reset_message", "adopt_title_new", "adopt_name_hint", "notif_channel_pet_desc",
        "a11y_description",
    )

    private val forbidden = Regex("lapin|carott|🐰", RegexOption.IGNORE_CASE)

    private fun strings(): List<Element> {
        val f = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml"))
            .first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = ArrayList<Element>()
        for (tag in listOf("string", "plurals", "string-array")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) out += nodes.item(i) as Element
        }
        return out
    }

    @Test
    fun `lapin seulement dans les textes du personnage de l'app`() {
        val found = strings().filter { forbidden.containsMatchIn(it.textContent) }
            .map { it.getAttribute("name") }.toSet()
        assertEquals(speciesWordsAllowed, found)
    }

    @Test
    fun `plusieurs arguments sont numerotes`() {
        val unnumbered = Regex("%[sd]")
        // Une <string>, ou chaque <item> d'un <plurals>
        val texts = strings().filter { it.tagName == "string" }.map { it.getAttribute("name") to it.textContent } +
            strings().filter { it.tagName == "plurals" }.flatMap { p ->
                val items = p.getElementsByTagName("item")
                (0 until items.length).map { p.getAttribute("name") to items.item(it).textContent }
            }
        for ((name, text) in texts) {
            val args = Regex("%(\\d+\\$)?[sd]").findAll(text).count()
            if (args > 1) assertTrue(name, !unnumbered.containsMatchIn(text))
        }
    }

    @Test
    fun `les pluriels ont les formes du francais`() {
        val plurals = strings().filter { it.tagName == "plurals" }
        assertTrue(plurals.isNotEmpty())
        for (p in plurals) {
            val items = p.getElementsByTagName("item")
            val q = (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }.toSet()
            assertEquals(p.getAttribute("name"), setOf("one", "many", "other"), q)
        }
    }
}
