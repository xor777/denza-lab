package dev.denza.apps.feature.defaultapps

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DefaultAppRoleRepositoryTest {
    @Test
    fun readAcceptsOneExactRowAndUsesTheFixedProviderQuery() = runBlocking {
        val shell = ScriptedShell(
            row(DefaultAppRole.MUSIC, "com.byd.mediacenter"),
        )
        val repository = DefaultAppRoleRepository(shell)

        assertEquals("com.byd.mediacenter", repository.read(DefaultAppRole.MUSIC))
        assertEquals(
            listOf(
                "content query --uri content://com.byd.autovoice/PersonBean " +
                    "--projection _id:SETTING:VALUE --where \"SETTING='MUSIC_SWITCH'\"",
            ),
            shell.commands,
        )
    }

    @Test
    fun protocolRejectsEveryRoleKeyOutsideTheThreeRoleAllowlist() = runBlocking {
        listOf("FM_SWITCH", "NEWS_SWITCH", "KARAOKE_SWITCH", "").forEach { roleKey ->
            expectAccessFailure { AutoVoicePersonBeanProtocol.queryCommand(roleKey) }
            expectAccessFailure {
                AutoVoicePersonBeanProtocol.updateCommand(roleKey, "example.valid.app")
            }
        }
    }

    @Test
    fun readFailsClosedForZeroOrMultipleRows() = runBlocking {
        listOf(
            "No result found.",
            row(DefaultAppRole.MUSIC, "com.byd.mediacenter") + "\n" +
                row(DefaultAppRole.MUSIC, "ru.yandex.music", row = 1, id = 145),
        ).forEach { output ->
            val repository = DefaultAppRoleRepository(ScriptedShell(output))

            expectAccessFailure { repository.read(DefaultAppRole.MUSIC) }
        }
    }

    @Test
    fun readRejectsMismatchedRowsUnexpectedOutputAndMalformedStoredPackages() = runBlocking {
        listOf(
            "Row: 0 _id=45, SETTING=VIDEO_SWITCH, VALUE=com.byd.mediacenter",
            row(DefaultAppRole.MUSIC, "com.byd.mediacenter") + "\nprovider warning",
            "Row: 0 _id=45, SETTING=MUSIC_SWITCH, VALUE=not_a_package",
        ).forEach { output ->
            val repository = DefaultAppRoleRepository(ScriptedShell(output))

            expectAccessFailure { repository.read(DefaultAppRole.MUSIC) }
        }
    }

    @Test
    fun setAcceptsEmptyDiLinkUpdateOutputAndRequiresExactReadback() = runBlocking {
        val shell = ScriptedShell(
            row(DefaultAppRole.MUSIC, "com.byd.mediacenter"),
            "",
            row(DefaultAppRole.MUSIC, "ru.yandex.music"),
        )
        val repository = DefaultAppRoleRepository(shell)

        assertEquals(
            "ru.yandex.music",
            repository.set(DefaultAppRole.MUSIC, "ru.yandex.music"),
        )
        assertEquals(
            listOf(
                "content query --uri content://com.byd.autovoice/PersonBean " +
                    "--projection _id:SETTING:VALUE --where \"SETTING='MUSIC_SWITCH'\"",
                "content update --uri content://com.byd.autovoice/PersonBean " +
                    "--bind VALUE:s:ru.yandex.music --where \"SETTING='MUSIC_SWITCH'\"",
                "content query --uri content://com.byd.autovoice/PersonBean " +
                    "--projection _id:SETTING:VALUE --where \"SETTING='MUSIC_SWITCH'\"",
            ),
            shell.commands,
        )
        assertTrue(shell.commands.none { " insert " in " $it " })
        assertTrue(shell.commands.none { "settings" in it })
    }

    @Test
    fun protocolAlsoAcceptsExactOneRowUpdateReports() {
        listOf("Updated 1 row", "Updated 1 rows", "Updated 1 row.", "Updated 1 rows.").forEach {
            AutoVoicePersonBeanProtocol.requireAcceptedUpdateOutput("MUSIC_SWITCH", it)
        }
    }

    @Test
    fun setRejectsInvalidPackageBeforeOpeningTheShell() = runBlocking {
        listOf(
            "",
            "music",
            ".ru.yandex.music",
            "ru.yandex.music;reboot",
            "ru.yandex.music\ncontent insert",
        ).forEach { packageName ->
            val shell = ScriptedShell()
            val repository = DefaultAppRoleRepository(shell)

            expectAccessFailure { repository.set(DefaultAppRole.MUSIC, packageName) }
            assertTrue(shell.commands.isEmpty())
        }
    }

    @Test
    fun setRejectsEveryOtherNonEmptyUpdateOutput() = runBlocking {
        listOf("Updated 0 rows.", "Updated 2 rows.", "Updated 1 rows.\nwarning", "Success").forEach {
            val shell = ScriptedShell(
                row(DefaultAppRole.VIDEO, "com.byd.videoplay"),
                it,
            )
            val repository = DefaultAppRoleRepository(shell)

            expectAccessFailure { repository.set(DefaultAppRole.VIDEO, "com.vk.vkvideo") }
            assertEquals(2, shell.commands.size)
        }
    }

    @Test
    fun setFailsWhenExactReadbackDoesNotMatchTheRequestedPackage() = runBlocking {
        val shell = ScriptedShell(
            row(DefaultAppRole.NAVIGATION, "com.byd.launchermap"),
            "",
            row(DefaultAppRole.NAVIGATION, "com.byd.launchermap"),
        )
        val repository = DefaultAppRoleRepository(shell)

        expectAccessFailure {
            repository.set(DefaultAppRole.NAVIGATION, "ru.yandex.yandexnavi")
        }
        assertEquals(3, shell.commands.size)
    }

    @Test
    fun conditionalSetIncludesTheExpectedValueInTheProviderPredicate() = runBlocking {
        val shell = ScriptedShell(
            row(DefaultAppRole.MUSIC, "com.byd.mediacenter"),
            "",
            row(DefaultAppRole.MUSIC, "ru.yandex.music"),
        )
        val repository = DefaultAppRoleRepository(shell)

        assertEquals(
            "ru.yandex.music",
            repository.setIfCurrent(
                role = DefaultAppRole.MUSIC,
                expectedCurrentPackageName = "com.byd.mediacenter",
                packageName = "ru.yandex.music",
            ),
        )
        assertEquals(
            "content update --uri content://com.byd.autovoice/PersonBean " +
                "--bind VALUE:s:ru.yandex.music --where " +
                "\"SETTING='MUSIC_SWITCH' AND VALUE='com.byd.mediacenter'\"",
            shell.commands[1],
        )
    }

    @Test
    fun conditionalSetRefusesAValueChangedBeforeItsPreflight() = runBlocking {
        val shell = ScriptedShell(
            row(DefaultAppRole.MUSIC, "example.external.player"),
        )
        val repository = DefaultAppRoleRepository(shell)

        expectAccessFailure {
            repository.setIfCurrent(
                role = DefaultAppRole.MUSIC,
                expectedCurrentPackageName = "com.byd.mediacenter",
                packageName = "ru.yandex.music",
            )
        }
        assertEquals(1, shell.commands.size)
    }

    @Test
    fun conditionalSetCannotOverwriteAValueChangedAfterItsPreflight() = runBlocking {
        val shell = ScriptedShell(
            row(DefaultAppRole.VIDEO, "com.byd.videoplay"),
            "",
            row(DefaultAppRole.VIDEO, "com.vk.vkvideo.external"),
        )
        val repository = DefaultAppRoleRepository(shell)

        expectAccessFailure {
            repository.setIfCurrent(
                role = DefaultAppRole.VIDEO,
                expectedCurrentPackageName = "com.byd.videoplay",
                packageName = "com.vk.vkvideo",
            )
        }
        assertTrue(shell.commands[1].contains("AND VALUE='com.byd.videoplay'"))
        assertEquals(3, shell.commands.size)
    }

    @Test
    fun concurrentRepositoryReadsNeverInterleaveShellOperations() = runBlocking {
        val shell = YieldingRoleShell()
        val repository = DefaultAppRoleRepository(shell)

        DefaultAppRole.entries.map { role ->
            async { repository.read(role) }
        }.awaitAll()

        assertEquals(1, shell.maxConcurrentCalls)
        assertEquals(DefaultAppRole.entries.size, shell.commands.size)
    }

    private suspend fun expectAccessFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected DefaultAppRoleAccessException")
        } catch (_: DefaultAppRoleAccessException) {
            // Expected fail-closed result.
        }
    }

    private class ScriptedShell(
        vararg outputs: String,
    ) : DefaultAppRoleShell {
        private val remainingOutputs = outputs.toMutableList()
        val commands = mutableListOf<String>()

        override suspend fun execute(command: String): String {
            commands += command
            check(remainingOutputs.isNotEmpty()) { "Unexpected shell command: $command" }
            return remainingOutputs.removeAt(0)
        }
    }

    private class YieldingRoleShell : DefaultAppRoleShell {
        val commands = mutableListOf<String>()
        var maxConcurrentCalls = 0
            private set
        private var concurrentCalls = 0

        override suspend fun execute(command: String): String {
            commands += command
            concurrentCalls += 1
            maxConcurrentCalls = maxOf(maxConcurrentCalls, concurrentCalls)
            yield()
            concurrentCalls -= 1

            val role = DefaultAppRole.entries.single { role ->
                command.contains("SETTING='${role.roleKey}'")
            }
            return row(role, role.stockPackageName)
        }
    }

    private companion object {
        fun row(
            role: DefaultAppRole,
            packageName: String,
            row: Int = 0,
            id: Int = when (role) {
                DefaultAppRole.NAVIGATION -> 43
                DefaultAppRole.MUSIC -> 45
                DefaultAppRole.VIDEO -> 186
            },
        ): String =
            "Row: $row _id=$id, SETTING=${role.roleKey}, VALUE=$packageName"
    }
}
