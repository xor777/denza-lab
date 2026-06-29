plugins {
    id("com.android.application")
}

android {
    namespace = "dev.denza.mirrors"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.denza.mirrors"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("denza-mirrors.apk")
        }
    }
}
