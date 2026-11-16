plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// android/VERSION holds the app's semver; versionCode = major*1_000_000 + minor*1_000 + patch.
val appVersion = rootProject.file("VERSION").readText().trim()
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (major, minor, patch) ->
    major * 1_000_000 + minor * 1_000 + patch
}

// Release signing comes from the environment (set only by the tag-triggered
// release job in CI); without it the release APK is built unsigned.
val releaseKeystore = providers.environmentVariable("NEKOPOST_KEYSTORE_FILE").orNull

android {
    namespace = "com.drinkcat.nekopost"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.drinkcat.nekopost"
        minSdk = 30
        // Not 37: Android 17 holds OTP SMS broadcasts for 3 hours for apps targeting 37.
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        // Stable, committed debug key so every CI build can be installed over the previous one.
        // It is public, so debug builds are a separate app (applicationIdSuffix below):
        // an APK signed with it can never update a release install. Real-device
        // runs with a real token use a signed release from an app-v* tag.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("NEKOPOST_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("NEKOPOST_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("NEKOPOST_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            // com.drinkcat.nekopost.debug: installs next to the release app and keeps its own data and Keystore keys.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += setOf("OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "3g"
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":forwarder"))

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.material3.adaptive.layout)
    implementation(libs.androidx.window)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.play.services.code.scanner)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
