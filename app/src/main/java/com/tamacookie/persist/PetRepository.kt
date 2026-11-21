package com.tamacookie.persist

import com.tamacookie.Tuning
import com.tamacookie.species.Species
import android.content.SharedPreferences
import com.tamacookie.Accessory
import com.tamacookie.AccColor
import com.tamacookie.Clock
import com.tamacookie.Ears
import com.tamacookie.EyeColor
import com.tamacookie.Fur
import com.tamacookie.Identity
import com.tamacookie.Look
import com.tamacookie.Pattern
import com.tamacookie.Personality
import com.tamacookie.PetId
import com.tamacookie.PetStats
import com.tamacookie.Traits
import com.tamacookie.feature.toy.Toy
import java.util.UUID

/**
 * Adaptateur [PetStore] sur SharedPreferences : fichier [com.tamacookie.Prefs.PETS_FILE], clés namespacées
 * par [PetId] (schéma [SCHEMA_VERSION], décrit dans CLAUDE.md). Les réglages (bêtises, dock, stations, pages,
 * activation) restent dans l'ancien fichier `"pet"` ([settings]), qui n'est lu ici que par les migrations.
 *
 * Construire par [open] : les migrations y sont jouées une fois, avant toute lecture.
 * Les écritures courantes sont asynchrones (`apply`), celles des migrations synchrones (`commit`).
 */
