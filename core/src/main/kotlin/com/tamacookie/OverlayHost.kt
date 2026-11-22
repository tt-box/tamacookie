package com.tamacookie

import com.tamacookie.feature.station.StationKind
import kotlin.math.roundToInt

/**
 * Couche d'une fenêtre, de la plus basse à la plus haute. Toutes les fenêtres overlay ont le même type
 * (`TYPE_APPLICATION_OVERLAY`) : le WindowManager les empile dans l'ordre d'ajout, et [WindowTable] maintient
 * cet ordre de couches quelle que soit la séquence des ajouts.
 *
 * L'animal est **sous** les stations (assis dans la baignoire, derrière les barreaux de la cage, sur la table
 * du véto : leur rebord le cache en partie), les objets (jouets, nourriture, crottes) passent devant ;
 * l'interface (bouton 🏠, bulles, panneaux, menu) est toujours au-dessus du décor.
 */
enum class WindowLayer { PET, STATION, PROP, DOCK, BUBBLE, PANEL, MENU }

/**
 * Identifiant d'une fenêtre overlay, typé et rangé par entité : deux animaux ont chacun leur fenêtre
 * et leur bulle, chaque objet posé et chaque station la sienne. Sert de clé à [OverlayHost].
 * [layer] : sa couche (ordre d'empilement).
 */
sealed interface WindowId {
    val layer: WindowLayer

    /** La fenêtre d'un animal. */
    data class Pet(val pet: PetId) : WindowId { override val layer get() = WindowLayer.PET }
    /** La bulle de dialogue d'un animal. */
    data class Bubble(val pet: PetId) : WindowId { override val layer get() = WindowLayer.BUBBLE }
    data class Station(val kind: StationKind) : WindowId { override val layer get() = WindowLayer.STATION }
    /** Un objet posé ([com.tamacookie.Prop.id]). */
    data class Prop(val id: Int) : WindowId { override val layer get() = WindowLayer.PROP }
    /** Le bouton 🏠. */
    data object Dock : WindowId { override val layer get() = WindowLayer.DOCK }
    /** Le menu de soins. */
    data object Menu : WindowId { override val layer get() = WindowLayer.MENU }
    /** Un panneau flottant (véto, coffre, 🏠…). */
    data class Panel(val name: String) : WindowId { override val layer get() = WindowLayer.PANEL }
}

/**
 * Port : la seule autorité sur les fenêtres overlay (implémentée par `OverlayManager`, faux hôte en test).
 *
 * Idempotent : attacher une fenêtre déjà affichée ne fait que la déplacer ; détacher une fenêtre absente
 * ne fait rien. Les déplacements sont **regroupés** : au plus une mise à jour par fenêtre à chaque [flush]
 * (fin d'image), et aucune si la position n'a pas changé.
 */
interface OverlayHost {
    fun isAttached(id: WindowId): Boolean

    /**
     * Affiche la fenêtre d'une entité (objet, station) en ([x], [y]) ; sa vue est fabriquée par l'adaptateur
     * d'après l'entité du monde. [fadeIn] : apparition en fondu. Déjà affichée : simple [move].
     */
    fun attach(id: WindowId, x: Int, y: Int, fadeIn: Boolean = false)

    /** Déplace la fenêtre (appliqué au prochain [flush]) ; une fenêtre non affichée garde seulement la position. */
    fun move(id: WindowId, x: Int, y: Int)

    /** L'état affiché par la fenêtre a changé : à redessiner. */
    fun redraw(id: WindowId)

    /** Retire la fenêtre (sa vue est gardée pour un prochain [attach]). */
    fun detach(id: WindowId)

    /** Retire la fenêtre et oublie sa vue (l'entité a disparu). */
    fun release(id: WindowId)

    /** Retire toutes les fenêtres (« Cacher », arrêt du service). */
    fun detachAll()

    /** Applique les déplacements en attente. */
    fun flush()
}

/**
 * Fenêtre ancrée à une vue (app / page du bureau) : affichée, déplacée ou retirée selon [off]
 * ([PageTracker.visibleOffset] ; NaN = vue pas affichée). [left] = bord gauche sur sa vue.
 * Renvoie le x à l'écran, ou null si la fenêtre a été retirée.
 */
