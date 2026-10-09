package dev.denza.apps.feature.split

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The durable store: the snapshot format and the single commit.
 *
 * The oracle is always the preferences file itself - the exact payload, the exact set of keys left
 * behind, the number of commits - never a restatement of the encoder. Key names are spelled out as
 * literals here on purpose, so a renamed key cannot pass unnoticed.
 */
class SplitStoreTest {

    @Test
    fun everyShapeOfASnapshotSurvivesTheRoundTrip() {
        // §6: durable - это тумблер, два слота и revision, и ничего сверх того
        val snapshots = listOf(
            SplitDurable(),
            SplitDurable(enabled = true, slots = slots(SplitSlot.Picker, SplitSlot.Picker)),
            SplitDurable(slots = slots(SplitSlot.Closed, SplitSlot.Picker)),
            SplitDurable(slots = slots(SplitSlot.Picker, SplitSlot.Closed)),
            SplitDurable(slots = slots(SplitSlot.App(MUSIC), SplitSlot.Closed), revision = 1L),
            SplitDurable(slots = slots(SplitSlot.Closed, SplitSlot.App(MUSIC))),
            SplitDurable(slots = slots(SplitSlot.App(MUSIC), SplitSlot.Picker)),
            SplitDurable(slots = slots(SplitSlot.Picker, SplitSlot.App(MAPS))),
            SplitDurable(
                enabled = true,
                slots = slots(SplitSlot.App(MUSIC), SplitSlot.App(MAPS)),
                revision = Long.MAX_VALUE,
            ),
        )

        snapshots.forEach { snapshot ->
            assertEquals(snapshot, roundTrip(snapshot))
        }
    }

    @Test
    fun onePackageInBothPanesIsTwoIndependentSlots() {
        // 1.5.2: два одинаковых package хранятся независимо
        val both = SplitDurable(
            enabled = true,
            slots = slots(SplitSlot.App(MUSIC), SplitSlot.App(MUSIC)),
            revision = 4L,
        )

        val loaded = roundTrip(both)

        assertEquals(both, loaded)
        assertEquals(SplitSlot.App(MUSIC), loaded.slot(SplitPane.PRIMARY))
        assertEquals(SplitSlot.App(MUSIC), loaded.slot(SplitPane.SECONDARY))
    }

    @Test
    fun aPackageMadeOfTheFormatItselfSurvivesTheRoundTrip() {
        // тотальность кодирования: имя пакета - произвольная строка, а не подмножество формата
        val hostile = listOf(
            "a|b",
            "|",
            "\\",
            "\\p",
            "a\\|b",
            "2|1|0|P|P",
            "A:C|P",
            "\"quoted\" and 'single'",
            "приложение",
            "日本語のアプリ",
            "line\nbreak\ttab",
            "",
        )

        hostile.forEach { packageName ->
            val snapshot = SplitDurable(
                enabled = true,
                slots = slots(SplitSlot.App(packageName), SplitSlot.App(packageName)),
                revision = 2L,
            )
            assertEquals("round trip of <$packageName>", snapshot, roundTrip(snapshot))
        }
    }

    @Test
    fun theSnapshotFormatIsFrozen() {
        // золотой снимок: дрейф формата ловится здесь, а не на машине после обновления
        val preferences = InMemorySharedPreferences()
        val store = PreferencesSplitStateStore(preferences)

        store.commit(
            SplitDurable(
                enabled = true,
                slots = slots(SplitSlot.App("ru.yandex.music"), SplitSlot.Picker),
                revision = 7L,
            ),
        )
        assertEquals("2|1|7|A:ru.yandex.music|P", preferences.snapshot()[STATE])

        store.commit(SplitDurable(slots = slots(SplitSlot.Closed, SplitSlot.Picker)))
        assertEquals("2|0|0|C|P", preferences.snapshot()[STATE])

        store.commit(SplitDurable(slots = slots(SplitSlot.App("a|b\\c"), SplitSlot.Closed)))
        assertEquals("2|0|0|A:a\\pb\\\\c|C", preferences.snapshot()[STATE])
    }

