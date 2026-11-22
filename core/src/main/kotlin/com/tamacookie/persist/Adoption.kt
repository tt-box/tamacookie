package com.tamacookie.persist

import com.tamacookie.Identity
import com.tamacookie.Look
import com.tamacookie.Personality
import com.tamacookie.PetId
import com.tamacookie.PetStats
import com.tamacookie.Traits
import com.tamacookie.feature.toy.Toy

/**
 * Choix de l'écran d'adoption (Kotlin pur) : un nouvel animal ([isNew]) ou la personnalisation du résident
 * (nom et apparence seulement : le caractère ne change pas).
 */
data class Adoption(
    val name: String,
    val look: Look,
    val personality: Personality,
    val favoriteToy: Toy,
    val isNew: Boolean,
) {
    /** L'animal qui en résulte : nouveau (nouvel id, jauges neuves, né [now]) ou [previous] personnalisé. */
    fun apply(previous: PetRecord?, newId: () -> PetId, now: Long): PetRecord =
        if (isNew || previous == null) {
            PetRecord(
                identity = Identity(newId(), name, bornAt = now),
                traits = Traits(personality, favoriteToy),
                look = look,
                stats = PetStats(),
                adopted = true,
                lastUpdate = now,
            )
        } else {
            previous.copy(identity = previous.identity.copy(name = name), look = look, adopted = true)
        }

    companion object {
        /**
         * Nom saisi : nettoyé comme un nom reçu d'un autre téléphone ([PetSnapshot.cleanName] : contrôles, sens
         * d'écriture, espaces, troncature sans couper un emoji), null s'il est vide.
         */
        fun cleanName(raw: String?): String? =
            if (raw.isNullOrBlank()) null else PetSnapshot.cleanName(raw).takeIf { it.isNotBlank() }
    }
}
