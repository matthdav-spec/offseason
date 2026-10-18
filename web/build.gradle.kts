// The iPhone (and any-browser) web app. Uses the same plan engine as the Android app.
// Build:  gradlew :web:jsBrowserDistribution  → web/build/dist/js/productionExecutable
// Try it: gradlew :web:jsBrowserDevelopmentRun  → opens http://localhost:8080
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    js {
        browser {
            testTask { enabled = false }
            commonWebpackConfig {
                outputFileName = "offseason.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":engine"))
            implementation(libs.compose.mp.runtime)
            implementation(libs.compose.html.core)
            implementation(libs.compose.html.svg)
        }
    }
}
