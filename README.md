# 🐰 TamaCookie

[![CI](https://github.com/tt-box/tamacookie/actions/workflows/ci.yml/badge.svg)](https://github.com/tt-box/tamacookie/actions/workflows/ci.yml)
[![Couverture des tests](https://raw.githubusercontent.com/tt-box/tamacookie/badges/coverage.svg)](https://github.com/tt-box/tamacookie/actions/workflows/ci.yml)
[![Release](https://github.com/tt-box/tamacookie/actions/workflows/release.yml/badge.svg)](https://github.com/tt-box/tamacookie/actions/workflows/release.yml)
[![Dernière version](https://img.shields.io/github/v/release/tt-box/tamacookie?label=version&logo=github)](https://github.com/tt-box/tamacookie/releases/latest)
[![Téléchargements](https://img.shields.io/github/downloads/tt-box/tamacookie/total?label=t%C3%A9l%C3%A9chargements&logo=android&color=3DDC84)](https://github.com/tt-box/tamacookie/releases)
[![Téléchargements (dernière version)](https://img.shields.io/github/downloads/tt-box/tamacookie/latest/total?label=derni%C3%A8re%20version)](https://github.com/tt-box/tamacookie/releases/latest)
[![Android 13+](https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/13)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Dependabot](https://img.shields.io/badge/Dependabot-auto--merge-025E8C?logo=dependabot)](.github/dependabot.yml)
[![Licence LGPL-3.0](https://img.shields.io/github/license/tt-box/tamacookie?label=licence&color=blue)](LICENSE)

Un petit lapin façon tamagotchi qui **vit par-dessus tes écrans Android** : il se promène sur le bureau,
saute sur les icônes, te suit d'application en application… et vient t'embêter quand tu l'oublies.

## 📥 Télécharger

➡️ **[Télécharger le dernier APK](https://github.com/tt-box/tamacookie/releases/latest)**

1. Télécharge `tamacookie-<version>.apk` depuis la dernière release.
2. Autorise l'installation d'applications de source inconnue pour ton navigateur / gestionnaire de fichiers.
3. Ouvre l'APK, installe, puis lance **TamaCookie** et adopte ton lapin.

Chaque release fournit aussi :

- `tamacookie-<version>-unsigned.apk` : le même APK **non signé**, à signer avec ta propre clé
  (il ne s'installe pas tel quel) ;
- `tamacookie-<version>.aab` : l'Android App Bundle, format de publication du Play Store, signé avec
  la clé d'upload du Play Store (RSA, distincte de la clé des APK) ;
- `SHA256SUMS.txt` : les empreintes de tous ces fichiers.

## ✨ Fonctionnalités

- **Adoption et personnalisation** : pelage, oreilles, yeux, motif, accessoire, personnalité
  (joueur, gourmand, paresseux, curieux, câlin) et jouet préféré.
- **Vie de tamagotchi** : faim, bonheur, énergie, propreté, santé, sommeil… et petites crottes à ramasser.
- **Fenêtre flottante** au-dessus de toutes les applications ; il commente l'app ouverte et la suit.
- **Saute sur les icônes** (service d'accessibilité, activé à la main : seule la *position* des éléments est lue).
- **Objets flottants** posés sur une page précise du bureau, qui suivent le swipe comme les icônes :
  - 🛁 **Bain** pour le laver, 🩺 **Vétérinaire** pour voir ses statistiques et le soigner,
  - 🔒 **Cage** pour l'isoler (son bonheur baisse plus vite),
  - 🎁 **Coffre** pour sortir à manger 🥕🥬🍓 ou un jouet ⚽🎈🥏🧸 — Cookie peut aussi l'ouvrir tout seul
    en sautant dedans quand il a faim ou envie de jouer.
- **Pages du bureau** : Cookie vit sur une page, défile avec elle, part explorer la page voisine
  et revient sur l'écran affiché.
- **Bêtises** (réglables) : il s'assoit sur un bouton, tape à l'écran, joue à cache-cache sur une autre page…
- **Chasseur de pubs** (réglable, activé par défaut ; accessibilité requise) : quand le bouton « Ignorer
  l'annonce » d'une publicité apparaît (YouTube…), il court, saute dessus et l'appuie — « Hop ! Au revoir la
  pub ! 🐾 ». Il n'appuie sur rien d'autre : ni « Consulter le site de l'annonceur », ni « Télécharger », ni
  « Fermer ».

## 🔐 Permissions

| Permission | Obligatoire ? | Pourquoi | Où l'accorder |
|---|---|---|---|
| Affichage par-dessus les autres apps | **Oui** | Faire vivre le lapin à l'écran (fenêtres flottantes) | Écran principal › « Faire apparaître Cookie », voir [ci-dessous](#1-affichage-par-dessus-les-autres-apps-obligatoire) |
| Notifications | **Oui** (Android 13+) | Le service tourne au premier plan, avec sa notification (Nourrir, Cacher…) et les alertes (faim, maladie…) | Demandée au premier lancement |
| Accès aux données d'utilisation | Non | Savoir quelle application est ouverte (sans accessibilité) pour qu'il la commente et la suive | Écran principal › ligne ⚠️, ou Paramètres › Applis › Accès spécial des applis › Accès aux données d'utilisation |
| Accessibilité | Non | Position des icônes, page du bureau, défilement : il saute sur les icônes et suit les pages. **Jamais** le texte saisi ni le contenu des écrans. Une seule action : appuyer sur le bouton « Ignorer / Passer l'annonce » d'une publicité (chasseur de pubs, désactivable) | Paramètres › Accessibilité › TamaCookie |

Accordées sans rien demander (déclarées dans le manifeste) : service au premier plan, démarrage au boot et après une
mise à jour, vibration, masquage des fenêtres flottantes d'autres apps pendant les réglages.

Rien n'est collecté ni envoyé : tout reste sur l'appareil.

### 1. Affichage par-dessus les autres apps (obligatoire)

Sans cette autorisation, le lapin ne peut pas apparaître.

1. Lance **TamaCookie** et touche **« Faire apparaître Cookie »** (ou la ligne ⚠️ *Afficher par-dessus
   les autres apps*) : Android ouvre la page de l'autorisation.
2. Active **« Autoriser l'affichage par-dessus d'autres applis »**.
3. Reviens dans TamaCookie : la ligne passe à ✅ et le lapin peut sortir.

Si la page ne s'ouvre pas, le réglage se trouve à la main :

| Appareil | Chemin |
|---|---|
| Samsung (One UI) | Paramètres › Applications › ⋮ › **Accès spécial** › **Apparaître au-dessus** › TamaCookie |
| Pixel / Android d'origine | Paramètres › Applis › **Accès spécial des applis** › **Superposition à d'autres applis** › TamaCookie |
| Autres | Paramètres › Applications › TamaCookie › *Afficher par-dessus d'autres applis* (ou *Superposition*) |

### 2. Interrupteur grisé : les « paramètres restreints »

Un APK installé **hors du Play Store** (fichier téléchargé, comme les releases de ce dépôt) est soumis aux
*paramètres restreints* d'Android : l'interrupteur est grisé, ou il refuse sans explication (« Paramètre
restreint » / « Pour votre sécurité, ce paramètre n'est pas disponible »).

- Android 13 et 14 : seul le **service d'accessibilité** est concerné.
- Android 15 et plus récent (vu sur Pixel 7 Pro, Android 17) : l'**affichage par-dessus les autres apps** l'est
  aussi — c'est le cas le plus fréquent de « je n'arrive pas à l'activer ».

Pour lever la restriction (une fois suffit, pour toutes les autorisations de l'app) :

1. Essaie d'abord d'activer l'autorisation : Android refuse (c'est ce refus qui débloque l'étape suivante).
2. Paramètres › Applis › **TamaCookie** (*Infos sur l'appli*) › **⋮** en haut à droite › **Autoriser les
   paramètres restreints** (confirmation par code / empreinte).
3. Recommence l'activation : l'interrupteur est maintenant actif.

Si le menu ⋮ n'apparaît pas, c'est que l'étape 1 n'a pas encore été tentée.

### 3. Accessibilité (optionnelle)

Paramètres › Accessibilité › **Applications installées** (Samsung) / **Applications téléchargées** (Pixel) ›
TamaCookie › activer. Grisé : voir « paramètres restreints » ci-dessus. Le service ne lit que la position des
éléments à l'écran (icônes, boutons, clavier) et le défilement du bureau. Il n'agit que dans un cas : si le
réglage « Chasseur de pubs » est activé, il appuie sur le bouton « Ignorer l'annonce » / « Passer l'annonce »
d'une publicité (identifiant `skip_ad_button` de YouTube, ou libellé exact), quand le lapin saute dessus.
YouTube refusant l'appui « accessibilité » sur ce bouton, le service simule alors un toucher bref **au centre de
ce bouton** (droit « effectuer des gestes »). Après une mise à jour qui ajoute ce droit, **désactive puis réactive**
TamaCookie dans Paramètres › Accessibilité pour qu'Android le prenne en compte.

### 4. Par ADB (développement, appareil branché)

```bash
P=com.tamacookie
adb shell appops set $P ACCESS_RESTRICTED_SETTINGS allow    # lève les paramètres restreints (APK hors Play Store)
adb shell appops set $P SYSTEM_ALERT_WINDOW allow           # affichage par-dessus les autres apps
adb shell pm grant $P android.permission.POST_NOTIFICATIONS # notifications
adb shell appops set $P GET_USAGE_STATS allow               # données d'utilisation (facultatif)
adb shell appops get $P                                     # vérifier
```

L'accessibilité ne s'active pas proprement par ADB sans écraser les autres services : passer par les Paramètres.

## 🛠️ Développement

Prérequis : JDK 21 (Gradle s'y lance d'office, voir `gradle/gradle-daemon-jvm.properties`) et le SDK Android (API 34).

```bash
./gradlew :core:test testDebugUnitTest   # tests unitaires (JVM) : cœur puis app
./gradlew assembleDebug       # APK de debug : app/build/outputs/apk/debug/
./gradlew :core:jacocoTestReport createDebugUnitTestCoverageReport   # couverture (JaCoCo) :
#   core/build/reports/jacoco/test/html/index.html et app/build/reports/coverage/test/debug/index.html
```

Deux modules : `:core` (Kotlin/JVM pur : règles, monde, comportement, ports) et `:app` (Android : service,
fenêtres, dessin, persistance). Le projet n'utilise que le framework Android et la bibliothèque standard Kotlin
(aucune dépendance AndroidX).

### CI / CD (GitHub Actions)

| Workflow | Déclencheur | Rôle |
|---|---|---|
| [`ci.yml`](.github/workflows/ci.yml) | push sur `main`, pull requests | tests unitaires, couverture (rapport en artefact ; badge SVG commité sur `main`, sans service externe), lint, APK debug (artefact) |
| [`release.yml`](.github/workflows/release.yml) | tag `v*` ou lancement manuel | tests, APK release signé, APK non signé, AAB signé, publication GitHub Release |
| [`dependabot-automerge.yml`](.github/workflows/dependabot-automerge.yml) | PR Dependabot | fusion automatique dès que la CI est verte, **uniquement pour les mises à jour patch et minor** (les majeures restent manuelles) |

Les actions GitHub sont épinglées par SHA de commit (le tag est indiqué en commentaire) ; Dependabot les met à jour.

**Publier une version** :

```bash
git tag v1.1.0 && git push origin v1.1.0
```

ou *Actions → Release → Run workflow* avec le numéro de version.

La version doit être de la forme `X.Y.Z` (ex. `1.2.0`), sinon le workflow échoue.

**Signature** : la release **exige** les 4 secrets du dépôt
`TAMACOOKIE_KEYSTORE_BASE64`, `TAMACOOKIE_KEYSTORE_PASSWORD`, `TAMACOOKIE_KEY_ALIAS` et `TAMACOOKIE_KEY_PASSWORD`.
Sans clé de signature, le workflow échoue (aucune release signée avec une clé de debug n'est publiée).

## 📄 Licence

TamaCookie est un logiciel libre distribué sous licence
**GNU Lesser General Public License v3.0 ou ultérieure** (LGPL-3.0-or-later) :
voir [`LICENSE`](LICENSE), qui complète la [GNU GPL v3](COPYING).

Copyright © 2026 tt-box
