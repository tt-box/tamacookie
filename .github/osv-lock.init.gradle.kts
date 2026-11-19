// Script d'init Gradle utilisé par le scan CVE (OSV-Scanner) de la CI, jamais par le build normal.
//
// OSV-Scanner ne lit pas les build.gradle.kts : il lit les fichiers gradle.lockfile. Ce script active le
// verrouillage des seuls classpaths d'exécution (les bibliothèques embarquées dans l'APK, transitives
// comprises ; lint, detekt, Kover et les plugins sont exclus), puis
//   ./gradlew --init-script .github/osv-lock.init.gradle.kts <projet>:dependencies --write-locks
// écrit <projet>/gradle.lockfile sur le runner. Rien n'est versionné : aucun fichier de verrous à tenir à
// jour à chaque PR Dependabot.
allprojects {
    configurations.configureEach {
        if (name == "runtimeClasspath" || name.endsWith("ReleaseRuntimeClasspath") || name == "releaseRuntimeClasspath") {
            resolutionStrategy.activateDependencyLocking()
        }
    }
}
