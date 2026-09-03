plugins {
    id("com.android.application")
}

android {
    namespace = "dev.denza.personbean.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.denza.personbean.probe"
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
                output.outputFileName.set("personbean-provider-probe.apk")
            }
        }
    }

    lint {
        // The target SDK is pinned to the Android 13 contract of the tested IVI.
        disable += "OldTargetApi"
    }
}
