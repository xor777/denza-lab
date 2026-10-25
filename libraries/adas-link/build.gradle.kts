plugins {
    id("com.android.library")
}

/**
 * The stock map's feed to ADAS, rebuilt from Yandex route and guidance (research/adas-bridge).
 * Compiled into Navigator's own dex by tools/yandex_hud_build/build.py with `javac --release 8`
 * and no dependencies, like :hud-link: plain Java 8, nothing but the JDK and android.jar.
 * Gson and protobuf-java are test-only oracles: the stock map's own serializers, run on the
 * library's output.
 */
android {
    namespace = "dev.denza.adaslink"
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
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation("com.google.protobuf:protobuf-java:3.25.5")
}

/** Product input and core checks are self-contained; factory output replay is a separate retained bench. */
tasks.withType<Test>().configureEach {
    // Legacy bridge/golden/variant wrappers remain historical diagnostics,
    // not acceptance targets or fixture dependencies for the new product path.
    include("dev/denza/adaslink/stocknative/**", "dev/denza/adaslink/yandexnative/**")
}
