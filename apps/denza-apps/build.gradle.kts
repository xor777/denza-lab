import javax.inject.Inject
import javax.tools.ToolProvider
import org.gradle.process.ExecOperations
import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Packs one platform-only `app_process` entry point into a jar of its own.
 *
 * These helpers run one class as the shell user through `app_process`. The split helper's former
 * classpath was the application APK: 62 MB of dex for ART to open and verify on every one-shot
 * call, measured at 1.36 s each on the car. Packing each platform-only entry point separately
 * keeps that classpath small and prevents application code from silently entering the shell side.
 *
 * Each is compiled here rather than taken from the variant's own class output on purpose: it
 * depends on nothing but the platform, so this task needs no build-order relationship with the
 * application's compilation, and the jar cannot silently pick up anything else.
 */
abstract class PackShellProxy : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:InputFiles
    abstract val additionalSources: ConfigurableFileCollection

    @get:InputFiles
    abstract val androidJar: ConfigurableFileCollection

    @get:Internal
    abstract val sdkDirectory: DirectoryProperty

    @get:Input
    abstract val minApi: Property<Int>

    @get:Input
    abstract val archiveName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun pack() {
        val classes = temporaryDir.resolve("classes")
        classes.deleteRecursively()
        classes.mkdirs()
        val platform = androidJar.files.joinToString(File.pathSeparator)
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) {
            "Gradle must run on a JDK to pack a shell proxy"
        }
        val compiled = compiler.run(
            null,
            null,
            null,
            "--release",
            "17",
            "-nowarn",
            "-classpath",
            platform,
            "-d",
            classes.absolutePath,
            *(listOf(source.get().asFile) + additionalSources.files.sortedBy { it.absolutePath })
                .map { it.absolutePath }.toTypedArray(),
        )
        check(compiled == 0) { "could not compile ${source.get().asFile.name}" }

        val output = outputDirectory.get().asFile
        output.mkdirs()
        val jar = output.resolve(archiveName.get())
        jar.delete()
        execOperations.exec {
            executable = d8().absolutePath
            androidJar.files.forEach { library -> args("--lib", library.absolutePath) }
            args("--min-api", minApi.get().toString())
            args("--release", "--output", jar.absolutePath)
            args(classes.walkTopDown().filter { it.extension == "class" }.map { it.absolutePath }
                .toList())
        }
        check(jar.isFile && jar.length() > 0) { "d8 produced no ${archiveName.get()}" }
    }

    /** The newest build-tools that actually ships a `d8`; the SDK may hold several. */
    private fun d8(): File {
        val candidates = sdkDirectory.get().asFile.resolve("build-tools")
            .listFiles()
            .orEmpty()
            .sortedBy(File::getName)
            .map { version -> version.resolve("d8") }
            .filter(File::canExecute)
        return candidates.lastOrNull() ?: error("no build-tools/*/d8 in the Android SDK")
    }
}

/** Offline candidate only. Packaging into an APK has a separate, non-overridable qualification gate. */
abstract class BuildCloudRuntime : DefaultTask() {
    @get:InputFiles abstract val sources: ConfigurableFileCollection
    @get:InputFile abstract val script: RegularFileProperty
    @get:InputFile abstract val firmware: RegularFileProperty
    @get:InputFile abstract val linker: RegularFileProperty
    @get:InputFiles abstract val androidJar: ConfigurableFileCollection
    @get:Internal abstract val sdkDirectory: DirectoryProperty
    @get:Input abstract val python: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction fun buildRuntime() {
        val platform = androidJar.files.single { it.name == "android.jar" }
        val d8 = sdkDirectory.get().asFile.resolve("build-tools").listFiles().orEmpty()
            .sortedBy(File::getName).map { it.resolve("d8") }.lastOrNull(File::canExecute)
            ?: error("no Android d8")
        execOperations.exec {
            executable = python.get()
            args(script.get().asFile.absolutePath, "--firmware", firmware.get().asFile.absolutePath,
                "--linker", linker.get().asFile.absolutePath, "--android-jar", platform.absolutePath,
                "--d8", d8.absolutePath, "--out", outputDirectory.get().asFile.absolutePath)
            environment("PYTHONDONTWRITEBYTECODE", "1")
        }
    }
}

