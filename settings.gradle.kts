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

include(":dishare-bridge")
project(":dishare-bridge").projectDir = file("libraries/dishare-bridge")

include(":denza-gateway")
project(":denza-gateway").projectDir = file("legacy/denza-gateway")
