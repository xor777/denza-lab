plugins {
    id("com.android.application")
}

android {
    namespace = "dev.denza.split.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.denza.split.launcher"
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
                output.outputFileName.set("denza-split-launcher.apk")
            }
        }
    }

    lint {
        disable += "OldTargetApi"
        disable += "GradleDependency"
    }
}
