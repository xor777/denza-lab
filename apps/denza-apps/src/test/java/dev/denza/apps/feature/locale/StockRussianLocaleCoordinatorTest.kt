package dev.denza.apps.feature.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockRussianLocaleCoordinatorTest {
    @Test
    fun readsRussianOverrideFromAndroidLocaleServiceOutput() {
        val repair = StockRussianLocaleRepair {
            "Locales for com.byd.carsettings for user 0 are [ru-RU]"
        }

        val override = repair.inspect()

        assertTrue(override.russianEnabled)
        assertFalse(override.usesSystemDefault)
    }

    @Test
    fun enablingRussianWritesOnePackageAndVerifiesIt() {
        val commands = mutableListOf<String>()
        val outputs = ArrayDeque(
            listOf(
                "Locales for com.byd.carsettings for user 0 are []",
                "",
                "Locales for com.byd.carsettings for user 0 are [ru-RU]",
            ),
        )
        val repair = StockRussianLocaleRepair { command ->
            commands += command
            outputs.removeFirst()
        }

        val (change, override) = repair.setEnabled(true)

        assertEquals(StockRussianLocaleChange.CHANGED, change)
        assertTrue(override.russianEnabled)
        assertEquals(
            listOf(
                "cmd locale get-app-locales com.byd.carsettings",
                "cmd locale set-app-locales com.byd.carsettings --locales ru-RU",
                "cmd locale get-app-locales com.byd.carsettings",
            ),
            commands,
        )
    }

    @Test
    fun disablingRussianClearsOverrideAndVerifiesIt() {
        val commands = mutableListOf<String>()
        val outputs = ArrayDeque(
            listOf(
                "Locales for com.byd.carsettings for user 0 are [ru-RU]",
                "",
                "Locales for com.byd.carsettings for user 0 are []",
            ),
        )
        val repair = StockRussianLocaleRepair { command ->
            commands += command
            outputs.removeFirst()
        }

        val (change, override) = repair.setEnabled(false)

        assertEquals(StockRussianLocaleChange.CHANGED, change)
        assertTrue(override.usesSystemDefault)
        assertEquals(
            listOf(
                "cmd locale get-app-locales com.byd.carsettings",
                "cmd locale set-app-locales com.byd.carsettings",
                "cmd locale get-app-locales com.byd.carsettings",
            ),
            commands,
        )
    }

    @Test
    fun alreadyEnabledRussianSkipsMutation() {
        val commands = mutableListOf<String>()
        val repair = StockRussianLocaleRepair { command ->
            commands += command
            "Locales for com.byd.carsettings for user 0 are [ru-RU]"
        }

        val (change, _) = repair.setEnabled(true)

        assertEquals(StockRussianLocaleChange.ALREADY_SET, change)
        assertEquals(listOf("cmd locale get-app-locales com.byd.carsettings"), commands)
    }

    @Test(expected = IllegalStateException::class)
    fun failedEnableVerificationFailsClosed() {
        val outputs = ArrayDeque(
            listOf(
                "Locales for com.byd.carsettings for user 0 are []",
                "",
                "Locales for com.byd.carsettings for user 0 are []",
            ),
        )

        StockRussianLocaleRepair { outputs.removeFirst() }.setEnabled(true)
    }

    @Test(expected = IllegalStateException::class)
    fun malformedReadResponseFailsBeforeMutation() {
        var calls = 0
        val repair = StockRussianLocaleRepair {
            calls += 1
            "Unknown package com.byd.carsettings"
        }

        try {
            repair.setEnabled(true)
        } finally {
            assertEquals(1, calls)
        }
    }
}
