package dev.denza.apps.adb

import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureWords
import dev.denza.disharebridge.LocalAdbClient
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException

/**
 * What a failed call over the local ADB channel means to a feature, read by the failure's type.
 *
 * There used to be three copies of this, each matching words in the exception's message - the
 * driver's screen, the passenger screen and the projection (which the HUD borrowed) - and they had
 * drifted apart: one looked for "timeout", which no socket timeout on this car says ("Read timed
 * out", "failed to connect … after 900ms"), another for "timed out", the third for both. Each turned
 * the words into a sentence on a tile, two of them naming a screen the app no longer has.
 *
 * Two kinds, because the report and the access page want to tell them apart. A tile does not: both
 * read [WORDS], since "no connection" on the driver's-screen tile reads as no network, and what the
 * driver is owed either way is the press that goes and looks.
 */
enum class AdbProblem(
    /** What a press on the tile does about it. */
    val resolution: FeatureResolution,
    /** The kind, in the technical report's words. */
    val report: String,
) {
    /** The car does not trust this key, a request is still waiting on its prompt, or the key cannot sign. */
    NO_ACCESS(FeatureResolution.CONFIRM_ON_CAR, "нет доступа"),

    /** adbd does not answer: the connection is refused, times out, or there is no address to try. */
    NO_LINK(FeatureResolution.RETRY, "нет связи"),
    ;

    companion object {
        /** The one caption a feature tile shows for either kind. */
        const val WORDS = FeatureWords.NO_ACCESS

        /**
         * The problem [error] is, looking down its chain of causes; null when it is not the
         * channel's at all, and the feature keeps its own word for what failed.
         */
        fun of(error: Throwable?): AdbProblem? {
            val chain = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
            return when {
                chain.any(::isAccess) -> NO_ACCESS
                chain.any(::isLink) -> NO_LINK
                else -> null
            }
        }

        private fun isAccess(error: Throwable): Boolean =
            error is LocalAdbClient.AuthorizationRequiredException ||
                error is LocalAdbClient.AuthorizationPendingException ||
                error is GeneralSecurityException

        /**
         * The refused, the unreachable and the silent.
         *
         * The one message read here is "Connection refused" on a bare [IOException]: the access
         * check counted it as unavailable before this type existed, and dropping it would quietly
         * change what that check says. Nothing else is matched by its words.
         */
        private fun isLink(error: Throwable): Boolean =
            error is ConnectException ||
                error is NoRouteToHostException ||
                error is SocketTimeoutException ||
                error is LocalAdbClient.NoHostsException ||
                (error is IOException && error.message.orEmpty().contains("Connection refused", true))

        /** A cause chain that loops on itself is still finite here. */
        private const val MAX_CAUSES = 16
    }
}
