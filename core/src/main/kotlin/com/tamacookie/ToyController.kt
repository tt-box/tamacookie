package com.tamacookie

import com.tamacookie.feature.ActHandler
import com.tamacookie.feature.ActRegistry
import com.tamacookie.feature.Travel
import com.tamacookie.feature.toy.Food
import com.tamacookie.feature.toy.FoodSpec
import com.tamacookie.feature.toy.Poop
import com.tamacookie.feature.toy.PropSpec
import com.tamacookie.feature.toy.Toy
import com.tamacookie.feature.toy.ToyCtx
import com.tamacookie.feature.toy.ToySpec
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Objets posés à l'écran : nourriture, crottes et jouets — leur placement (fenêtres via [PetCtx.overlay]),
 * la partie en cours et les activités `GOTO_FOOD` / `EAT` / `PLAY` (enregistrées dans [ActRegistry] par
 * [registerActs]). Ce qui est propre à chaque objet (taille, lancer, dessin, jeu, physique, répliques) est
 * dans son spec (`feature/toy/` : [ToySpec], [FoodSpec], [Poop]).
 *
 * Kotlin pur (testé en JVM) : les gestes sur les fenêtres des objets arrivent par [grab] / [drag] /
 * [release] / [pickUp] (adaptateur `EntityWindows`).
 */
class ToyController(private val ctx: PetCtx) {
    /** Objets posés : ils appartiennent au monde (partagés entre animaux). */
    private val props get() = ctx.world.props

    private var toyHeldByUser = false

    private val arena get() = ctx.arena
    private val d get() = arena.d
    private val pet: Pet get() = ctx.pet

    /** Ce que reçoivent les jouets : la façade + l'état de la partie en cours (un seul jouet à la fois). */
    private val tctx = object : ToyCtx, PetCtx by ctx {
        override val userHolds: Boolean get() = toyHeldByUser
        override var holdTime = 0f
        override var catchCooldown = 0f
        override var groundSaidAt = 0L
    }

    private val playing = object : ActHandler {
        override val travel = Travel.BUSY
        override fun update(pet: Pet, dt: Float) = updatePlay(pet, dt)
    }
    private val goingToFood = object : ActHandler {
        override val travel = Travel.BUSY
        override fun update(pet: Pet, dt: Float) = updateGoToFood(pet, dt)
    }
    private val eating = object : ActHandler {
        override val travel = Travel.BUSY
        override fun update(pet: Pet, dt: Float) = updateEat(pet)
    }

    /** Jouer, aller manger, manger : la boucle et le toucher délèguent à ce contrôleur. */
    fun registerActs(acts: ActRegistry) {
        acts.register(Act.PLAY) { playing }
        acts.register(Act.GOTO_FOOD) { goingToFood }
        acts.register(Act.EAT) { eating }
    }

    /** Crottes à l'écran ([owner] : celles de cet animal ; null = toutes). */
    fun poopCount(owner: PetId? = null): Int = props.count { it.isPoop && (owner == null || it.owner == owner) }

    /** Un objet bouge (en vol, tenu par l'utilisateur) : animation fluide. */
    fun anyMoving(): Boolean = toyHeldByUser || props.any { !it.isPoop && !it.onGround }

    // =====================================================================
    //  Objets et leurs fenêtres
    // =====================================================================
    fun addProp(spec: PropSpec): Prop {
        val p = Prop(ctx.world.newPropId(), spec, (spec.wDp * d).toInt(), (spec.hDp * d).toInt())
        props.add(p)
        if (!ctx.hidden) ctx.overlay.attach(p.windowId, p.left, p.top)
        ctx.requestFrames()
        return p
    }

    fun removeProp(p: Prop) {
        ctx.overlay.release(p.windowId)
        props.remove(p)
        // La fenêtre du jouet a disparu : l'utilisateur ne peut plus le tenir
        if (p.toy != null) toyHeldByUser = false
    }

    /** Retire tous les objets (nouvel animal). */
    fun clear() { props.toList().forEach(::removeProp) }

    /** Fenêtre de l'objet à sa position (regroupé : au plus un déplacement par image). */
    fun placeProp(p: Prop) = ctx.overlay.move(p.windowId, p.left, p.top)

    /** Réaffiche les objets (après "Montrer") : tout sauf les crottes d'autres vues. */
    fun reattach() {
        val here = ctx.pages.currentView
        for (p in props) if (!p.isPoop || p.anchor == here) ctx.overlay.attach(p.windowId, p.left, p.top)
    }

