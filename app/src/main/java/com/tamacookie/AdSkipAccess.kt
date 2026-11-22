package com.tamacookie

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Chasseur de pubs, côté service d'accessibilité ([ScreenSensorService]) : la **recherche légère** du bouton
 * « Ignorer l'annonce » entre deux scans (il apparaît en cours de vidéo, sur un simple changement de contenu que
 * le scan complet ignore hors du bureau), et l'**appui** demandé par le port [ScreenActions] ([AdSkipRelay]).
 *
 * Tout passe par [ScreenScan.findAdSkip] (liste blanche stricte) ; l'appui **relit** l'arbre sur le fil du scan et
 * n'envoie `ACTION_CLICK` qu'à un nœud de la liste blanche qui recoupe encore la cible ; refusé, un toucher simulé
 * ([tap]) **seulement dans YouTube** ([ScreenScan.TAP_PKGS]), au centre du nœud rogné à la fenêtre active, après une
 * dernière relecture de l'arbre — jamais ailleurs (un faux bouton d'une autre app ne nous fait pas appuyer
 * pour elle). Fil principal, sauf [find] (fil du scan, [worker]).
 */
internal class AdSkipAccess(
    private val service: AccessibilityService,
    private val hub: ScreenSensorHub,
    private val main: Handler,
    private val worker: () -> Handler,
) {
    private var pending = false
    private var lastAt = 0L
    private var stopped = false
    private val probe = Runnable { pending = false; run() }

    /** Le chasseur est voulu et le lapin tourne (sinon : aucune recherche, aucun appui). */
    private val active: Boolean get() = !stopped && hub.adSkipWanted && BunnyService.running

    /** Programme une recherche (au plus une toutes les [PROBE_INTERVAL_MS], et pas avant [delay] ms). */
    fun schedule(delay: Long = 0L) {
        if (pending || !active) return
        val wait = maxOf(delay, lastAt + PROBE_INTERVAL_MS - SystemClock.uptimeMillis(), 0L)
        pending = true
        main.postDelayed(probe, wait)
    }

    // Une lecture de l'arbre ratée (fenêtre fermée entre-temps, service déconnecté) ne doit jamais planter le
    // processus : journalisée et ignorée, comme le scan de ScreenSensorService.
    @Suppress("TooGenericExceptionCaught")
    private fun run() {
        if (!active || !hub.snapshot.screenOn) return
        lastAt = SystemClock.uptimeMillis()
        worker().post {
            val found = try { find(null) } catch (e: Exception) {
                Log.w("TamaCookie", "ad probe", e); null
            }
            if (found != null) main.post { if (!stopped) hub.onAdProbe(found.box) }
        }
    }

    /** Résultat d'une recherche : le nœud trouvé, ses bornes (null = pas de bouton), l'app et sa fenêtre. */
    private class Found(val node: AccessibilityNodeInfo?, val box: Box?, val pkg: String, val window: Box) {
        /** Où toucher pour de vrai si le clic est refusé (null : interdit, voir [ScreenScan.tapPoint]). */
        val tap: Pair<Float, Float>? get() = ScreenScan.tapPoint(pkg, box, window)
    }

    /**
     * **Fil du scan** : le bouton de pub de la fenêtre active (recoupant [near] s'il est donné). null = rien de
     * lisible (pas de fenêtre, notre app, le volet) : on ne publie rien.
     */
    private fun find(near: Box?): Found? {
        val root = try { service.rootInActiveWindow } catch (_: Exception) { null } ?: return null
        val pkg = root.packageName?.toString()
        if (pkg == null || ScreenScan.ignoredPkg(pkg, service.packageName)) return null
        val reader = ScreenSensorService.NodeInfoReader()
        val n = ScreenScan.findAdSkip(root, reader, NodeInfoFinder, pkg, near)
        return Found(n, n?.let { reader.bounds(it) }, pkg, reader.bounds(root))
    }

    /**
     * Appui demandé par le relais ([AdSkipRelay.presser] : réglage actif, cible qui recoupe le bouton connu). Sur
     * le fil du scan : relit l'arbre, clique le nœud de la liste blanche qui recoupe encore [target], sinon rien ;
     * puis revérifie que le bouton a disparu.
     */
    @Suppress("TooGenericExceptionCaught")   // voir run()
    fun press(target: Box): Boolean {
        if (!active || !hub.snapshot.screenOn) return false
        worker().post {
            try {
                val f = find(target)
                val clicked = f?.node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                // YouTube refuse le clic d'accessibilité sur ce bouton : vrai toucher simulé (YouTube seulement)
                val box = f?.box
                if (!clicked && box != null && f.tap != null) main.post { if (!stopped) tap(box) }
            } catch (e: Exception) {
                Log.w("TamaCookie", "ad skip", e)
            }
            main.post { schedule(RECHECK_MS) }
        }
        return true
    }

    /**
     * Fil principal : toucher bref sur le bouton [box] (bornes du nœud **relu** à l'instant, liste blanche). Nos
     * fenêtres qui recouvrent le bouton (l'animal perché dessus) sont d'abord rendues traversables, sinon le
     * toucher tomberait sur elles. Une mise en page plus tard, l'arbre est **relu une dernière fois** sur le fil du
     * scan (même app autorisée, bouton toujours là, fenêtre active inchangée) : le geste ne part qu'à ce point.
     */
    @Suppress("TooGenericExceptionCaught")   // voir run()
    private fun tap(box: Box) {
        AppGraph.of(service).clearOverlayArea?.invoke(box)
        main.postDelayed({
            if (stopped) return@postDelayed
            worker().post {
                val at = try { find(box)?.tap } catch (e: Exception) { Log.w("TamaCookie", "ad tap", e); null }
                if (at != null) main.post { if (!stopped) gesture(at) }
            }
        }, CLEAR_DELAY_MS)
    }

    private fun gesture(at: Pair<Float, Float>) {
        val path = Path().apply { moveTo(at.first, at.second) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_MS)
        service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Service détruit : plus de recherche ni d'appui. */
    fun stop() {
        stopped = true
        main.removeCallbacks(probe)
    }

    private companion object {
        /** Au plus une recherche légère par intervalle (ms) : ~4 IPC chacune. */
        const val PROBE_INTERVAL_MS = 500L
        /** Après un appui : on revérifie que le bouton a disparu (ms). */
        const val RECHECK_MS = 400L
        /** Délai (ms) entre « fenêtres traversables » et le toucher simulé (une mise en page). */
        const val CLEAR_DELAY_MS = 60L
        /** Durée (ms) du toucher simulé (un tap). */
        const val TAP_MS = 50L
    }
}

/** [ScreenScan.NodeFinder] sur `AccessibilityNodeInfo` : les recherches sont faites par l'application (une IPC). */
internal object NodeInfoFinder : ScreenScan.NodeFinder<AccessibilityNodeInfo> {
    override fun viewId(n: AccessibilityNodeInfo): String? = n.viewIdResourceName
    override fun parent(n: AccessibilityNodeInfo): AccessibilityNodeInfo? = n.parent
    override fun findByText(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> =
        root.findAccessibilityNodeInfosByText(text).orEmpty()
    override fun findByViewId(root: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo> =
        root.findAccessibilityNodeInfosByViewId(id).orEmpty()
}
