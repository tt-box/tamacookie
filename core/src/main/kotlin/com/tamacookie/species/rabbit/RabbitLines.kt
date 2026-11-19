package com.tamacookie.species.rabbit

import com.tamacookie.AppCategory
import com.tamacookie.Mood
import com.tamacookie.Season
import com.tamacookie.species.SpeciesLines

/**
 * Répliques du lapin dites par la boucle et le toucher (bavardage, accueil, voyage, toucher…) :
 * [SpeciesLines] de [Rabbit]. Les répliques des fonctionnalités (stations, jouets, bêtises, pages) sont
 * dans leur fonctionnalité. Kotlin pur ; chaque liste est un tirage possible pour `Speech.pickFrom`.
 * Textes en français, en dur : le cœur ne lit pas les ressources Android (voir docs/textes-et-accessibilite.md).
 */
object RabbitLines : SpeciesLines {

    // --- Accueil, réveil, apparition ---
    override fun greeting(name: String, hungry: Boolean, hour: Int): String = when {
        hungry -> "Coucou… j'ai faim 🥺"
        hour < 11 -> "Bonjour ! ☀️ C'est moi, $name !"
        hour < 18 -> "Coucou ! C'est $name 🐰"
        else -> "Bonsoir ! 🌙 $name est là"
    }
    override fun newLook(name: String) = arrayOf("Je m'appelle $name ! ✨", "Tu aimes mon nouveau look ? ✨")
    override val WELCOME_BACK = arrayOf("Te revoilà ! 💕", "Coucou toi !", "Tu m'as manqué !")
    override val SHOWN = arrayOf("Coucou, me revoilà !", "Je suis là ! 🐰", "Hop !")

    // --- Bavardage selon l'humeur ---
    override fun chatter(mood: Mood): Array<String> = when (mood) {
        Mood.HUNGRY -> arrayOf("J'ai faim… 🥕", "Mon ventre gargouille…", "Une petite carotte ?")
        Mood.SLEEPY -> arrayOf("*bâille*", "Je suis fatigué…", "Une sieste me ferait du bien")
        Mood.DIRTY -> arrayOf("Je suis tout sale…", "Un bain ? 🫧", "Ça sent pas très bon ici…")
        Mood.SAD -> arrayOf("Je m'ennuie…", "Tu joues avec moi ?", "Un câlin ? 🥺")
        Mood.SICK -> arrayOf("Je ne me sens pas bien… 🤒", "Atchoum !", "J'ai besoin d'un médicament")
        Mood.HAPPY -> arrayOf("La vie est belle ! ✨", "♪ La la la ♫", "Je t'aime bien 💕", "Tu fais quoi ?", "Il fait beau sur ton écran !")
        Mood.OK -> arrayOf("Hmm hmm…", "*renifle*", "Tu fais quoi ?", "Je me promène 🐾")
    }

    override fun seasonal(season: Season): Array<String> = when (season) {
        Season.CHRISTMAS -> arrayOf("Joyeux Noël ! 🎄", "Il neige sur ton écran ! ❄️", "Le Père Noël aime les carottes ? 🎅")
        Season.HALLOWEEN -> arrayOf("Bouh ! 🎃", "Des bonbons ou une carotte ! 👻")
        Season.SPRING -> arrayOf("Ça sent le printemps ! 🌸", "Les fleurs sont de sortie 🌷")
        Season.NONE -> emptyArray()
    }

    // --- Vie quotidienne ---
    override val POOPED = arrayOf("Oups… 💩", "C'est pas moi !", "Hihi… pardon")
    override val WOKE_UP = arrayOf("Bien dormi ! ☀️", "*s'étire* Bonjour !", "Quelle bonne sieste !")
    override val ATE = arrayOf("Miam ! 🥕", "Trop bon !", "Merci ! *rot*", "Délicieux !")
    override val GOING_TO_SLEEP = arrayOf("Bonne nuit… 💤", "*bâille* Je fais une petite sieste", "Zzz…")

