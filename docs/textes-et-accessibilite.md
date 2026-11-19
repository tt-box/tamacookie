# TamaCookie — textes et accessibilité

Détail des règles des étapes 8c (textes) et 8d (accessibilité).

## Textes : où ils vont, en ajouter un, traduire (étape 8c)

**Où vont les textes**

| Texte | Où | Exemple |
|---|---|---|
| Interface de l'app (activités, panneaux, notification et ses canaux, widget, tuile, toasts, manifeste) | `app/src/main/res/values/strings.xml`, clé `<écran>_<rôle>` | `main_rename`, `vet_diag`, `notif_feed` |
| Réplique d'une fonctionnalité (bulle) | l'`object` de la fonctionnalité (`feature/…`, `social/…`) | `VetStation.HEALED`, `HugSocial.ASK` |
| Réplique d'un contrôleur du cœur | son `companion object` | `ToyController.PLAY_OVER`, `PageTracker.WANDER` |
| Réplique partagée, ou réponse de l'hôte à une commande | `CommonLines` (cœur) | `CommonLines.ASLEEP`, `goodbye(nom)` |
| Réplique qui nomme l'espèce ou sa nourriture (« lapin », « carotte ») | `SpeciesLines` (une implémentation par espèce : `RabbitLines`) | `SONGS`, `GOING_TO_CHEST` |
| Texte d'interface calculé par le cœur | `NotifierTexts`, `WidgetTexts`, `TileTexts`, `MenuEntries`, `label` des enums | `NotifierTexts.title` |

Le cœur n'a pas accès aux ressources : ses textes restent en Kotlin, **regroupés** dans ces `object` (jamais dans le
code d'une méthode). Le port de textes est **décidé mais pas implémenté** (voir l'étape 8c dans docs/historique.md : `UiTexts` pour
l'interface du cœur, un jeu d'`object` par langue pour les répliques).

**Ajouter un texte**

1. Dans l'app : une `<string>` dans `strings.xml`, dans le groupe de son écran ; un texte avec des valeurs prend un
   **format numéroté** (`%1$s`, `%2$d`) — jamais `"…" + x` ni `"$a $b"` dans `setText` (lint `SetTextI18n` bloque) ;
   un nombre qui accorde un mot prend un `<plurals>` (`one`, `many`, `other`) et `getQuantityString(id, n, n)`.
   Apostrophe : `\'`, ou la valeur entre guillemets (qui garde aussi les espaces). Un symbole ou un format pur :
   `translatable="false"`.
2. Dans le cœur : la réplique dans l'`object` de sa fonctionnalité (tableau = tirage : `ctx.pick(*TABLEAU)` /
   `speech.pickFrom`) ; si elle nomme l'espèce, un membre de `SpeciesLines` (le compilateur demande sa version à
   chaque espèce). Ajouter une réplique à un tableau change les tirages : vérifier les tests de référence.
3. `NoSpeciesWordsTest` (cœur) et `StringsResourceTest` (app) signalent un « lapin » / « carotte » / 🐰 hors des
   endroits prévus.

