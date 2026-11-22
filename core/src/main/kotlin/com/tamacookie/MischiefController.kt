package com.tamacookie

import com.tamacookie.feature.ActHandler
import com.tamacookie.feature.ActRegistry
import com.tamacookie.feature.mischief.HideSeekMischief
import com.tamacookie.feature.mischief.Mischief
import com.tamacookie.feature.mischief.MischiefCtx
import com.tamacookie.feature.mischief.MischiefState
import com.tamacookie.feature.mischief.Mischiefs
import com.tamacookie.feature.mischief.UserEvents

/**
 * Bêtises : le cadre (niveau, délai entre deux bêtises, applications exemptées, lecture des clics /
 * frappes / défilements) et le choix parmi les bêtises enregistrées ([Mischiefs.ALL]). Ce que fait
 * chaque bêtise est dans son `object` (`feature/mischief/`) ; ses activités passent par [ActRegistry]
 * ([registerActs]).
 *
 * Ne dépend que de [PetCtx] (testable avec `FakePetCtx`) ; agit sur [PetCtx.pet]. Les clics / frappes /
 * défilements arrivent par [PetCtx.screen] ([ScreenSensor.takeSignals]), sans perte.
 */
class MischiefController(private val ctx: PetCtx, private val all: List<Mischief> = Mischiefs.ALL) {
    /** 0 sage, 1 taquin, 2 petit monstre. */
    var level = 1
    private var cooldownUntil = 0L
    /** Premier passage fait : les signaux reçus avant (lapin pas encore là) sont ignorés. */
    private var primed = false
    private val events = UserEvents()

    /** L'animal courant (celui qui décide, qu'on touche…) ; l'état de sa bêtise est dans son esprit. */
    private val pet: Pet get() = ctx.pet
    private val shownBuf = ArrayList<Pet>()

    /** Ce que reçoivent les bêtises : la façade + niveau, délai et état. */
    private val mctx: MischiefCtx = object : MischiefCtx, PetCtx by ctx {
        override val level: Int get() = this@MischiefController.level
        override var cooldownUntil: Long
            get() = this@MischiefController.cooldownUntil
            set(v) { this@MischiefController.cooldownUntil = v }
        override val state: MischiefState get() = ctx.pet.mind.mischief
    }

    /** Une bêtise en cours : la boucle et le toucher lui délèguent ses activités. */
    private inner class Running(private val m: Mischief) : ActHandler {
        override val slowFrames get() = m.slowFrames
        override val travel get() = m.travel
        override fun update(pet: Pet, dt: Float) = m.update(mctx, pet, dt)
        override fun onPet(pet: Pet) = m.onPet(mctx, pet)
    }

    /** Enregistre les activités de chaque bêtise ([Mischief.acts]). */
    fun registerActs(acts: ActRegistry) {
        for (m in all) {
            val h = Running(m)
            for (a in m.acts) acts.register(a) { h }
        }
    }

    /** Vue où un animal s'est caché (null = pas de cache-cache). */
    val hidingView: String? get() = ctx.world.pets.values.firstNotNullOfOrNull { it.mind.mischief.hidingView }

    fun loadLevel() { level = ctx.prefs.getInt(Prefs.MISCHIEF, 1) }

    private fun exemptApp(): Boolean {
        val pkg = (ctx.screen.snapshot.foregroundPkg ?: ctx.foregroundPkg()).orEmpty().lowercase()
        return EXEMPT.any { it in pkg }
    }

    /** Appelé quand il choisit quoi faire : s'il est ignoré depuis longtemps, il fait une bêtise. */
    fun maybeSeekAttention(): Boolean {
        if (ctx.world.stationOf(pet) != null) return false
        if (!ctx.director.mayStartMischief(pet)) return false          // un autre animal fait déjà une bêtise
        val now = ctx.clock.wallMs()
        val thresholdMs = BunnyBrain.attentionThresholdMs(level, pet.personality, pet.species)
        if (!BunnyBrain.canSeekAttention(now, ctx.lastInteraction, cooldownUntil, thresholdMs)) return false
        if (!pet.mind.brain.rollAttention()) return false
        cooldownUntil = now + BunnyBrain.attentionCooldownMs(level)
        ctx.lastInteraction = now - thresholdMs / 2      // recommence plus tard s'il est toujours ignoré
        if (level == 0 || exemptApp()) {
            ctx.say(ctx.pick(*IGNORED))
            return false
        }
        val p = pet
        return Mischiefs.seek(
            all, level, ctx.rnd, { it.canStart(mctx, p) }, { it.start(mctx, p) }, p.species.behavior(p.personality),
        ) != null
    }

