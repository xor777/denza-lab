package dev.denza.gateway

internal fun Throwable.gatewayMessage(): String {
    val chain = generateSequence(this) { it.cause }
        .take(4)
        .map { error ->
            val type = error::class.java.simpleName.ifBlank { error::class.java.name }
            val message = error.message?.takeIf { it.isNotBlank() }
            if (message == null) type else "$type: $message"
        }
        .toList()

    return chain.joinToString(" <- ")
}
