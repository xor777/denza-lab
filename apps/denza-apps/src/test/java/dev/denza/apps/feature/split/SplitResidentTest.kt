package dev.denza.apps.feature.split

import dev.denza.apps.TaskMoveOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ф1/Ф4 волны 15: the resident shell-UID helper, and the rule that it can never cost anything.
 *
 * The helper is an accelerator behind the one funnel every command already goes through, so the
 * only two things that can go wrong with it are serving a command it must not serve, and failing
 * in a way the product does not survive. Both are what this covers.
 */
class SplitResidentTest {

    // region what may be served at all

    @Test
    fun theHelperServesTheWorldReadTheReadTransactionsAndRemovals() {
        assertEquals("world", requestFor("am stack list"))
        assertEquals("call-int 30", requestFor("service call activity_task 30"))
        assertEquals(
            "call-int 112 s16 'com.example.app'",
            requestFor("service call activity_task 112 s16 'com.example.app'"),
        )
        assertEquals("call-int 118 i32 3", requestFor("service call activity_task 118 i32 3"))
        assertEquals(
            "remove-task 42 'com.example' 'com.example.Main' '-' '-'",
            requestFor(
                "CLASSPATH='/data/local/tmp/denza-split-proxy-30.jar' app_process /system/bin " +
                    "--nice-name=denza_split_cmd dev.denza.apps.feature.split.SplitTaskProxyMain " +
                    "remove-task 42 'com.example' 'com.example.Main' '-' '-'",
            ),
        )
    }

    /**
     * Contract 1.12: 125 extends the firmware's split-capable list and 126 moves the gate, and
     * both outlive the session. They are a handful of calls, and the whole worth of the helper is
     * that it may be wrong for free - which a write is not.
     */
    @Test
    fun theHelperNeverServesAWriteOrAnythingItWasNotTaughtLetterForLetter() {
        listOf(
            "service call activity_task 125 s16 'com.example.app'",
            "service call activity_task 126 i32 1",
            "service call activity_task 126 i32 0",
            "service call activity_task 1120",
            "service call window 30",
            "am stack move-task 42 3 true",
            "am stack list --user 0",
            "am task focus 42",
            "dumpsys input",
            "settings get global development_settings_enabled",
            "CLASSPATH='/x.jar' app_process /system/bin " +
                "--nice-name=denza_split_cmd dev.denza.apps.feature.split.SplitTaskProxyMain " +
                "start-in-task 1 a b c d",
        ).forEach { command ->
            assertNull(command, SplitResidentRequest.of(command))
        }
    }

    /**
     * A request line is split by the one quoting rule a POSIX shell has, so it has to be the exact
     * inverse of how the recipes quote an argument - otherwise a package name with a quote in it
     * would mean one thing on the shell and another to the helper.
     */
    @Test
    fun quotingAnArgumentAndSplittingItBackAgainIsTheIdentity() {
        listOf(
            "com.example.app",
            "com.example.app/.MainActivity",
            "it's here",
            "with space",
            "\$HOME `id` \"quoted\"",
            "привет",
            "back\\slash",
            "-",
        ).forEach { value ->
            val quoted = "'${value.replace("'", "'\\''")}'"
            assertEquals(value, SplitTaskProxyMain.splitArguments(quoted).single())
        }
    }

    @Test
    fun aRequestLineSplitsIntoTheSameArgvTheCommandLineWouldHaveBuilt() {
        assertEquals(
            listOf("remove-task", "42", "com.example", "com.example.Main", "-", "-"),
            SplitTaskProxyMain.splitArguments(
                "remove-task 42 'com.example' 'com.example.Main' '-' '-'",
            ),
        )
        assertEquals(
            listOf("call-int", "112", "s16", "it's here"),
            SplitTaskProxyMain.splitArguments("call-int 112 s16 'it'\\''s here'"),
        )
    }

    /** The helper answers a transaction in the very line `service call` prints for one. */
    @Test
    fun aTransactionIsAnsweredInTheWordsServiceCallWouldHaveUsed() {
        assertEquals(
            "Result: Parcel(00000000 00000004 '........')",
            SplitTaskProxyMain.parcelInt(4),
        )
        assertEquals(
            "Result: Parcel(00000000 ffffffff '........')",
            SplitTaskProxyMain.parcelInt(-1),
        )
    }

