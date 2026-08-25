import javax.inject.Inject
import javax.tools.ToolProvider
import org.gradle.process.ExecOperations

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Packs `SplitTaskProxyMain` into a jar of its own.
 *
 * The split recipes run that one class as the shell user through `app_process`, and the only
 * classpath they had was the application APK: 62 MB of dex for ART to open and verify on every
 * single one-shot call, measured at 1.36 s each on the car. The same class on its own is a 3.6 KB
 * jar, which the product stages next to the other shell-UID helpers of this project and loads from
 * there instead (see `SplitStagedProxyDex`).
 *
 * It is compiled here rather than taken from the variant's own class output on purpose: the proxy
 * depends on nothing but the platform, so this task needs no build-order relationship with the
 * application's compilation, and the jar cannot silently pick up anything else.
 */
abstract class PackSplitTaskProxy : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:InputFiles
    abstract val androidJar: ConfigurableFileCollection

    @get:Internal
    abstract val sdkDirectory: DirectoryProperty

    @get:Input
    abstract val minApi: Property<Int>

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
            "Gradle must run on a JDK to pack the split task proxy"
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
            source.get().asFile.absolutePath,
        )
        check(compiled == 0) { "could not compile ${source.get().asFile.name}" }

        val output = outputDirectory.get().asFile
        output.mkdirs()
        val jar = output.resolve(PROXY_JAR)
        jar.delete()
        execOperations.exec {
            executable = d8().absolutePath
            androidJar.files.forEach { library -> args("--lib", library.absolutePath) }
            args("--min-api", minApi.get().toString())
            args("--release", "--output", jar.absolutePath)
            args(classes.walkTopDown().filter { it.extension == "class" }.map { it.absolutePath }
                .toList())
        }
        check(jar.isFile && jar.length() > 0) { "d8 produced no $PROXY_JAR" }
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

    companion object {
        const val PROXY_JAR = "split-task-proxy.jar"
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
        versionCode = 28
        versionName = "0.5.5"
    }

    buildFeatures {
        aidl = true
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
            val pack = tasks.register<PackSplitTaskProxy>(
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
            }
            variant.sources.assets?.addGeneratedSourceDirectory(
                pack,
                PackSplitTaskProxy::outputDirectory,
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
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
