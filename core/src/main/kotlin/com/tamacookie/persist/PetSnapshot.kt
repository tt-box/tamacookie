package com.tamacookie.persist

import com.tamacookie.species.Species
import com.tamacookie.Accessory
import com.tamacookie.AccColor
import com.tamacookie.Ears
import com.tamacookie.EyeColor
import com.tamacookie.Fur
import com.tamacookie.GameRules
import com.tamacookie.Identity
import com.tamacookie.Look
import com.tamacookie.Pattern
import com.tamacookie.Personality
import com.tamacookie.PetId
import com.tamacookie.PetRules
import com.tamacookie.PetStats
import com.tamacookie.Traits
import com.tamacookie.feature.toy.Toy
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Un animal sous forme **portable et versionnée** (Kotlin pur) : base du futur échange Bluetooth (visite,
 * transfert). Ne contient que ce qui a un sens sur un autre téléphone : identité, espèce, nom, caractère,
 * apparence (par identifiants stables) et jauges — pas la cage, les crottes, le sommeil ni les dates locales.
 *
 * Format texte UTF-8, une ligne par champ, valeurs encodées (`URLEncoder`) :
 * ```
 * tamacookie-pet 1
 * id=…
 * species=rabbit
 * name=Noisette
 * …
 * ```
 * [decode] **valide** tout ce qui vient d'un pair : taille, en-tête, version, champs en double, identifiant,
 * espèce (rejetés) ; ids d'apparence, caractère, jouet inconnus (remplacés par défaut) ; jauges bornées,
 * nombres invalides (défaut), nom nettoyé et tronqué. Les champs inconnus sont ignorés (version future).
 */
