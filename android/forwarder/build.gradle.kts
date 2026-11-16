plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.drinkcat.nekopost.forwarder"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        disable += setOf("OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

dependencies {
    // Only the forwarder API (interfaces and plain data classes) is used from :core.
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.tink.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
