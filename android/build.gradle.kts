// Root build file: plugin versions come from gradle/libs.versions.toml.
// AGP 9 compiles Kotlin itself ("built-in Kotlin"); declaring the Kotlin
// Android plugin here only pins the Kotlin Gradle plugin version on the
// build classpath, it is never applied to a module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
}
