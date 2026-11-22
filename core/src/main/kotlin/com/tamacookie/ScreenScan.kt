package com.tamacookie

import java.util.Locale

/**
 * Lecture de l'arbre d'accessibilité, **sans Android** : quels éléments deviennent des plateformes,
 * quelle page du bureau est affichée. L'arbre est lu au travers de [NodeReader] (adaptateur :
 * `AccessibilityNodeInfo` dans `ScreenSensorService` ; faux arbre en test).
 */
object ScreenScan {
    /** Nœuds visités au plus par scan. */
    const val MAX_NODES = 800
    /** Plateformes retenues au plus par scan. */
    const val MAX_PLATFORMS = 60
    /** Longueur maximale d'un nom court. */
    const val MAX_LABEL = 30

    /**
     * Numéro de page extrait d'une "page 2 sur 3" / "page 2 of 3" / "Pagina 2 van 3" /
     * "Strona 2 z 3"… (groupes 1 et 2), ou d'un simple "2 / 3" (groupes 3 et 4).
     * Utiliser [parsePage] plutôt que les groupes directement.
     */
    val PAGE_RE = Regex(
        "(?iu)(?:page|écran|ecran|seite|página|pagina|pagine|strona|stránka|stranka|sida|side|sivu|oldal|" +
            "страница|экран)\\s*(\\d+)\\s*(?:sur|of|/|de|do|von|di|van|z|ze|av|af|из|-)\\s*(\\d+)" +
            "|^\\s*(\\d+)\\s*/\\s*(\\d+)\\s*$"
    )

    /** (page courante, nombre de pages) d'une description d'indicateur de page, sinon null. */
    fun parsePage(desc: CharSequence?): Pair<Int, Int>? {
        if (desc.isNullOrEmpty()) return null
        val m = PAGE_RE.find(desc) ?: return null
        val g = m.groupValues
        val page = (g[1].ifEmpty { g[3] }).toIntOrNull() ?: return null
        val count = (g[2].ifEmpty { g[4] }).toIntOrNull() ?: return null
        return if (page in 1..count && count <= 50) page to count else null
    }

    /**
     * Bande verticale [minTop, maxTop] (px) où l'on cherche des plateformes : proportionnelle
     * à la hauteur pour rester non vide en paysage sur téléphone.
     */
    fun detectionBand(height: Int, density: Float): Pair<Float, Float> {
        val minTop = maxOf(120 * density, 0.22f * height)          // place pour la tête et le saut
        val maxTop = height - maxOf(90 * density, 0.12f * height)  // pas collé au sol
        return minTop to maxTop
    }

    /** Notre propre app et le volet de notifications ne changent pas la "vue". */
    fun ignoredPkg(pkg: String?, ownPkg: String): Boolean = pkg == ownPkg || pkg == "com.android.systemui"

    /** Taille et position acceptables pour une plateforme, sur un écran [width] × [height]. */
    class Geometry(val width: Int, val height: Int, density: Float) {
        val minTop: Float
        val maxTop: Float
        private val minW = (36 * density).toInt()
        private val maxW = (480 * density).toInt()
        private val minH = (36 * density).toInt()
        private val maxH = (260 * density).toInt()

        init {
            val (a, b) = detectionBand(height, density)
            minTop = a; maxTop = b
        }

        fun accepts(b: Box): Boolean {
            val w = b.width.toInt(); val h = b.height.toInt()
            return w in minW..maxW && h in minH..maxH &&
                b.top >= minTop && b.top <= maxTop && b.left >= 0f && b.right <= width
        }
    }

    /** Accès aux nœuds d'un arbre (sans allocation d'enveloppe par nœud). */
    interface NodeReader<N> {
        fun isVisible(n: N): Boolean
        fun isEditable(n: N): Boolean
        fun isPassword(n: N): Boolean
        fun isSelected(n: N): Boolean
        fun isClickable(n: N): Boolean
        fun description(n: N): CharSequence?
        fun text(n: N): CharSequence?
        fun bounds(n: N): Box
        fun childCount(n: N): Int
        fun child(n: N, i: Int): N?
    }

