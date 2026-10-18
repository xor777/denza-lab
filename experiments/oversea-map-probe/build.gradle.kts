plugins {
    id("com.android.application")
}

abstract class PrepareOverseaAssets : Exec() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

val capture = rootProject.file("captures/oversea-map-probe-20261018")
val generatedAssets = layout.buildDirectory.dir("generated/overseaAssets")
val prepareAssets by tasks.registering(PrepareOverseaAssets::class) {
    inputs.file("prepare_assets.py")
    inputs.file(rootProject.file("research/cluster-native/read_binary_scene.py"))
    inputs.file(File(capture, "stock-map-source.png"))
    inputs.dir(File(capture, "firmware-assets"))
    outputDirectory.set(generatedAssets)
    commandLine(
        rootProject.file(".venv-firmware/bin/python"), file("prepare_assets.py"),
        "--capture", capture, "--output", generatedAssets.get().asFile,
        "--decoder-path", File(capture, "python-deps")
    )
}

android {
    namespace = "dev.denza.overseamap.probe"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.denza.overseamap.probe"
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
            variant.sources.assets?.addGeneratedSourceDirectory(prepareAssets, PrepareOverseaAssets::outputDirectory)
            variant.outputs.forEach { it.outputFileName.set("oversea-map-probe.apk") }
        }
    }
    lint { disable += "OldTargetApi" }
}

tasks.named("preBuild") { dependsOn(prepareAssets) }