    /** Toutes les fenêtres ont été retirées (« Cacher ») : plus personne ne tient le jouet. */
    fun onDetached() {
        toyHeldByUser = false
    }

    /** Nouvelle taille d'écran : objets ramenés dans l'écran et au sol. */
    fun onScreenResized() {
        for (p in props) {
            p.x = p.x.coerceIn(arena.minX, arena.maxX)
            if (p.onGround || p.isPoop) p.y = arena.groundY
        }
    }

    /** Les crottes restent sur la vue (page du bureau) où elles sont tombées. */
    fun layoutPoops() {
        val pages = ctx.pages
        for (i in props.indices) {
            val p = props[i]
            if (!p.isPoop) continue
            if (p.anchor.isEmpty()) p.anchor = pages.currentView
            ctx.overlay.placeAnchored(p.windowId, p.x - p.w / 2f, p.top, pages.visibleOffset(p.anchor))
        }
    }

    // =====================================================================
    //  Nourriture
    // =====================================================================

    /**
     * Occupé (station, cache-cache) ou endormi : il le dit et refuse (nourriture, jouet).
     * [noteFirst] : l'interaction compte même s'il dort (nourriture).
     */
    private fun refuses(pet: Pet, asleepLine: String, noteFirst: Boolean): Boolean {
        if (ctx.sayIfBusy(pet)) return true
        if (noteFirst) ctx.noteInteraction()
        if (pet.stats.sleeping) { ctx.say(asleepLine); return true }
        return false
    }

    /** L'objet jaillit du coffre [from] en direction de l'animal. */
    private fun popFrom(p: Prop, from: Station, maxVxDp: Float, vyDp: Float) {
        p.x = from.cx; p.y = from.cy
        p.vx = ((pet.body.fx - from.cx) * 0.9f).coerceIn(-maxVxDp * d, maxVxDp * d)
        p.vy = vyDp * d
    }

    /**
     * Donne à manger. [from] = le coffre d'où sort la nourriture (sinon elle tombe du ciel).
     *
     * La nourriture n'est à personne : elle sort même si l'animal courant dort ou est occupé (un autre, ou lui à son
     * réveil, ira la manger — le plus proche l'emporte), et même si tous sont repus (elle attend au sol qu'un animal
     * ait faim : ils vont la manger de toute façon). Elle remplace une nourriture encore au sol. L'animal courant y
     * court lui-même s'il est libre et éveillé.
     */
    fun feed(food: Food = Food.CARROT, from: Station? = null) {
        ctx.noteInteraction()
        // Une seule nourriture au sol : la nouvelle remplace celle que personne n'a encore mangée.
        props.filter { it.food != null }.forEach(::removeProp)
        val spec = food.spec
        val c = addProp(spec)
        val rnd = ctx.rnd
        if (from != null) {
            popFrom(c, from, maxVxDp = 600f, vyDp = -900f)
        } else {
            c.x = (pet.body.fx + (if (rnd.nextBoolean()) 1 else -1) * (90 + rnd.nextFloat() * 120) * d).coerceIn(arena.minX, arena.maxX)
            c.y = arena.topY + 60 * d
            c.vy = 0f
        }
        placeProp(c)
        if (pet.stats.sleeping || ctx.world.busy(pet)) return
        pet.express(Expr.SURPRISED, 1f)
        ctx.say(ctx.speech.pickFrom(spec.lines), 2f)
        pet.setActOrAfterAir(Act.GOTO_FOOD)
    }

    /** Act.GOTO_FOOD : il attend que la nourriture tombe puis court la manger. */
    private fun updateGoToFood(pet: Pet, dt: Float) {
        val c = props.firstOrNull { it.food != null }
        val food = c?.food
        if (c == null || food == null) { pet.setAct(Act.IDLE); return }
        if (!c.onGround) {
            pet.body.pose = Pose.IDLE
            pet.body.facing = sign(c.x - pet.body.fx).takeIf { it != 0f } ?: pet.body.facing
            pet.express(Expr.SURPRISED, 0.3f)
        } else if (ctx.director.foodWinner(c).let { it != null && it !== pet }) {
            // Un autre animal est plus près : l'arbitre lui donne la nourriture, celui-ci renonce (la dispute
            // est mise en scène par le système social : `CarrotDispute`)
            ctx.director.loseFood(pet, c, ctx.director.foodWinner(c))
            pet.setAct(Act.IDLE, 2f)
        } else if (ctx.actor.walkToward(pet, c.x, 130 * d, dt, stopDist = 26 * d)) {
            pet.body.eatingFood = food
            removeProp(c)
            pet.setAct(Act.EAT, 3f)
        }
    }

