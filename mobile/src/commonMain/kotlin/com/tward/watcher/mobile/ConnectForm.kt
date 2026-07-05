package com.tward.watcher.mobile

/**
 * Validated connection parameters entered on the connect screen. Kept free of
 * Compose so validation is unit testable.
 */
data class ConnectForm(
    val host: String = "",
    val port: String = "8765",
    val token: String = "",
) {
    data class Validated(val host: String, val port: Int, val token: String)

    /** Returns validated parameters, or a human readable problem description. */
    fun validate(): Result<Validated> {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) {
            return Result.failure(IllegalArgumentException("Host must not be empty"))
        }
        if (trimmedHost.any { it.isWhitespace() }) {
            return Result.failure(IllegalArgumentException("Host must not contain spaces"))
        }
        val portNumber = port.trim().toIntOrNull()
            ?: return Result.failure(IllegalArgumentException("Port must be a number"))
        if (portNumber !in 1..65535) {
            return Result.failure(IllegalArgumentException("Port must be between 1 and 65535"))
        }
        val trimmedToken = token.trim()
        if (trimmedToken.isEmpty()) {
            return Result.failure(IllegalArgumentException("Token must not be empty"))
        }
        return Result.success(Validated(trimmedHost, portNumber, trimmedToken))
    }
}
