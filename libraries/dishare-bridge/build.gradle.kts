plugins {
    id("com.android.library")
}

android {
    namespace = "dev.denza.disharebridge"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