    // endregion

    // region the policy that keeps the helper from ever costing anything

    @Test
    fun aReadNeverStandsAHelperUpAndARemovalDoes() {
        val channels = mutableListOf<FakeChannel>()
        val proxy = SplitResidentProxy(open = { FakeChannel().also(channels::add) })

        assertSame(
            SplitResidentAnswer.NotServed,
            proxy.answer(request("am stack list"), ::launch),
        )
        assertTrue("a read may not pay 0.4 s to start one", channels.isEmpty())

        val served = proxy.answer(request(REMOVE_COMMAND), ::launch)
        assertEquals("remove-task 42 'p' 'a' '-' '-'", (served as SplitResidentAnswer.Served).output)
        assertEquals(1, channels.size)
        assertEquals(listOf("launch:${channels.single().nonce}"), channels.single().started)

        // and now that one is up, the read it would not have paid for is free
        assertEquals(
            "world",
            (proxy.answer(request("am stack list"), ::launch) as SplitResidentAnswer.Served).output,
        )
        assertEquals("no second helper", 1, channels.size)
    }

    @Test
    fun aCarThatWillNotRunTheHelperIsAskedThreeTimesAndNeverAgain() {
        val channels = mutableListOf<FakeChannel>()
        val proxy = SplitResidentProxy(open = { FakeChannel(failStart = true).also(channels::add) })

        repeat(5) { attempt ->
            val answer = proxy.answer(request(REMOVE_COMMAND), ::launch)
            if (attempt < 3) {
                assertSame("attempt $attempt reached the car", SplitResidentAnswer.Failed, answer)
            } else {
                assertSame("attempt $attempt asked nothing", SplitResidentAnswer.NotServed, answer)
            }
        }
        assertEquals(3, channels.size)
        assertTrue("a failed start leaves nothing open", channels.all(FakeChannel::closed))
    }

    /** What the car's sleep leaves behind: a channel that is there and answers nothing. */
    @Test
    fun aHelperThatStoppedAnsweringIsDroppedAndTheNextRemovalStandsANewOneUp() {
        val channels = mutableListOf<FakeChannel>()
        val proxy = SplitResidentProxy(open = { FakeChannel().also(channels::add) })

        proxy.answer(request(REMOVE_COMMAND), ::launch)
        channels.single().failRequests = true

        assertSame(SplitResidentAnswer.Failed, proxy.answer(request(REMOVE_COMMAND), ::launch))
        assertTrue("the dead channel is closed", channels.single().closed)
        assertSame(
            "and a read does not stand a new one up on its own",
            SplitResidentAnswer.NotServed,
            proxy.answer(request("am stack list"), ::launch),
        )

        val revived = proxy.answer(request(REMOVE_COMMAND), ::launch)
        assertTrue(revived is SplitResidentAnswer.Served)
        assertEquals(2, channels.size)
    }

    /** Ф4: whoever owns the helper ends it, and closing the channel is what kills it on the car. */
    @Test
    fun closingTheProxyClosesTheChannelAndTheProcessBehindIt() {
        val channels = mutableListOf<FakeChannel>()
        val proxy = SplitResidentProxy(open = { FakeChannel().also(channels::add) })
        proxy.answer(request(REMOVE_COMMAND), ::launch)

        proxy.close()

        assertTrue(channels.single().closed)
        assertSame(
            "and nothing is left to answer with",
            SplitResidentAnswer.NotServed,
            proxy.answer(request("am stack list"), ::launch),
        )
    }

    // endregion

    // region behind the one funnel of the operation

