// The plan engine: all training logic, in plain Kotlin with no Android or browser code.
// It's built twice from the same source: for the Android app (jvm) and for the iPhone web app (js).
// Shared code lives in src/commonMain; the few platform-specific bits in src/jvmMain and src/jsMain.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    js {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            // Garmin's official FIT SDK: reads .fit ride files on Android.
            implementation(libs.garmin.fit)
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            implementation("org.junit.jupiter:junit-jupiter-params")
            runtimeOnly(libs.junit.launcher)
            implementation(libs.garmin.fit)
        }
        jsMain.dependencies {
            // Garmin's official FIT SDK for JavaScript, and a small zip library.
            implementation(npm("@garmin/fitsdk", "21.218.0"))
            implementation(npm("fflate", "0.8.3"))
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
