package com.tamacookie.feature.mischief

import com.tamacookie.PetEvent
import com.tamacookie.Act
import com.tamacookie.Expr
import com.tamacookie.PType
import com.tamacookie.Pose
import com.tamacookie.Pet
import com.tamacookie.Tuning
import com.tamacookie.ViewKey
import com.tamacookie.feature.Travel
import com.tamacookie.species.BehaviorMods
import java.util.EnumSet

/**
 * Cache-cache : il court se cacher sur une autre page du bureau ([Act.HIDE_RUN] puis [Act.HIDDEN]) ;
 * sur la bonne page, sa tête dépasse d'un bord ([Act.PEEK]) et un tap le trouve. Il abandonne au bout de
 * [Tuning.HIDE_GIVE_UP_MS]. Lancé quand il est ignoré, ou depuis le menu ([startFrom]).
 */
object HideSeekMischief : Mischief {
    /** La partie commence. */
    val START = arrayOf(
        "Cache-cache ! Compte jusqu'à 10… 🙈", "Tu ne me trouveras jamais ! 🫣", "Attrape-moi si tu peux !",
    )
    /** Trouvé (tap sur sa tête qui dépasse). */
    val FOUND = arrayOf("Trouvé !! 🎉", "Oh non, tu m'as trouvé ! 😆", "Tu es trop fort ! On rejoue ?")
    /** Personne ne l'a trouvé : il abandonne. */
    val GAVE_UP = arrayOf("Tu ne m'as pas trouvé… 😢 Je reviens !", "Personne ne me cherche ? 🥺")
    /** Caché, il pouffe. */
    val GIGGLE = arrayOf("hihi…", "🤭", "chut…")
    /** Cache-cache demandé sans le service d'accessibilité. */
    const val NEEDS_ACCESSIBILITY = "Active l'accessibilité pour jouer à cache-cache 🫣"
    /** Cache-cache demandé hors de l'écran d'accueil. */
    const val NEEDS_HOME = "Va sur l'écran d'accueil pour jouer à cache-cache ! 🏠"

    override val acts: Set<Act> = EnumSet.of(Act.HIDE_RUN, Act.HIDDEN, Act.PEEK)
    /** Il ne suit pas l'utilisateur dans une autre application, et n'en dit rien. */
    override val travel = Travel.IGNORES

    /** Occupé à cache-cache : réplique de `PetCtx.sayIfBusy` (on lui donne à manger, un jouet…). */
    const val BUSY_LINE = "Je joue à cache-cache ! 🫣"

    override fun chance(level: Int) = 0.35f

    /** Un peureux se cache plus volontiers ([BehaviorMods.hideChanceFactor]). */
    override fun chance(level: Int, mods: BehaviorMods) = chance(level) * mods.mischiefChanceFactor * mods.hideChanceFactor

    /** Possible seulement sur le bureau, avec plusieurs pages (détectées via l'accessibilité). */
    override fun canStart(ctx: MischiefCtx, pet: Pet): Boolean {
        val (pkg, page) = ctx.pages.split(ctx.pages.currentView)
        val s = ctx.screen.snapshot
        return s.connected && page != null && s.pageCount >= 2 && pkg == s.launcherPkg
    }

    override fun start(ctx: MischiefCtx, pet: Pet) = startFrom(ctx, pet, fromMenu = false)

    /** Lance la partie ; depuis le menu, il explique pourquoi c'est impossible. */
    fun startFrom(ctx: MischiefCtx, pet: Pet, fromMenu: Boolean): Boolean {
        // En train de manger : pas maintenant (le repas serait perdu)
        val busy = pet.stats.sleeping || pet.isHiding || pet.act == Act.EAT
        if (ctx.world.stationOf(pet) != null || busy) return false
        if (!canStart(ctx, pet)) {
            if (fromMenu) ctx.say(if (!ctx.screen.snapshot.connected) NEEDS_ACCESSIBILITY else NEEDS_HOME, 3f)
            return false
        }
        val a = ctx.arena
        // `canStart` garantit une page du bureau (sans aléatoire : cette garde ne change aucun tirage)
        val (pkg, page) = ctx.pages.split(ctx.pages.currentView)
        if (page == null) return false
        val target = (1..ctx.screen.snapshot.pageCount).filter { it != page }.random(ctx.rnd)
        ctx.state.hidingView = ViewKey.of(pkg, target)
        pet.body.targetX = if (target > page) a.screenW + a.bw else -a.bw.toFloat()
        ctx.noteInteraction()
        ctx.closeMenu()
        ctx.say(ctx.pick(*START), 2.5f)
        // Le jouet qu'il tenait reste là (il ne part pas avec hors de l'écran) ; en plein saut, il court en retombant
        ctx.world.dropHeldToy(pet.id)
        pet.setActOrAfterAir(Act.HIDE_RUN)
        return true
    }