fun OverlayHost.placeAnchored(id: WindowId, left: Float, y: Int, off: Float, fadeIn: Boolean = false): Int? {
    if (off.isNaN()) { detach(id); return null }
    val x = (left + off).roundToInt()
    if (isAttached(id)) move(id, x, y) else attach(id, x, y, fadeIn)
    return x
}

/** Ce que l'adaptateur sait faire d'une fenêtre (WindowManager côté Android, enregistreur en test). */
interface WindowBackend<W> {
    /** Ajoute la fenêtre **au-dessus** de toutes les autres (ordre d'ajout du WindowManager). */
    fun add(w: W, x: Int, y: Int, fadeIn: Boolean)
    fun move(w: W, x: Int, y: Int)
    fun remove(w: W)
    fun redraw(w: W)
    /** Largeur / hauteur de la fenêtre en px ; ≤ 0 = inconnue (elle est supposée chevaucher toutes les autres). */
    fun width(w: W): Int = -1
    fun height(w: W): Int = -1
}

/**
 * Registre des fenêtres (Kotlin pur) : qui est affiché, où, regroupement des déplacements, et **ordre des
 * couches** ([WindowId.layer]). Le WindowManager empile dans l'ordre d'ajout : une fenêtre ajoutée alors que des
 * fenêtres de couches supérieures qui la chevauchent sont affichées les fait repasser au-dessus (retirées puis
 * rajoutées, dans leur ordre). Ex. : une station qui réapparaît (changement de page) ne recouvre plus le
 * panneau 🏠 ; le lapin qui revient reste sous sa cage.
 * [factory] fabrique la fenêtre d'une entité au premier [attach] ; les autres sont fournies par [attach] (avec `w`).
 */
open class WindowTable<W : Any>(private val backend: WindowBackend<W>) : OverlayHost {
    private class Entry<W>(var w: W) {
        var attached = false
        /** Position appliquée. */
        var x = 0
        var y = 0
        /** Position demandée, en attente du prochain flush. */
        var pending = false
        var px = 0
        var py = 0
    }

    private val entries = HashMap<WindowId, Entry<W>>()
    private val dirty = ArrayList<Entry<W>>()
    /** Fenêtres affichées, de la plus basse à la plus haute (ordre du WindowManager). */
    private val stack = ArrayList<WindowId>()

    var factory: ((WindowId) -> W?)? = null

    /**
     * Un ajout de fenêtre refusé (permission de surimpression retirée…) : signalé ici, la fenêtre reste non
     * affichée. Sans gestionnaire, l'exception remonte à l'appelant.
     */
    var onAddFailed: ((WindowId, Exception) -> Unit)? = null

    /** Nombre de mises à jour de position envoyées (statistique, tests). */
    var moves = 0L
        private set

    /** Fenêtres affichées, de la plus basse à la plus haute. */
    val stacking: List<WindowId> get() = stack.toList()

    /** Nombre de fenêtres rajoutées pour rétablir l'ordre des couches (statistique, tests). */
    var restacks = 0L
        private set

    /** La fenêtre enregistrée sous [id] (affichée ou non). */
    fun window(id: WindowId): W? = entries[id]?.w

    override fun isAttached(id: WindowId): Boolean = entries[id]?.attached == true

    /** Affiche [w] sous [id] ; une autre fenêtre déjà affichée sous cet id est d'abord retirée. */
    fun attach(id: WindowId, w: W, x: Int, y: Int, fadeIn: Boolean = false) {
        val e = entries[id]
        if (e != null && e.w !== w) { detach(id); e.w = w }
        place(id, e ?: Entry(w).also { entries[id] = it }, x, y, fadeIn)
    }

    override fun attach(id: WindowId, x: Int, y: Int, fadeIn: Boolean) {
        val e = entries[id] ?: (factory?.invoke(id) ?: return).let { Entry(it).also { n -> entries[id] = n } }
        place(id, e, x, y, fadeIn)
    }

    private fun place(id: WindowId, e: Entry<W>, x: Int, y: Int, fadeIn: Boolean) {
        if (e.attached) { move(id, x, y); return }
        e.pending = false
        try {
            backend.add(e.w, x, y, fadeIn)   // peut lever : la fenêtre reste alors non affichée
        } catch (ex: Exception) {
            (onAddFailed ?: throw ex).invoke(id, ex)
            return
        }
        e.x = x; e.y = y
        e.attached = true
        stack.add(id)                        // le WindowManager l'a mise tout en haut
        fixOrder()
    }

