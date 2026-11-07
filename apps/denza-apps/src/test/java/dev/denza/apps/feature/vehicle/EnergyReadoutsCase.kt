package dev.denza.apps.feature.vehicle

import dev.denza.apps.R
import dev.denza.apps.core.AppText
import dev.denza.apps.core.say

/**
 * Capitals into the car page's sentence case: a capital, lower case after it, and «ДВС» left as it is
 * written. English has no such abbreviation, so ENGINE becomes Engine and engine like any other word.
 *
 * The app did this once, and a language writes its sentences as strings now. The rule is kept here
 * so that `EnergyReadoutsTest` can hold the two records of a phrase together in either language.
 */
internal fun EnergyReadouts.Companion.sentence(caps: String): String =
    caps.lowercase()
        .replaceFirstChar { it.uppercaseChar() }
        .replace(ABBREVIATION.lowercase(), ABBREVIATION)

private const val ABBREVIATION = "ДВС"

/**
 * One word of the cluster's engine heading in the case the car page writes it: «ДВС» stays «ДВС» and
 * ENGINE is Engine. A unit is case-sensitive and comes through as it is.
 */
internal fun EnergyReadouts.Companion.headingWord(word: String): String =
    if (word != ABBREVIATION && word.any { it.isLetter() } && word.none { it.isLowerCase() }) sentence(word) else word

/**
 * The contract's table in capitals (`docs/energy-display-contract.md`, 2.1). The app draws none of
 * them, so nothing in `main` holds them; `EnergyReadoutsTest` holds each direction to its row and to
 * the sentence the car page prints, in the language it runs in.
 */
internal val EnergyReadouts.Direction.word: String
    get() = when (this) {
        EnergyReadouts.Direction.NEUTRAL -> say("БАТАРЕЯ", "BATTERY")
        EnergyReadouts.Direction.FROM_PACK -> say("ИЗ БАТАРЕИ", "FROM BATTERY")
        EnergyReadouts.Direction.TO_PACK -> say("В БАТАРЕЮ", "TO BATTERY")
        EnergyReadouts.Direction.FROM_ENGINE -> say("В БАТАРЕЮ ОТ ДВС", "TO BATTERY FROM ENGINE")
        EnergyReadouts.Direction.FROM_CHARGER -> say("В БАТАРЕЮ ОТ ЗАРЯДКИ", "TO BATTERY FROM CHARGER")
    }

internal val EnergyReadouts.word: String get() = direction.word

internal val EnergyReadouts.Companion.WORD_NEUTRAL: String get() = EnergyReadouts.Direction.NEUTRAL.word
internal val EnergyReadouts.Companion.WORD_FROM_PACK: String get() = EnergyReadouts.Direction.FROM_PACK.word
internal val EnergyReadouts.Companion.WORD_TO_PACK: String get() = EnergyReadouts.Direction.TO_PACK.word
internal val EnergyReadouts.Companion.WORD_FROM_ENGINE: String get() = EnergyReadouts.Direction.FROM_ENGINE.word
internal val EnergyReadouts.Companion.WORD_FROM_CHARGER: String get() = EnergyReadouts.Direction.FROM_CHARGER.word

internal val EnergyReadouts.Companion.WORD_NEUTRAL_SENTENCE: String
    get() = EnergyReadouts.Direction.NEUTRAL.sentence
internal val EnergyReadouts.Companion.WORD_FROM_PACK_SENTENCE: String
    get() = EnergyReadouts.Direction.FROM_PACK.sentence
internal val EnergyReadouts.Companion.WORD_TO_PACK_SENTENCE: String
    get() = EnergyReadouts.Direction.TO_PACK.sentence
internal val EnergyReadouts.Companion.WORD_FROM_ENGINE_SENTENCE: String
    get() = EnergyReadouts.Direction.FROM_ENGINE.sentence
internal val EnergyReadouts.Companion.WORD_FROM_CHARGER_SENTENCE: String
    get() = EnergyReadouts.Direction.FROM_CHARGER.sentence

/** The trip's phrase with no kilometres in front of it, which the strip's tests put a figure behind. */
internal val EnergyReadouts.Companion.TRIP_KM_SENTENCE: String
    get() = AppText.s(R.string.energy_trip_km, "")
