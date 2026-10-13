import java.util.Properties

plugins {
    id("com.android.application")
}

// Owner identity is deliberately local. Never fall back to generating another ADB identity.
abstract class GenerateDipilotKeyMaterial : DefaultTask() {
    @get:InputFile
    abstract val identityFile: RegularFileProperty
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        check(identityFile.get().asFile.isFile) {
            "Local Dipilot identity missing: .local-secrets/dipilot-adb-rescue/identity.properties"
        }
        val identity = Properties().apply {
            identityFile.get().asFile.inputStream().use { load(it) }
        }
        fun member(name: String): String {
            val value = identity.getProperty(name).orEmpty()
            check(value.length >= 100 && value.matches(Regex("[A-Za-z0-9+/=]+"))) {
                "Invalid local Dipilot identity member: $name"
            }
            return value
        }
        val privateKey = member("pkcs8Base64")
        val publicKey = member("publicBlobBase64")
        val target = outputDirectory.get().file("dev/denza/dipilotkey/probe/DipilotKeyMaterial.java").asFile
        target.parentFile.mkdirs()
        target.writeText("""
            package dev.denza.dipilotkey.probe;
            // Generated from an ignored local file. Contains private key material; never publish.
            final class DipilotKeyMaterial {
                static final String PKCS8_BASE64 = "$privateKey";
                static final String PUBLIC_BLOB_PREFIX = "${publicKey.take(40)}";
                private DipilotKeyMaterial() { }
            }
        """.trimIndent() + "\n")
        target.setReadable(false, false)
        target.setReadable(true, true)
        target.setWritable(false, false)
        target.setWritable(true, true)
    }
}

val generateDipilotKeyMaterial = tasks.register<GenerateDipilotKeyMaterial>("generateDipilotKeyMaterial") {
    identityFile.set(rootProject.layout.projectDirectory.file(
        ".local-secrets/dipilot-adb-rescue/identity.properties"
    ))
    outputDirectory.set(layout.buildDirectory.dir("generated/dipilot-key/java"))
}

android {
    namespace = "dev.denza.dipilotkey.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.denza.dipilotkey.probe"
        minSdk = 33
        targetSdk = 33
        versionCode = 5
        versionName = "0.5.0"
    }

    compileOptions {
        encoding = "UTF-8"
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidComponents {
        onVariants(selector().all()) { variant ->
            variant.sources.java?.addGeneratedSourceDirectory(
                generateDipilotKeyMaterial, GenerateDipilotKeyMaterial::outputDirectory
            )
            variant.outputs.forEach { output ->
                output.outputFileName.set("dipilot-adb-rescue.apk")
            }
        }
    }

    lint {
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation(project(":dishare-bridge"))

    testImplementation("junit:junit:4.13.2")
}
