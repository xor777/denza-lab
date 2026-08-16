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

include(":car-adb-gateway")
project(":car-adb-gateway").projectDir = file("apps/car-adb-gateway")

include(":denza-apps")
project(":denza-apps").projectDir = file("apps/denza-apps")

include(":denza-split")
project(":denza-split").projectDir = file("apps/denza-split")

include(":dishare-bridge")
project(":dishare-bridge").projectDir = file("libraries/dishare-bridge")

include(":night-vision-probe")
project(":night-vision-probe").projectDir = file("experiments/night-vision-probe")

include(":audio-probe")
project(":audio-probe").projectDir = file("experiments/audio-probe")

include(":display-probe")
project(":display-probe").projectDir = file("experiments/display-probe")

include(":single-package-split-probe")
project(":single-package-split-probe").projectDir =
    file("experiments/single-package-split-probe")

include(":denza-gateway")
project(":denza-gateway").projectDir = file("legacy/denza-gateway")
