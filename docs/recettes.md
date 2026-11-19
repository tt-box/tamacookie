# TamaCookie — recettes « Ajouter… »

Chemins : `core/src/main/kotlin/com/tamacookie/…` (cœur) et `app/src/main/java/com/tamacookie/…` (app).

## Ajouter une station (ex. : le lit)

Chemins : `core/src/main/kotlin/com/tamacookie/…` (cœur) et `app/src/main/java/com/tamacookie/…` (dessin).

1. Cœur — `feature/station/BedStation.kt` : `object BedStation : StationBehavior` — `name` (« 🛏️ Lit »), `tapHint`,
   `busyLine`, `seat`, les propriétés utiles (`capacity = 2`, `slowFrames = true`, `holdsPet`…) et
   `onEnter` / `update` / `onLeave` / `onPet` si besoin (répliques dans le fichier).
2. Cœur — `StationKind` (`feature/station/StationKind.kt`) : ajouter **à la fin** `BED("Lit", 140, 90, BedStation)` —
   l'ordre fixe le placement par défaut, le nom les clés `st_BED_*` (créées automatiquement, rien à migrer).
3. App — `feature/station/BedStationDrawer.kt` : `object BedStationDrawer : StationDrawer` (`draw(c, v, w, h)` :
   pinceaux `v.pen`, état `v.t` / `v.open` / `v.occupied`) et sa branche dans `StationDrawers.of` (le `when`
   exhaustif l'exige) ; ses couleurs dans `Palette` (cœur).
4. (Facultatif) un test de son comportement à côté de `StationBehaviorTest` (`core/src/test`) ; les tests
   d'exhaustivité (`StationBehaviorTest`, `DrawersTest`) la couvrent déjà.
5. Accessibilité : son `name` est aussi sa **description** au lecteur d'écran, emoji retiré (« 🛏️ Lit » → « Lit »,
   « Lit, Cookie dedans », action « Mettre dans Lit » de l'animal) — il doit rester parlant sans l'emoji, et son
   `tapHint` est l'explication annoncée au double toucher (`A11yTextsTest` vérifie chaque station). Rien à
   déclarer : `ids.xml` a 12 places d'action (`A11yActionIdsTest` échoue s'il en manque).

Rien d'autre : panneau 🏠, survol/dépôt, toucher, cadence, voyage, sommeil, caresse, sauvegarde du placement
suivent. Avant 3a il fallait toucher ~10 endroits (enum + `when` du dessin, `positionInStation`, `enterStation`,
`leaveStation`, `busyInStation`, tap, `Act` + `SLOW`/`BUSY_FOR_TRAVEL`, `updateBehavior`, `onAppChanged`,
`startSleep`, `onPetTap`, noms du menu). Seules exceptions : une station avec **son propre panneau** (ajouter une
méthode à `StationCtx` et le `FloatingPanel` dans `StationController`). Plusieurs animaux : `capacity` suffit (chacun
s'assoit à `seat` ; pour des places distinctes, `seatFor(rang)` — voir `BedStation`) ; une espèce peut la refuser
(`Species.accepts`). Il peut s'y endormir : `sleepsInside = true`. Une nouvelle règle de jauges : un `PetEvent`
(recette « Ajouter une stat », étape 1).

## Ajouter un jouet (ex. : le cerf-volant)

1. Cœur — `feature/toy/KiteToy.kt` : `object KiteToy : ToySpec` — `toy get() = Toy.KITE` (getter : pas de cycle
   d'initialisation avec l'enum), `wDp`/`hDp`, `startLines`, `play` (pendant `Act.PLAY`), `step` (sa physique,
   via `ctx.stepToy`) et au besoin `launch`, `randomTint`, `complainsWhenTaken` ; ses répliques dans le fichier.
2. Cœur — `Toy` (`feature/toy/Toy.kt`) : ajouter **à la fin** `KITE("Cerf-volant", "🪁", KiteToy)` (le nom est
   sauvegardé dans `favoriteToy`).
3. App — `feature/toy/KiteToyDrawer.kt` : `object KiteToyDrawer : PropDrawer` (`draw(c, v, w, h)` : `v.spin`,
   `v.tint`, pinceaux `v.pen`) et sa branche dans `PropDrawers.of(Toy)` (`when` exhaustif) ; ses couleurs dans
   `Palette`. `ToySpecTest` (cœur) et `DrawersTest` (app) le couvrent déjà (exhaustivité).
4. Accessibilité : son libellé (« Cerf-volant ») est sa description (« Cerf-volant, jouet ») et le nom de son bouton
   dans le coffre ; l'action TalkBack « Lancer » suit. Une nourriture : idem (« …, à manger »). `A11yTextsTest`
   vérifie que chaque jouet et chaque nourriture ont un libellé lisible sans emoji.

Coffre, jouet préféré à l'adoption, fiche du véto, partie, lancer au doigt, protestation, cadence suivent. Avant 3b :
~10 endroits (enum `Toy`, `PropView.Kind` + `when` du dessin, `Prop.toy`, taille dans `startPlay`, teinte, lancer,
répliques, `updatePlay`, physique dans `update`, protestation dans le toucher). Une nourriture : un `object … : FoodSpec`
dans `Foods.kt` + une ligne dans `Food` (cœur), son `…FoodDrawer` dans `FoodDrawers.kt` + sa branche dans
`PropDrawers.of(Food)` (app).

## Ajouter une bêtise (ex. : il tire la langue à l'écran)

Tout est dans le cœur (`core/src/main/kotlin/com/tamacookie/`), tests dans `core/src/test`.

1. `feature/mischief/XxxMischief.kt` : `object XxxMischief : Mischief` — pour qu'il la fasse quand on l'ignore :
   `chance(level)` (> 0), `canStart`, `start` ; pour réagir à l'utilisateur : `react(ctx, pet, ev)` ; si elle a sa
   propre activité : `acts`, `update`, `onPet`, `travel`. Son état éventuel va dans `MischiefState`, ses répliques
   dans le fichier.
2. `Mischiefs.ALL` (`feature/mischief/Mischief.kt`) : l'insérer à sa place — l'ordre est celui de la cascade et des
   réactions ; une bêtise de probabilité 1 doit rester la dernière (toc-toc).
3. Seulement si elle a une nouvelle activité : l'ajouter à `Act` (BunnyBrain.kt) et aux ensembles utiles (`MOVING`
   pour une animation fluide, `RUSH_BACK`, `BUSY_FOR_TRAVEL` — `BunnyBrainTest` vérifie leur cohérence), et si
   besoin ce que dit le lecteur d'écran pendant cette activité (`A11yTexts.ACTIVITIES` ; sans entrée : son humeur).
   Si elle fait des particules purement décoratives : ne pas les émettre quand `AmbientAnimator.reducedMotion`.

