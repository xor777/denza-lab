plugins {
    id("com.android.application")
}

dependencies {
    // The SOME/IP wire (HudSomeIp: bind, start, fire) and the byte-exact stock encoders
    // (StockMessages, Pb, Topics, Json). The probe keeps its own feed player and answer listener:
    // it must play one scenario, go quiet, and always send the stock end sequence, which neither
    // the shared session policy nor the live engine does. adas-link is a library, not a probe, so
    // this dependency keeps the "no probe in a product dependency" rule (nothing depends back).
    implementation(project(":hud-link"))
    implementation(project(":adas-link"))
}

android {
    namespace = "dev.denza.adasplayer.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.denza.adasplayer.probe"
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
                output.outputFileName.set("adas-parked-player-probe.apk")
            }
        }
    }

    lint {
        // The target SDK is pinned to the Android 13 contract of the tested IVI.
        disable += "OldTargetApi"
    }
}
