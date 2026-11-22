package com.tamacookie.persist

import com.tamacookie.Identity
import com.tamacookie.Look
import com.tamacookie.PetId
import com.tamacookie.PetStats
import com.tamacookie.Traits

/** Ce qui est sauvegardé d'un animal (Kotlin pur). [lastUpdate] : dernière sauvegarde, pour le rattrapage hors-ligne. */
data class PetRecord(
    val identity: Identity,
    val traits: Traits = Traits(),
    val look: Look = Look(),
    val stats: PetStats = PetStats(),
    /** L'écran d'adoption a été validé (sinon : premier lancement, on le propose). */
    val adopted: Boolean = false,
    val lastUpdate: Long = identity.bornAt,
) {
    val id: PetId get() = identity.id
}

/**
 * Port de persistance des animaux. Seul `PetRepository` (Android) l'implémente : c'est la **seule** classe qui lit
 * ou écrit l'état des animaux. Les lectures n'ont pas d'effet de bord (le rattrapage hors-ligne est un
 * `PetRules.apply(Elapsed)` explicite de l'appelant).
 */
interface PetStore {
    /** Animaux enregistrés, dans l'ordre d'arrivée. */
    fun ids(): List<PetId>
    fun load(id: PetId): PetRecord?
    /** L'animal qui vit sur ce téléphone (null : jamais créé). */
    fun residentId(): PetId?
    fun loadResident(): PetRecord? = residentId()?.let(::load)

    /**
     * Enregistre [record] (horodaté par l'horloge du magasin). Ignoré (false) si l'animal n'est plus
     * enregistré : un ancien lapin remplacé par une adoption ne peut pas réapparaître.
     */
    fun save(record: PetRecord): Boolean

    /** Ajoute [record] et en fait le résident ; [replacing] (l'ancien résident) est effacé. */
    fun adopt(record: PetRecord, replacing: PetId?)

    /** Ajoute [record] comme compagnon du résident (le résident ne change pas). */
    fun addCompanion(record: PetRecord)

    /** Les compagnons (tous les animaux sauf le résident), dans l'ordre d'arrivée. */
    fun companions(): List<PetRecord> {
        val r = residentId()
        return ids().filter { it != r }.mapNotNull(::load)
    }

    fun remove(id: PetId)

    /** [id] (déjà enregistré) devient le résident ; l'ancien, s'il existe encore, devient un compagnon. */
    fun makeResident(id: PetId)

    /**
     * Rend **durables** (sur disque) les écritures précédentes, qui sont sinon différées ; false = échec. Le voyage
     * s'en sert avant de confirmer au pair ([com.tamacookie.travel.TravelRules]).
     */
    fun sync(): Boolean = true

    companion object {
        /** Un identifiant valide : pas de séparateur de liste ni de clé. */
        fun isValidId(s: String) = s.isNotEmpty() && s.length <= 64 && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        /** Valeur d'enum sauvegardée / reçue par son nom, ou [default] si inconnue (autre version, pair…). */
        inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
