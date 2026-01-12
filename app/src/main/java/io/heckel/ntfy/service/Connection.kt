package io.heckel.ntfy.service

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
