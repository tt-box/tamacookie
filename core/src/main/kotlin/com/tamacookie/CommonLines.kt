package com.tamacookie

/**
 * Répliques partagées par plusieurs fonctionnalités, ou dites par l'hôte en réponse à une commande (adoption,
 * départ d'un compagnon) — indépendantes de l'espèce. Les autres répliques sont dans leur fonctionnalité
 * (`feature/…`, `social/…`), dans le `companion object` de leur contrôleur, ou dans
 * [com.tamacookie.species.SpeciesLines] quand elles nomment l'espèce (voir CLAUDE.md, « Textes »).
 */
object CommonLines {
    /** Brosse refusée : il dort. */
    const val ASLEEP = "Chut… il dort 💤"
    /** Adoption d'un compagnon refusée : la maison est pleine (`Tuning.MAX_PETS`). */
    const val HOUSE_FULL = "On est déjà assez nombreux ici ! 🐾"

    /** Un compagnon quitte le téléphone (dit par le résident). */
    fun goodbye(name: String) = "Au revoir $name ! 👋"
}