Enregistrement dans `ActRegistry`, boucle, caresse, voyage, cadence, niveau / pause / applications exemptées suivent.
Avant 3b : ~6 endroits (cascade et état dans `MischiefController`, `Act` + ensembles, `when` de `updateBehavior`,
`when` de `onPetTap`, `onAppChanged`).

## Ajouter une action de menu (ex. : chanter)

1. `MenuEntries` (`feature/menu/MenuEntry.kt`, cœur) : `val SING = MenuEntry("🎵", "Chanter") { ctx, pet -> … }` (avec
   `refresh = { st -> … }` si le bouton change selon l'état) et l'ajouter à `ALL` (même fichier ; deux par rangée).
2. Seulement si l'action a besoin d'autre chose que `PetCtx` (un panneau, un autre contrôleur) : une méthode dans
   `MenuCtx` (cœur) et son implémentation dans `TouchHandler.menuCtx` (app).
3. Accessibilité : le bouton est lu par son `label` (« Chanter »), et un texte dynamique (`refresh`) par sa partie
   sans emoji (« ☀️\nRéveil » → « Réveil ») — garder la forme « emoji, retour à la ligne, libellé ».
   `A11yTextsTest` vérifie chaque entrée (endormi et réveillé).

Fermeture du menu, mise en page, rafraîchissement suivent. Avant 3b : 4 endroits (enum `PetMenu.Action`, bouton et
rangée dans `PetMenu`, branche de `TouchHandler.onMenuAction`, texte dynamique dans `refresh`).

## Ajouter une stat (ex. : la soif)

1. `PetStats` : `val thirst: Float = 80f` ; `PetRules.clamp` : la borner ; `PetRules.elapsed` : son évolution
   (taux dans `GameRules`, ex. `thirstLossPerH`) ; un `PetEvent` qui la remonte (`Drank`), sa branche dans
   `apply` et dans `savesNow` (le compilateur exige les deux) ; l'humeur dans `PetRules.mood` si besoin
   (`Mood.THIRSTY` → `SpeciesLines`, `NotifierTexts`, `PetMenu`, `PetRenderState.moodExpr` : signalés par le
   compilateur). Tout cela est dans le cœur, sauf `PetMenu` (app).
2. Persistance (app, `persist/PetRepository.kt`) : `PetFields` (`THIRST = "thirst"`, `write` + `read` avec défaut) — **pas de migration** : une clé
   absente prend la valeur par défaut. `PetSnapshot` : l'ajouter seulement si elle a du sens sur un autre
   téléphone (champ ignoré par les anciennes versions, défaut chez nous s'il manque).
3. Si un caractère la module : une entrée `StatMod` et sa valeur dans `Personality.modifiers`.
4. Tests : `PetRulesTest` (cœur : taux, bornes) — la trace de référence ne change pas tant que la nouvelle stat
   n'influence pas les autres ; `PetRepositoryTest` (app : aller-retour).

Un changement de **forme** (renommer / scinder une clé, changer une unité) demande une migration : une entrée à la
fin de `PetMigrations.ALL`, `SCHEMA_VERSION` + 1, et un test à partir d'un fichier de la version précédente.

## Ajouter une personnalité (ex. : peureux)

1. `Personality` (`Personality.kt`, cœur) : ajouter **à la fin** `SHY("Peureux", "🙈", "…", mapOf(StatMod.HAPPINESS_LOSS
   to 1.1f))` — le nom d'enum est sauvegardé et échangé (`PetSnapshot`) : ne plus le changer.
2. `PersonalityTest` : sa ligne dans la table `expected` (le test d'exhaustivité l'exige).
3. Comportement propre (hors jauges) : sa ligne dans `behavior()` de chaque espèce (`Rabbit.modsFor` : un champ de
   `BehaviorMods`, ou un nouveau champ lu là où il agit) — le `when` exhaustif l'exige. L'écran d'adoption la propose
   tout seul. Un nouveau champ de `BehaviorMods` doit être **neutre par défaut** (facteur 1, chance 0 testée avant
   tout tirage) pour ne rien changer aux autres caractères (modèle : `mischiefChanceFactor`, `startleChance`).
4. Les tests qui itèrent sur `Personality.entries` avec une empreinte figée (`PetRulesTest.GOLDEN`) s'en tiennent à
   `TRACE_PERSONALITIES` : donner son propre test au nouveau caractère.

Un pair qui envoie un caractère inconnu (version plus récente) le voit remplacé par `CUDDLY` (`PetSnapshot.decode`).

## Ajouter une espèce (ex. : le hamster)

1. `species/hamster/Hamster.kt` : `object Hamster : Species` — `id` (« hamster » : **sauvegardé et échangé**, ne plus
   le changer), `label`, `emoji`, `spec` (`SpeciesSpec` : gabarit de fenêtre, pieds, haut de la tête, vitesses),
   `behavior(p)` (un `BehaviorMods` par `Personality`, `when` exhaustif), `accepts(k)` / `accepts(t)` si certaines
   stations / jouets ne lui conviennent pas, et `lines` (cœur, `core/…/species/hamster/`).
2. Cœur — `species/hamster/HamsterLines.kt` : `object HamsterLines : SpeciesLines` (le compilateur liste ce qui
   manque, y compris les répliques qui nomment l'espèce ou sa nourriture : `SONGS`, `GOING_TO_CHEST`, `ADOPT_PROMPT`). App — `species/hamster/HamsterRenderer.kt` : `class HamsterRenderer(d: Float) : PetRenderer` (`draw` du
   corps pour chaque `Pose` / `Expr`, `onState` pour l'apparence, `hitTest`) et sa ligne dans `SpeciesRenderers`
   (`Hamster.ID to ::HamsterRenderer`) ; ses couleurs dans `Palette`.
3. `Species.ALL` (`species/Species.kt`) : l'ajouter **après** le lapin (le premier est l'espèce par défaut des
   anciennes sauvegardes). `SpeciesTest` (cœur) et `DrawersTest` (app : un renderer par espèce) le couvrent
   (exhaustivité) ; `PetSnapshot` l'accepte tout seul.
4. Pour le proposer : un choix d'espèce dans `AdoptActivity` (l'`Adoption` porte alors l'espèce jusqu'à
   `Identity(species = …)`) — pas encore d'interface. Les textes de l'app qui disent « lapin »
   (`StringsResourceTest.speciesWordsAllowed`) deviennent alors neutres ou par espèce. Si son gabarit diffère du lapin : la fenêtre (`Arena.bw/bh`)
   devient par animal (`ensurePetWindow` prend `p.species.spec`), et `BunnyActor` / `PageTracker` lisent la largeur
   de l'animal.

Boucle, cerveau, jauges, stations, jouets, bêtises, social, persistance suivent.

## Ajouter une interaction sociale (ex. : jouer à chat)

1. `social/TagSocial.kt` (cœur) : `object TagSocial : SocialInteraction` — par le cerveau : `chance` (> 0), `canStart(ctx,
   a, b)` (sans aléatoire), `start(sys, a, b)` qui engage les participants (`sys.engage(p, this, partner, durée,
   leads)` : un seul pour une interaction « passive » comme suivre, les deux pour une interaction mutuelle) et
   `update(sys, self, partner, e, dt)` (chaque image pour chaque engagé ; `false` = fini ; `partner == null` = parti
   ou indisponible) ; ou par le monde : `observe(sys, a, b, dt)` (chaque paire affichée, sans engagement : les passes
   à la balle). Répliques dans le fichier, dites par `sys.say(p, …)` (priorité basse) ; tirages avec `p.mind.rnd`.
2. `Socials.ALL` (`social/SocialSystem.kt`) : l'insérer à sa place (ordre de la cascade).
3. Si elle demande un arbitrage (une ressource unique) : une question au `Director` (déduite du monde), interrogée
   dans `canStart` ; si c'est le `Director` qui la déclenche (dispute) : une file comme `takeDisputes`.

`Act.SOCIAL`, `ActRegistry`, interruption (caresse, glisser, voyage), cadence et perception suivent. Tests : à côté de
`SocialSystemTest` (`core/src/test`), avec un `FakePetCtx` + `addPet` et des `Random` seedés.

