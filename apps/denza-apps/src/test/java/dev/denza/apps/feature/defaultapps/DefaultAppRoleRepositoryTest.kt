package dev.denza.apps.feature.defaultapps

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DefaultAppRoleRepositoryTest {
    @Test
    fun readAcceptsOneExactRowAndUsesTheFixedProviderPredicate() = runBlocking {
        val access = FakePersonBeanAccess(row(DefaultAppRole.MUSIC, "com.byd.mediacenter"))
        val repository = DefaultAppRoleRepository(access)

        assertEquals("com.byd.mediacenter", repository.read(DefaultAppRole.MUSIC))
        assertEquals(listOf("SETTING=?"), access.querySelections)
        assertEquals(listOf(listOf("MUSIC_SWITCH")), access.queryArgs)
        assertTrue(access.updateSelections.isEmpty())
    }

    @Test
    fun protocolRejectsEveryRoleKeyOutsideTheThreeRoleAllowlist() = runBlocking {
        listOf("FM_SWITCH", "NEWS_SWITCH", "KARAOKE_SWITCH", "").forEach { roleKey ->
            expectAccessFailure {
                AutoVoicePersonBeanProtocol.requireSingleRow(
                    roleKey,
                    listOf(PersonBeanRow(1L, roleKey, "example.valid.app")),
                )
            }
        }
    }

    @Test
    fun readFailsClosedForZeroOrMultipleRows() = runBlocking {
        listOf(
            emptyList(),
            listOf(
                row(DefaultAppRole.MUSIC, "com.byd.mediacenter"),
                row(DefaultAppRole.MUSIC, "ru.yandex.music", id = 145L),
            ),
        ).forEach { rows ->
            val repository = DefaultAppRoleRepository(FakePersonBeanAccess(rows))

            expectAccessFailure { repository.read(DefaultAppRole.MUSIC) }
        }
    }

    @Test
    fun readRejectsAMismatchedSettingAndAMalformedStoredPackage() = runBlocking {
        listOf(
            PersonBeanRow(45L, "VIDEO_SWITCH", "com.byd.mediacenter"),
            PersonBeanRow(45L, "MUSIC_SWITCH", "not_a_package"),
            PersonBeanRow(45L, "MUSIC_SWITCH", ""),
        ).forEach { stored ->
            // The fake filters on SETTING; a mismatched row is handed back deliberately.
            val access = FakePersonBeanAccess(listOf(stored), filterBySetting = false)
            val repository = DefaultAppRoleRepository(access)

            expectAccessFailure { repository.read(DefaultAppRole.MUSIC) }
        }
    }

    @Test
    fun setRequiresOneMatchedRowAndAnExactReadback() = runBlocking {
        val access = FakePersonBeanAccess(row(DefaultAppRole.MUSIC, "com.byd.mediacenter"))
        val repository = DefaultAppRoleRepository(access)

        assertEquals(
            "ru.yandex.music",
            repository.set(DefaultAppRole.MUSIC, "ru.yandex.music"),
        )
        assertEquals(listOf("SETTING=?"), access.updateSelections)
        assertEquals(listOf(listOf("MUSIC_SWITCH")), access.updateArgs)
        assertEquals(listOf("ru.yandex.music"), access.updateValues)
        // Preflight read, then the exact readback.
        assertEquals(2, access.querySelections.size)
    }

    @Test
    fun setRejectsAnInvalidPackageBeforeTouchingTheProvider() = runBlocking {
        listOf(
            "",
            "music",
            ".ru.yandex.music",
            "ru.yandex.music;reboot",
            "ru.yandex.music\ncontent insert",
            "ru.yandex.music' OR '1'='1",
        ).forEach { packageName ->
            val access = FakePersonBeanAccess(row(DefaultAppRole.MUSIC, "com.byd.mediacenter"))
            val repository = DefaultAppRoleRepository(access)

            expectAccessFailure { repository.set(DefaultAppRole.MUSIC, packageName) }
            assertTrue(access.querySelections.isEmpty())
            assertTrue(access.updateSelections.isEmpty())
        }
    }

    @Test
    fun setRefusesEveryMatchedRowCountOtherThanOne() = runBlocking {
        listOf(0, 2, 3).forEach { count ->
            val access = FakePersonBeanAccess(
                rows = listOf(row(DefaultAppRole.VIDEO, "com.byd.videoplay")),
                scriptedUpdateCount = count,
            )
            val repository = DefaultAppRoleRepository(access)

            expectAccessFailure { repository.set(DefaultAppRole.VIDEO, "com.vk.vkvideo") }
            assertEquals(1, access.updateSelections.size)
            // Preflight only: a refused count is never followed by a readback.
            assertEquals(1, access.querySelections.size)
        }
    }

    @Test
    fun setFailsWhenTheReadbackDoesNotMatchTheRequestedPackage() = runBlocking {
        val access = FakePersonBeanAccess(
            rows = listOf(row(DefaultAppRole.NAVIGATION, "com.byd.launchermap")),
            applyUpdates = false,
        )
        val repository = DefaultAppRoleRepository(access)

        expectAccessFailure {
            repository.set(DefaultAppRole.NAVIGATION, "ru.yandex.yandexnavi")
        }
        assertEquals(2, access.querySelections.size)
    }

    @Test
    fun conditionalSetPutsTheExpectedValueIntoTheUpdatePredicate() = runBlocking {
        val access = FakePersonBeanAccess(row(DefaultAppRole.MUSIC, "com.byd.mediacenter"))
        val repository = DefaultAppRoleRepository(access)

        assertEquals(
            "ru.yandex.music",
            repository.setIfCurrent(
                role = DefaultAppRole.MUSIC,
                expectedCurrentPackageName = "com.byd.mediacenter",
                packageName = "ru.yandex.music",
            ),
        )
        assertEquals(listOf("SETTING=? AND VALUE=?"), access.updateSelections)
        assertEquals(
            listOf(listOf("MUSIC_SWITCH", "com.byd.mediacenter")),
            access.updateArgs,
        )
    }

    @Test
    fun conditionalSetRefusesAValueChangedBeforeItsPreflight() = runBlocking {
        val access = FakePersonBeanAccess(row(DefaultAppRole.MUSIC, "example.external.player"))
        val repository = DefaultAppRoleRepository(access)

        expectAccessFailure {
            repository.setIfCurrent(
                role = DefaultAppRole.MUSIC,
                expectedCurrentPackageName = "com.byd.mediacenter",
                packageName = "ru.yandex.music",
            )
        }
        assertEquals(1, access.querySelections.size)
        assertTrue(access.updateSelections.isEmpty())
    }

    @Test
    fun conditionalSetFailsWhenItsPredicateMatchesNothingAfterThePreflight() = runBlocking {
        val access = FakePersonBeanAccess(
            rows = listOf(row(DefaultAppRole.VIDEO, "com.byd.videoplay")),
            scriptedUpdateCount = 0,
        )
        val repository = DefaultAppRoleRepository(access)

        expectAccessFailure {
            repository.setIfCurrent(
                role = DefaultAppRole.VIDEO,
                expectedCurrentPackageName = "com.byd.videoplay",
                packageName = "com.vk.vkvideo",
            )
        }
        assertEquals(
            listOf(listOf("VIDEO_SWITCH", "com.byd.videoplay")),
            access.updateArgs,
        )
        assertEquals(1, access.querySelections.size)
    }

    @Test
    fun readAllIssuesOneInQueryAndReportsEveryRoleIndependently() = runBlocking {
        val access = FakePersonBeanAccess(
            row(DefaultAppRole.NAVIGATION, "com.byd.launchermap"),
            row(DefaultAppRole.MUSIC, "ru.yandex.music"),
            row(DefaultAppRole.VIDEO, "com.byd.videoplay"),
        )
        val repository = DefaultAppRoleRepository(access)

        val observed = repository.readAll()

        assertEquals(listOf("SETTING IN (?,?,?)"), access.querySelections)
        assertEquals(
            listOf(listOf("DEFAULT_MAP_SWITCH", "MUSIC_SWITCH", "VIDEO_SWITCH")),
            access.queryArgs,
        )
        assertEquals(
            "com.byd.launchermap",
            observed.getValue(DefaultAppRole.NAVIGATION).getOrNull(),
        )
        assertEquals("ru.yandex.music", observed.getValue(DefaultAppRole.MUSIC).getOrNull())
        assertEquals("com.byd.videoplay", observed.getValue(DefaultAppRole.VIDEO).getOrNull())
    }

    @Test
    fun readAllFailsOnlyTheRoleWhoseRowIsMissingOrDuplicated() = runBlocking {
        val access = FakePersonBeanAccess(
            row(DefaultAppRole.NAVIGATION, "com.byd.launchermap"),
            row(DefaultAppRole.VIDEO, "com.byd.videoplay"),
            row(DefaultAppRole.VIDEO, "com.vk.vkvideo", id = 187L),
        )
        val repository = DefaultAppRoleRepository(access)

        val observed = repository.readAll()

        assertEquals(1, access.querySelections.size)
        assertEquals(
            "com.byd.launchermap",
            observed.getValue(DefaultAppRole.NAVIGATION).getOrThrow(),
        )
        // MUSIC has no row at all; VIDEO has two. Neither may take NAVIGATION down with it.
        assertNull(observed.getValue(DefaultAppRole.MUSIC).getOrNull())
        assertNull(observed.getValue(DefaultAppRole.VIDEO).getOrNull())
        assertTrue(
            observed.getValue(DefaultAppRole.MUSIC).exceptionOrNull()
                is DefaultAppRoleAccessException,
        )
        assertTrue(
            observed.getValue(DefaultAppRole.VIDEO).exceptionOrNull()
                is DefaultAppRoleAccessException,
        )
    }

    @Test
    fun readAllReportsATransportFailureForEveryRole() = runBlocking {
        val access = FakePersonBeanAccess(
            rows = emptyList(),
            queryFailure = DefaultAppRoleAccessException("no cursor"),
        )
        val repository = DefaultAppRoleRepository(access)

        val observed = repository.readAll()

        assertEquals(DefaultAppRole.entries.size, observed.size)
        assertTrue(
            observed.values.all { it.exceptionOrNull() is DefaultAppRoleAccessException },
        )
    }

    @Test
    fun concurrentRepositoryReadsNeverInterleaveProviderOperations() = runBlocking {
        val access = YieldingPersonBeanAccess()
        val repository = DefaultAppRoleRepository(access)

        DefaultAppRole.entries.map { role ->
            async { repository.read(role) }
        }.awaitAll()

        assertEquals(1, access.maxConcurrentCalls)
        assertEquals(DefaultAppRole.entries.size, access.calls)
    }

    private suspend fun expectAccessFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected DefaultAppRoleAccessException")
        } catch (_: DefaultAppRoleAccessException) {
            // Expected fail-closed result.
        }
    }

    /**
     * In-memory PersonBean. Records every predicate it was handed, and can be scripted to report a
     * matched-row count the rows do not justify - which is what an update losing a race looks like.
     */
    private class FakePersonBeanAccess(
        rows: List<PersonBeanRow>,
        private val filterBySetting: Boolean = true,
        private val applyUpdates: Boolean = true,
        private val scriptedUpdateCount: Int? = null,
        private val queryFailure: Exception? = null,
    ) : PersonBeanAccess {
        constructor(vararg rows: PersonBeanRow) : this(rows.toList())

        private val stored = rows.toMutableList()

        val querySelections = mutableListOf<String>()
        val queryArgs = mutableListOf<List<String>>()
        val updateSelections = mutableListOf<String>()
        val updateArgs = mutableListOf<List<String>>()
        val updateValues = mutableListOf<String>()

        override suspend fun query(
            selection: String,
            selectionArgs: Array<String>,
        ): List<PersonBeanRow> {
            querySelections += selection
            queryArgs += selectionArgs.toList()
            queryFailure?.let { throw it }
            if (!filterBySetting) return stored.toList()
            return stored.filter { it.setting in selectionArgs }
        }

        override suspend fun update(
            value: String,
            selection: String,
            selectionArgs: Array<String>,
        ): Int {
            updateSelections += selection
            updateArgs += selectionArgs.toList()
            updateValues += value
            val roleKey = selectionArgs.first()
            val expected = selectionArgs.getOrNull(1)
            val matched = stored.filter {
                it.setting == roleKey && (expected == null || it.value == expected)
            }
            if (applyUpdates) {
                matched.forEach { match ->
                    stored[stored.indexOf(match)] = match.copy(value = value)
                }
            }
            return scriptedUpdateCount ?: matched.size
        }
    }

    private class YieldingPersonBeanAccess : PersonBeanAccess {
        var calls = 0
            private set
        var maxConcurrentCalls = 0
            private set
        private var concurrentCalls = 0

        override suspend fun query(
            selection: String,
            selectionArgs: Array<String>,
        ): List<PersonBeanRow> {
            calls += 1
            concurrentCalls += 1
            maxConcurrentCalls = maxOf(maxConcurrentCalls, concurrentCalls)
            yield()
            concurrentCalls -= 1

            val role = DefaultAppRole.entries.single { it.roleKey in selectionArgs }
            return listOf(row(role, role.stockPackageName))
        }

        override suspend fun update(
            value: String,
            selection: String,
            selectionArgs: Array<String>,
        ): Int = 1
    }

    private companion object {
        fun row(
            role: DefaultAppRole,
            packageName: String,
            id: Long = when (role) {
                DefaultAppRole.NAVIGATION -> 43L
                DefaultAppRole.MUSIC -> 45L
                DefaultAppRole.VIDEO -> 186L
            },
        ): PersonBeanRow = PersonBeanRow(id, role.roleKey, packageName)
    }
}