    /** Act.EAT : il croque pendant 3 s (avancement lu par la vue), puis les jauges montent. */
    private fun updateEat(pet: Pet) {
        val b = pet.body
        b.pose = Pose.EAT
        b.eatProgress = b.actTime / 3f
        if (b.actTime >= 3f) {
            b.eatProgress = 0f
            if (ctx.on(pet, PetEvent.Fed(b.eatingFood)).refused) {
                // Repu : il l'a mangée quand même (la nourriture n'est jamais refusée au coffre), sans effet
                ctx.say(ctx.speech.pickFrom(FULL))
            } else {
                ctx.emit(pet, PType.HEART, 3)
                pet.express(Expr.HAPPY, 2f)
                ctx.speech.sayOneOf(pet.species.lines.ATE)
            }
            pet.setAct(Act.IDLE, 3f)
        }
    }

    // =====================================================================
    //  Crottes
    // =====================================================================
    /** Une crotte de [owner] (l'animal courant : sa page) tombe en [x]. */
    fun spawnPoop(x: Float, owner: Pet = pet) {
        val p = addProp(Poop)
        p.owner = owner.id
        p.anchor = ctx.pages.bunnyView()
        p.x = x; p.y = arena.groundY
        placeProp(p)
    }

    /** On a touché une crotte : ramassée (c'est son auteur qui remercie). */
    fun pickUp(p: Prop) {
        if (!p.isPoop || p !in props) return
        removeProp(p)
        val owner = p.owner?.let { ctx.world.pets[it] } ?: pet
        ctx.world.asPet(owner) {
            ctx.on(owner, PetEvent.PoopRemoved)
            ctx.emit(owner, PType.SPARKLE, 4)
            owner.express(Expr.HAPPY, 1.5f)
            ctx.say(ctx.pick(*POOP_THANKS), 2f)
        }
    }

    /** Au démarrage (ou quand il apparaît) : les crottes sauvegardées de l'animal courant reviennent. */
    fun restorePoops(owner: Pet = pet) {
        repeat(owner.stats.poops - poopCount(owner.id)) {
            spawnPoop(arena.minX + ctx.rnd.nextFloat() * (arena.maxX - arena.minX), owner)
        }
    }

    /** L'animal [id] est parti : ses crottes disparaissent. */
    fun removePoopsOf(id: PetId) { props.filter { it.isPoop && it.owner == id }.forEach(::removeProp) }

    // =====================================================================
    //  Jouets
    // =====================================================================
    fun startPlay(toy: Toy, from: Station? = null) {
        val rnd = ctx.rnd
        if (!pet.species.accepts(toy)) return
        if (refuses(pet, CommonLines.ASLEEP_NO_PLAY, noteFirst = false)) return
        if (ctx.on(pet, PetEvent.PlayStarted).refused) { ctx.say(ctx.speech.pickFrom(TOO_TIRED)); return }
        props.filter { it.toy != null }.forEach { removeProp(it) }
        toyHeldByUser = false
        val spec = toy.spec
        val p = addProp(spec)
        if (spec.randomTint) p.tint = AccColor.entries[rnd.nextInt(AccColor.entries.size)].argb
        val fx = pet.body.fx; val fy = pet.body.fy; val facing = pet.body.facing
        val launch = spec.launch
        p.x = (fx + facing * 70 * d).coerceIn(arena.minX, arena.maxX)
        p.y = fy - launch.aboveDp * d
        p.vx = facing * launch.vxDp * d; p.vy = launch.vyDp * d
        if (from != null) popFrom(p, from, maxVxDp = 700f, vyDp = launch.chestVyDp)
        placeProp(p)
        pet.mind.playTime = 0f; tctx.holdTime = 0f; tctx.catchCooldown = 0f
        ctx.noteInteraction()
        val fav = toy == pet.favoriteToy
        ctx.say(
            if (fav) ctx.pick(*favorite(toy.label))
            else ctx.speech.pickFrom(spec.startLines)
        )
        if (fav) { ctx.emit(pet, PType.HEART, 4); pet.express(Expr.HAPPY, 2f) }
        pet.setActOrAfterAir(Act.PLAY)
    }