    /**
     * Rétablit l'ordre des couches : tant qu'une fenêtre d'une couche supérieure est **sous** une fenêtre d'une
     * couche inférieure qui la chevauche, elle est retirée puis rajoutée (elle repasse tout en haut). Les fenêtres
     * qui ne se chevauchent pas gardent leur place (rien ne se voit, aucun appel).
     */
    private fun fixOrder() {
        var guard = stack.size * stack.size + 1
        while (guard-- > 0) {
            val i = inverted() ?: return
            val o = stack.removeAt(i)
            val oe = entries.getValue(o)
            backend.remove(oe.w)
            try {
                backend.add(oe.w, oe.x, oe.y, false)
                stack.add(o)
                restacks++
            } catch (ex: Exception) {
                oe.attached = false; oe.pending = false
            }
        }
    }

    /** Indice d'une fenêtre recouverte par une fenêtre d'une couche inférieure qui la chevauche, ou null. */
    private fun inverted(): Int? {
        // Cas courant : déjà rangées par couche
        var sorted = true
        for (i in 1 until stack.size) if (stack[i].layer < stack[i - 1].layer) { sorted = false; break }
        if (sorted) return null
        for (i in stack.indices) {
            val hi = stack[i]
            val he = entries.getValue(hi)
            for (j in i + 1 until stack.size) {
                val lo = stack[j]
                if (lo.layer < hi.layer && overlaps(he, entries.getValue(lo))) return i
            }
        }
        return null
    }

    /** Les deux fenêtres se chevauchent (taille inconnue : supposé que oui). */
    private fun overlaps(a: Entry<W>, b: Entry<W>): Boolean {
        val aw = backend.width(a.w); val ah = backend.height(a.w)
        val bw = backend.width(b.w); val bh = backend.height(b.w)
        if (aw <= 0 || ah <= 0 || bw <= 0 || bh <= 0) return true
        return a.x < b.x + bw && b.x < a.x + aw && a.y < b.y + bh && b.y < a.y + ah
    }

    override fun move(id: WindowId, x: Int, y: Int) {
        val e = entries[id] ?: return
        if (!e.attached) { e.x = x; e.y = y; return }
        if (!e.pending) {
            if (x == e.x && y == e.y) return
            e.pending = true
            dirty.add(e)
            if (dirty.size == 1) onPending()
        }
        e.px = x; e.py = y
    }

    /** Position appliquée immédiatement (taille ou paramètres changés : panneau replacé, bulle). */
    fun relayout(id: WindowId, x: Int, y: Int) {
        val e = entries[id] ?: return
        e.pending = false
        e.x = x; e.y = y
        if (e.attached) { backend.move(e.w, x, y); moves++; fixOrder() }
    }

    override fun flush() {
        if (dirty.isEmpty()) return
        var moved = false
        for (i in dirty.indices) {
            val e = dirty[i]
            if (!e.pending) continue
            e.pending = false
            if (!e.attached || (e.px == e.x && e.py == e.y)) continue
            e.x = e.px; e.y = e.py
            backend.move(e.w, e.x, e.y)
            moves++
            moved = true
        }
        dirty.clear()
        // Un déplacement peut faire passer une fenêtre sur une autre : l'ordre des couches est revérifié
        if (moved) fixOrder()
    }

    override fun redraw(id: WindowId) {
        val e = entries[id] ?: return
        backend.redraw(e.w)
    }

    override fun detach(id: WindowId) {
        val e = entries[id] ?: return
        e.pending = false
        if (!e.attached) return
        e.attached = false
        stack.remove(id)
        backend.remove(e.w)
    }

    override fun release(id: WindowId) {
        detach(id)
        entries.remove(id)
    }

    override fun detachAll() {
        for (id in entries.keys.toList()) detach(id)
        dirty.clear()
    }

    /** Un premier déplacement attend : l'adaptateur peut programmer un [flush] (hors de la boucle d'images). */
    protected open fun onPending() {}
}
