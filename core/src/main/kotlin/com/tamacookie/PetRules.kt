package com.tamacookie

import com.tamacookie.feature.toy.Food
import com.tamacookie.feature.toy.Toy

/** Humeur globale affichée par l'animal (voir [PetRules.mood]). */
enum class Mood { HAPPY, OK, HUNGRY, SLEEPY, DIRTY, SAD, SICK }

/**
 * Jauges d'un animal (immuable) : de [GameRules.min] (mauvais) à [GameRules.max] (parfait).
 * Ne change que par [PetRules.apply].
 */
data class PetStats(
    /** 100 = repu, 0 = affamé. */
    val satiety: Float = 80f,
    val happiness: Float = 80f,
    val energy: Float = 90f,
    val cleanliness: Float = 90f,
    val health: Float = 100f,
    val sleeping: Boolean = false,
    val poops: Int = 0,
    /** Fraction de crotte accumulée (une crotte à 1). */
    val poopAccumulator: Float = 0f,
    val visitedApps: Int = 0,
    /** Dernière caresse (heure murale, ms) — non sauvegardé. */
    val lastPetAt: Long = 0L,
    /** Enfermé dans la cage : son bonheur chute plus vite. */
    val caged: Boolean = false,
) {
    val isSick: Boolean get() = PetRules.isSick(this)
    val mood: Mood get() = PetRules.mood(this)
}

/** Ce qui fait évoluer les jauges. [savesNow] : l'hôte sauvegarde aussitôt (sinon à la prochaine sauvegarde périodique). */
sealed interface PetEvent {
    val savesNow: Boolean get() = when (this) {
        is Fed, PlayStarted, Bathed, PoopRemoved, Healed, FellAsleep, is Woken, Caged, Released,
        WheelRun, Grazed, Brushed -> true
        is Elapsed, Petted, is PlayHit, AppVisited, HardLanding, Found, NotFound, is Rested -> false
    }

    /** Le temps passe ([hours] heures ; plafonné à [GameRules.maxElapsedHours]). */
    data class Elapsed(val hours: Float) : PetEvent
    data class Fed(val food: Food = Food.CARROT) : PetEvent
    data object Petted : PetEvent
    /** Début d'une partie (refusé s'il est trop fatigué ou endormi). */
    data object PlayStarted : PetEvent
    /** Une action pendant le jeu (balle frappée, frisbee attrapé…). */
    data class PlayHit(val toy: Toy, val amount: Float = 2f) : PetEvent
    data object Bathed : PetEvent
    data object PoopRemoved : PetEvent
    data object Healed : PetEvent
    /** Il découvre une nouvelle application au premier plan. */
    data object AppVisited : PetEvent
    /** Atterrissage brutal au sol. */
    data object HardLanding : PetEvent
    data object FellAsleep : PetEvent
    /** Réveillé ; [grumpy] = de force (caresse, glisser) : ça le contrarie. */
    data class Woken(val grumpy: Boolean) : PetEvent
    data object Caged : PetEvent
    data object Released : PetEvent
    /** Trouvé à cache-cache. */
    data object Found : PetEvent
    /** Personne ne l'a trouvé à cache-cache. */
    data object NotFound : PetEvent

    // --- Évolution 4a : stations ---
    /** Il dort dans le lit depuis [hours] heures (en plus du temps qui passe) : énergie en plus. */
    data class Rested(val hours: Float) : PetEvent
    /** Un tour de roue (refusé s'il est trop fatigué ou endormi). */
    data object WheelRun : PetEvent
    /** Il a grignoté et s'est roulé dans le jardin. */
    data object Grazed : PetEvent
    /** Brossé (menu) : un peu plus propre, content. */
    data object Brushed : PetEvent
}

/** Conséquences d'un événement, à mettre en scène par l'appelant. */
sealed interface PetEffect {
    /** [n] crottes sont apparues. */
    data class PoopSpawned(val n: Int) : PetEffect
    /** L'animal refuse (repu, trop fatigué) : les jauges n'ont pas changé. */
    data object Refused : PetEffect
    /** Il s'est réveillé de lui-même (énergie pleine). */
    data object WokeUp : PetEffect
}

/** Ce que sait de l'animal la règle, en plus de ses jauges (son caractère). */
data class Traits(val personality: Personality = Personality.CUDDLY, val favoriteToy: Toy = Toy.BALL)

