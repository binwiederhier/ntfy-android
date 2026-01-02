package io.heckel.ntfy.tls

/**
 * SSL/TLS settings for a connection.
 * This is a simple data class that holds configuration - no logic.
 */
data class SSLSettings(
    val validateSSL: Boolean = true,
    val caCertPath: String? = null,
    val clientCertPath: String? = null,
    val clientCertPassword: String? = null
)

