pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "denza-lab"

// The default build is the products and the library they share.
include(":car-adb-gateway")
project(":car-adb-gateway").projectDir = file("apps/car-adb-gateway")

include(":denza-apps")
project(":denza-apps").projectDir = file("apps/denza-apps")

include(":dishare-bridge")
project(":dishare-bridge").projectDir = file("libraries/dishare-bridge")

// The disposable on-device probes under experiments/ and the frozen legacy app are configured
// only on request, so that an ordinary product build does not pay for nine modules it never
// touches. Ask for them with the `experiments` property:
//
//     ./gradlew -Pexperiments :night-vision-probe:assembleDebug
//     ./gradlew -Pexperiments :denza-gateway:testDebugUnitTest :denza-gateway:assembleDebug
if (providers.gradleProperty("experiments").isPresent) {
    include(":night-vision-probe")
    project(":night-vision-probe").projectDir = file("experiments/night-vision-probe")

    include(":audio-probe")
    project(":audio-probe").projectDir = file("experiments/audio-probe")

    include(":display-probe")
    project(":display-probe").projectDir = file("experiments/display-probe")

    include(":single-package-split-probe")
    project(":single-package-split-probe").projectDir =
        file("experiments/single-package-split-probe")

    include(":speaker-lift-yandex-probe")
    project(":speaker-lift-yandex-probe").projectDir =
        file("experiments/speaker-lift-yandex-probe")

    include(":adb-rescue-probe")
    project(":adb-rescue-probe").projectDir = file("experiments/adb-rescue-probe")

    include(":personbean-provider-probe")
    project(":personbean-provider-probe").projectDir =
        file("experiments/personbean-provider-probe")

    include(":dicar-media-probe")
    project(":dicar-media-probe").projectDir = file("experiments/dicar-media-probe")

    // Frozen, maintenance-only (see CLAUDE.md). Kept buildable until it is retired.
    include(":denza-gateway")
    project(":denza-gateway").projectDir = file("legacy/denza-gateway")
}