abstract class QualifiedCloudAssets : DefaultTask() {
    @get:InputDirectory abstract val candidate: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun qualify() {
        val input = candidate.get().asFile
        val manifest = JsonSlurper().parse(input.resolve("cloud-native-manifest.json")) as Map<*, *>
        check(manifest["protocol"] == 3 && manifest["native_protocol"] == 2 &&
            manifest["profile"] == "awake-alpha-v1" && manifest["profile_qualified"] == true &&
            manifest["product_qualified"] == false) { "Cloud runtime is not qualified for the controlled awake alpha" }
        val capabilities = manifest["profile_capabilities"] as Map<*, *>
        check(listOf("native_registration_codec", "opaque_data_ingest", "control_awake",
            "wake_ack_awake", "timers_awake", "post_login_awake", "heartbeat")
            .all { capabilities[it] == true }) { "Cloud awake profile has incomplete native chains" }
        val files = manifest["files"] as Map<*, *>
        val names = listOf("cloud-native-proxy.jar", "cloud-native-worker")
        val hashes = names.associateWith { name ->
            MessageDigest.getInstance("SHA-256").digest(input.resolve(name).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        check(names.all { files[it] == hashes[it] }) { "Cloud runtime hash mismatch" }
        check(manifest["runtime_id"] == hashes.getValue(names[0]).take(12) + "-" + hashes.getValue(names[1]).take(12))
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        (names + "cloud-native-manifest.json").forEach { input.resolve(it).copyTo(output.resolve(it)) }
    }
}

// Native session lifecycle is still under offline qualification. Do not ship a
// partial experimental worker merely by passing a build property.
val cloudNativePilot = false

android {
    namespace = "dev.denza.apps"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.denza.apps"
        minSdk = 33
        targetSdk = 33
        // versionName is the owner's product version - it changes only by their
        // explicit decision. versionCode is an internal build counter so the car
        // can tell builds apart during acceptance; it never drives the version.
        versionCode = 60
        versionName = "0.7.0-alpha.1"
        buildConfigField("boolean", "CLOUD_NATIVE_PILOT", cloudNativePilot.toString())
        buildConfigField("String", "CLOUD_RUNTIME_PROFILE", "\"awake-alpha-v1\"")
    }

    buildFeatures {
        // The navigation picker offers this app's own instruments beside the third-party
        // navigators, and addresses them by the real application id rather than by a copy of
        // the string that would quietly stop matching if the id ever moved.
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidComponents {
        val platform = sdkComponents.bootClasspath
        val sdk = sdkComponents.sdkDirectory
        val cloudCandidate = tasks.register<BuildCloudRuntime>("buildCloudRuntimeCandidate") {
            sources.from(rootProject.fileTree("tools/telematics/runtime") { include("*.java"); exclude("*Test.java") })
            sources.from(rootProject.file("tools/telematics/OncarTls.java"))
            sources.from(rootProject.fileTree("research/telematics-firmware") { include("*.py", "*.c", "*.h") })
            script.set(rootProject.layout.projectDirectory.file("tools/telematics/build_runtime_package.py"))
            firmware.set(rootProject.layout.projectDirectory.file(
                "captures/telematics-20260923/readable-firmware/current-files/system/bin/cloudmanager"))
            linker.set(layout.file(providers.gradleProperty("cloudRuntimeLinker")
                .orElse(providers.environmentVariable("DENZA_CLOUD_LINKER")).map { File(it) }))
            python.convention(providers.environmentVariable("DENZA_CLOUD_PYTHON").orElse("python3"))
            androidJar.from(platform)
            sdkDirectory.set(sdk)
            outputDirectory.set(layout.buildDirectory.dir("cloud-runtime/candidate"))
        }
        onVariants(selector().all()) { variant ->
            // Management must survive a downgrade/kill switch: it can detach/STOP old owners
            // even when this APK does not contain or permit the ARM64 session engine.
            val cloudControl = tasks.register<PackShellProxy>(
                "pack${variant.name.replaceFirstChar(Char::titlecase)}CloudControlProxy",
            ) {
                source.set(rootProject.layout.projectDirectory.file("tools/telematics/runtime/CloudNativeMain.java"))
                additionalSources.from(rootProject.fileTree("tools/telematics/runtime") {
                    include("*.java"); exclude("*Test.java", "CloudNativeMain.java", "CloudStartPermit.java")
                })
                additionalSources.from(rootProject.file("tools/telematics/OncarTls.java"))
                androidJar.from(platform)
                sdkDirectory.set(sdk)
                minApi.set(33)
                archiveName.set("cloud-control-proxy.jar")
            }
            variant.sources.assets?.addGeneratedSourceDirectory(cloudControl, PackShellProxy::outputDirectory)
            if (cloudNativePilot) {
                val cloudAssets = tasks.register<QualifiedCloudAssets>(
                    "pack${variant.name.replaceFirstChar(Char::titlecase)}CloudRuntime",
                ) {
                    candidate.set(cloudCandidate.flatMap { it.outputDirectory })
                    outputDirectory.set(layout.buildDirectory.dir("cloud-runtime/${variant.name}/assets"))
                }
                variant.sources.assets?.addGeneratedSourceDirectory(cloudAssets, QualifiedCloudAssets::outputDirectory)
            }
            variant.outputs.forEach { output ->
                output.outputFileName.set("denza-apps.apk")
            }
            val packSplit = tasks.register<PackShellProxy>(
                "pack${variant.name.replaceFirstChar(Char::titlecase)}SplitTaskProxy",
            ) {
                source.set(
                    layout.projectDirectory.file(
                        "src/main/java/dev/denza/apps/feature/split/SplitTaskProxyMain.java",
                    ),
                )
                androidJar.from(platform)
                sdkDirectory.set(sdk)
                minApi.set(33)
                archiveName.set("split-task-proxy.jar")
            }
            variant.sources.assets?.addGeneratedSourceDirectory(
                packSplit,
                PackShellProxy::outputDirectory,
            )
            val packSignals = tasks.register<PackShellProxy>(
                "pack${variant.name.replaceFirstChar(Char::titlecase)}VehicleSignalProxy",
            ) {
                source.set(
                    layout.projectDirectory.file(
                        "src/main/java/dev/denza/apps/feature/vehicle/signal/" +
                            "TargetedBydLightEventProxyMain.java",
                    ),
                )
                androidJar.from(platform)
                sdkDirectory.set(sdk)
                minApi.set(33)
                archiveName.set("vehicle-signal-proxy.jar")
            }
            variant.sources.assets?.addGeneratedSourceDirectory(
                packSignals,
                PackShellProxy::outputDirectory,
            )
            val packMediaFocus = tasks.register<PackShellProxy>(
                "pack${variant.name.replaceFirstChar(Char::titlecase)}MediaFocusPauseProxy",
            ) {
                source.set(layout.projectDirectory.file(
                    "src/main/java/dev/denza/apps/feature/media/MediaFocusPauseProxyMain.java",
                ))
                androidJar.from(platform)
                sdkDirectory.set(sdk)
                minApi.set(33)
                archiveName.set("media-focus-pause-proxy.jar")
            }
            variant.sources.assets?.addGeneratedSourceDirectory(
                packMediaFocus,
                PackShellProxy::outputDirectory,
            )
        }
    }

    lint {
        // DiLink 5.1 is pinned to the Android 13 compatibility contract until
        // firmware validation permits a target SDK upgrade.
        disable += "OldTargetApi"
        // Dependency versions are intentionally firmware-qualified as a set.
        disable += "GradleDependency"
    }
}

dependencies {
    implementation(project(":dishare-bridge"))
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
