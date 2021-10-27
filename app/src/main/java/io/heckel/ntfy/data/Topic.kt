package io.heckel.ntfy.data

enum class Status {
    SUBSCRIBED, CONNECTING
}

data class Topic(
    val id: Long, // Internal ID, only used in Repository and activities
    val name: String,
    val baseUrl: String,
    val status: Status,
    val messages: Int
)

fun topicUrl(t: Topic) = "${t.baseUrl}/${t.name}"
fun topicShortUrl(t: Topic) = topicUrl(t).replace("http://", "").replace("https://", "")