/**
 * Les règles des jauges, pures : `apply(stats, event, now, traits, rules) → Result(stats, effects)`.
 * Les bornes sont appliquées à un seul endroit (fin de [apply]). Les taux, l'ordre des calculs et les seuils
 * sont ceux de l'ancien `PetState` (testés par `PetRulesTest`).
 */
object PetRules {

    data class Result(val stats: PetStats, val effects: List<PetEffect> = emptyList()) {
        val refused: Boolean get() = PetEffect.Refused in effects
        val newPoops: Int get() = effects.sumOf { (it as? PetEffect.PoopSpawned)?.n ?: 0 }
    }

    fun apply(
        stats: PetStats,
        event: PetEvent,
        now: Long,
        traits: Traits = Traits(),
        rules: GameRules = GameRules.DEFAULT,
    ): Result {
        val p = traits.personality
        val s = stats
        val r = rules
        return when (event) {
            is PetEvent.Elapsed -> elapsed(s, minOf(event.hours, r.maxElapsedHours), p, r)
            is PetEvent.Fed -> {
                val food = event.food
                if (s.satiety > r.fullAbove && food !in r.alwaysWelcome) return refused(s)
                done(s.copy(
                    satiety = s.satiety + food.satiety,
                    happiness = s.happiness + food.happiness * p.mod(StatMod.FOOD_JOY),
                    health = s.health + food.health,
                    cleanliness = s.cleanliness - r.eatingCleanlinessCost,
                ), r)
            }
            PetEvent.Petted -> {
                // Les caresses répétées rapportent de moins en moins
                val gain = (if (now - s.lastPetAt < r.petCooldownMs) r.petJoyRepeated else r.petJoy) * p.mod(StatMod.PET_JOY)
                done(s.copy(lastPetAt = now, happiness = s.happiness + gain), r)
            }
            PetEvent.PlayStarted -> {
                if (s.energy < r.playMinEnergy || s.sleeping) return refused(s)
                done(s.copy(happiness = s.happiness + r.playStartJoy, energy = s.energy - r.playStartEnergyCost), r)
            }
            is PetEvent.PlayHit -> {
                var g = event.amount * p.mod(StatMod.PLAY_JOY)
                if (event.toy == traits.favoriteToy) g *= r.favoriteToyFactor
                done(s.copy(
                    happiness = s.happiness + g,
                    energy = s.energy - r.playHitEnergyCost,
                    satiety = s.satiety - r.playHitSatietyCost,
                ), r)
            }
            PetEvent.Bathed -> done(s.copy(cleanliness = r.max, happiness = s.happiness + r.bathJoy), r)
            PetEvent.PoopRemoved -> done(s.copy(
                poops = if (s.poops > 0) s.poops - 1 else s.poops,
                cleanliness = s.cleanliness + r.poopRemovedCleanliness,
                happiness = s.happiness + r.poopRemovedJoy,
            ), r)
            PetEvent.Healed -> done(s.copy(health = s.health + r.healHealth, happiness = s.happiness - r.healJoyCost), r)
            PetEvent.AppVisited -> done(s.copy(
                visitedApps = s.visitedApps + 1,
                happiness = s.happiness + r.appVisitJoy * p.mod(StatMod.APP_VISIT_JOY),
            ), r)
            PetEvent.HardLanding -> done(s.copy(happiness = s.happiness - r.hardLandingJoyCost), r)
            PetEvent.FellAsleep -> done(s.copy(sleeping = true), r)
            is PetEvent.Woken -> done(s.copy(
                sleeping = false,
                happiness = if (event.grumpy) s.happiness - r.wokenJoyCost else s.happiness,
            ), r)
            PetEvent.Caged -> done(s.copy(caged = true), r)
            PetEvent.Released -> done(s.copy(caged = false), r)
            PetEvent.Found -> done(s.copy(happiness = s.happiness + r.foundJoy), r)
            PetEvent.NotFound -> done(s.copy(happiness = s.happiness - r.notFoundJoyCost), r)
            is PetEvent.Rested -> {
                if (!s.sleeping || event.hours <= 0f) return Result(s)
                done(s.copy(energy = s.energy + r.bedEnergyGainPerH * minOf(event.hours, r.maxElapsedHours)), r)
            }
            PetEvent.WheelRun -> {
                if (s.energy < r.playMinEnergy || s.sleeping) return refused(s)
                done(s.copy(
                    energy = s.energy - r.wheelEnergyCost,
                    happiness = s.happiness + r.wheelJoy * p.mod(StatMod.PLAY_JOY),
                    satiety = s.satiety - r.wheelSatietyCost,
                ), r)
            }
            PetEvent.Grazed -> done(s.copy(
                happiness = s.happiness + r.gardenJoy,
                satiety = s.satiety + r.gardenSatiety,
                cleanliness = s.cleanliness - r.gardenCleanlinessCost,
            ), r)
            PetEvent.Brushed -> done(s.copy(
                cleanliness = s.cleanliness + r.brushCleanliness,
                happiness = s.happiness + r.brushJoy * p.mod(StatMod.PET_JOY),
            ), r)
        }
    }

