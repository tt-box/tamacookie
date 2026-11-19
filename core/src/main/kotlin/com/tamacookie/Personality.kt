package com.tamacookie

/**
 * Caractère de l'animal, choisi à l'adoption (sauvegardé par son nom d'enum : ne pas renommer).
 * [modifiers] : facteurs appliqués par [PetRules] (absent = 1). Le reste de son influence (seuil d'attention
 * des bêtises, durée des parties, vitesse de marche, gourmandise au coffre…) est porté par l'espèce
 * (`Species.behavior` → `BehaviorMods`). Nouveau caractère : **à la fin** (voir docs/recettes.md).
 */
enum class Personality(val label: String, val emoji: String, val desc: String, val modifiers: Map<StatMod, Float>) {
    PLAYFUL("Joueur", "🎾", "Adore jouer, s'ennuie vite",
        mapOf(StatMod.HAPPINESS_LOSS to 1.25f, StatMod.PLAY_JOY to 1.5f)),
    GLUTTON("Gourmand", "🥕", "A tout le temps faim, adore manger",
        mapOf(StatMod.SATIETY_LOSS to 1.35f, StatMod.FOOD_JOY to 2f)),
    LAZY("Paresseux", "😴", "Fait de longues siestes, marche lentement",
        mapOf(StatMod.ENERGY_LOSS to 1.3f)),
    CURIOUS("Curieux", "🔍", "Explore les icônes et commente tes apps",
        mapOf(StatMod.APP_VISIT_JOY to 3f)),
    CUDDLY("Câlin", "💕", "Réclame des caresses, très affectueux",
        mapOf(StatMod.HAPPINESS_LOSS to 1.2f, StatMod.PET_JOY to 1.6f)),
    // --- Évolution 4b (ajoutés à la fin : noms sauvegardés et échangés) ---
    SHY("Peureux", "🙈", "Sursaute pour un rien, se cache volontiers, met du temps à se lier",
        mapOf(StatMod.HAPPINESS_LOSS to 1.15f, StatMod.PET_JOY to 1.2f)),
    PRANKSTER("Farceur", "😜", "Fait plein de bêtises, ne supporte pas qu'on l'ignore",
        mapOf(StatMod.HAPPINESS_LOSS to 1.1f, StatMod.PLAY_JOY to 1.2f)),
    EXPLORER("Explorateur", "🧭", "Visite les pages, saute sur les icônes, te suit partout",
        mapOf(StatMod.ENERGY_LOSS to 1.15f, StatMod.APP_VISIT_JOY to 2f)),
    ;

    /** Facteur de ce caractère pour [m] (1 = sans effet). */
    fun mod(m: StatMod): Float = modifiers[m] ?: 1f
}
