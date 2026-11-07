import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9 : support Kotlin intégré, le plugin org.jetbrains.kotlin.android n'est plus appliqué ici
// (la version de KGP est fixée dans le build.gradle.kts racine).
plugins {
    id("com.android.application")
}

// Version : passée par la CI (-PversionName=1.2.0 -PversionCode=42), sinon valeurs de dev.
val appVersionName = (project.findProperty("versionName") as String?) ?: "1.0"
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// Signature release (clé ECDSA P-256) : `keystore.properties` à la racine en local (ignoré
// par git), variables d'environnement TAMACOOKIE_* en CI (secrets GitHub). Sans clé, l'APK
// release sort NON signé — jamais signé avec la clé de debug, qui est publique de fait.
val keystoreProperties =
    Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

fun signingValue(key: String, env: String): String? = keystoreProperties.getProperty(key) ?: System.getenv(env)

val keystorePath: String? = signingValue("storeFile", "TAMACOOKIE_KEYSTORE")
val keystorePassword: String? = signingValue("storePassword", "TAMACOOKIE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = signingValue("keyAlias", "TAMACOOKIE_KEY_ALIAS")
val releaseKeyPassword: String? = signingValue("keyPassword", "TAMACOOKIE_KEY_PASSWORD")
val hasReleaseKey =
    keystorePath != null && file(keystorePath).exists() &&
        listOf(keystorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.tamacookie"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tamacookie"
        minSdk = 36          // Android 16
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // minSdk 36 : v1 (JAR) inutile ; v2 + v3/v3.1 (rotation de clé) + v4.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Couverture des tests JVM (JaCoCo intégré à AGP) : ./gradlew createDebugUnitTestCoverageReport
            // → app/build/reports/coverage/test/debug/ (index.html + report.xml). R, BuildConfig et Manifest
            // sont exclus par AGP.
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testCoverage {
        // Version de JaCoCo plus récente que celle d'AGP 8.5 : lit les classes des JDK récents (21+).
        jacocoVersion = "0.8.13"
    }

    testOptions {
        // Les classes Android (stubs) renvoient des valeurs par défaut au lieu de lever "Stub!"
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Les avertissements existants sont figés dans la baseline ; tout nouveau problème bloque.
        baseline = file("lint-baseline.xml")
        abortOnError = true
        // Étape 8c : les textes d'interface sont dans res/values/strings.xml — un texte en dur ou une
        // concaténation affichée fait échouer le build (au lieu d'un simple avertissement).
        error += listOf("HardcodedText", "SetTextI18n")
        // Étape 8d (accessibilité) : bloquants pour le code neuf ; les anciens cas sont dans la baseline ou
        // supprimés localement avec leur raison (@SuppressLint : gestes doublés par des actions d'accessibilité).
        error += listOf(
            "ContentDescription", "ClickableViewAccessibility", "KeyboardInaccessibleWidget", "LabelFor",
            "GetContentDescriptionOverride", "AccessibilityFocus", "AccessibilityScrollActions",
            "AccessibilityWindowStateChangedEvent",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Aucune dépendance AndroidX : uniquement le framework Android + Kotlin stdlib.
dependencies {
    // Le cœur Kotlin/JVM pur (règles, monde, cerveau, ports, fonctionnalités) : voir core/build.gradle.kts.
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
