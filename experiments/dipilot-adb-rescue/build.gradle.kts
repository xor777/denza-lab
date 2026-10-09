plugins {
    id("com.android.application")
}

android {
    namespace = "dev.denza.dipilotkey.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.denza.dipilotkey.probe"
        minSdk = 33
        targetSdk = 33
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        encoding = "UTF-8"
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidComponents {
        onVariants(selector().all()) { variant ->
            variant.outputs.forEach { output ->
                output.outputFileName.set("dipilot-adb-rescue.apk")
            }
        }
    }

    lint {
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation(project(":dishare-bridge"))

    testImplementation("junit:junit:4.13.2")
}
