plugins {
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

// The bridge's certificate builder brings three jars with the same license/notice paths.
// Preserve their notices in every APK consuming it, including opt-in probes and legacy builds.
subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            packaging.resources.merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
    }
}
