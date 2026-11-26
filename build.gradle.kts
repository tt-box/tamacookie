plugins {
    id("com.android.application") version "9.5.3" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    // Module :core (Kotlin/JVM pur) : même version de Kotlin que l'app.
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    // Analyse statique (outil de build seulement) : detekt 2.x, compilé avec Kotlin 2.4 (voir docs/historique.md, étape 8b).
    id("dev.detekt") version "2.0.0-alpha.6" apply false
}

// Même configuration detekt pour les deux modules : règles par défaut + config/detekt/detekt.yml,
// une baseline par module (config/detekt/baseline-<module>.xml) pour que l'existant ne bloque pas.
subprojects {
    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline.set(rootProject.file("config/detekt/baseline-${project.name}.xml"))
        parallel.set(true)
    }
}
