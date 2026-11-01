package ru.adbgw.gateway

/** The persisted record of relay rejections of the registered vehicle key since the last success. */
data class RejectionEvidence(
    val firstAtMillis: Long,
    val count: Int,
)

/** Where [RegistrationRejectionGate] keeps its evidence; [GatewayStateStore] in production. */
interface RejectionEvidenceStore {
    fun rejectionEvidence(): RejectionEvidence?
    fun saveRejectionEvidence(value: RejectionEvidence?): Boolean
}

sealed interface RejectionVerdict {
    /** Enough rejections over enough time: the registration may be dropped. */
    data object Confirmed : RejectionVerdict

    /** Not yet: keep the registration and retry. [count] of [required] rejections so far. */
    data class Unconfirmed(val count: Int, val required: Int) : RejectionVerdict
}

/**
 * Decides when the relay's refusal of the vehicle key is real enough to drop the registration and
 * rotate the keys. A single rejection is never enough: a registration is dropped only after
 * [requiredRejections] definitive rejections, the first of them at least [confirmationWindowMillis]
 * old. Any accepted key clears the evidence.
 */
class RegistrationRejectionGate(
    private val store: RejectionEvidenceStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val requiredRejections: Int = REQUIRED_REJECTIONS,
    private val confirmationWindowMillis: Long = CONFIRMATION_WINDOW_MILLIS,
) {
    @Synchronized
    fun recordRejection(): RejectionVerdict {
        val now = clock()
        val previous = store.rejectionEvidence()
            // A first rejection "in the future" means the clock moved back; restart the window.
            ?.takeIf { it.firstAtMillis <= now && it.count > 0 }
        val next = previous?.copy(count = previous.count + 1) ?: RejectionEvidence(now, 1)
        store.saveRejectionEvidence(next)
        val confirmed = next.count >= requiredRejections &&
            now - next.firstAtMillis >= confirmationWindowMillis
        return if (confirmed) RejectionVerdict.Confirmed
        else RejectionVerdict.Unconfirmed(minOf(next.count, requiredRejections), requiredRejections)
    }

    @Synchronized
    fun recordSuccess() {
        if (store.rejectionEvidence() != null) store.saveRejectionEvidence(null)
    }

    companion object {
        const val REQUIRED_REJECTIONS = 3
        const val CONFIRMATION_WINDOW_MILLIS = 15L * 60L * 1_000L
    }
}
