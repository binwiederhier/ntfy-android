package io.heckel.ntfy.data

fun topicUrl(baseUrl: String, topic: String) = "${baseUrl}/${topic}"
fun topicShortUrl(baseUrl: String, topic: String) =
    topicUrl(baseUrl, topic)
        .replace("http://", "")
        .replace("https://", "")
