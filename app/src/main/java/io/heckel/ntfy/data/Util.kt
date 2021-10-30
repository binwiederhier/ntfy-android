package io.heckel.ntfy.data

fun topicShortUrl(baseUrl: String, topic: String) =
    "${baseUrl}/${topic}"
        .replace("http://", "")
        .replace("https://", "")
