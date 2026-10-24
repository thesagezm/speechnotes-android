plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.speechnotes.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.speechnotes.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 stayed off until 2026-10-24: the release/universal APKs
            // shipped the full dex set unshrunk and 168 MB of native libs for
            // every ABI. Minify + resource-shrink is the single biggest
            // install-size and cold-start lever for the arm64 phone build.
            isMinifyEnabled = true
            isShrinkResources = true
            // Debug-key signing so release builds are installable for
            // verification (Waydroid / sideload) without a release keystore.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // The APK carried all four Android ABIs' ONNX Runtime + ML Kit native
    // libraries — 168 MB of .so for a device that runs exactly one of them.
    // abiFilters keeps the launcher-side build installable anywhere (it falls
    // back to a universal APK when a build target is set) while the per-ABI
    // splits below each carry a single set, ~40 MB apiece.
    // Run `./gradlew assembleDebug` for the universal APK the user installs by
    // hand, or `assembleDebugX86_64`/`assembleDebugArm64` for a split.
    splits {
        abi {
            isEnable = providers.gradleProperty("splits").orNull == "true"
            reset()
            include("x86_64", "arm64-v8a")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildToolsVersion = "36.0.0"

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Offline workstation: lint-vital resolves intellij-core /
        // kotlin-compiler jars that were never cached and cannot be
        // downloaded here. The fatal-lint gate stays off; unit tests plus
        // on-device verification remain the build gates.
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

    // The M3 Expressive BOM (2026.09.00) resolves Compose 1.12.x, which
    // requires compileSdk 37. Pin the compose UI/foundation families back
    // to the 1.9.x line the AGP 8.9.1 toolchain supports.
    // material-icons-extended intentionally stays where the BOM puts it —
    // androidx publishes icons-core and icons-extended as one family and
    // forcing either alone breaks the pair.
    configurations.all {
        resolutionStrategy {
            force("androidx.compose.ui:ui-android:1.9.1")
            force("androidx.compose.ui:ui:1.9.1")
            force("androidx.compose.ui:ui-graphics-android:1.9.1")
            force("androidx.compose.ui:ui-text-android:1.9.1")
            force("androidx.compose.ui:ui-unit-android:1.9.1")
            force("androidx.compose.ui:ui-tooling-preview-android:1.9.1")
            force("androidx.compose.ui:ui-tooling-android:1.9.1")
            force("androidx.compose.ui:ui-tooling-data-android:1.9.1")
            force("androidx.compose.foundation:foundation-android:1.9.1")
            force("androidx.compose.foundation:foundation-layout-android:1.9.1")
            force("androidx.compose.runtime:runtime-android:1.9.1")
            force("androidx.compose.runtime:runtime-saveable-android:1.9.1")
            force("androidx.compose.animation:animation-android:1.9.1")
            force("androidx.compose.animation:animation-core-android:1.9.1")
            force("androidx.compose.material:material-ripple-android:1.9.1")
            force("androidx.compose.material:material-android:1.9.1")
            force("androidx.compose.material:material:1.9.1")
            // material-icons-core/extended stay at the BOM's own pin (1.7.8)
            // — the icons artifact family is versioned independently from
            // the compose UI line and 1.9.1 does not exist for it.
            force("androidx.compose.material:material-icons-core:1.7.8")
            force("androidx.compose.material:material-icons-core-android:1.7.8")
            force("androidx.compose.material:material-icons-extended:1.7.8")
            force("androidx.compose.material:material-icons-extended-android:1.7.8")
            // material3 pinned to 1.3.2 to match the 1.9.x ui/runtime line:
            // the BOM's 1.4.0 is compiled against the 1.12 runtime, and that
            // pairing mismatch is the prime suspect for the on-device
            // interaction-timing bugs (toggles reading taps late). The
            // expressive look comes from Theme.kt shapes/colors; no 1.4-only
            // API is referenced anywhere in the app.
            force("androidx.compose.material3:material3:1.3.2")
            force("androidx.compose.material3:material3-android:1.3.2")
        }
    }

    dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.onnxruntime.android)
    implementation(libs.pdfbox.android)
    implementation(libs.androidx.webkit)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.coil.compose)
    implementation(libs.androidx.media)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
