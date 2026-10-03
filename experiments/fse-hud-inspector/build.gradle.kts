plugins {
    id("com.android.application")
}

android {
    namespace = "dev.denza.fsehud.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.denza.fsehud.probe"
        minSdk = 32
        targetSdk = 32 // The investigated FSE is Android 12 / SDK 32.
        versionCode = 5
        versionName = "0.2.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidComponents {
        onVariants(selector().all()) { variant ->
            variant.outputs.forEach { output ->
                output.outputFileName.set("fse-hud-inspector.apk")
            }
        }
    }

    lint {
        // Private firmware probe, not a Play release; retain SDK 32 behavior on the FSE.
        disable += listOf("OldTargetApi", "ExpiredTargetSdkVersion")
    }
}
