plugins { id("com.android.application") }

android {
    namespace = "dev.denza.alicewheel.probe"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.denza.alicewheel.probe"
        minSdk = 33
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { disable += "OldTargetApi" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
