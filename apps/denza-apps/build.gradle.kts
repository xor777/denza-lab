import javax.inject.Inject
import javax.tools.ToolProvider
import org.gradle.process.ExecOperations

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
 * Each is compiled here rather than taken from the variant's own class output on purpose: its
 * [sources] - the entry point, the shared `ShellProxyBootstrap` and whatever else is named for it -
 * depend on nothing but the platform, so this task needs no build-order relationship with the
 * application's compilation, and the jar cannot silently pick up anything else: a reference to any
 * other application class fails the compile.
 *
 * One application class is visible, and only to the compiler: `BuildConfig`, with
 * `APPLICATION_ID` alone, written here from the variant. A helper that must know this app's own
 * package - navigation's proxy refuses to move this app's tasks - reads the same constant the app
 * does; javac copies a constant into the class that uses it, and `-implicit:none` keeps the stub
 * out of the jar.
 */
abstract class PackShellProxy : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val buildConfigPackage: Property<String>

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
        val stubs = temporaryDir.resolve("stubs")
        stubs.deleteRecursively()
        stubs.resolve(buildConfigPackage.get().replace('.', '/')).apply { mkdirs() }
            .resolve("BuildConfig.java")
            .writeText(
                "package ${buildConfigPackage.get()};\n\n" +
                    "public final class BuildConfig {\n" +
                    "    public static final String APPLICATION_ID = \"${applicationId.get()}\";\n" +
                    "}\n",
            )
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
            "-sourcepath",
            stubs.absolutePath,
            "-implicit:none",
            "-d",
            classes.absolutePath,
            *sources.files.map(File::getAbsolutePath).sorted().toTypedArray(),
        )
        check(compiled == 0) { "could not compile ${sources.files.map(File::getName)}" }

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
        onVariants(selector().all()) { variant ->
            variant.outputs.forEach { output ->
                output.outputFileName.set("denza-apps.apk")
            }
            // Each helper jar is its entry point, the shared bootstrap, and nothing else unless named
            // here. The asset names are the ones ShellProxyJar (platform/shell) stages.
            fun packShellProxy(name: String, archive: String, vararg files: String) {
                val pack = tasks.register<PackShellProxy>(
                    "pack${variant.name.replaceFirstChar(Char::titlecase)}$name",
                ) {
                    (files.toList() + "platform/shell/ShellProxyBootstrap.java").forEach { file ->
                        sources.from(
                            layout.projectDirectory.file("src/main/java/dev/denza/apps/$file"),
                        )
                    }
                    applicationId.set(variant.applicationId)
                    buildConfigPackage.set(variant.namespace)
                    androidJar.from(platform)
                    sdkDirectory.set(sdk)
                    minApi.set(33)
                    archiveName.set(archive)
                }
                variant.sources.assets?.addGeneratedSourceDirectory(
                    pack,
                    PackShellProxy::outputDirectory,
                )
            }
            packShellProxy(
                "SplitTaskProxy",
                "split-task-proxy.jar",
                "feature/split/SplitTaskProxyMain.java",
            )
            packShellProxy(
                "VehicleSignalProxy",
                "vehicle-signal-proxy.jar",
                "feature/vehicle/signal/TargetedBydLightEventProxyMain.java",
            )
            packShellProxy(
                "NavigationProxy",
                "navigation-proxy.jar",
                "feature/navigation/ClusterProxyMain.java",
                "feature/navigation/ProjectablePackages.java",
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
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // The real org.json on the test classpath, ahead of android.jar's stub, so code that builds or
    // reads JSON - the stock weather widget's payload - can run in a unit test. The version the
    // gateway's tests already use.
    testImplementation("org.json:json:20250517")
}

/**
 * The contract tests read files this module does not compile, so Gradle has to be told about
 * them or it calls the tests up to date when only a board or the feature map changed. The
 * Luminofor boards and their spec, the debug build's fixtures, the split crew's board and
 * `docs/feature-map.md` are each held to the code by a test here (`LuminoforSpecContractTest`,
 * `ContourFixturesContractTest`, `SplitCrewBoardContractTest`, `FeatureMapContractTest`, ...).
 *
 * So are the files the unit tests read as text: the manifest (six manifest contracts), the
 * strings the split crew's caption is held to, the Jura font the cluster's widths are measured
 * in, the sources the wiring contracts cut sections out of - a comment moves their anchors too -
 * the host recorder whose columns `VehicleCaptureTest` matches, the recorded drives
 * `VehicleLogReplayTest` replays, and this script, whose helper jar names
 * `ShellProxyJarAssetsTest` holds to the ones the app stages. Until 2026-10-08 an edit to the
 * manifest alone left the task up to date and the manifest contracts unrun.
 */
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.fileTree("tools/design-canvas/luminofor"),
        rootProject.fileTree("tools/design-canvas/split-crew"),
        fileTree("src/debug/assets/luminofor"),
        rootProject.file("docs/feature-map.md"),
        file("build.gradle.kts"),
        file("src/main/AndroidManifest.xml"),
        file("src/main/res/values/strings.xml"),
        file("src/main/res/font/jura_medium.ttf"),
        fileTree("src/main/java"),
        rootProject.file("tools/vehicle_log.py"),
        rootProject.fileTree("captures/vehicle-log") { include("*.csv") },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("contractSources")
    // The split's command log (SplitCommandLog in the tests): off unless -PsplitCommandLog=<file>
    // names the file the fake car's commands of every split test are written to.
    providers.gradleProperty("splitCommandLog").orNull?.let { file ->
        systemProperty("denza.splitCommandLog", file)
    }
}