class PetRepository private constructor(
    private val prefs: SharedPreferences,
    private val clock: Clock,
    private val newId: () -> String,
) : PetStore {

    companion object {
        const val SCHEMA_VERSION = 1

        const val KEY_SCHEMA = "schema_version"
        const val KEY_RESIDENT = "resident"
        /** Identifiants des animaux, séparés par [ID_SEP], dans l'ordre d'arrivée. */
        const val KEY_IDS = "pets"
        private const val ID_SEP = ","

        /** Préfixe des clés d'un animal : `pet.<id>.<champ>`. */
        fun prefix(id: PetId) = "pet.${id.value}."

        /** Ouvre le magasin : migre [prefs] (et l'ancien fichier [settings]) jusqu'à [SCHEMA_VERSION]. */
        fun open(
            prefs: SharedPreferences,
            settings: SharedPreferences,
            clock: Clock,
            newId: () -> String = { UUID.randomUUID().toString() },
        ): PetRepository {
            PetMigrations.run(prefs, settings, clock, newId)
            return PetRepository(prefs, clock, newId)
        }

        /** Un identifiant valide : pas de séparateur de liste ni de clé ([PetStore.isValidId]). */
        fun isValidId(s: String) = PetStore.isValidId(s)
    }

    /** Un nouvel identifiant d'animal (UUID). */
    fun newPetId(): PetId = PetId(newId())

    override fun ids(): List<PetId> = readIds(prefs)

    override fun residentId(): PetId? = prefs.getString(KEY_RESIDENT, null)?.let(::PetId)?.takeIf { it in ids() }

    override fun load(id: PetId): PetRecord? = if (id in ids()) PetFields.read(prefs, id) else null

    override fun save(record: PetRecord): Boolean {
        if (record.id !in ids()) return false
        val e = prefs.edit()
        PetFields.write(e, record.copy(lastUpdate = clock.wallMs()))
        e.apply()
        return true
    }

    override fun adopt(record: PetRecord, replacing: PetId?) {
        require(isValidId(record.id.value)) { "identifiant invalide" }
        val e = prefs.edit()
        val ids = ids().filter { it != replacing && it != record.id } + record.id
        if (replacing != null && replacing != record.id) removeKeys(e, replacing)
        PetFields.write(e, record.copy(lastUpdate = clock.wallMs()))
        e.putString(KEY_IDS, ids.joinToString(ID_SEP) { it.value })
        e.putString(KEY_RESIDENT, record.id.value)
        e.apply()
    }

    override fun addCompanion(record: PetRecord) {
        require(isValidId(record.id.value)) { "identifiant invalide" }
        require(record.id != residentId()) { "le résident n'est pas son propre compagnon" }
        val e = prefs.edit()
        val ids = ids().filter { it != record.id } + record.id
        PetFields.write(e, record.copy(lastUpdate = clock.wallMs()))
        e.putString(KEY_IDS, ids.joinToString(ID_SEP) { it.value })
        e.apply()
    }

    /**
     * Adopte un compagnon **quand le service ne tourne pas** (sinon : commande `ACTION_ADOPT_COMPANION`).
     * Toujours un nouvel animal (nouvel id, jauges neuves) ; null si le plafond [Tuning.MAX_PETS] est atteint
     * ou s'il n'y a pas encore de résident.
     */
    fun adoptCompanion(a: Adoption): PetRecord? {
        if (residentId() == null || ids().size >= Tuning.MAX_PETS) return null
        val rec = a.copy(isNew = true).apply(null, ::newPetId, clock.wallMs())
        addCompanion(rec)
        return rec
    }

    /** Retire le compagnon [id] **quand le service ne tourne pas** ; false = c'est le résident (ou inconnu). */
    fun removeCompanion(id: PetId): Boolean {
        if (id == residentId() || id !in ids()) return false
        remove(id)
        return true
    }

    /**
     * Le résident, créé (jauges neuves, non adopté) s'il n'existe pas encore : premier lancement.
     * Écrit seulement dans ce cas ; aucun rattrapage hors-ligne ici.
     */
    fun residentOrCreate(): PetRecord = loadResident() ?: PetRecord(
        identity = Identity(newPetId(), bornAt = clock.wallMs()),
        lastUpdate = clock.wallMs(),
    ).also { adopt(it, replacing = null) }

    /**
     * Applique l'écran d'adoption **quand le service ne tourne pas** (sinon c'est lui l'autorité :
     * commande `ACTION_ADOPT`). Un nouvel animal remplace le résident (ses clés sont effacées).
     */
    fun adopt(a: Adoption): PetRecord {
        val prev = loadResident()
        val rec = a.apply(prev, ::newPetId, clock.wallMs())
        if (prev != null && rec.id == prev.id) save(rec) else adopt(rec, replacing = prev?.id)
        return rec
    }

    /** Renomme le résident **quand le service ne tourne pas** (sinon : commande `ACTION_SET_NAME`). */
    fun rename(name: String): Boolean {
        val prev = loadResident() ?: return false
        return save(prev.copy(identity = prev.identity.copy(name = name)))
    }

    override fun remove(id: PetId) {
        val e = prefs.edit()
        removeKeys(e, id)
        e.putString(KEY_IDS, ids().filter { it != id }.joinToString(ID_SEP) { it.value })
        if (prefs.getString(KEY_RESIDENT, null) == id.value) e.remove(KEY_RESIDENT)
        e.apply()
    }

    override fun makeResident(id: PetId) {
        require(id in ids()) { "animal inconnu" }
        prefs.edit().putString(KEY_RESIDENT, id.value).apply()
    }

    private fun removeKeys(e: SharedPreferences.Editor, id: PetId) {
        val p = prefix(id)
        for (k in prefs.all.keys) if (k.startsWith(p)) e.remove(k)
    }

    private fun readIds(p: SharedPreferences): List<PetId> =
        p.getString(KEY_IDS, null).orEmpty().split(ID_SEP).filter { it.isNotEmpty() }.map(::PetId)
}

/** Champs d'un animal (schéma 1) : `pet.<id>.<champ>`. Les valeurs inconnues retombent sur les défauts. */
internal object PetFields {
    const val NAME = "name"
    const val SPECIES = "species"
    const val BORN_AT = "bornAt"
    const val LAST_UPDATE = "lastUpdate"
    const val ADOPTED = "adopted"
    const val PERSONALITY = "personality"
    const val FAVORITE_TOY = "favoriteToy"
    const val FUR = "look.fur"
    const val EARS = "look.ears"
    const val EYES = "look.eyes"
    const val PATTERN = "look.pattern"
    const val ACCESSORY = "look.accessory"
    const val ACC_COLOR = "look.accColor"
    const val SATIETY = "satiety"
    const val HAPPINESS = "happiness"
    const val ENERGY = "energy"
    const val CLEANLINESS = "cleanliness"
    const val HEALTH = "health"
    const val SLEEPING = "sleeping"
    const val POOPS = "poops"
    const val POOP_ACC = "poopAcc"
    const val VISITED_APPS = "visitedApps"
    const val CAGED = "caged"

