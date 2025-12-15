package io.heckel.ntfy.db

/**
 * Represents a custom HTTP header for a specific server
 */
data class CustomHeader(
    val baseUrl: String,
    val name: String,
    val value: String
)
