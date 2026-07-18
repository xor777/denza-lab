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

rootProject.name = "denza-gateway"
include(":denza-gateway")
include(":denza-mirrors")
include(":dishare-bridge")
include(":denza-apps")
include(":car-adb-gateway")