    /**
     * Réagit à ce que fait l'utilisateur (clics, frappe au clavier, défilement), une fois par image.
     * Chaque frappe compte ; chaque clic est proposé aux bêtises, dans l'ordre, jusqu'à la première qui
     * réagit ; le défilement compte une fois par image (il arrive en rafale).
     */
    fun checkUserEvents() {
        val signals = ctx.screen.takeSignals().let { if (primed) it else emptyList() }
        primed = true
        if (level == 0 || ctx.hidden) return
        val p = reactingPet() ?: return
        ctx.world.asPet(p) { react(p, signals) }
    }

    /**
     * L'animal qui réagit à l'utilisateur : le premier affiché ni endormi ni dans une station, de
     * préférence libre ([Act.FREE]) ; null = aucun (les signaux sont consommés sans réaction).
     */
    private fun reactingPet(): Pet? {
        // Une bêtise en cours : c'est son auteur qui réagit (les autres n'en commencent pas une deuxième)
        ctx.director.mischiefOwner()?.let { return it.takeIf { o -> ctx.world.isShown(o) && !o.stats.sleeping && ctx.world.stationOf(o) == null } }
        var first: Pet? = null
        for (q in ctx.world.shown(shownBuf)) {
            if (q.stats.sleeping || ctx.world.stationOf(q) != null) continue
            if (q.body.act in Act.FREE) return q
            if (first == null) first = q
        }
        return first
    }

    private fun react(pet: Pet, signals: List<UserSignal>) {
        var clicked = false
        var scrolled = false
        for (s in signals) when (s) {
            UserSignal.Typing -> events.typing++
            UserSignal.Scroll -> scrolled = true
            is UserSignal.Click -> clicked = true
        }
        val now = ctx.clock.wallMs()
        if (pet.body.act !in Act.FREE || now < cooldownUntil || exemptApp()) return
        events.scroll = scrolled; events.now = now
        val p = pet
        if (!clicked) {
            events.click = null
            Mischiefs.react(all) { it.react(mctx, p, events) }
            return
        }
        for (s in signals) {
            if (s !is UserSignal.Click) continue
            events.click = s.box
            if (Mischiefs.react(all) { it.react(mctx, p, events) } != null) break
            events.scroll = false                                   // le défilement n'est proposé qu'une fois
        }
        events.click = null
    }

    // --- Cache-cache (menu, changement de vue, dodo, glisser) ---
    fun startHideSeek(fromMenu: Boolean): Boolean {
        val other = ctx.director.mischiefOwner()?.takeIf { it !== pet }
        if (other != null) {
            if (fromMenu) ctx.say(oneAtATime(other.name), 2.5f)
            return false
        }
        return HideSeekMischief.startFrom(mctx, pet, fromMenu)
    }

    /** Fin du cache-cache de [p] (attrapé, dodo…). */
    fun endHiding(p: Pet = pet) = ctx.world.asPet(p) { HideSeekMischief.end(mctx) }

    fun onViewChanged() = HideSeekMischief.onViewChanged(mctx, pet)

    private companion object {
        val EXEMPT = listOf("dialer", "incallui", "telecom", "phone")
        /** Ignoré trop longtemps, au niveau « sage » ou dans une app exemptée : il réclame sans bêtise. */
        val IGNORED = arrayOf("Tu m'oublies ? 🥺", "Coucou… je suis là ! 👋", "Psst… 👀")
        /** Cache-cache du menu refusé : un autre animal fait déjà une bêtise. */
        fun oneAtATime(other: String) = "$other fait déjà des bêtises… une à la fois ! 😅"
    }
}
