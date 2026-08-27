package dev.denza.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт 7 аудита: общий владелец права двигать задачи.
 *
 * Здесь проверяется само владение; что именно отказывается закрыто, когда оно занято, проверяют
 * сценарии split и Simulcast.
 */
class TaskMoveOwnershipTest {

    private var now = 0L
    private val ownership = TaskMoveOwnership { now }

    @Test
    fun `a free ownership is granted, and it is held against everyone else`() {
        assertNotNull(ownership.acquire(TaskMoveOwner.SIMULCAST, 1_000L))

        assertEquals(TaskMoveOwner.SIMULCAST, ownership.holder())
        assertTrue(ownership.heldByOther(TaskMoveOwner.SPLIT))
        assertFalse("своё владение - не чужое", ownership.heldByOther(TaskMoveOwner.SIMULCAST))
    }

    @Test
    fun `a second owner is refused while the first holds`() {
        ownership.acquire(TaskMoveOwner.SIMULCAST, 1_000L)

        assertNull(ownership.acquire(TaskMoveOwner.SPLIT, 1_000L))
        assertEquals(TaskMoveOwner.SIMULCAST, ownership.holder())
    }

    /**
     * Simulcast зовёт `pulse` из каждого колбэка моста, поэтому повторное взятие своим владельцем -
     * это норма, а не гонка. Оно продлевает срок и не делает выданный ранее lease недействительным:
     * иначе первый же пульс из колбэка превратил бы `release` начала операции в промах.
     */
    @Test
    fun `the same owner extends its own hold and its earlier lease still releases it`() {
        val lease = ownership.acquire(TaskMoveOwner.SIMULCAST, 1_000L)!!
        now = 900L
        ownership.acquire(TaskMoveOwner.SIMULCAST, 1_000L)

        now = 1_500L
        assertEquals("срок продлён", TaskMoveOwner.SIMULCAST, ownership.holder())

        lease.release()
        assertNull("и ранний lease всё ещё отпускает своё владение", ownership.holder())
    }

    @Test
    fun `a released ownership goes to whoever asks next`() {
        ownership.acquire(TaskMoveOwner.SIMULCAST, 10_000L)!!.release()

        assertNotNull(ownership.acquire(TaskMoveOwner.SPLIT, 1_000L))
        assertEquals(TaskMoveOwner.SPLIT, ownership.holder())
    }

    /** Владение отпускают только своё: чужое этот вызов не снимает ни при каких серийных номерах. */
    @Test
    fun `a stale lease does not release the owner that came after it`() {
        val stale = ownership.acquire(TaskMoveOwner.SIMULCAST, 1_000L)!!
        now = 1_000L
        ownership.acquire(TaskMoveOwner.SPLIT, 1_000L)

        stale.release()

        assertEquals(TaskMoveOwner.SPLIT, ownership.holder())
    }

    /**
     * Единственная защита от навсегда закрытого отказа: отпустить бывает некому - мост не позвал
     * колбэк, процесс службы умер посреди запуска, - и без срока функция была бы заперта до
     * перезапуска.
     */
    @Test
    fun `an ownership nobody released expires on its own`() {
        ownership.acquire(TaskMoveOwner.SIMULCAST, 1_000L)

        now = 999L
        assertEquals(TaskMoveOwner.SIMULCAST, ownership.holder())

        now = 1_000L
        assertNull(ownership.holder())
        assertNotNull(ownership.acquire(TaskMoveOwner.SPLIT, 1_000L))
    }

    /**
     * Выключение тумблера - отказ от владения, а не просьба о нём: разбор сцены не ждёт чужого
     * разрешения (контракт 1.2, сценарий §11.31).
     */
    @Test
    fun `a preempting owner takes the ownership away, and the displaced lease is inert`() {
        val displaced = ownership.acquire(TaskMoveOwner.SIMULCAST, 10_000L)!!

        assertNotNull(ownership.acquire(TaskMoveOwner.SPLIT, 1_000L, preempt = true))
        assertEquals(TaskMoveOwner.SPLIT, ownership.holder())

        displaced.release()
        assertEquals(
            "вытесненный lease чужого владения не снимает",
            TaskMoveOwner.SPLIT,
            ownership.holder(),
        )
    }

    @Test
    fun `a pulse is the five second window the old bypass was`() {
        val pulsed = TaskMoveOwnership { now }
        pulsed.acquire(TaskMoveOwner.SIMULCAST, TaskMoveOwnership.PULSE_MS)

        now = TaskMoveOwnership.PULSE_MS - 1
        assertEquals(TaskMoveOwner.SIMULCAST, pulsed.holder())

        now = TaskMoveOwnership.PULSE_MS
        assertNull(pulsed.holder())
    }
}
