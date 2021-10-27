package io.heckel.ntfy.data

data class Topic(
    val id: Long, // Internal to Repository only
    val name: String,
    val baseUrl: String,
)
