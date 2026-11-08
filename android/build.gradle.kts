plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// D45: the licence notices tasks (licencesWrite / licencesCheck, wrapped by scripts/licences.sh) are applied to :app from here:
// lintAnalyzeDebug crashed when app/build.gradle.kts applied the script, so that file stays plain.
project(":app") { apply(from = rootProject.file("licences/licences.gradle.kts")) }
