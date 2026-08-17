plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("buildlogic.removefirstlast-fix")
}

android {
    namespace = "com.newoether.agora"
    compileSdk { version = release(36) }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // Deliberately different from the installed legacy Agora package so this APK can be
        // installed without uninstalling the old app and can receive a manual .agora import.
        applicationId = "com.newoether.agora.workbench.v2"
        minSdk = 24
        targetSdk = 36
        versionCode = 40
        versionName = "1.5.0-workbench-v2"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_shared")
                targets += listOf("agora_llama", "agora_proot")
            }
        }
    }

    ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    flavorDimensions += "store"
    productFlavors {
        create("play") { dimension = "store" }
        create("fdroid") { dimension = "store" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    buildFeatures { compose = true }
    @Suppress("UnstableApiUsage")
    packaging { jniLibs { useLegacyPackaging = true } }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.markdown)
    implementation(libs.jetbrains.markdown)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.jlatexmath.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.okhttp)
    implementation(libs.material.color.utilities)
    implementation(libs.lottie.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.jsch)
    implementation(libs.commons.compress)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
}

tasks.whenTaskAdded {
    if (name.contains("ArtProfile") || name.contains("BaselineProfile") || name.contains("baselineProfile")) enabled = false
    if (name.contains("StripDebugSymbols") || name.contains("MergeNativeDebugMetadata")) enabled = false
}
