package dev.denza.apps.feature.vehicle

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * How both journals reach the disk, which is the same way for the same reason.
 *
 * The car's power goes away without warning: the ignition is cut and the process is gone mid-write,
 * with no unmount and no flush. [ConsumptionJournal] and [TripJournal] have opposite shapes - a list
 * appended to and a record rewritten - and identical durability, and they were each carrying their
 * own copy of it.
 *
 * ### What "written" means here
 *
 * A `write` that returned has reached the page cache and nothing more. So every path below flushes
 * the stream, `fsync`s the file's own descriptor, and - for a replacement - `fsync`s the
 * **directory** after the rename. That last one is the half that was missing from both copies: a
 * rename is a change to the directory, and a directory entry that was never synced can be gone
 * after a power cut even though the file it pointed at is on the platter. The window is small and
 * this car closes it several times an hour.
 *
 * The directory sync is best effort. Not every filesystem lets a directory be opened as a channel,
 * and by the time it runs the content is already durable and the rename has already been applied:
 * failing the whole write there would throw away a journal that is on disk over a guarantee about
 * an ordering. What must not fail silently is the write itself, which is what the returned flag is.
 */
internal object JournalFile {

    /**
     * Add [text] to the end of [file], durably.
     *
     * The append is the cheap path and the one taken while the car is moving. It is safe to be a
     * plain append: a line cut in half by a power cut is recognisable and skippable, which is the
     * whole reason the consumption journal is a list of lines rather than a document.
     *
     * @return whether the bytes are on the disk
     */
    fun append(file: File, text: String): Boolean = runCatching {
        FileOutputStream(file, true).use { out ->
            out.write(text.toByteArray())
            out.flush()
            out.fd.sync()
        }
    }.isSuccess

    /**
     * Replace everything in [file] with [text], or leave what is already there.
     *
     * Through a temporary file and a rename, so an interrupted write leaves the previous content
     * intact rather than half of the new one. On failure the temporary file is removed: a `.tmp`
     * left in an app-private directory is litter that the next run would have to reason about.
     *
     * @return whether the new content is on the disk
     */
    fun replace(file: File, text: String): Boolean {
        val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
        val ok = runCatching {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray())
                out.flush()
                out.fd.sync()
            }
            check(temp.renameTo(file)) { "переименование не удалось" }
        }.isSuccess
        if (!ok) {
            temp.delete()
            return false
        }
        syncDirectory(file.parentFile)
        return true
    }

    /** Make the rename itself durable, where the platform allows a directory to be opened. */
    private fun syncDirectory(directory: File?) {
        if (directory == null) return
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private const val TEMP_SUFFIX = ".tmp"
}
