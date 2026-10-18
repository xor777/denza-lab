plugins {
    id("com.android.library")
}

/**
 * The HUD's wire, shared by Denza Apps and the YandexHUD module (docs/hud-output-architecture.md).
 * The module is compiled into Navigator's own dex by tools/yandex_hud_build/build.py with
 * `javac --release 8` and no dependencies, so this library stays plain Java 8: no Kotlin, no
 * AndroidX, nothing but the JDK and android.jar.
 */
android {
    namespace = "dev.denza.hudlink"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
