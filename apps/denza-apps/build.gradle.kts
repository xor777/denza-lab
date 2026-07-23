plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.denza.apps"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.denza.apps"
        minSdk = 33
        targetSdk = 33
        versionCode = 6
        versionName = "0.4.2"
    }

    buildFeatures {
        aidl = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidComponents {
        onVariants(selector().all()) { variant ->
            variant.outputs.forEach { output ->
                output.outputFileName.set("denza-apps.apk")
            }
        }
    }

    lint {
        // DiLink 5.1 is pinned to the Android 13 compatibility contract until
        // firmware validation permits a target SDK upgrade.
        disable += "OldTargetApi"
        // Dependency versions are intentionally firmware-qualified as a set.
        disable += "GradleDependency"
        // KTX substitutions are stylistic and would add noise to this Java/Kotlin
        // mixed module without changing the platform contract.
        disable += "UseKtx"
    }
}

dependencies {
    implementation(project(":dishare-bridge"))
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