data class PetSnapshot(
    val id: PetId,
    val species: String,
    val name: String,
    val bornAt: Long,
    val traits: Traits,
    val look: Look,
    val stats: PetStats,
) {
    /** Ce qu'il devient chez nous (jauges seulement ; le reste est local). Naissance bornée à [now]. */
    fun toRecord(now: Long): PetRecord = PetRecord(
        identity = Identity(id, name, bornAt.coerceIn(0L, now), species),
        traits = traits, look = look, stats = stats, adopted = true, lastUpdate = now,
    )

    fun encode(): String = buildString {
        append(MAGIC).append(' ').append(VERSION).append('\n')
        fun put(k: String, v: Any) { append(k).append('=').append(URLEncoder.encode(v.toString(), "UTF-8")).append('\n') }
        put(ID, id.value)
        put(SPECIES, species)
        put(NAME, name)
        put(BORN_AT, bornAt)
        put(PERSONALITY, traits.personality.name)
        put(FAVORITE_TOY, traits.favoriteToy.name)
        put(FUR, look.fur.id); put(EARS, look.ears.id); put(EYES, look.eyes.id)
        put(PATTERN, look.pattern.id); put(ACCESSORY, look.accessory.id); put(ACC_COLOR, look.accColor.id)
        put(SATIETY, stats.satiety); put(HAPPINESS, stats.happiness); put(ENERGY, stats.energy)
        put(CLEANLINESS, stats.cleanliness); put(HEALTH, stats.health)
    }

    fun toBytes(): ByteArray = encode().toByteArray(Charsets.UTF_8)

    /** Résultat d'un décodage : accepté, ou rejeté avec la raison. */
    sealed interface Decoded {
        data class Ok(val snapshot: PetSnapshot) : Decoded
        data class Rejected(val reason: Reason) : Decoded
    }

    enum class Reason { TOO_LARGE, NOT_A_PET, UNSUPPORTED_VERSION, MALFORMED, DUPLICATE_FIELD, BAD_ID, UNKNOWN_SPECIES }

    companion object {
        const val MAGIC = "tamacookie-pet"
        const val VERSION = 1
        /** Taille maximale acceptée (octets UTF-8) : un animal encodé fait ~400 octets. */
        const val MAX_BYTES = 2048
        /** Espèces connues (registre [Species.ALL]) : une autre est rejetée. */
        val SPECIES_KNOWN: Set<String> get() = Species.ALL.mapTo(HashSet()) { it.id }

        private const val ID = "id"
        private const val SPECIES = "species"
        private const val NAME = "name"
        private const val BORN_AT = "bornAt"
        private const val PERSONALITY = "personality"
        private const val FAVORITE_TOY = "favoriteToy"
        private const val FUR = "look.fur"
        private const val EARS = "look.ears"
        private const val EYES = "look.eyes"
        private const val PATTERN = "look.pattern"
        private const val ACCESSORY = "look.accessory"
        private const val ACC_COLOR = "look.accColor"
        private const val SATIETY = "satiety"
        private const val HAPPINESS = "happiness"
        private const val ENERGY = "energy"
        private const val CLEANLINESS = "cleanliness"
        private const val HEALTH = "health"

        fun of(r: PetRecord) = PetSnapshot(
            id = r.id, species = r.identity.species, name = r.identity.name, bornAt = r.identity.bornAt,
            traits = r.traits, look = r.look,
            stats = r.stats.let { PetStats(it.satiety, it.happiness, it.energy, it.cleanliness, it.health) },
        )

        fun fromBytes(bytes: ByteArray): Decoded =
            if (bytes.size > MAX_BYTES) Decoded.Rejected(Reason.TOO_LARGE) else decode(String(bytes, Charsets.UTF_8))

        fun decode(text: String, rules: GameRules = GameRules.DEFAULT): Decoded {
            if (text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return reject(Reason.TOO_LARGE)
            val lines = text.split('\n').filter { it.isNotEmpty() }
            val header = lines.firstOrNull()?.split(' ') ?: return reject(Reason.NOT_A_PET)
            if (header.size != 2 || header[0] != MAGIC) return reject(Reason.NOT_A_PET)
            val version = header[1].toIntOrNull() ?: return reject(Reason.NOT_A_PET)
            if (version < 1 || version > VERSION) return reject(Reason.UNSUPPORTED_VERSION)

            val f = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val eq = line.indexOf('=')
                if (eq <= 0) return reject(Reason.MALFORMED)
                val k = line.substring(0, eq)
                val v = try { URLDecoder.decode(line.substring(eq + 1), "UTF-8") } catch (_: IllegalArgumentException) {
                    return reject(Reason.MALFORMED)
                }
                if (f.put(k, v) != null) return reject(Reason.DUPLICATE_FIELD)
            }

            val id = f[ID]?.takeIf(PetStore::isValidId) ?: return reject(Reason.BAD_ID)
            val species = f[SPECIES]?.takeIf { it in SPECIES_KNOWN } ?: return reject(Reason.UNKNOWN_SPECIES)
            val d = PetStats()
            val dl = Look()
            fun gauge(k: String, def: Float) = f[k]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: def
            val stats = PetRules.clamp(
                PetStats(
                    satiety = gauge(SATIETY, d.satiety), happiness = gauge(HAPPINESS, d.happiness),
                    energy = gauge(ENERGY, d.energy), cleanliness = gauge(CLEANLINESS, d.cleanliness),
                    health = gauge(HEALTH, d.health),
                ),
                rules,
            )
            return Decoded.Ok(
                PetSnapshot(
                    id = PetId(id),
                    species = species,
                    name = cleanName(f[NAME]),
                    bornAt = (f[BORN_AT]?.toLongOrNull() ?: 0L).coerceAtLeast(0L),
                    traits = Traits(
                        personality = PetStore.enumOr(f[PERSONALITY], Personality.CUDDLY),
                        favoriteToy = PetStore.enumOr(f[FAVORITE_TOY], Toy.BALL),
                    ),
                    look = Look(
                        fur = Look.byId(Fur.entries, f[FUR], dl.fur),
                        ears = Look.byId(Ears.entries, f[EARS], dl.ears),
                        eyes = Look.byId(EyeColor.entries, f[EYES], dl.eyes),
                        pattern = Look.byId(Pattern.entries, f[PATTERN], dl.pattern),
                        accessory = Look.byId(Accessory.entries, f[ACCESSORY], dl.accessory),
                        accColor = Look.byId(AccColor.entries, f[ACC_COLOR], dl.accColor),
                    ),
                    stats = stats,
                ),
            )
        }

        /** Nom reçu : caractères de contrôle → espaces, caractères de format (sens d'écriture…) retirés, espaces normalisés, tronqué ; vide → nom par défaut. */
        fun cleanName(raw: String?): String {
            val s = raw.orEmpty().filter { it.category != CharCategory.FORMAT }
                .map { if (it.isISOControl()) ' ' else it }.joinToString("")
                .replace(Regex("\\s+"), " ").trim()
            // Tronqué sans couper un emoji (paire de substitution) en deux
            var n = s.take(Identity.MAX_NAME)
            if (n.isNotEmpty() && n.last().isHighSurrogate()) n = n.dropLast(1)
            return n.ifEmpty { Identity.DEFAULT_NAME }
        }

        private fun reject(r: Reason) = Decoded.Rejected(r)
    }
}
