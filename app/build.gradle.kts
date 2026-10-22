// The Android app: screens, storage and file export. All training logic comes from :engine.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.offseason.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.offseason.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "1.0.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key for now so testers can sideload it. Replace before any Play Store release.
            signingConfig = signingConfigs.getByName("debug")
        }
        // A test build of unreleased features. It installs ALONGSIDE the main app as
        // "DBAP Preview", with its own data, so testing it can't touch a real plan.
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            // Its app name comes from app/src/preview/res.
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
}