    fun write(e: SharedPreferences.Editor, r: PetRecord) {
        val p = PetRepository.prefix(r.id)
        val s = r.stats
        e.putString(p + NAME, r.identity.name)
            .putString(p + SPECIES, r.identity.species)
            .putLong(p + BORN_AT, r.identity.bornAt)
            .putLong(p + LAST_UPDATE, r.lastUpdate)
            .putBoolean(p + ADOPTED, r.adopted)
            .putString(p + PERSONALITY, r.traits.personality.name)
            .putString(p + FAVORITE_TOY, r.traits.favoriteToy.name)
            .putString(p + FUR, r.look.fur.id)
            .putString(p + EARS, r.look.ears.id)
            .putString(p + EYES, r.look.eyes.id)
            .putString(p + PATTERN, r.look.pattern.id)
            .putString(p + ACCESSORY, r.look.accessory.id)
            .putString(p + ACC_COLOR, r.look.accColor.id)
            .putFloat(p + SATIETY, s.satiety)
            .putFloat(p + HAPPINESS, s.happiness)
            .putFloat(p + ENERGY, s.energy)
            .putFloat(p + CLEANLINESS, s.cleanliness)
            .putFloat(p + HEALTH, s.health)
            .putBoolean(p + SLEEPING, s.sleeping)
            .putInt(p + POOPS, s.poops)
            .putFloat(p + POOP_ACC, s.poopAccumulator)
            .putInt(p + VISITED_APPS, s.visitedApps)
            .putBoolean(p + CAGED, s.caged)
    }

    fun read(prefs: SharedPreferences, id: PetId): PetRecord {
        val p = PetRepository.prefix(id)
        val d = PetStats()
        val dl = Look()
        val bornAt = prefs.getLong(p + BORN_AT, 0L)
        return PetRecord(
            identity = Identity(
                id = id,
                name = prefs.getString(p + NAME, null) ?: Identity.DEFAULT_NAME,
                bornAt = bornAt,
                species = prefs.getString(p + SPECIES, null) ?: Species.DEFAULT.id,
            ),
            traits = Traits(
                personality = enumOr(prefs.getString(p + PERSONALITY, null), Personality.CUDDLY),
                favoriteToy = enumOr(prefs.getString(p + FAVORITE_TOY, null), Toy.BALL),
            ),
            look = Look(
                fur = Look.byId(Fur.entries, prefs.getString(p + FUR, null), dl.fur),
                ears = Look.byId(Ears.entries, prefs.getString(p + EARS, null), dl.ears),
                eyes = Look.byId(EyeColor.entries, prefs.getString(p + EYES, null), dl.eyes),
                pattern = Look.byId(Pattern.entries, prefs.getString(p + PATTERN, null), dl.pattern),
                accessory = Look.byId(Accessory.entries, prefs.getString(p + ACCESSORY, null), dl.accessory),
                accColor = Look.byId(AccColor.entries, prefs.getString(p + ACC_COLOR, null), dl.accColor),
            ),
            stats = PetStats(
                satiety = prefs.getFloat(p + SATIETY, d.satiety),
                happiness = prefs.getFloat(p + HAPPINESS, d.happiness),
                energy = prefs.getFloat(p + ENERGY, d.energy),
                cleanliness = prefs.getFloat(p + CLEANLINESS, d.cleanliness),
                health = prefs.getFloat(p + HEALTH, d.health),
                sleeping = prefs.getBoolean(p + SLEEPING, d.sleeping),
                poops = prefs.getInt(p + POOPS, d.poops),
                poopAccumulator = prefs.getFloat(p + POOP_ACC, d.poopAccumulator),
                visitedApps = prefs.getInt(p + VISITED_APPS, d.visitedApps),
                caged = prefs.getBoolean(p + CAGED, d.caged),
            ),
            adopted = prefs.getBoolean(p + ADOPTED, false),
            lastUpdate = prefs.getLong(p + LAST_UPDATE, bornAt),
        )
    }

    inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E = PetStore.enumOr(name, default)
}
