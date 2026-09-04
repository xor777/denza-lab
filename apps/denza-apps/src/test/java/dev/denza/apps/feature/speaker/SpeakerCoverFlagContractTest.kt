package dev.denza.apps.feature.speaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The feature never touches the car's auto-lift setting, in either direction.
 *
 * `AUDIO_RLSA_STATE_SET` (`0x16300025`) is the stock auto-lift switch: `1` enables it and on the
 * Z9GT also drives the motor, `2` disables it and latches the amplifier into manual mode until the
 * next ignition cycle. The N9 draws that switch in its own Settings; the Z9GT has no UI for it and
 * it always reads on. On both cars it is the driver's and the car's, and the app being a second
 * hand on it is what made «off» mean two different things depending on when you looked. The echo
 * `AUDIO_SPEAKER_FLIP_SETTING_STATUS` (`0x35A000DA`) is not read either: a feature that reads the
 * switch is one decision away from writing it.
 *
 * Read from source rather than from behaviour, because the property is edge-triggered and a
 * write of it cannot be observed from a unit test - only its absence can.
 */
class SpeakerCoverFlagContractTest {

    @Test
    fun theSpeakerFeatureNeverNamesTheAutoLiftProperty() {
        val forbidden = listOf(
            "372244517", "372_244_517", "0x16300025", "AUDIO_RLSA_STATE_SET",
            "899678426", "899_678_426", "0x35A000DA", "AUDIO_SPEAKER_FLIP_SETTING_STATUS",
        )
        val offenders = File("src/main/java/dev/denza/apps/feature/speaker")
            .listFiles { file -> file.extension == "kt" }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap { file ->
                val source = file.readText()
                forbidden.filter { it in source }.map { "${file.name}: $it" }
            }
        assertEquals(emptyList<String>(), offenders)
    }
}
