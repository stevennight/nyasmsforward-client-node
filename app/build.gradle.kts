plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// VERSION (MAJOR.MINOR.PATCH) is the single source of truth; the release workflow checks it against the git tag.
val appVersion: String = rootProject.file("VERSION").readText().trim()
val versionParts = appVersion.split(".").map { it.toInt() }
require(versionParts.size == 3) { "VERSION must be MAJOR.MINOR.PATCH, got '$appVersion'" }

android {
    namespace = "app.nya.smsforward.node"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.nya.smsforward.node"
        minSdk = 26
        targetSdk = 36
        versionName = appVersion
        versionCode = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2]
    }

    // Two editions from one codebase (docs/版本.md). "lite" is the forwarding receiver and keeps the original package, so
    // existing installs update in place. "full" is a separate app that can also be the phone's default SMS app, which
    // Android requires before anything may write (e.g. delete from) the SMS database.
    flavorDimensions += "edition"
    productFlavors {
        create("lite") {
            dimension = "edition"
            buildConfigField("boolean", "FULL_EDITION", "false")
            buildConfigField("String", "APK_NAME_PREFIX", "\"NyaSmsForward-Node\"")
        }
        create("full") {
            dimension = "edition"
            applicationIdSuffix = ".full"
            buildConfigField("boolean", "FULL_EDITION", "true")
            buildConfigField("String", "APK_NAME_PREFIX", "\"NyaSmsForward-Node-Full\"")
        }
    }

    // Release signing comes from the environment (the release workflow restores the keystore from a secret).
    // Without it `assembleRelease` still works and produces an unsigned APK.
    val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (!keystoreFile.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!keystoreFile.isNullOrBlank()) {
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
        // android.* stubs return defaults instead of throwing, so pure logic that touches e.g. Log can be unit-tested.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.work.runtime)
    implementation(libs.zxing.embedded) // QR scanner for pairing (works without Google Play services)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.sqlite.jdbc)
}
