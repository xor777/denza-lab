plugins {
    id("com.android.application")
}

dependencies {
    // The HUD's wire: Binder calls, protobuf writer, topics and road-packet fields. The probe
    // keeps its own scenario runner: it must be able to go quiet without a clear, hold two
    // sessions at once and stop one without clearing, which the shared session policy never does.
    implementation(project(":hud-link"))
    testImplementation("junit:junit:4.13.2")
}

android {
    namespace = "dev.denza.hudsurfaces.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.denza.hudsurfaces.probe"
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
                output.outputFileName.set("hud-surfaces-probe.apk")
            }
        }
    }

    lint {
        // The target SDK is pinned to the Android 13 contract of the tested IVI.
        disable += "OldTargetApi"
    }
}
