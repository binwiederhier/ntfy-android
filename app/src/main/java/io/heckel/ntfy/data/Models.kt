package io.heckel.ntfy.data

enum class Status {
    CONNECTED, CONNECTING, RECONNECTING
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

typealias NotificationListener = (notification: Notification) -> Unit

fun topicUrl(s: Subscription) = "${s.baseUrl}/${s.topic}"
fun topicJsonUrl(s: Subscription) = "${s.baseUrl}/${s.topic}/json"
fun topicShortUrl(s: Subscription) = topicUrl(s).replace("http://", "").replace("https://", "")
