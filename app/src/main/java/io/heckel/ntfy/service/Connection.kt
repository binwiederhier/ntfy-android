package io.heckel.ntfy.service

interface Connection {
    fun start()
    fun close()
    fun since(): Long
}

data class ConnectionId(
    val baseUrl: String,
    val authUserId: Long?,
    val topicsToSubscriptionIds: Map<String, Long>
)
