plugins {
    id("com.android.application")
}

android {
    namespace = "dev.denza.audio.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.denza.audio.probe"
        minSdk = 33
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidComponents {
        onVariants(selector().all()) { variant ->
            variant.outputs.forEach { output ->
                output.outputFileName.set("audio-probe.apk")
            }
        }
    }

    lint {
        // The target SDK is pinned to the Android 13 contract of the tested IVI.
        disable += "OldTargetApi"
    }
}