    /**
     * A command the helper served never reaches the car, and one it could not serve reaches it
     * letter for letter - which is the whole safety argument: no recipe, no postcondition and no
     * parser knows whether a helper exists.
     */
    @Test
    fun theHelperAnswersInsteadOfTheCarOrTheCarIsAskedExactlyAsBefore() {
        val sent = mutableListOf<String>()
        val channel = FakeChannel()
        val workspace = workspace(sent, SplitResidentProxy(open = { channel }))

        // No helper is up yet, and a read may not stand one up: this goes to the car.
        assertEquals("shell:am stack list", workspace.shell("am stack list"))
        assertEquals(listOf("am stack list"), sent)

        // A removal is worth the 0.4 s, so it stands one up and is answered by it.
        assertEquals(
            "remove-task 42 'p' 'a' '-' '-'",
            workspace.shell(REMOVE_COMMAND),
        )
        assertEquals("the removal never reached the car", listOf("am stack list"), sent)

        // And now the read is free too.
        assertEquals("world", workspace.shell("am stack list"))
        assertEquals(listOf("am stack list"), sent)

        // The car sleeps and takes the helper with it. The command is sent, unchanged.
        channel.failRequests = true
        assertEquals("shell:$REMOVE_COMMAND", workspace.shell(REMOVE_COMMAND))
        assertEquals(listOf("am stack list", REMOVE_COMMAND), sent)
    }

    /** Ф5 and Ф1 together: a fallback is two round trips, and the budget line may not hide one. */
    @Test
    fun aFallbackIsCountedAsTheTwoRoundTripsItReallyIs() {
        val diagnostics = mutableListOf<String>()
        val channel = FakeChannel()
        val workspace = workspace(
            mutableListOf(),
            SplitResidentProxy(open = { channel }),
            diagnostics::add,
        )

        workspace.shell(REMOVE_COMMAND)
        channel.failRequests = true
        workspace.shell(REMOVE_COMMAND)
        workspace.reportBudget("open")

        assertEquals(
            listOf(
                "open: обращений 3, в shell 0.0 с, транспорт (очередь 0.0, отправка 0.0, " +
                    "ответ 0.0), разбор 0.0 с, в паузах 0.0 с",
            ),
            diagnostics.filter { line -> line.startsWith("open:") },
        )
    }

    /** Ф4: the toggle going off ends the split session, and nothing of ours is left on the car. */
    @Test
    fun theToggleGoingOffAndTheShutdownBothEndTheHelper() {
        val channels = mutableListOf<FakeChannel>()
        val proxy = SplitResidentProxy(open = { FakeChannel().also(channels::add) })
        proxy.answer(request(REMOVE_COMMAND), ::launch)
        val car = SplitCarFixture(FakeShell().apply { liveProductScene() })
        try {
            val core = car.core(SplitDurable(enabled = true, slots = PICKER_PAIR), resident = proxy)
            core.initialize {}
            car.barrier()

            core.setEnabled(false)
            car.barrier()
            assertTrue("the toggle left a helper running", channels.single().closed)

            core.shutdown()
        } finally {
            car.close()
        }
    }

    /**
     * Ф4: 60 MB of PSS may not stand beside a split the user is only looking at.
     *
     * The window is armed when an operation ends and disarmed when the next one starts, so what
     * it measures is "no operation at all", which is the only time the helper is certainly unused.
     */
    @Test
    fun aHelperNothingHasNeededForAWhileIsLetGoOf() {
        val channels = mutableListOf<FakeChannel>()
        val proxy = SplitResidentProxy(open = { FakeChannel().also(channels::add) })
        proxy.answer(request(REMOVE_COMMAND), ::launch)
        val car = SplitCarFixture(FakeShell().apply { liveProductScene() })
        try {
            val core = car.core(SplitDurable(enabled = false), resident = proxy)
            core.initialize {}
            // An operation that sends the car nothing at all (1.2.1) - what is being measured is
            // the window after one, not anything the operation itself did.
            core.setEnabled(true)
            car.barrier()

            car.clock.advance(SplitCoordinatorCore.RESIDENT_IDLE_MS - 1)
            assertFalse("let go of too early", channels.single().closed)

            car.clock.advance(1)
            assertTrue("the helper stood about with nothing to do", channels.single().closed)

            core.shutdown()
        } finally {
            car.close()
        }
    }

