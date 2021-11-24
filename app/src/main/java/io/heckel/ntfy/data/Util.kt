package io.heckel.ntfy.data

fun topicUrl(baseUrl: String, topic: String) = "${baseUrl}/${topic}"
fun topicUrlJson(baseUrl: String, topic: String, since: String) = "${topicUrl(baseUrl, topic)}/json?since=$since"
fun topicUrlJsonPoll(baseUrl: String, topic: String) = "${topicUrl(baseUrl, topic)}/json?poll=1"
fun topicShortUrl(baseUrl: String, topic: String) =
    topicUrl(baseUrl, topic)
        .replace("http://", "")
        .replace("https://", "")

