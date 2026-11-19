package dev.denza.apps.feature.split

import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicReference
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
        // §6: durable - это тумблер, два слота и память пар (ред. 2026-11-11), и ничего сверх того
        val snapshots = listOf(
            SplitDurable(),
            SplitDurable(enabled = true, slots = slots(SplitSlot.Picker, SplitSlot.Picker)),
            SplitDurable(slots = slots(SplitSlot.Closed, SplitSlot.Picker)),
            SplitDurable(slots = slots(SplitSlot.Picker, SplitSlot.Closed)),
            SplitDurable(slots = slots(SplitSlot.App(MUSIC), SplitSlot.Closed)),
            SplitDurable(slots = slots(SplitSlot.Closed, SplitSlot.App(MUSIC))),
            SplitDurable(slots = slots(SplitSlot.App(MUSIC), SplitSlot.Picker)),
            SplitDurable(slots = slots(SplitSlot.Picker, SplitSlot.App(MAPS))),
            SplitDurable(enabled = true, slots = slots(SplitSlot.App(MUSIC), SplitSlot.App(MAPS))),
            SplitDurable(
                enabled = true,
                slots = slots(SplitSlot.App(MUSIC), SplitSlot.App(MAPS)),
                memory = SplitMemory(last = SplitPair(MUSIC, MAPS)),
            ),
            SplitDurable(memory = SplitMemory(recent = listOf(SplitPair(MAPS, MUSIC)))),
            SplitDurable(memory = SplitMemory(pinned = listOf(SplitPair(MUSIC, MUSIC)))),
            SplitDurable(
                enabled = true,
                slots = slots(SplitSlot.Picker, SplitSlot.App(MAPS)),
                memory = SplitMemory(
                    last = SplitPair(MUSIC, MAPS),
                    recent = listOf(
                        SplitPair(MAPS, MUSIC),
                        SplitPair(NAVI, MUSIC),
                        SplitPair(MUSIC, NAVI),
                        SplitPair(NAVI, MAPS),
                        SplitPair(MAPS, NAVI),
                    ),
                    // Последняя пара может быть и закреплённой: правила между ними только у недавних.
                    pinned = listOf(SplitPair(NAVI, NAVI), SplitPair(MUSIC, MAPS)),
                ),
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
            )
            assertEquals("round trip of <$packageName>", snapshot, roundTrip(snapshot))
        }
        // И в памяти пар: каждое имя - поле пары, и поле разбирается так же тотально, как слот.
        hostile.filter(String::isNotBlank).forEach { packageName ->
            val snapshot = SplitDurable(
                enabled = true,
                slots = slots(SplitSlot.App(packageName), SplitSlot.App(MUSIC)),
                memory = SplitMemory(
                    last = SplitPair(packageName, MUSIC),
                    recent = listOf(SplitPair(MUSIC, packageName)),
                    pinned = listOf(SplitPair(packageName, packageName)),
                ),
            )
            assertEquals("round trip of the pair <$packageName>", snapshot, roundTrip(snapshot))
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
            ),
        )
        assertEquals("3|1|A:ru.yandex.music|P|0|0|0", preferences.snapshot()[STATE])

        store.commit(SplitDurable(slots = slots(SplitSlot.Closed, SplitSlot.Picker)))
        assertEquals("3|0|C|P|0|0|0", preferences.snapshot()[STATE])

        store.commit(SplitDurable(slots = slots(SplitSlot.App("a|b\\c"), SplitSlot.Closed)))
        assertEquals("3|0|A:a\\pb\\\\c|C|0|0|0", preferences.snapshot()[STATE])

        store.commit(
            SplitDurable(
                enabled = true,
                slots = slots(SplitSlot.App(MUSIC), SplitSlot.App(MAPS)),
                memory = SplitMemory(
                    last = SplitPair(MUSIC, MAPS),
                    recent = listOf(SplitPair(MAPS, MUSIC)),
                    pinned = listOf(SplitPair("a|b", MUSIC)),
                ),
            ),
        )
        assertEquals(
            "3|1|A:$MUSIC|A:$MAPS|1|1|1|$MUSIC|$MAPS|$MAPS|$MUSIC|a\\pb|$MUSIC",
            preferences.snapshot()[STATE],
        )
        assertEquals("один ключ", setOf(STATE), preferences.snapshot().keys)
    }

    @Test
    fun aV2SnapshotReadsAsTheToggleTheSlotsAndAnEmptyMemory() {
        // раздел 6, ред. 2026-11-11: снимок v2 читается без отдельной миграции и ничего не пишет;
        // третье поле до 2026-10-09 считало операции, и такой снимок читается как прежде
        val stored = mapOf<String, Any>(STATE_V2 to "2|1|7|A:ru.yandex.music|P")
        val preferences = InMemorySharedPreferences(stored)
        val store = PreferencesSplitStateStore(preferences)

        assertEquals(
            SplitDurable(
                enabled = true,
                slots = slots(SplitSlot.App("ru.yandex.music"), SplitSlot.Picker),
                memory = SplitMemory(),
            ),
            store.load(),
        )
        assertEquals("чтение ничего не переписало", stored, preferences.snapshot())
        assertEquals(0, preferences.commits)

        // Следующая обычная запись пишет v3 и убирает v2 - одним коммитом, и ключ остаётся один.
        assertTrue(store.commit(store.load().copy(enabled = false)))
        assertEquals(1, preferences.commits)
        assertEquals(mapOf(STATE to "3|0|A:ru.yandex.music|P|0|0|0"), preferences.snapshot())
    }

    @Test
    fun aV2KeyBesideV3MeansAnOlderBuildWroteLater() {
        // Ред. 2026-11-11: commit пишет v3 и убирает v2 одним коммитом, так что v2 рядом с v3
        // оставила сборка старше - и написала позже. Тумблер и слоты - её, память - из v3, которой
        // она не знала. Чтение ничего не переписывает.
        val stored = mapOf<String, Any>(
            STATE to "3|0|P|P|1|0|0|$MUSIC|$MAPS",
            STATE_V2 to "2|1|0|A:$MAPS|A:$MUSIC",
        )
        val preferences = InMemorySharedPreferences(stored)
        val store = PreferencesSplitStateStore(preferences)

        assertEquals(
            SplitDurable(
                enabled = true,
                slots = slots(SplitSlot.App(MAPS), SplitSlot.App(MUSIC)),
                memory = SplitMemory(last = SplitPair(MUSIC, MAPS)),
            ),
            store.load(),
        )
        assertEquals(stored, preferences.snapshot())
        assertEquals(0, preferences.commits)

        // Следующая запись снова оставляет один ключ.
        assertTrue(store.commit(store.load()))
        assertEquals(setOf(STATE), preferences.snapshot().keys)
    }

    @Test
    fun anUnreadableV2BesideAGoodV3IsIgnored() {
        val preferences = InMemorySharedPreferences(
            mapOf(STATE to "3|1|A:$MUSIC|P|0|0|0", STATE_V2 to "2|1|x|A:$MUSIC"),
        )

        assertEquals(
            SplitDurable(enabled = true, slots = slots(SplitSlot.App(MUSIC), SplitSlot.Picker)),
            PreferencesSplitStateStore(preferences).load(),
        )
    }

    @Test
    fun aCorruptV3SnapshotDoesNotFallBackToTheV2One() {
        // порча v3 - это порча, а не повод воскресить снимок, который v3 уже заменил; и так же,
        // когда v2 рядом с ней оставила сборка старше
        val stored = mapOf<String, Any>(STATE to "3|1|P", STATE_V2 to "2|1|0|A:$MUSIC|A:$MAPS")
        val preferences = InMemorySharedPreferences(stored)

        assertEquals(safeDefault(), PreferencesSplitStateStore(preferences).load())
        assertEquals(stored, preferences.snapshot())
        assertEquals(0, preferences.commits)
    }

    @Test
    fun anUnreadableSnapshotResolvesToTheSafeDefault() {
        // U4: нечитаемое хранилище отказывает в сторону "выключено и ничего не помним"
        val pair = "$MUSIC|$MAPS"
        val corrupt = listOf(
            "",
            "3",
            "3|1|P|P",
            "3|1|P|P|0|0",
            "3|1|P|P|0|0|0|",
            "3|1|P|P|0|0|0|$MUSIC",
            "2|1|0|P|P",
            "4|1|P|P|0|0|0",
            "3|yes|P|P|0|0|0",
            "3|1|X|P|0|0|0",
            "3|1|P|A:escape\\qhere|0|0|0",
            "3|1|P|A:dangling\\|0|0|0",
            // Счётчики: не число, знак, ведущий ноль, больше одной последней, больше пяти недавних,
            // число полей не сходится со счётчиками.
            "3|1|P|P|x|0|0",
            "3|1|P|P|-1|0|0",
            "3|1|P|P|01|0|0|$pair",
            "3|1|P|P|2|0|0|$pair|$MAPS|$MUSIC",
            "3|1|P|P|0|6|0" + "|$pair".repeat(6),
            "3|1|P|P|1|0|0",
            "3|1|P|P|0|1|0|$pair|$pair",
            // Память, нарушающая собственные правила: пустое имя, повтор среди недавних, недавняя
            // равна последней или закреплённой, повтор среди закреплённых.
            "3|1|P|P|1|0|0||$MAPS",
            "3|1|P|P|0|2|0|$pair|$pair",
            "3|1|P|P|1|1|0|$pair|$pair",
            "3|1|P|P|0|1|1|$pair|$pair",
            "3|1|P|P|0|0|2|$pair|$pair",
            "3|1|P|P|1|0|0|$MUSIC\\q|$MAPS",
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
    fun anUnreadableV2SnapshotResolvesToTheSafeDefault() {
        // правила v2 - прежние, слово в слово: всё, что не снимок v2, - порча
        val corrupt = listOf(
            "",
            "2",
            "2|1|0|P",
            "2|1|0|P|P|P",
            "3|1|0|P|P",
            "3|1|P|P|0|0|0",
            "1|1|0|P|P",
            "2|yes|0|P|P",
            "2|1|seven|P|P",
            "2|1|0|X|P",
            "2|1|0|P|A:escape\\qhere",
            "2|1|0|P|A:dangling\\",
        )

        corrupt.forEach { payload ->
            val stored = mapOf<String, Any>(STATE_V2 to payload)
            val preferences = InMemorySharedPreferences(stored)

            assertEquals("v2 payload <$payload>", safeDefault(), PreferencesSplitStateStore(preferences).load())
            assertEquals(stored, preferences.snapshot())
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

        assertTrue(store.commit(SplitDurable(enabled = true)))
        assertTrue(store.commit(SplitDurable()))
        assertEquals(3, preferences.commits)

        store.load()
        store.load()
        assertEquals("a load over an existing key writes nothing", 3, preferences.commits)
    }

    @Test
    fun aRejectedCommitReportsFailureAndChangesNothing() {
        // §7.8: отказ записи - это отказ операции, а не половина состояния. Ревью 2026-11-19
        // (находка 6): настоящий редактор кладёт значения в память процесса ДО записи на диск, и
        // отвергнутый снимок оставался виден следующим чтениям, хотя на диске его нет.
        val preferences = InMemorySharedPreferences()
        val store = PreferencesSplitStateStore(preferences)
        store.commit(SplitDurable(slots = slots(SplitSlot.App(MUSIC), SplitSlot.Picker)))
        val before = preferences.snapshot()

        preferences.accept = false
        val committed = store.commit(SplitDurable(slots = slots(SplitSlot.Closed, SplitSlot.Closed)))

        assertFalse(committed)
        assertEquals("память процесса - та, что на диске", before, preferences.snapshot())
        assertEquals("а на диске отвергнутого нет", before, preferences.disk())
        assertEquals(
            SplitDurable(slots = slots(SplitSlot.App(MUSIC), SplitSlot.Picker)),
            store.load(),
        )
    }

    @Test
    fun aRejectedCommitOverA071SnapshotKeepsTheV2KeyReadable() {
        // Ревью 2026-11-19 (находка 6): первая запись v3 удаляет ключ v2. Если она не легла на диск,
        // ни этот процесс, ни прямой читатель тумблера не видят ни v3, ни пропажи v2.
        val v071 = "2|1|0|A:$NAVI|A:$MUSIC"
        val preferences = InMemorySharedPreferences(mapOf(STATE_V2 to v071))
        val store = PreferencesSplitStateStore(preferences)
        val loaded = store.load()

        preferences.accept = false
        assertFalse(store.commit(loaded.copy(enabled = false)))

        assertEquals(mapOf<String, Any>(STATE_V2 to v071), preferences.snapshot())
        assertEquals(loaded, PreferencesSplitStateStore(preferences).load())
        assertTrue("тумблер по-прежнему включён", PreferencesSplitStateStore(preferences).load().enabled)
    }

    @Test
    fun aReaderOnAnotherThreadNeverSeesASnapshotWhileItIsBeingRejected() {
        // Ревью 2026-11-19 (находка 6): снимок публикуется только после того, как он лёг на диск.
        // Читатель с другого потока (`SplitScreenSettings.isEnabled`) посреди отвергнутой записи
        // видит прежний снимок, а не отвергнутый.
        val preferences = InMemorySharedPreferences()
        val store = PreferencesSplitStateStore(preferences)
        val kept = SplitDurable(enabled = true, slots = slots(SplitSlot.App(MUSIC), SplitSlot.Picker))
        store.commit(kept)
        val seen = AtomicReference<SplitDurable>()
        var reader: Thread? = null
        preferences.accept = false
        preferences.whileWriting = {
            preferences.whileWriting = null
            reader = Thread { seen.set(PreferencesSplitStateStore(preferences).load()) }.apply {
                start()
                join(READER_GRACE_MS)
            }
        }

        assertFalse(store.commit(SplitDurable()))
        reader!!.join(READER_GRACE_MS * 10)

        assertEquals(kept, seen.get())
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
    )

    /**
     * A preferences file a test can read back byte for byte.
     *
     * `commit()` is the only way in, and it behaves as Android's editor does: the whole map in the
     * process memory is swapped first, and only then is the file written - which [accept] can
     * refuse, leaving [disk] as it was and the process memory holding the refused values
     * (`SharedPreferencesImpl.commitToMemory` before `writeToFile`; ревью 2026-11-19, находка 6).
     * [whileWriting] runs between the two. `apply()` throws: a store that fires and forgets cannot
     * report a failed write, and the contract requires it to.
     */
    private class InMemorySharedPreferences(
        initial: Map<String, Any> = emptyMap(),
    ) : SharedPreferences {

        @Volatile
        private var values: Map<String, Any> = initial.toMap()

        @Volatile
        private var file: Map<String, Any> = initial.toMap()

        var commits: Int = 0
        var accept: Boolean = true

        @Volatile
        var whileWriting: (() -> Unit)? = null

        /** What this process reads. */
        fun snapshot(): Map<String, Any> = values

        /** What the next process reads. */
        fun disk(): Map<String, Any> = file

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
                val next = values.toMutableMap()
                staged.forEach { edit -> edit(next) }
                values = next.toMap()
                staged.clear()
                whileWriting?.invoke()
                if (!accept) return false
                file = values
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
        const val NAVI = "ru.yandex.yandexnavi"

        const val STATE = "split_state_v3"
        const val STATE_V2 = "split_state_v2"

        const val LAST_PRIMARY = "picker_last_primary_package_v1"

        const val READER_GRACE_MS = 200L
    }
}
