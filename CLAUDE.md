# TamaCookie — guide pour Claude

Lapin façon tamagotchi qui vit en **overlay** (WindowManager) par-dessus l'écran Android.
Kotlin 2.0, Android 16+ (minSdk / compile / target 36), deux modules — `:core` (Kotlin/JVM pur) et `:app`
(Android, dépend de `:core`) —, aucune dépendance runtime. Code, commentaires, commits et UI sont **en français**.

**Documentation détaillée** (à lire seulement si la tâche le demande) :
- `docs/historique.md` — écarts et décisions de chaque étape / évolution (pourquoi le code est ainsi, ce qui reste
  à vérifier sur tablette). Y ajouter une entrée à chaque étape.
- `docs/recettes.md` — ajouter une station, un jouet / une nourriture, une bêtise, une action de menu, une stat,
  une personnalité, une espèce, une interaction sociale.
- `docs/textes-et-accessibilite.md` — où vont les textes, traduction, « lapin » autorisé où, règles et essais
  TalkBack.

## Commandes

```bash
./gradlew test                # tous les tests JVM (JUnit 4) — doivent rester verts à chaque étape
./gradlew :core:test          # tests du cœur (l'essentiel) ; ceux de l'app : ./gradlew testDebugUnitTest
./gradlew assembleDebug       # APK : app/build/outputs/apk/debug/
./gradlew :core:jacocoTestReport createDebugUnitTestCoverageReport   # couverture (core/build/reports/jacoco/,
#   app/build/reports/coverage/test/debug/) ; total : python .github/scripts/coverage_badge.py <xml…> sortie.svg
./gradlew lintDebug           # démon Gradle en JDK 21 (gradle/gradle-daemon-jvm.properties : lint plante en JDK 25) ;
#   JDK 21 trouvé par JDK21_HOME ou org.gradle.java.installations.paths (~/.gradle/gradle.properties)
./gradlew detekt              # analyse statique des deux modules (bloquant en CI) ; rapports <module>/build/reports/detekt/
./gradlew detektBaseline      # régénère config/detekt/baseline-<module>.xml — voir ci-dessous
```

**Baseline detekt** (`config/detekt/baseline-core.xml`, `baseline-app.xml`) : elle fige les problèmes d'avant
detekt. Un nouveau problème se **corrige** (ou, voulu, `@Suppress("Règle")` local commenté) — **jamais** en
régénérant la baseline. `detektBaseline` ne sert qu'à la **nettoyer** (le diff ne doit que retirer) ou après un
changement de règle / version justifié ici. Identifiants `Règle:signature` : renommer une fonction la périme.
Audit non bloquant avec résolution de types : `./gradlew :core:detektMain :core:detektTest :app:detektDebug`.

## Pièges connus

- **APK à installer : toujours `./gradlew clean assembleDebug --no-build-cache`**. Une classe Kotlin incrémentale
  périmée a déjà donné un `AbstractMethodError` qui bloque le fil principal : **toutes les fenêtres overlay figées**
  (même symptôme qu'une boucle arrêtée). Les tests JVM ne le voient pas.
- **Le cœur est dans `:core`** (`core/src/main/kotlin`) : aucune classe Android, garanti par le compilateur. Logique
  testable → `:core` ; Android (vues, `Canvas`, `SharedPreferences`, `Context`, services) → `:app` derrière un port
  (`SettingsStore`, `PetStore`, `OverlayHost`, `ScreenSensor`, `ScreenActions`, `Clock`…). Une constante Android utile
  au cœur y est **recopiée** et vérifiée par `AndroidConstantsTest` (`Haptic`, `AppCategory`, `AutoRestart`).
- Tests de `:app` : `unitTests.isReturnDefaultValues = true` (les classes Android renvoient 0/false/null) — **ne
  pas y tester de logique**, la mettre dans `:core`.
- **Réglages** : par `Prefs` (fichier `"pet"`). **Animaux** : uniquement par `PetRepository` (fichier `"pets"`,
  schéma ci-dessous). Ne jamais renommer une clé ni un identifiant sauvegardé (`id` des options de `Look`, noms
  d'enum `Personality`, `Toy`, `Food`, `StationKind`, `Species.id`) sans migration. Ajouts **à la fin** des enums.
- Les jauges ne changent **que** par `PetCtx.on(pet, PetEvent)` (→ `PetRules`) : jamais `pet.stats = …` hors tests.
  `PetRepository.save()` ignore un animal qui n'est plus enregistré.
- Les activités lisent l'animal par le repository et écrivent par `PetCommands` (le service est l'autorité).
- **Traces seedées** (`BunnyBrainTest`, `MischiefTest`, `MultiPetTest`, `SocialSystemTest`, `PetRulesTest.GOLDEN`) :
  un ajout ne doit pas changer les tirages existants — nouveau comportement **sans tirage** quand il ne s'applique pas,
  nouveau champ de `BehaviorMods` neutre par défaut, nouvelle bêtise / réaction placée pour ne pas consommer le
  `Random` d'avant. Ne pas retoucher les graines.
- **Textes** : interface de l'app dans `res/values/strings.xml` (lint `HardcodedText`, `SetTextI18n` bloquants :
  formats numérotés `%1$s`, `<plurals>`) ; texte du cœur dans un `object` (fonctionnalité, `companion object` du
  contrôleur, `CommonLines`, `SpeciesLines`), jamais dans le code d'une méthode ; tirage par `ctx.pick(*TABLEAU)`.
  Rien de « lapin » / « carotte » / 🐰 hors de `species/rabbit/` (`NoSpeciesWordsTest`, `StringsResourceTest`).
