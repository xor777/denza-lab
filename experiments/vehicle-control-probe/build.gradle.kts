import java.security.MessageDigest

plugins { id("com.android.application") }
android {
    namespace = "dev.denza.vehiclecontrol.probe"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.denza.vehiclecontrol.probe"
        minSdk = 33
        targetSdk = 33
        versionCode = 7
        versionName = "0.3.3"
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
abstract class BundleVoiceModel : DefaultTask() {
    @get:InputFile abstract val archive: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun bundle() {
        val source = archive.get().asFile
        val expected = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "Unexpected Vosk model SHA-256" }
        val output = outputDirectory.get().asFile.apply { mkdirs() }
        source.copyTo(output.resolve("voice-model.zip"), overwrite = true)
        output.resolve("voice-model.sha256").writeText(expected)
    }
}
// Preparation is explicit and host-only; the APK never downloads models.
val bundleVoiceModel = tasks.register<BundleVoiceModel>("bundleVoiceModel") {
    archive.set(layout.buildDirectory.file("voice-input/voice-model.zip"))
    outputDirectory.set(layout.buildDirectory.dir("generated/voice"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(bundleCatalog, BundleVehicleCatalog::outputDirectory)
    variant.sources.assets?.addGeneratedSourceDirectory(bundleVoiceModel, BundleVoiceModel::outputDirectory)
}
tasks.withType<Test>().configureEach { systemProperty("vehicle.catalog", rootProject.file("tools/vehicle_controls.json").absolutePath) }
dependencies {
    implementation(project(":dishare-bridge"))
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
