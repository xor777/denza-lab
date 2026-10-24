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

/** The tests read the car's stock route and the recorded Navigator routes from captures/. */
tasks.withType<Test>().configureEach {
    systemProperty("denza.root", rootProject.projectDir.absolutePath)
    inputs.files(
        rootProject.fileTree("captures/stock-map-car-lanes-20261023/reports/route-wire") { include("**/*.someip.bin") },
        rootProject.fileTree("captures/yandex-adas-prototype-20261023") { include("*/denza-hud-native-route.json") },
        rootProject.fileTree("captures/adas-bridge-review/dynamics/sequences"),
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("adasCaptures")
    /** FieldContractChecks reads it: an edit to the contract alone must rerun the tests. */
    inputs.file(rootProject.file("research/adas-bridge/field-contract-20261024.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("fieldContract")
    inputs.file(rootProject.file("research/adas-bridge/lane-category-source-20261024.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("laneCategorySource")
}