    @Test
    fun anUnreadableSnapshotResolvesToTheSafeDefault() {
        // U4: нечитаемое хранилище отказывает в сторону "выключено и ничего не помним"
        val corrupt = listOf(
            "",
            "2",
            "2|1|0|P",
            "2|1|0|P|P|P",
            "3|1|0|P|P",
            "1|1|0|P|P",
            "2|yes|0|P|P",
            "2|1|seven|P|P",
            "2|1|0|X|P",
            "2|1|0|P|A:escape\\qhere",
            "2|1|0|P|A:dangling\\",
            "{\"enabled\":true,\"primary\":\"ru.yandex.music\"}",
        )

        corrupt.forEach { payload ->
            val stored = mapOf<String, Any>(STATE to payload, LAST_PRIMARY to MUSIC)
            val preferences = InMemorySharedPreferences(stored)

            val loaded = PreferencesSplitStateStore(preferences).load()

            assertEquals("payload <$payload>", safeDefault(), loaded)
            assertEquals("payload <$payload> is not repaired or migrated", stored, preferences.snapshot())
            assertEquals(0, preferences.commits)
        }
    }

    @Test
    fun aValueOfAnotherTypeIsCorruptionRatherThanAnException() {
        // чужой тип значения - это порча, а не исключение, и старые ключи рядом не читаются
        val stored = mapOf<String, Any>(STATE to 42, LAST_PRIMARY to MUSIC)
        val preferences = InMemorySharedPreferences(stored)

        val loaded = PreferencesSplitStateStore(preferences).load()

        assertEquals(safeDefault(), loaded)
        assertEquals(stored, preferences.snapshot())
        assertEquals(0, preferences.commits)
    }

    @Test
    fun aFreshInstallStartsOffDisabledWithTwoPickers() {
        // 1.3.3: сохранённого выбора нет - значит два пикера, и чтение ничего не пишет
        val preferences = InMemorySharedPreferences()

        val loaded = PreferencesSplitStateStore(preferences).load()

        assertEquals(safeDefault(), loaded)
        assertEquals(emptyMap<String, Any>(), preferences.snapshot())
        assertEquals(0, preferences.commits)
    }

    @Test
    fun oneSnapshotIsOneCommit() {
        // K9: одна успешная запись - ровно один commit; чтение по готовому ключу не пишет вовсе
        val preferences = InMemorySharedPreferences()
        val store = PreferencesSplitStateStore(preferences)

        assertTrue(store.commit(SplitDurable(slots = slots(SplitSlot.App(MUSIC), SplitSlot.Picker))))
        assertEquals(1, preferences.commits)

        assertTrue(store.commit(SplitDurable(revision = 2L)))
        assertTrue(store.commit(SplitDurable(revision = 3L)))
        assertEquals(3, preferences.commits)

        store.load()
        store.load()
        assertEquals("a load over an existing key writes nothing", 3, preferences.commits)
    }

    @Test
    fun aRejectedCommitReportsFailureAndChangesNothing() {
        // §7.8: отказ записи - это отказ операции, а не половина состояния
        val preferences = InMemorySharedPreferences()
        val store = PreferencesSplitStateStore(preferences)
        store.commit(SplitDurable(slots = slots(SplitSlot.App(MUSIC), SplitSlot.Picker), revision = 1L))
        val before = preferences.snapshot()

        preferences.accept = false
        val committed = store.commit(SplitDurable(slots = slots(SplitSlot.Closed, SplitSlot.Closed), revision = 2L))

        assertFalse(committed)
        assertEquals(before, preferences.snapshot())
        assertEquals(
            SplitDurable(slots = slots(SplitSlot.App(MUSIC), SplitSlot.Picker), revision = 1L),
            store.load(),
        )
    }