- **Accessibilité** : tout élément graphique ou emoji seul a une description (calculée : `A11yTexts`,
  `SpokenText.plain` ; fixe : `strings.xml`, `access_*`) ; cible ≥ 48 dp (`touchTarget`) ; texte en `sp` sans taille
  de boîte fixe ; couleur de texte = paire `Palette.UI_*` ≥ 4,5:1 (`A11yTextsTest`) ; un geste overlay a son action
  TalkBack (`PetAccessibility`, `EntityWindows`) ; seules les répliques HIGH sont annoncées ; particules décoratives
  coupées si `AmbientAnimator.reducedMotion`. Lints d'accessibilité bloquants.
- Ignorer `.claude/worktrees/` dans les recherches. Le repo est indexé par **graft** (`graft ask "<q>" --source`,
  `graft grep`, `graft skeleton <fichier>`, `graft callers <symbole> --depth all` avant tout déplacement / renommage).

## Architecture

Packages `com.tamacookie` (+ `.feature.*`, `.species.*`, `.social`, `.persist`) **identiques des deux côtés** :
seul le module dit ce qui est pur. Le dessin d'une station / d'un objet / d'une espèce est dans `:app`, même package
que son comportement (`…Drawer.kt` / `…Renderer.kt`, registres `StationDrawers`, `PropDrawers`, `SpeciesRenderers`
en `when` exhaustifs).

- **Monde d'entités** : `World` { `pets`, `props`, stations (`Occupancy` : occupants `PetId` + capacité) } ;
  `Pet` = `Identity` + `Species` + `Look` + `PetStats` + `Body` (position, act, pose) + `Mind` (`Random` propre,
  état de bêtise, engagement social). Jamais de singleton « le lapin » : `World.asPet(p) { … }` installe l'animal
  courant (`PetCtx.pet`, `rnd`, bulle, page).
- **Boucle** : `FrameLoop` (pur, port `FrameScheduler`) → `BunnyService.update` → `PetBehavior` par animal affiché
  (≤ `Tuning.MAX_PETS_SHOWN` = 3 ; les autres « dorment hors écran ») → `OverlayHost.flush()` (≤ 1
  `updateViewLayout` par fenêtre déplacée et par image).
- **Plugins** : une interface par famille, rattachée à son enum / registre — `StationBehavior` (`StationKind`),
  `ToySpec` / `FoodSpec` (`Toy`, `Food`), `Mischief` (`Mischiefs.ALL`, ordre = cascade), `MenuEntry`
  (`MenuEntries.ALL`), `SocialInteraction` (`Socials.ALL`), `Species` (`Species.ALL`, lapin = défaut). Une activité
  de fonctionnalité passe par `ActRegistry` (`ActHandler`) ; le `when (act)` de `PetBehavior` ne garde que
  locomotion, sommeil, voyage, humeur. Une fonctionnalité ne dépend que de `PetCtx` (ou d'un `XxxCtx` qui l'étend)
  et interroge le monde, jamais un autre contrôleur.
- **Director** (pur) : arbitre ce qui est unique (une bêtise, coffre / gamelle `mayHeadTo`, nourriture
  `foodTarget` / `foodWinner`, bulles `BubbleQueue`, chasse aux pubs `AdHunt`, notifications) — **déduit du monde**,
  rien à réserver ni libérer.
- **Rendu** : les vues n'affichent qu'un `PetRenderState` immuable ; `OverlayManager` unique (ids `WindowId`,
  couches `WindowLayer` PET < STATION < PROP < DOCK < BUBBLE < PANEL < MENU) ; couleurs dans `Palette`.
