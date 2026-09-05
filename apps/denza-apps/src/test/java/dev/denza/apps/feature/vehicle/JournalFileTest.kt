package dev.denza.apps.feature.vehicle

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The one way either journal reaches the disk.
 *
 * There were two copies of this - a list appended to and a record rewritten, with identical
 * durability arithmetic inside each - and neither of them synced the directory after the rename,
 * which is the half that makes a rename survive the ignition being cut.
 */
class JournalFileTest {

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File.createTempFile("journal-file", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun file() = File(directory, "journal.log")

    @Test
    fun anAppendAddsToWhatIsAlreadyThere() {
        assertTrue(JournalFile.append(file(), "one\n"))
        assertTrue(JournalFile.append(file(), "two\n"))
        assertEquals("one\ntwo\n", file().readText())
    }

    @Test
    fun aReplacementIsWholeAndLeavesNoTemporaryBehind() {
        JournalFile.append(file(), "old\n")
        assertTrue(JournalFile.replace(file(), "new\n"))
        assertEquals("new\n", file().readText())
        assertEquals(
            "the directory holds the journal and nothing else",
            listOf("journal.log"),
            directory.list()!!.sorted(),
        )
    }

    @Test
    fun aWriteThatCouldNotHappenSaysSoRatherThanLookingLikeOne() {
        // A path with a file where a directory should be. The journals answer a false here by
        // wiping, which is the owner's rule: a journal that cannot be trusted is worth less than
        // no journal at all.
        val blocked = File(File(directory, "journal.log"), "nested.log")
        JournalFile.append(file(), "old\n")
        assertFalse(JournalFile.append(blocked, "x\n"))
        assertFalse(JournalFile.replace(blocked, "x\n"))
    }

    @Test
    fun aFailedReplacementLeavesTheOldContentAndNoLitter() {
        val target = File(directory, "locked")
        target.mkdirs()
        // A directory cannot be opened as a stream, so the replacement fails at the first write.
        assertFalse(JournalFile.replace(target, "x\n"))
        assertTrue("the original is untouched", target.isDirectory)
        assertFalse("and the temporary is gone", File(directory, "locked.tmp").exists())
    }
}
