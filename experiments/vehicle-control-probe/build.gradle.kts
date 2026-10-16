plugins {
    id("com.android.application")
    id("com.chaquo.python") version "17.0.0"
}
android {
    namespace = "dev.denza.vehiclecontrol.probe"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.denza.vehiclecontrol.probe"
        minSdk = 33
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
val bundleController = tasks.register<Copy>("bundleController") {
    from(rootProject.file("tools")) {
        include("vehicle_control.py", "vehicle_windows.py", "vehicle_controls.json", "vehicle_control.html")
    }
    into(layout.buildDirectory.dir("generated/controller"))
}
chaquopy {
    defaultConfig { version = "3.13" }
    sourceSets.getByName("main").srcDir(layout.buildDirectory.dir("generated/controller").get().asFile)
}
tasks.named("preBuild") { dependsOn(bundleController) }
tasks.configureEach {
    if (name.contains("PythonSources")) dependsOn(bundleController)
}
dependencies {
    implementation(project(":dishare-bridge"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
