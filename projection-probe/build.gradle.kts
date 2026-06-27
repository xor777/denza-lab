plugins {
    id("com.android.application")
}

android {
    namespace = "com.byd.cluster.projection.mapdemo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.byd.cluster.projection.mapdemo"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("denza-mirrors-${variant.name}.apk")
        }
    }
}
