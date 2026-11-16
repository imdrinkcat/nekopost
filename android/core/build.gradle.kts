plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.drinkcat.nekopost.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        // targetSdk stays at 36 on purpose (Android 17 holds OTP SMS for
        // targetSdk 37 apps); version-bump nags are handled by Dependabot.
        disable += setOf("OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.material.icons.core)
    api(libs.material3.adaptive)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.window)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