- **Ports** : `ScreenSensor` (`ScreenSensorHub`, scan sur `HandlerThread`, `UserSignal` sans perte),
  `ForegroundAppSource`, `ScreenActions` (appui sur « Ignorer l'annonce », liste blanche `ScreenScan.isAdSkip`),
  `PetStore`, `SettingsStore`, `OverlayHost`, `StationPanels`, `Clock`. DI manuelle : `TamaApp` / `AppGraph`.
- **Temps et aléatoire injectés** : tout le cœur est déterministe ; faux de test `FakePetCtx`, `FakeClock`,
  `FakeOverlayHost`, `FakeScreenSensor`, `FakeSettings`, `testPet()`.
- Noms historiques gardés exprès : `BunnyService`, `BunnyBrain`, `BunnyPhysics`, `BunnyActor`, `PageTracker.bunny*`.
- Dette connue : `BunnyService` reste gros (cycle de vie + boucle + câblage) ; `TooManyFunctions` /
  `LongMethod` des contrôleurs sont dans la baseline detekt.

## Feuille de route

Chaque étape : comportement inchangé sauf mention, tests verts, commits séparés, entrée dans `docs/historique.md`.

| # | Étape | État |
|---|---|---|
| 0–7 | Corrections ; extractions du service ; `Pet`/`World`/`PetCtx` ; plugins ; `OverlayManager` + `RenderState` ; `PetStats`/`PetRules`/`PetRepository` ; port `ScreenSensor` ; multi-animal (`Species`, `Director`, `SocialSystem`) | ✅ |
| 8 | Qualité : 8a module `:core` ; 8b detekt ; 8c textes ; 8d accessibilité | ✅ |
| 4a–4c | Contenu (stations, jouets, nourritures, bêtises, menu) ; caractères, apparence, saisons ; bien-être, widget, tuile | ✅ |
| — | Chasseur de pubs ; gamelle autonome ; nourriture jamais refusée / jeu partagé ; CI et release accélérées | ✅ |
| — | **Prochaine** : évolutions de contenu sur les plugins (recettes dans `docs/recettes.md`) | ⏳ |
| — | Ensuite : échange Bluetooth (BLE + L2CAP) — visites et transfert (`PetSnapshot`), protocole en deux phases | |

## Schéma de persistance v1

Fichier **`pets`** (SharedPreferences, `Prefs.PETS_FILE`) :

| Clé | Type | Contenu |
|---|---|---|
| `schema_version` | Int | `1` (absent = schéma 0 : animal dans le fichier `"pet"`) |
| `pets` | String | ids des animaux séparés par `,`, ordre d'arrivée (résident et compagnons) |
| `resident` | String | id de l'animal qui vit sur ce téléphone (les autres de `pets` sont ses compagnons) |
| `pet.<id>.name` / `.species` | String | nom (≤ 16), espèce (`Species.id` : `rabbit`) |
| `pet.<id>.bornAt` / `.lastUpdate` | Long | naissance, dernière sauvegarde (ms, heure murale) |
| `pet.<id>.adopted` | Boolean | écran d'adoption validé |
| `pet.<id>.personality` / `.favoriteToy` | String | nom d'enum `Personality` / `Toy` |
| `pet.<id>.look.fur` / `.ears` / `.eyes` / `.pattern` / `.accessory` / `.accColor` | String | `id` de l'option (`chocolate`, `lop`…) |
| `pet.<id>.satiety` / `.happiness` / `.energy` / `.cleanliness` / `.health` | Float | jauges 0..100 |
| `pet.<id>.sleeping` / `.caged` | Boolean | endormi, en cage |
| `pet.<id>.poops` / `.visitedApps` | Int | crottes à l'écran, apps visitées |
| `pet.<id>.poopAcc` | Float | fraction de la prochaine crotte |

Fichier **`pet`** (réglages) : `enabled`, `mischief`, `dock_fx`, `dock_fy`, `pageW2_<largeur>`,
`st_<STATION>_anchor` / `_fx` / `_fy`, `seasonal` (vrai si absent), `wellbeing` (faux si absent), `ad_skip`
(vrai si absent). Valeur inconnue ou absente → défaut (`PetFields.read`). Une nouvelle clé n'exige pas de
migration ; un changement de **forme** si (entrée à la fin de `PetMigrations.ALL`, `SCHEMA_VERSION` + 1, test).

## Règles pour chaque étape

Pas de big-bang, pas de nouvelle dépendance sans justification écrite, tests JVM pour tout ce qui devient pur,
mettre à jour ce fichier (feuille de route) et `docs/historique.md` (écarts et décisions, essais sur tablette).
