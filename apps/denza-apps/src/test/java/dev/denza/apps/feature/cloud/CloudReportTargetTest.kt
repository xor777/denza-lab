package dev.denza.apps.feature.cloud

import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudReportTargetTest {
    private class Provider(var matches: List<String> = emptyList()) {
        var inserts = 0
        val writes = mutableListOf<String>()
        var inaccessible: String? = null
        var forbidden: String? = null
        var remembered: String? = null
        var actualNames = mutableMapOf<String, CloudReportTarget.Match>()
        val deleted = mutableListOf<String>()

        fun target() = CloudReportTarget(
            find = { matches },
            insert = { inserts++; "new-$inserts" },
            inspect = { actualNames[it] ?: CloudReportTarget.Match.EXACT },
            discardFresh = { deleted += it },
            write = { uri ->
                if (uri == inaccessible) throw FileNotFoundException()
                if (uri == forbidden) throw SecurityException()
                writes += uri
            },
            remember = { remembered = it },
        )
    }

    @Test fun missingPrefsReuseOneExistingFile() {
        val provider = Provider(listOf("old"))
        provider.target().publish(null)
        assertEquals(listOf("old"), provider.writes)
        assertEquals("old", provider.remembered)
        assertEquals(0, provider.inserts)
    }

    @Test fun staleOrDeniedSavedUriRecoversOnlyVisibleExistingFile() {
        val provider = Provider(listOf("old"))
        provider.inaccessible = "stale"
        provider.target().publish("stale")
        assertEquals(listOf("old"), provider.writes)
        assertEquals(0, provider.inserts)
        provider.forbidden = "forbidden"
        provider.target().publish("forbidden")
        assertEquals(listOf("old", "old"), provider.writes)
        assertEquals(0, provider.inserts)
    }

    @Test fun ambiguousOrInvisibleOldFileNeverTriggersRepeatedInsert() {
        val provider = Provider(listOf("a", "b"))
        assertThrows(IllegalStateException::class.java) { provider.target().publish(null) }
        provider.matches = emptyList()
        provider.forbidden = "old"
        assertThrows(IllegalStateException::class.java) { provider.target().publish("old") }
        provider.forbidden = null
        provider.inaccessible = "old"
        assertThrows(IllegalStateException::class.java) { provider.target().publish("old") }
        assertEquals(0, provider.inserts)
    }

    @Test fun completeEmptyLookupWithoutSavedUriInsertsOneFixedTarget() {
        val provider = Provider()
        provider.target().publish(null)
        assertEquals(1, provider.inserts)
        assertEquals(listOf("new-1"), provider.writes)
    }

    @Test fun deniedLookupNeverCreatesAnotherReport() {
        var inserts = 0
        val target = CloudReportTarget<String>(
            find = { throw SecurityException() },
            insert = { inserts++; "new" },
            inspect = { CloudReportTarget.Match.EXACT },
            discardFresh = {},
            write = {},
            remember = {},
        )
        assertThrows(SecurityException::class.java) { target.publish(null) }
        assertEquals(0, inserts)
    }

    @Test fun autoRenamedInsertIsRemovedBeforeAnyWrite() {
        val provider = Provider()
        provider.actualNames["new-1"] = CloudReportTarget.Match.DIFFERENT
        assertThrows(IllegalStateException::class.java) { provider.target().publish(null) }
        assertEquals(emptyList<String>(), provider.writes)
        assertEquals(listOf("new-1"), provider.deleted)
        assertEquals(null, provider.remembered)
    }

    @Test fun renamedSavedUriIsNeverOverwrittenOrDeleted() {
        val provider = Provider(listOf("fixed"))
        provider.actualNames["saved-archive"] = CloudReportTarget.Match.DIFFERENT
        provider.target().publish("saved-archive")
        assertEquals(listOf("fixed"), provider.writes)
        assertEquals(emptyList<String>(), provider.deleted)
        assertEquals(0, provider.inserts)
    }

    @Test fun visibleRenamedArchiveAllowsOneNewFixedFileWhenNoMatchExists() {
        val provider = Provider()
        provider.actualNames["saved-archive"] = CloudReportTarget.Match.DIFFERENT
        provider.target().publish("saved-archive")
        assertEquals(listOf("new-1"), provider.writes)
        assertEquals("new-1", provider.remembered)
        assertEquals(emptyList<String>(), provider.deleted)
        assertEquals(1, provider.inserts)
    }

    @Test fun missingSavedRowStillRefusesAmbiguousInsert() {
        val provider = Provider()
        provider.actualNames["saved"] = CloudReportTarget.Match.MISSING
        assertThrows(IllegalStateException::class.java) { provider.target().publish("saved") }
        assertEquals(0, provider.inserts)
    }
}