    /** Act.PLAY : la partie dure un moment (plus s'il est joueur), le jouet fait le reste. */
    private fun updatePlay(pet: Pet, dt: Float) {
        val p = ctx.world.currentToy()
        pet.mind.playTime += dt
        val toy = p?.toy
        if (p == null || toy == null) { pet.setAct(Act.IDLE); return }
        val limit = pet.species.behavior(pet.personality).playSeconds
        if (pet.mind.playTime > limit || pet.stats.energy < ctx.rules.playStopEnergyBelow) { endPlay(p); return }
        toy.spec.play(tctx, pet, p, dt)
    }

    private fun endPlay(p: Prop) {
        removeProp(p)
        ctx.say(ctx.pick(*PLAY_OVER))
        pet.express(Expr.HAPPY, 2f)
        pet.setAct(Act.IDLE, 4f)
    }

    // =====================================================================
    //  Gestes sur un jouet (reçus de sa fenêtre)
    // =====================================================================
    /** L'utilisateur attrape le jouet [t] (si l'animal le tenait, il proteste). */
    fun grab(t: Prop) {
        toyHeldByUser = true
        ctx.noteInteraction()
        ctx.requestFrames()
        if (t.held) {
            t.held = false
            if (t.toy?.spec?.complainsWhenTaken == true) {
                pet.express(Expr.ANGRY, 1.2f)
                ctx.say(ctx.pick(*TAKEN), 1.6f)
            }
        }
    }

    /** Le doigt déplace le jouet (coordonnées écran du doigt). */
    fun drag(t: Prop, rawX: Float, rawY: Float) {
        t.x = rawX; t.y = rawY + t.h / 2f
        t.vx = 0f; t.vy = 0f; t.onGround = false
    }

    /** Le doigt lâche le jouet à la vitesse ([vx], [vy]) px/s (bornée). */
    fun release(t: Prop, vx: Float, vy: Float) {
        val max = Tuning.TOY_THROW_MAX_DP * d
        t.vx = vx.coerceIn(-max, max)
        t.vy = vy.coerceIn(-max, max)
        toyHeldByUser = false
        t.onGround = false
        tctx.catchCooldown = 0.3f
        if (pet.body.act == Act.PLAY && hypot(t.vx, t.vy) > 1500 * d) ctx.say(ctx.pick(*BIG_THROW), 1.6f)
    }

    // =====================================================================
    //  Physique des objets (à chaque image)
    // =====================================================================
    fun update(dt: Float) {
        if (tctx.catchCooldown > 0f) tctx.catchCooldown -= dt
        val g = pet.body.phys.gravity
        val groundY = arena.groundY
        // Parcours à l'envers : un objet peut être retiré pendant la boucle, sans copier la liste
        var i = props.size - 1
        while (i >= 0) {
            val p = props[i--]
            p.age += dt
            val spin0 = p.spin
            when (val spec = p.spec) {
                is ToySpec -> if (!toyHeldByUser) spec.step(tctx, pet, p, dt)
                is FoodSpec -> {
                    if (!p.onGround) {
                        p.spin += 200f * dt
                        if (BunnyPhysics.stepFalling(p, dt, g, arena.minX, arena.maxX, groundY)) p.spin = spec.restSpin
                    }
                    if (p.age > Tuning.FOOD_LIFETIME_S) { removeProp(p); continue }
                }
                else -> p.y = groundY            // crotte
            }
            // Le dessin ne dépend que de la rotation : on ne redessine que si elle a changé
            if (p.spin != spin0) ctx.overlay.redraw(p.windowId)
            if (!p.isPoop) placeProp(p)
        }
    }

    /** Répliques des repas et des parties (indépendantes de l'espèce ; l'ordre fixe les tirages). */
    private companion object {
        val FULL = arrayOf("J'ai plus faim !", "Mon ventre va exploser !")
        val TOO_TIRED = arrayOf("Trop fatigué… 😴", "Plus tard, j'ai plus d'énergie")
        val POOP_THANKS = arrayOf("Merci ! ✨", "Ouf, ça sentait…", "Tu es le meilleur !")
        fun favorite(toyLabel: String) = arrayOf("Mon jouet préféré !! ⭐", "Ouiii, le ${toyLabel.lowercase()} !! ⭐")
        val PLAY_OVER = arrayOf("C'était trop bien ! 💕", "Pfiou, je suis épuisé !", "Encore demain ?")
        val TAKEN = arrayOf("Hé ! C'est à moi !", "Rends-le moi ! 😾", "Mon jouet !")
        val BIG_THROW = arrayOf("Waouh, quel lancer !", "J'y vais !")
    }
}
