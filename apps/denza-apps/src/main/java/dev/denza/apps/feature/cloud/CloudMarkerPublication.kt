package dev.denza.apps.feature.cloud

/** Serialize publishers without holding the UI's settings monitor over external-storage I/O. */
internal class CloudMarkerPublication(private val settingsLock: Any) {
    private val publicationLock = Any()
    fun <T> publish(snapshot: () -> T, write: (T) -> Unit, current: (T) -> Boolean): T =
        synchronized(publicationLock) {
            val captured = synchronized(settingsLock) { snapshot() }
            write(captured)
            synchronized(settingsLock) {
                check(current(captured)) { "Настройки изменились" }
            }
            captured
        }
}
