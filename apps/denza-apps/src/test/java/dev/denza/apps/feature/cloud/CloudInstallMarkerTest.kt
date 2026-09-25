package dev.denza.apps.feature.cloud

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudInstallMarkerTest {
    @Test fun atomicMarkerChangesDesiredStateAndKeepsSingleFile() {
        val directory = Files.createTempDirectory("cloud-marker-")
        try {
            val path = directory.resolve("install.json")
            val id = "12".repeat(16)
            CloudInstallMarker(id, 1, true).publish(path)
            val first = JSONObject(Files.readString(path))
            assertEquals("custom", first.getString("desired"))
            CloudInstallMarker(id, 2, false).publish(path)
            val last = JSONObject(Files.readString(path))
            assertEquals(id, last.getString("install_id"))
            assertEquals(2L, last.getLong("generation"))
            assertEquals("off", last.getString("desired"))
            assertEquals(4, last.length())
            Files.list(directory).use { assertEquals(1L, it.count()) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun staleTemporaryRecoversButSymlinkIsNotFollowed() {
        val directory = Files.createTempDirectory("cloud-marker-")
        try {
            val path = directory.resolve("install.json")
            Files.writeString(directory.resolve("install.json.pending"), "interrupted")
            val marker = CloudInstallMarker("ab".repeat(16), 1, false)
            marker.publish(path)
            Files.delete(path)
            val other = directory.resolve("other")
            Files.writeString(other, "untouched")
            Files.createSymbolicLink(path, other)
            assertThrows(IllegalStateException::class.java) { marker.publish(path) }
            assertEquals("untouched", Files.readString(other))
        } finally { directory.toFile().deleteRecursively() }
    }
}
