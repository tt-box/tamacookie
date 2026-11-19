package com.tamacookie

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import com.tamacookie.feature.menu.MenuCtx
import com.tamacookie.feature.menu.MenuEntries
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Interactions tactiles avec les animaux : toucher (caresses, chatouilles, réveil),
 * glisser / lancer (dépôt dans une station), et menu de soins (double toucher / appui long).
 * Chaque fenêtre d'animal a son écouteur ([attach]) : le geste agit sur **l'animal touché** (il devient
 * l'animal courant, `World.asPet`, le temps du geste) ; le menu agit sur l'animal pour lequel il a été ouvert.
 *
 * Adaptateur d'entrée : passe par [PetCtx] pour l'animal, et reçoit explicitement les deux
 * contrôleurs qu'il pilote ([StationController] : survol / dépôt / panneau ; [MischiefController] :
 * fin du cache-cache quand on l'attrape, cache-cache depuis le menu). La caresse pendant une activité
 * (station, bêtise) va à son gestionnaire ([PetCtx.acts]). Reste lié à Android (gestes, vues) :
 * reçoit le [context] et les [windows] (le menu est la fenêtre [WindowId.Menu]).
 */
class TouchHandler(
    private val context: Context,
    private val windows: OverlayWindows,
    private val ctx: PetCtx,
    private val stations: StationController,
    private val mischief: MischiefController,
) {
    private val arena get() = ctx.arena
    private val pet: Pet get() = ctx.pet

    private val tapTimes = ArrayDeque<Long>()
    private var menu: PetMenu? = null
    /** L'animal pour lequel le menu est ouvert. */
    private var menuPet: PetId? = null
    private var lastMenuClose = 0L

    /** Écouteur posé sur la fenêtre de chaque animal (réutilisé par la cage pour lui transmettre les gestes). */
    private val listeners = HashMap<View, View.OnTouchListener>()

    /** Transmet un geste à la fenêtre [view] d'un animal (la cage lui passe les touchers). */
    fun forward(view: View, ev: MotionEvent): Boolean = listeners[view]?.onTouch(view, ev) ?: false

    val menuOpen: Boolean get() = menu != null

    /** Ce que reçoivent les entrées du menu ([MenuEntries]) : la façade + le panneau 🏠 et le cache-cache. */
    private val menuCtx: MenuCtx = object : MenuCtx, PetCtx by ctx {
        override fun toggleStationsPanel() = stations.toggleStationsPanel()
        override fun startHideSeek() = mischief.startHideSeek(fromMenu = true)
    }

    fun refreshMenu() { menu?.refresh() }

    /** Lecteur d'écran (étape 8d) : les mêmes gestes, en actions sur la fenêtre de chaque animal. */
    val accessibility = PetAccessibility(context, windows, ctx, stations, object : PetAccessibility.Gestures {
        override fun tap() = onPetTap()
        override fun menu() = toggleMenu()
        override fun grab() = startDrag()
    })

    // =====================================================================
    //  Toucher, glisser, lancer
    // =====================================================================
    /** Pose l'écouteur sur la fenêtre [bunny] de l'animal [petOf] (relu à chaque geste : la fenêtre peut changer d'animal). */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(bunny: PetView, petOf: () -> Pet?) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
        /** Le geste agit sur l'animal de cette fenêtre. */
        fun touched(block: () -> Unit) { val p = petOf() ?: return; ctx.speech.at(SpeechPriority.HIGH) { ctx.world.asPet(p, block) } }
        val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { touched { onPetTap() }; return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { touched { toggleMenu() }; return true }
            override fun onLongPress(e: MotionEvent) = touched {
                ctx.haptic(pet, HapticFeedbackConstants.LONG_PRESS)
                toggleMenu()
            }
        })
        var downX = 0f; var downY = 0f
        var offX = 0f; var offY = 0f
        var dragging = false
        var lastRawX = 0f
        var vt: VelocityTracker? = null

        fun onTouch(ev: MotionEvent): Boolean {
            val e2 = MotionEvent.obtain(ev); e2.setLocation(ev.rawX, ev.rawY)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; lastRawX = ev.rawX
                    dragging = false
                    vt?.recycle(); vt = VelocityTracker.obtain()
                    ctx.userTouched()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(ev.rawX - downX, ev.rawY - downY) > slop) {
                        dragging = true
                        val cancel = MotionEvent.obtain(ev); cancel.action = MotionEvent.ACTION_CANCEL
                        gd.onTouchEvent(cancel); cancel.recycle()
                        ctx.pages.rebaseToActivePage()
                        offX = downX - pet.body.fx; offY = downY - pet.body.fy
                        startDrag()
                    }
                    if (dragging) {
                        val d = arena.d
                        pet.body.fx = (ev.rawX - offX).coerceIn(0f, arena.screenW)
                        pet.body.fy = min(ev.rawY - offY, arena.groundY).coerceAtLeast(arena.topY + 90 * d)
                        if (abs(ev.rawX - lastRawX) > 2 * d) pet.body.facing = sign(ev.rawX - lastRawX)
                        lastRawX = ev.rawX
                        stations.updateHover()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.addMovement(e2)
                    if (dragging) {
                        vt?.computeCurrentVelocity(1000)
                        val target = stations.takeHover()
                        if (target != null) stations.enterStation(target)
                        else release(vt?.xVelocity ?: 0f, vt?.yVelocity ?: 0f)
                        dragging = false
                        vt?.recycle(); vt = null
                        e2.recycle()
                        return true   // pas de "tap" après un lancer
                    }
                    vt?.recycle(); vt = null
                }
            }
            vt?.addMovement(e2)
            e2.recycle()
            if (!dragging) gd.onTouchEvent(ev)
            return true
        }
        val l = View.OnTouchListener { _, ev ->
            val p = petOf() ?: return@OnTouchListener false
            ctx.speech.at(SpeechPriority.HIGH) { ctx.world.asPet(p) { onTouch(ev) } }
        }
        listeners[bunny] = l
        bunny.setOnTouchListener(l)
        accessibility.attach(bunny, petOf)
    }

    /** La fenêtre [view] est retirée pour de bon (compagnon parti) : son écouteur est oublié. */
    fun detach(view: View) { listeners.remove(view); view.setOnTouchListener(null) }

    private fun startDrag() {
        if (pet.body.act == Act.PEEK) {
            mischief.endHiding()
            ctx.on(pet, PetEvent.Found)
            ctx.say(pet.species.lines.FOUND_WHILE_DRAGGED, 1.5f)
        } else if (pet.isHiding) mischief.endHiding()
        closeMenu()
        pet.body.platform = null
        pet.body.phys.airLandY = null
        stations.leaveStation(pet, hopOut = false)
        if (pet.stats.sleeping) ctx.on(pet, PetEvent.Woken(grumpy = true))
        ctx.world.dropHeldToy(pet.id)
        ctx.noteInteraction()
        pet.setAct(Act.DRAGGED)
        ctx.speech.sayOneOf(pet.species.lines.DRAGGED, 1.8f)
        ctx.haptic(pet, HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun release(vxPx: Float, vyPx: Float) {
        val phys = pet.body.phys
        phys.release(vxPx, vyPx)
        if (hypot(phys.vx, phys.vy) > Tuning.THROW_SHOUT_DP * arena.d) ctx.speech.sayOneOf(pet.species.lines.THROWN, 1.6f)
        pet.body.afterAir = Act.IDLE
        pet.setAct(Act.AIR)
    }

    private fun onPetTap() {
        val now = ctx.clock.wallMs()
        ctx.haptic(pet, HapticFeedbackConstants.CONFIRM)
        ctx.noteInteraction()
        // Activité prise en charge par une fonctionnalité (station, bêtise…) : elle réagit à la caresse
        ctx.acts.handlerFor(pet)?.let { if (it.onPet(pet)) return }
        if (pet.body.act == Act.SLEEP || pet.stats.sleeping) {
            ctx.on(pet, PetEvent.Woken(grumpy = true))
            pet.express(Expr.ANGRY, 2.5f)
            ctx.speech.sayOneOf(pet.species.lines.WOKEN_BY_TAP)
            pet.setActOrAfterAir(Act.IDLE, 3f)
            return
        }
        tapTimes.addLast(now)
        while (tapTimes.isNotEmpty() && now - tapTimes.first() > Tuning.TAP_WINDOW_MS) tapTimes.removeFirst()
        if (tapTimes.size >= Tuning.TICKLE_TAPS) {
            tapTimes.clear()
            pet.express(Expr.HAPPY, 1.5f)
            ctx.speech.sayOneOf(pet.species.lines.TICKLED)
            val fx = pet.body.fx; val screenW = arena.screenW
            pet.body.targetX = if (fx > screenW / 2) fx - screenW * 0.35f else fx + screenW * 0.35f
            pet.setActOrAfterAir(Act.WALK)
            return
        }
        ctx.on(pet, PetEvent.Petted)
        ctx.emit(pet, PType.HEART, 2 + ctx.rnd.nextInt(3))
        pet.express(Expr.HAPPY, 1.6f)
        if (ctx.rnd.nextFloat() < 0.45f) ctx.speech.sayOneOf(pet.species.lines.PETTED, 2f)
        val act = pet.body.act
        if (act == Act.IDLE || act == Act.WALK || act == Act.SIT) {
            if (ctx.rnd.nextFloat() < 0.35f) ctx.actor.jump(pet, -560 * arena.d, 0f, then = Act.IDLE) else pet.setAct(Act.IDLE, 2.5f)
        }
    }

    // =====================================================================
    //  Menu de soins
    // =====================================================================
    @SuppressLint("ClickableViewAccessibility")
    private fun toggleMenu() {
        if (menu != null) { closeMenu(); return }
        if (ctx.clock.wallMs() - lastMenuClose < Tuning.MENU_REOPEN_GUARD_MS) return
        val d = arena.d
        lateinit var m: PetMenu
        // Un seul menu à la fois (id [WindowId.Menu]) : un ancien panneau a déjà été retiré en ouvrant le nouveau
        val target = pet
        m = PetMenu(context, target, MenuEntries.ALL,
            onEntry = { e ->
                closeMenu()
                // l'animal a pu partir (compagnon retiré) : rien à faire
                if (ctx.world.pets[target.id] === target) {
                    ctx.speech.at(SpeechPriority.HIGH) { ctx.world.asPet(target) { e.run(menuCtx, target) } }
                }
            },
            onClose = { if (menu === m) closeMenu() },
        )
        ctx.noteInteraction()
        val w = windows.panelWidth(Tuning.MENU_W_DP)
        val lp = windows.panelParams(w, watchOutside = true)
        val mh = windows.measurePanel(m, w)
        // position à l'écran du lapin (il peut être décalé par le défilement du bureau)
        val sx = pet.body.fx + ctx.pages.bunnyShift
        lp.x = windows.clampPanelX(sx - w / 2f, w)
        var y = (pet.body.fy - 125 * d - mh).roundToInt()
        if (y < arena.topY + 8 * d) y = (arena.topY + 8 * d).roundToInt()
        lp.y = y
        m.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                if (menu === m) closeMenu(); true
            } else false
        }
        windows.overlay.attach(WindowId.Menu, m, lp)
        m.alpha = 0f; m.scaleX = 0.9f; m.scaleY = 0.9f
        m.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        menu = m
        menuPet = target.id
        if (pet.body.act == Act.WALK) pet.setAct(Act.IDLE, 6f)
    }

    fun closeMenu() {
        if (menu != null) {
            windows.overlay.detach(WindowId.Menu)
            lastMenuClose = ctx.clock.wallMs()
        }
        menu = null
        menuPet = null
    }

    /** Ferme le menu s'il est ouvert pour [id] (l'animal disparaît / sa fenêtre est retirée). */
    fun closeMenuOf(id: PetId) { if (menuPet == id) closeMenu() }
}