    /**
     * Ce qu'il faut en plus pour trouver le bouton d'une publicité ([findAdSkip]) : identifiant de ressource,
     * parent, et recherches faites **par l'application** (une IPC chacune côté Android). Les faux arbres des tests
     * cherchent par [collect].
     */
    interface NodeFinder<N> {
        /** Identifiant de ressource (« pkg:id/nom » : `viewIdResourceName`), null si inconnu. */
        fun viewId(n: N): String?
        /** Parent de [n] (null = racine, ou inconnu). */
        fun parent(n: N): N?
        /** Nœuds sous [root] dont le texte ou la description **contient** [text], sans tenir compte de la casse. */
        fun findByText(root: N, text: String): List<N>
        /** Nœuds sous [root] d'identifiant [id] (« pkg:id/nom »). */
        fun findByViewId(root: N, id: String): List<N>
    }

    /** Parcours en profondeur (au plus [MAX_NODES] nœuds) : les nœuds qui vérifient [pred]. */
    fun <N> collect(root: N, r: NodeReader<N>, pred: (N) -> Boolean): List<N> {
        val out = ArrayList<N>()
        val stack = ArrayDeque<N>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val n = stack.removeLast()
            visited++
            if (pred(n)) out += n
            for (i in r.childCount(n) - 1 downTo 0) r.child(n, i)?.let { stack.addLast(it) }
        }
        return out
    }

    // =====================================================================
    //  Chasseur de pubs : le bouton « Ignorer l'annonce » (liste blanche stricte)
    // =====================================================================
    /** Fin de l'identifiant du bouton « Ignorer » des pubs YouTube (`com.google.android.youtube:id/skip_ad_button`). */
    const val AD_SKIP_ID_SUFFIX = ":id/skip_ad_button"

    /**
     * Libellés **exacts** (casse, espaces et apostrophe typographique mis à part) d'un bouton qui passe une
     * publicité. Rien d'autre : ni « Fermer », ni « Ignorer » seul, ni « En savoir plus ».
     */
    val AD_SKIP_LABELS: Set<String> =
        setOf("ignorer l'annonce", "passer l'annonce", "skip ad", "ignorer les annonces")

    /** Ce que l'on demande à l'app de chercher (contenu, sans casse) : chaque libellé de [AD_SKIP_LABELS] en a un. */
    private val AD_SKIP_SEARCH = listOf("Ignorer l", "Passer l", "Skip ad")

    /** [s] est exactement un libellé de [AD_SKIP_LABELS]. */
    fun isAdSkipLabel(s: CharSequence?): Boolean {
        if (s.isNullOrBlank() || s.length > MAX_LABEL) return false
        val norm = s.toString().trim().replace(APOSTROPHE, '\'').replace(WHITESPACE, " ").lowercase(Locale.ROOT)
        return norm in AD_SKIP_LABELS
    }

    private val WHITESPACE = Regex("\\s+")
    /** Apostrophe typographique (U+2019), ramenée à l'apostrophe simple. */
    private const val APOSTROPHE = '\u2019'

    /**
     * [n] est un bouton « passer la publicité » : visible, **cliquable**, non éditable, de taille non nulle, et
     * soit d'identifiant `…:id/skip_ad_button`, soit de texte / description exactement dans [AD_SKIP_LABELS]
     * (ou ceux de son **unique** enfant, non cliquable).
     */
    fun <N> isAdSkip(n: N, r: NodeReader<N>, f: NodeFinder<N>): Boolean {
        if (!r.isVisible(n) || !r.isClickable(n)) return false
        if (r.isEditable(n) || r.isPassword(n) || r.bounds(n).isEmpty) return false
        return f.viewId(n)?.endsWith(AD_SKIP_ID_SUFFIX) == true || labelled(n, r) || labelledChild(n, r)
    }

    private fun <N> labelled(n: N, r: NodeReader<N>) = isAdSkipLabel(r.description(n)) || isAdSkipLabel(r.text(n))

    /** Son unique enfant, non cliquable, porte le libellé (le bouton YouTube : cadre cliquable → conteneur décrit). */
    private fun <N> labelledChild(n: N, r: NodeReader<N>): Boolean {
        val c = (if (r.childCount(n) == 1) r.child(n, 0) else null) ?: return false
        return !r.isClickable(c) && labelled(c, r)
    }

    /**
     * Le bouton « passer la publicité » de l'arbre [root] (application [pkg]), null s'il n'y en a pas ; avec
     * [near], seulement un bouton dont les bornes recoupent ce rectangle (au moment d'appuyer : c'est
     * **encore** le bouton visé). Quelques recherches ciblées (identifiant, puis libellés — faites seulement si
     * besoin), pas un parcours : rien ne dépend des plateformes ni de leurs limites.
     */
    fun <N> findAdSkip(root: N, r: NodeReader<N>, f: NodeFinder<N>, pkg: String?, near: Box? = null): N? {
        val byId = if (pkg != null) f.findByViewId(root, pkg + AD_SKIP_ID_SUFFIX).asSequence() else emptySequence()
        val byText = AD_SKIP_SEARCH.asSequence().flatMap { q ->
            f.findByText(root, q).asSequence().flatMap { n -> sequence { yield(n); f.parent(n)?.let { yield(it) } } }
        }
        return (byId + byText).firstOrNull { isAdSkip(it, r, f) && (near == null || r.bounds(it).intersects(near)) }
    }

    /**
     * Applications où l'on peut **toucher pour de vrai** (geste simulé) le bouton de pub quand l'application refuse le
     * clic d'accessibilité. Ailleurs, jamais : une application pourrait exposer un faux bouton « Skip ad » refusant le
     * clic, aux bornes posées sur une autre fenêtre (« Autoriser »…), et nous faire appuyer pour elle.
     */
    val TAP_PKGS = setOf("com.google.android.youtube")

    /**
     * Où toucher le bouton [button] de l'application [pkg] dont la fenêtre active occupe [window] : le centre du
     * bouton **rogné à cette fenêtre**, ou null (application non autorisée, bouton hors de sa fenêtre).
     */
    fun tapPoint(pkg: String?, button: Box?, window: Box?): Pair<Float, Float>? {
        if (pkg !in TAP_PKGS || button == null || window == null) return null
        val clip = Box(
            maxOf(button.left, window.left), maxOf(button.top, window.top),
            minOf(button.right, window.right), minOf(button.bottom, window.bottom),
        )
        return if (clip.isEmpty) null else clip.centerX to clip.centerY
    }

    /** Résultat du parcours : plateformes et (page, nombre de pages) du bureau si lue. */
    data class Walk(val platforms: List<Platform>, val page: Pair<Int, Int>?)

    /**
     * Parcours en profondeur (au plus [MAX_NODES] nœuds) : un élément de taille / position acceptable,
     * cliquable ou nommé, devient une plateforme (on ne descend pas dans ses enfants ; pas deux
     * plateformes qui se chevauchent au même niveau). L'indicateur de page : le point « sélectionné »
     * est prioritaire ; sinon (Pixel Launcher, [mayBeHome]) le premier indicateur lisible.
     */
    fun <N> walk(root: N, r: NodeReader<N>, g: Geometry, mayBeHome: Boolean): Walk {
        var page: Pair<Int, Int>? = null       // depuis le point "sélectionné" (prioritaire)
        var pageAny: Pair<Int, Int>? = null    // depuis un indicateur non sélectionné (Pixel)
        val out = ArrayList<Platform>()
        val stack = ArrayDeque<N>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val n = stack.removeLast()
            visited++
            if (!r.isVisible(n)) continue
            val hidden = r.isEditable(n) || r.isPassword(n)
            if (page == null && !hidden) {
                val cd = r.description(n)
                if (cd != null) {
                    val selected = r.isSelected(n)
                    if (selected || (pageAny == null && mayBeHome)) {
                        parsePage(cd)?.let { if (selected) page = it else pageAny = it }
                    }
                }
            }
            val b = r.bounds(n)
            if (g.accepts(b)) {
                // Nom court seulement pour les éléments non éditables (jamais le texte saisi)
                val label = if (!hidden) (r.description(n) ?: r.text(n))?.toString()?.take(MAX_LABEL) else null
                if (r.isClickable(n) || !label.isNullOrBlank()) {
                    // élément retenu : on ne descend pas dans ses enfants
                    if (out.size < MAX_PLATFORMS && out.none { it.box.intersects(b) && it.box.top == b.top }) {
                        out.add(Platform(b, label))
                    }
                    continue
                }
            }
            for (i in 0 until r.childCount(n)) r.child(n, i)?.let { stack.addLast(it) }
        }
        return Walk(out, page ?: pageAny)
    }
}