    private fun roundTrip(snapshot: SplitDurable): SplitDurable {
        val store = PreferencesSplitStateStore(InMemorySharedPreferences())
        assertTrue(store.commit(snapshot))
        return store.load()
    }

    private fun slots(primary: SplitSlot, secondary: SplitSlot): Map<SplitPane, SplitSlot> =
        mapOf(SplitPane.PRIMARY to primary, SplitPane.SECONDARY to secondary)

    private fun safeDefault(): SplitDurable = SplitDurable(
        enabled = false,
        slots = slots(SplitSlot.Picker, SplitSlot.Picker),
        revision = 0L,
    )

    /**
     * A preferences file a test can read back byte for byte.
     *
     * `commit()` is the only way in, and it either swaps the whole map or changes nothing, which is
     * what "atomic" has to mean for a snapshot. `apply()` throws: a store that fires and forgets
     * cannot report a failed write, and the contract requires it to.
     */
    private class InMemorySharedPreferences(
        initial: Map<String, Any> = emptyMap(),
    ) : SharedPreferences {

        private var values: Map<String, Any> = initial.toMap()

        var commits: Int = 0
        var accept: Boolean = true

        fun snapshot(): Map<String, Any> = values

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String, defValue: String?): String? =
            when (val stored = values[key]) {
                null -> defValue
                is String -> stored
                else -> throw mismatch(key, stored)
            }

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            when (val stored = values[key]) {
                null -> defValue
                is Boolean -> stored
                else -> throw mismatch(key, stored)
            }

        override fun getInt(key: String, defValue: Int): Int =
            when (val stored = values[key]) {
                null -> defValue
                is Int -> stored
                else -> throw mismatch(key, stored)
            }

        override fun getLong(key: String, defValue: Long): Long =
            when (val stored = values[key]) {
                null -> defValue
                is Long -> stored
                else -> throw mismatch(key, stored)
            }

        override fun getFloat(key: String, defValue: Float): Float =
            when (val stored = values[key]) {
                null -> defValue
                is Float -> stored
                else -> throw mismatch(key, stored)
            }

        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = throw UnsupportedOperationException("no split key is a string set")

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = InMemoryEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = throw UnsupportedOperationException("the store is read on demand, never observed")

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = throw UnsupportedOperationException("the store is read on demand, never observed")

        /** Mirrors the real class: a value of the wrong type throws, it does not silently default. */
        private fun mismatch(key: String, stored: Any): ClassCastException =
            ClassCastException("$key holds a ${stored::class.simpleName}")

        private inner class InMemoryEditor : SharedPreferences.Editor {
            private val staged = mutableListOf<(MutableMap<String, Any>) -> Unit>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                stage { staging ->
                    if (value == null) staging.remove(key) else staging[key] = value
                }

            override fun putStringSet(
                key: String,
                stringSet: MutableSet<String>?,
            ): SharedPreferences.Editor = throw UnsupportedOperationException("no split key is a string set")

            override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                stage { it[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                stage { it[key] = value }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                stage { it[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                stage { it[key] = value }

            override fun remove(key: String): SharedPreferences.Editor = stage { it.remove(key) }

            override fun clear(): SharedPreferences.Editor = stage { it.clear() }

            override fun commit(): Boolean {
                commits += 1
                if (!accept) return false
                val next = values.toMutableMap()
                staged.forEach { edit -> edit(next) }
                values = next.toMap()
                staged.clear()
                return true
            }

            override fun apply() =
                throw UnsupportedOperationException("a durable snapshot is committed, never applied")

            private fun stage(edit: (MutableMap<String, Any>) -> Unit): SharedPreferences.Editor {
                staged += edit
                return this
            }
        }
    }

    private companion object {
        const val MUSIC = "ru.yandex.music"
        const val MAPS = "ru.yandex.yandexmaps"

        const val STATE = "split_state_v2"

        const val LAST_PRIMARY = "picker_last_primary_package_v1"
    }
}