**Traduire** (quand une deuxième langue arrivera) : app — `res/values-en/strings.xml` avec les mêmes clés (lint
`MissingTranslation` liste ce qui manque ; `translatable="false"` n'est pas à traduire) ; cœur — appliquer la voie
décrite dans l'étape 8c (docs/historique.md) (port `UiTexts` + `object` de répliques par langue) avant d'écrire la première traduction.

**Ce qui reste « lapin » (et carotte) hors de `species/rabbit/`** — volontairement :

- **Cœur** (`NoSpeciesWordsTest`, liste d'exceptions) : la **carotte objet du jeu** — `Food.CARROT` (« Carotte ») et
  sa réplique « Une carotte !! 🥕 » (`CarrotFood`) : c'est une nourriture du coffre comme la fraise, pas un mot de
  l'espèce ; une espèce qui n'en mange pas la refusera (`Species.accepts`). L'emoji 🥕 sert aussi d'**icône de la
  faim** (`NotifierTexts.statusLine`, `WidgetTexts.gauges`, humeur « faim » des compagnons, `GLUTTON`, fiche du véto,
  boutons « Nourrir ») : à rendre propre à l'espèce (`Species.foodEmoji`) le jour où une espèce ne mange pas de
  carottes. Les commentaires et noms historiques (`BunnyService`, « le lapin » dans la KDoc) ne sont pas du texte.
- **App** (`StringsResourceTest.speciesWordsAllowed`, 10 clés) : le **personnage de l'app** — l'adoption ne crée que
  des lapins (pas de choix d'espèce) : `main_tagline`, `main_name_hint`, `adopt_name_hint`, `main_reset`,
  `main_reset_title`, `main_reset_message`, `adopt_title_new`, `notif_channel_pet_desc`, et les
  textes de divulgation de l'accessibilité (`main_a11y_dialog_message`, `a11y_description`). Avec une deuxième
  espèce adoptable, ces textes deviennent neutres (« ton animal ») ou par espèce (recette « Ajouter une espèce »,
  étape 4). « Cookie » (canaux de notification, widget, tuile, `a11y_summary`) est le nom par défaut du personnage
  (TamaCookie), pas celui de l'animal : gardé.
- Le **genre** des textes (« il dort », « Touche pour t'occuper de lui ») reste masculin : à revoir avec la traduction.

## Accessibilité (étape 8d)

**Principes**

1. **Tout ce qui se fait au doigt se fait au lecteur d'écran.** Sur une fenêtre overlay, un geste (toucher, double
   toucher, appui long, glisser) a son équivalent : clic, appui long ou action personnalisée
   (`AccessibilityAction` à id de ressource, `res/values/ids.xml`). L'écouteur tactile garde les vrais touchers ;
   un `setOnClickListener` posé à côté ne sert qu'au lecteur d'écran (et au clavier / contacteur).
2. **Décrire, pas épeler les emojis.** Un élément graphique ou un emoji seul a une `contentDescription` ; un texte
   à emojis décoratifs est décrit par `SpokenText.plain` (sans emojis). Les états se disent par le système
   (`isSelected` → « sélectionné », `isEnabled`), les titres par `isAccessibilityHeading` / `accessibilityPaneTitle`.
3. **Parler peu.** Seules les répliques qui répondent à l'utilisateur sont annoncées (`A11yTexts.announces`) ;
   les descriptions sont calculées quand le lecteur d'écran les demande, jamais à chaque image.
4. **Voir et toucher** : texte ≥ 4,5:1 (`Palette.UI_*` + `Contrast`, testé), cibles ≥ 48 dp (`touchTarget`), texte
   en `sp` sans boîte de taille fixe (police à 200 %), animations décoratives coupées si Android les supprime
   (`ReducedMotion`, `AmbientAnimator.reducedMotion`).

**Où sont les textes**

| Texte | Où |
|---|---|
| Description calculée (animal, jauges, station, objet, aperçu) ; règle d'annonce | `A11yTexts` (cœur) |
| Texte affiché sans ses emojis | `SpokenText.plain` / `plainLines` (cœur) |
| Libellé fixe : actions (« Caresser », « Ouvrir le menu », « Mettre dans %1$s »…), « Fermer », dock, rôles, autorisations, widget | `strings.xml`, préfixe `access_` |
| Ids des actions personnalisées | `res/values/ids.xml` (`access_action_*`), `A11yActionIds` |
| Gestes de l'animal au lecteur d'écran | `PetAccessibility` ; stations et objets : `EntityWindows` |

**Tester avec TalkBack** : Paramètres › Accessibilité › TalkBack (raccourci : les deux touches de volume 3 s). Un
toucher = lire, double toucher = activer, glisser à droite / gauche = élément suivant / précédent, menu d'actions
= glisser vers le haut puis à droite (ou toucher à trois doigts selon la version), « Actions » liste les actions
personnalisées. `adb shell settings put global animator_duration_scale 0` (puis `1`) pour les animations réduites ;
Paramètres › Affichage › Taille de police au maximum (200 %) ; `adb shell uiautomator dump` pour lire l'arbre
(descriptions, `selected`, `heading`). Les fenêtres overlay ne prennent pas le focus clavier
(`FLAG_NOT_FOCUSABLE`) : on y va par exploration au toucher ou en glissant d'élément en élément (TalkBack parcourt
toutes les fenêtres affichées).

**Essais sur la tablette avec TalkBack** (non couverts par les tests JVM) :

1. Animal : explorer au doigt → « Cookie, lapin, content » (puis « dort », « joue », « dans Baignoire »… selon ce
   qu'il fait) ; double toucher = caresse (cœurs, réplique **annoncée**) ; appui long (double toucher maintenu) =
   menu ; actions : « Ouvrir le menu », « Mettre dans Baignoire » (il y entre, réplique de la station), « Sortir de
   Baignoire » ; « Mettre dans … » absent pour une station rangée, pleine ou d'une autre page ; avec deux animaux,
   chacun agit sur le **bon** animal.
2. Bavardage et répliques sociales : affichés, **pas** lus ; réponses à un toucher / au menu / à une commande
   (notification, widget) : lues une fois, sans couper la lecture en cours (zone polie).
3. Menu : annoncé « Menu de Cookie » à l'ouverture ; titre en en-tête ; boutons « Dodo » (puis « Réveil »),
   « Fermer » ; le panneau ne se ferme pas tout seul pendant l'exploration (toucher à l'extérieur).
4. Panneau 🏠 (dock « Objets de cette page ») : lignes « Baignoire, sur cette page », boutons « Ranger Baignoire » /
   « Mettre ici … » ; coffre : « Carotte », « Balle, jouet préféré » ; fiche du véto : une rangée = « Satiété 80 % »,
   « Soigner », « Sortir ».
5. Stations : « Baignoire, vide » → double toucher = explication **lue** ; coffre « Ouvrir » ; cage occupée
   « Cage, Cookie dedans » → double toucher = caresse à Cookie.
6. Objets : « Balle, jouet » → action « Lancer » (il court après) ; « Crotte de Cookie » → double toucher = ramassée.
7. Écran principal : titres de section (navigation par titres), aperçu « Cookie, lapin, … », lignes des animaux
   « Cookie, résident. Satiété … », niveaux « Sage / Taquin / Petit monstre » avec « sélectionné », autorisations
   « … : autorisé / non autorisé », « Comment jouer » sans noms d'emojis. Adoption : pastilles de couleur nommées
   et « sélectionné », cartes de caractère « sélectionné », aperçu qui suit le nom saisi.
8. Widget : rangées « Satiété 80 % »…, « Nourrir Cookie », « Appeler Cookie » ; à la taille minimale (3 × 2), les
   boutons (désormais 48 dp) ne sont pas coupés. Tuile : nom, état (« Sur l'écran »), action.
9. Police à 200 % : boutons du menu, du coffre, de la fiche du véto (libellés et valeurs) non coupés ; dock
   inchangé ; écrans principal et d'adoption qui défilent sans texte tronqué.
10. Animations supprimées (Paramètres › Accessibilité › Supprimer les animations) **pendant** que le lapin tourne :
   plus de flocons / pétales, de bulles de saleté, de miettes, d'oreille qui frémit ; une seule particule par
   caresse ; il marche, respire, cligne et fait ses Zzz. Remettre : tout revient sans relancer.
11. Contrastes : boutons framboise (« Faire apparaître », « Adopter ! », widget) et bouton gris clair sans la
   permission de surimpression.

**Ce qui n'est pas couvert**

- **Glisser-déposer libre** (lancer l'animal, le poser ailleurs que dans une station) et **déplacer une station**
  (appui long + glisser) : pas d'action — une station se place avec le panneau 🏠 (« Mettre ici »).
- **Cache-cache** : l'animal caché sur une autre page se trouve en changeant de page ; caché (fenêtre hors de
  l'écran ou cachée), il n'est pas lisible — seul le dock dit « Cache-cache en cours ».
- **Bêtises qui gênent** (il s'assoit sur un bouton, sur le clavier) : la fenêtre de l'animal peut recouvrir un
  bouton d'une autre app ; le lecteur d'écran le décrit mais ne passe pas « dessous » — le toucher le fait partir.
- **Bulle** : contraste réel ≤ celui annoncé (opacité 0,8 imposée par Android aux fenêtres non tactiles).
- **Notification** : boutons « 🥕 Nourrir »… lus avec leur emoji (texte de notification, laissé tel quel).
- **Ordre de lecture entre fenêtres overlay** : décidé par le système (ordre des fenêtres), pas par l'app.
- Pas de test instrumenté (Espresso / Accessibility Test Framework) : aucune dépendance de test Android.
