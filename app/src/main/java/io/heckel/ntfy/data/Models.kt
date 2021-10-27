package io.heckel.ntfy.data

enum class Status {
    CONNECTED, CONNECTING
}

data class Subscription(
    val id: Long, // Internal ID, only used in Repository and activities
    val topic: String,
    val baseUrl: String,
    val status: Status,
    val messages: Int
)

data class Notification(
    val subscription: Subscription,
    val message: String
)

interface NotificationListener {
    fun onNotification(subscriptionId: Long, notification: Notification)
}

interface ConnectionListener : NotificationListener {
    fun onStatusChanged(subcriptionId: Long, status: Status)
}

fun topicUrl(s: Subscription) = "${s.baseUrl}/${s.topic}"
fun topicShortUrl(s: Subscription) = topicUrl(s).replace("http://", "").replace("https://", "")