    /**
     * And an operation that is already waiting for the worker takes the window off the clock.
     *
     * The window is armed when an operation ends, so an operation that queues behind a long one
     * could otherwise watch its own helper be taken away while it waited its turn - and pay half a
     * second to stand a new one up for no reason at all.
     */
    @Test
    fun aHelperIsNeverPulledOutFromUnderAnOperationInFlight() {
        val channels = mutableListOf<FakeChannel>()
        val proxy = SplitResidentProxy(open = { FakeChannel().also(channels::add) })
        proxy.answer(request(REMOVE_COMMAND), ::launch)
        val car = SplitCarFixture(FakeShell().apply { liveProductScene() })
        try {
            val core = car.core(SplitDurable(enabled = false), resident = proxy)
            core.initialize {}
            core.setEnabled(true)
            car.barrier()

            val hold = car.hold()
            core.setEnabled(true)
            car.clock.advance(SplitCoordinatorCore.RESIDENT_IDLE_MS + 1)

            assertFalse("taken from an operation still waiting", channels.single().closed)

            hold.release()
            car.barrier()
            core.shutdown()
        } finally {
            car.close()
        }
    }

    /** And an operation that starts inside the window takes the helper back off the clock. */
    @Test
    fun aHelperIsNeverPulledOutFromUnderAnOperationThatJustStarted() {
        val channels = mutableListOf<FakeChannel>()
        val proxy = SplitResidentProxy(open = { FakeChannel().also(channels::add) })
        proxy.answer(request(REMOVE_COMMAND), ::launch)
        val car = SplitCarFixture(FakeShell().apply { liveProductScene() })
        try {
            val core = car.core(SplitDurable(enabled = false), resident = proxy)
            core.initialize {}
            core.setEnabled(true)
            car.barrier()

            car.clock.advance(SplitCoordinatorCore.RESIDENT_IDLE_MS - 1)
            core.setEnabled(true)
            car.barrier()
            car.clock.advance(2)

            assertFalse("the window belongs to the new operation now", channels.single().closed)

            car.clock.advance(SplitCoordinatorCore.RESIDENT_IDLE_MS)
            assertTrue(channels.single().closed)

            core.shutdown()
        } finally {
            car.close()
        }
    }

    private fun workspace(
        sent: MutableList<String>,
        resident: SplitResidentProxy,
        diagnostics: (String) -> Unit = {},
    ) = SplitOperationWorkspace(
        shellFactory = {
            object : SplitShellHandle {
                override fun shell(command: String): String {
                    sent += command
                    return "shell:$command"
                }

                override fun close() = Unit
            }
        },
        store = CountingStore(),
        catalog = FakeCatalog,
        leases = emptyList(),
        gateLeaseStore = FakeGateLease(),
        apkPath = SPLIT_APK_PATH,
        resident = resident,
        clock = object : SplitClock {
            override fun nowMs(): Long = 0L

            override fun schedule(delayMs: Long, action: () -> Unit) = SplitCancellable {}
        },
        sleeper = {},
        diagnostics = { line, _ -> diagnostics(line) },
        readState = { SplitState() },
        readLive = { emptyMap() },
        externalMoveInFlight = { false },
        ownership = TaskMoveOwnership { 0L },
        publisher = { _, _ -> },
    )

    // endregion

    private fun requestFor(command: String): String? = SplitResidentRequest.of(command)?.line

    private fun request(command: String): SplitResidentRequest =
        checkNotNull(SplitResidentRequest.of(command)) { "the helper may not serve $command" }

    private fun launch(nonce: String): String = "launch:$nonce"

    private class FakeChannel(
        private val failStart: Boolean = false,
    ) : SplitResidentChannel {
        val nonce: String = "nonce"
        val started = mutableListOf<String>()

        var failRequests = false
        var closed = false
            private set

        override fun start(launch: (nonce: String) -> String) {
            check(!failStart) { "app_process did not come up" }
            started += launch(nonce)
        }

        override fun request(line: String): String {
            check(!failRequests) { "the helper is gone" }
            return line
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val REMOVE_COMMAND =
            "CLASSPATH='/data/local/tmp/denza-split-proxy-31.jar' app_process /system/bin " +
                "--nice-name=denza_split_cmd dev.denza.apps.feature.split.SplitTaskProxyMain " +
                "remove-task 42 'p' 'a' '-' '-'"
    }
}
