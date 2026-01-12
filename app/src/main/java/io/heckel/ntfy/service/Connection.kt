package io.heckel.ntfy.service

import okhttp3.internal.http2.StreamResetException
import java.io.EOFException
import java.net.ConnectException

interface Connection {
    fun start()
    fun close()
    fun since(): String?
}

/**
 * Represents a unique connection identifier that changes every time a
 * connection needs to be re-established.
 */
data class ConnectionId(
    val baseUrl: String,
    val topicsToSubscriptionIds: Map<String, Long>,
    val connectionProtocol: String,
    val credentialsHash: Int,    // Hash of "username:password" or 0 if no user
    val headersHash: Int,        // Hash of sorted headers or 0 if none
    val trustedCertsHash: Int,   // Hash of trusted certificates or 0 if none
    val clientCertHash: Int,     // Hash of client certificate or 0 if none
    val reconnectVersion: Long   // Incremented to force reconnection for this baseUrl
)

/**
 * Checks if the throwable or any of its causes is of the specified type.
 */
inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return true
        current = current.cause
    }
    return false
}

/**
 * Returns true if the exception indicates the connection was broken normally
 * (e.g., server closed connection). These errors should not be shown to the user.
 */
fun isConnectionBrokenException(t: Throwable): Boolean {
    return t.hasCause<EOFException>() || t.hasCause<StreamResetException>()
}

/**
 * Returns true if the exception indicates the connection was refused
 * (e.g., server is down or address is incorrect).
 */
fun isConnectionRefused(t: Throwable): Boolean {
    return t.hasCause<ConnectException>()
}
