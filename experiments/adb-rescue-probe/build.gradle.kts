plugins {
    id("com.android.application")
}

android {
    namespace = "dev.denza.adbrescue.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.denza.adbrescue.probe"
        minSdk = 33
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        // The report and the buttons are Russian; javac must not guess.
        encoding = "UTF-8"
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidComponents {
        onVariants(selector().all()) { variant ->
            variant.outputs.forEach { output ->
                output.outputFileName.set("adb-rescue.apk")
            }
        }
    }

    lint {
        // The target SDK is pinned to the Android 13 contract of the tested IVI.
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation(project(":dishare-bridge"))

    testImplementation("junit:junit:4.13.2")
}