    /** Fin du cache-cache (trouvé, abandon, attrapé, dodo…). */
    fun end(ctx: MischiefCtx) {
        ctx.state.hidingView = null
        ctx.setDockHiding(false)
    }

    /** Changement de vue : arrivé sur la bonne page -> il apparaît caché ; parti -> il redisparaît. */
    fun onViewChanged(ctx: MischiefCtx, pet: Pet) {
        val here = ctx.pages.currentView
        val where = ctx.state.hidingView
        if (pet.body.act == Act.HIDDEN && here == where) startPeek(ctx, pet)
        else if (pet.body.act == Act.PEEK && here != where) pet.setAct(Act.HIDDEN)
    }

    /** Il apparaît caché sur la bonne page : tête qui dépasse d'un bord. */
    private fun startPeek(ctx: MischiefCtx, pet: Pet) {
        val a = ctx.arena
        val rnd = ctx.rnd
        val b = pet.body
        b.platform = null; b.phys.airLandY = null; b.phys.stop()
        when (rnd.nextInt(3)) {
            0 -> { b.fx = 0f; b.fy = a.groundY; b.facing = 1f }
            1 -> { b.fx = a.screenW; b.fy = a.groundY; b.facing = -1f }
            else -> {
                b.fx = a.minX + rnd.nextFloat() * (a.maxX - a.minX); b.fy = a.groundY + 62 * a.d
                b.facing = if (b.fx < a.screenW / 2) 1f else -1f
            }
        }
        ctx.state.giggleIn = 4f + rnd.nextFloat() * 4f
        pet.setAct(Act.PEEK)
    }

    /** Tap : trouvé (tête qui dépasse) ; pendant qu'il court se cacher, on ne peut pas l'arrêter. */
    override fun onPet(ctx: MischiefCtx, pet: Pet): Boolean = when (pet.act) {
        Act.PEEK -> { found(ctx, pet); true }
        Act.HIDE_RUN -> true
        else -> false
    }

    /** Touché pendant qu'il dépasse : trouvé ! */
    private fun found(ctx: MischiefCtx, pet: Pet) {
        val a = ctx.arena
        end(ctx)
        ctx.noteInteraction()
        ctx.on(pet, PetEvent.Found)
        ctx.emit(pet, PType.HEART, 5)
        pet.express(Expr.HAPPY, 2.5f)
        ctx.say(ctx.pick(*FOUND), 2.5f)
        val fx = pet.body.fx
        ctx.actor.jumpTo(pet, (fx.coerceIn(a.minX, a.maxX) + (if (fx < a.screenW / 2) 120 else -120) * a.d).coerceIn(a.minX, a.maxX),
            a.groundY, Act.IDLE)
    }

    private fun giveUp(ctx: MischiefCtx, pet: Pet) {
        val a = ctx.arena
        val b = pet.body
        val wasHidden = b.act == Act.HIDDEN
        end(ctx)
        ctx.on(pet, PetEvent.NotFound)
        if (wasHidden) {
            b.fx = a.screenW / 2; b.fy = a.topY + Tuning.SPAWN_DROP_DP * a.d; b.phys.stop()
            b.afterAir = Act.IDLE
            pet.setAct(Act.AIR)
            ctx.applyView()
        } else {
            // Depuis PEEK : il dépassait d'un bord (ou du sol), on le ramène dans l'écran
            b.fx = b.fx.coerceIn(a.minX, a.maxX); b.fy = a.groundY
            pet.setAct(Act.IDLE, 2f)
        }
        pet.express(Expr.SAD, 2f)
        ctx.say(ctx.pick(*GAVE_UP), 3f)
    }

    /** Act.HIDE_RUN / HIDDEN / PEEK */
    override fun update(ctx: MischiefCtx, pet: Pet, dt: Float) {
        val s = ctx.state
        when (pet.body.act) {
            Act.HIDE_RUN -> {
                if (ctx.actor.walkToward(pet, pet.body.targetX, pet.species.spec.hideRunDp * ctx.arena.d, dt, clamp = false)) {
                    s.hideStarted = ctx.clock.wallMs()
                    pet.setAct(Act.HIDDEN)
                    ctx.setDockHiding(true)
                    ctx.updateNotification(force = true)
                    ctx.applyView()
                }
            }
            Act.HIDDEN -> {
                if (ctx.clock.wallMs() - s.hideStarted > Tuning.HIDE_GIVE_UP_MS) giveUp(ctx, pet)
            }
            Act.PEEK -> {
                pet.body.pose = Pose.SIT
                s.giggleIn -= dt
                if (s.giggleIn <= 0f) {
                    s.giggleIn = 5f + ctx.rnd.nextFloat() * 5f
                    if (ctx.rnd.nextFloat() < 0.5f) ctx.say(ctx.pick(*GIGGLE), 1.2f)
                }
                if (ctx.clock.wallMs() - s.hideStarted > Tuning.HIDE_GIVE_UP_MS) giveUp(ctx, pet)
            }
            else -> {}
        }
    }
}