    // --- Plateformes et chutes ---
    override val PLATFORM_LOST = arrayOf("Oups !", "Whoaaa !", "Ça bouge !")
    override val HARD_LANDING_PLATFORM = arrayOf("Ouille ! Ça tourne…", "Aïe aïe aïe…")
    override val HARD_LANDING_GROUND = arrayOf("Ouille ! Ça tourne…", "Aïe aïe aïe…", "Doucement !")
    override fun onPlatform(label: String) =
        arrayOf("Je suis sur $label !", "Coucou depuis $label 👋", "La vue est belle d'ici !", "Hop, sur $label !")

    // --- Toucher, glisser, lancer ---
    override val FOUND_WHILE_DRAGGED = "Trouvé !! 🎉"
    override val DRAGGED = arrayOf("Waaah !", "Hé ! Pose-moi !", "Je voleeee !", "Hiii !")
    override val THROWN = arrayOf("Wiiiiii !", "Je vole !!", "Aaaaah !")
    override val WOKEN_BY_TAP = arrayOf("Hmpf… je dormais !", "Encore 5 minutes…", "Grrr… 😾")
    override val TICKLED = arrayOf("Ça chatouille !! 🤭", "Arrête, hihi !")
    override val PETTED = arrayOf("Hihi ! 💕", "Encore !", "J'adore les câlins", "Mmmh…", "Tu es gentil·le !", "*ronronne* (oui, les lapins aussi)")

    // --- Menu ---
    override val GETTING_UP = "*bâille* D'accord, je me lève…"
    override val NOT_TIRED = arrayOf("Je ne suis pas fatigué !", "Dodo ? Déjà ?!")
    override val SONGS = arrayOf("🎵 La la laaa… 🎶", "🎵 Une carotte, deux carottes… 🎶",
        "🎶 Je suis un lapin heureux ! 🎵", "🎵 Tralala, tralali… 🎶")

    // --- Coffre ---
    override val GOING_TO_CHEST = arrayOf("Je vais me servir ! 😋", "Hop, au coffre !",
        "Il y a sûrement des carottes là-dedans…", "Je sais où sont les jouets ! 🎁")

    // --- Voyage d'application en application ---
    override val FOLLOWING = arrayOf("J'arrive !", "Attends-moi !", "Hop, on change d'app !", "Je te suis ! 🐾")

    /**
     * Commentaires possibles à l'arrivée dans une app.
     * [category] : [AppCategory] (valeurs d'`ApplicationInfo.CATEGORY_*`).
     */
    override fun appComments(label: String, pkg: String, category: Int, isHome: Boolean): Array<String> {
        val p = pkg.lowercase()
        return when {
            isHome -> arrayOf("De retour à la maison 🏠", "Ah, l'écran d'accueil !", "On fait quoi maintenant ?")
            "camera" in p -> arrayOf("Prends-moi en photo ! 📸", "Je fais mon plus beau sourire")
            "clock" in p || "alarm" in p -> arrayOf("Il est l'heure de… manger ?", "Tic tac 🕰️")
            "store" in p || "vending" in p -> arrayOf("On fait les boutiques ? 🛍️", "Tu m'achètes une carotte ?")
            category == AppCategory.GAME -> arrayOf("Un jeu ! Je peux jouer ? 🎮", "Vas-y, bats le record !")
            category == AppCategory.VIDEO -> arrayOf("Pop-corn ? 🍿 Je regarde avec toi", "Chut, ça commence !")
            category == AppCategory.AUDIO -> arrayOf("J'adore cette musique ♪", "On danse ? ♫")
            category == AppCategory.SOCIAL -> arrayOf("Dis bonjour de ma part sur $label !", "Qui t'écrit ? 👀")
            category == AppCategory.MAPS -> arrayOf("On part en voyage ? 🗺️", "Je peux venir ?")
            category == AppCategory.NEWS -> arrayOf("Quoi de neuf dans le monde ?", "Des nouvelles des lapins ?")
            category == AppCategory.IMAGE -> arrayOf("Montre-moi tes photos !", "Il y a des photos de moi ?")
            category == AppCategory.PRODUCTIVITY -> arrayOf("Courage pour le travail 💪", "Je ne dérange pas… promis")
            else -> arrayOf("Coucou $label !", "Oh, $label ! On fait quoi ici ?", "Je visite $label 🐾", "C'est joli chez $label")
        }
    }

    // --- Textes d'interface qui nomment l'espèce ---
    override val ADOPT_PROMPT = "Touche pour adopter ton lapin"
}
