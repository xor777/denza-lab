plugins { id("com.android.application") }
android {
    namespace = "dev.denza.vehiclecontrol.probe"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.denza.vehiclecontrol.probe"
        minSdk = 33
        targetSdk = 33
        versionCode = 4
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("test").resources.srcDir("src/sharedTest/resources")
    sourceSets.getByName("androidTest").assets.srcDir("src/sharedTest/resources")
    sourceSets.getByName("test").java.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/java")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
abstract class BundleVehicleCatalog : DefaultTask() {
    @get:InputFile abstract val catalog: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun copyCatalog() {
        val target = outputDirectory.file("vehicle_controls.json").get().asFile
        target.parentFile.mkdirs()
        catalog.get().asFile.copyTo(target, overwrite = true)
    }
}
val bundleCatalog = tasks.register<BundleVehicleCatalog>("bundleCatalog") {
    catalog.set(rootProject.layout.projectDirectory.file("tools/vehicle_controls.json"))
    outputDirectory.set(layout.buildDirectory.dir("generated/catalog"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(bundleCatalog, BundleVehicleCatalog::outputDirectory)
}
tasks.withType<Test>().configureEach { systemProperty("vehicle.catalog", rootProject.file("tools/vehicle_controls.json").absolutePath) }
dependencies {
    implementation(project(":dishare-bridge"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