    private fun elapsed(s0: PetStats, hours: Float, p: Personality, r: GameRules): Result {
        if (hours <= 0f) return Result(s0)
        var s = s0
        val sleepFactor = if (s.sleeping) r.sleepSlowdown else 1f
        val satiety = s.satiety - r.satietyLossPerH * hours * sleepFactor * p.mod(StatMod.SATIETY_LOSS)
        val happiness = s.happiness - (if (s.poops > 0) r.happinessLossWithPoopPerH else r.happinessLossPerH) * hours *
            sleepFactor * p.mod(StatMod.HAPPINESS_LOSS) * (if (s.caged) r.cagedHappinessFactor else 1f)
        val cleanliness = s.cleanliness - (r.cleanlinessLossPerH + s.poops * r.cleanlinessLossPerPoopPerH) * hours
        val energy = s.energy + if (s.sleeping) r.energyGainAsleepPerH * hours else -r.energyLossPerH * hours * p.mod(StatMod.ENERGY_LOSS)

        // Santé : baisse si négligé, remonte doucement si tout va bien
        val neglect = (if (satiety < r.neglectBelow) 1 else 0) + (if (cleanliness < r.neglectBelow) 1 else 0) +
            (if (happiness < r.neglectBelow) 1 else 0)
        val health = s.health + if (neglect > 0) -r.healthLossPerNeglectPerH * neglect * hours else r.healthGainPerH * hours

        // Les crottes : environ une toutes les 3 h quand on a mangé
        var poops = s.poops
        var acc = s.poopAccumulator
        var newPoops = 0
        if (!s.sleeping && satiety > r.poopAboveSatiety) {
            acc += hours / r.poopEveryHours
            while (acc >= 1f && poops < r.maxPoops) {
                acc -= 1f
                poops++
                newPoops++
            }
            // Écran plein : au plus une crotte d'avance (sinon tout ce qu'on ramasse reviendrait d'un coup)
            if (poops >= r.maxPoops) acc = acc.coerceAtMost(1f)
        }
        val wakes = s.sleeping && energy >= r.max
        s = s.copy(
            satiety = satiety, happiness = happiness, cleanliness = cleanliness, energy = energy, health = health,
            poops = poops, poopAccumulator = acc, sleeping = s.sleeping && !wakes,
        )
        val effects = buildList {
            if (newPoops > 0) add(PetEffect.PoopSpawned(newPoops))
            if (wakes) add(PetEffect.WokeUp)
        }
        return Result(clamp(s, r), effects)
    }

    private fun refused(s: PetStats) = Result(s, listOf(PetEffect.Refused))

    private fun done(s: PetStats, r: GameRules) = Result(clamp(s, r))

    /** Le seul endroit où les jauges sont bornées. */
    fun clamp(s: PetStats, r: GameRules = GameRules.DEFAULT): PetStats = s.copy(
        satiety = s.satiety.coerceIn(r.min, r.max), happiness = s.happiness.coerceIn(r.min, r.max),
        energy = s.energy.coerceIn(r.min, r.max), cleanliness = s.cleanliness.coerceIn(r.min, r.max),
        health = s.health.coerceIn(r.min, r.max),
    )

    fun isSick(s: PetStats, r: GameRules = GameRules.DEFAULT): Boolean = s.health < r.sickBelow

    /** Humeur globale (par ordre de priorité). */
    fun mood(s: PetStats, r: GameRules = GameRules.DEFAULT): Mood = when {
        s.sleeping -> Mood.SLEEPY
        isSick(s, r) -> Mood.SICK
        s.satiety < r.hungryBelow -> Mood.HUNGRY
        s.energy < r.sleepyBelow -> Mood.SLEEPY
        s.cleanliness < r.dirtyBelow || s.poops >= r.dirtyPoops -> Mood.DIRTY
        s.happiness < r.sadBelow -> Mood.SAD
        s.happiness > r.happyAbove && s.satiety > r.happySatietyAbove -> Mood.HAPPY
        else -> Mood.OK
    }
}
