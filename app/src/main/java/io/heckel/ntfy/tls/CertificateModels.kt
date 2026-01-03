package io.heckel.ntfy.tls

import android.util.Base64
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * Calculate SHA-256 fingerprint of a certificate
 */
fun calculateFingerprint(cert: X509Certificate): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(cert.encoded)
    return digest.joinToString(":") { "%02X".format(it) }
}

/**
 * Encode certificate to PEM format
 */
fun encodeToPem(cert: X509Certificate): String {
    val base64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
    val sb = StringBuilder()
    sb.append("-----BEGIN CERTIFICATE-----\n")
    // Split into 64-character lines
    var i = 0
    while (i < base64.length) {
        val end = minOf(i + 64, base64.length)
        sb.append(base64.substring(i, end))
        sb.append("\n")
        i += 64
    }
    sb.append("-----END CERTIFICATE-----")
    return sb.toString()
}

/**
 * Get a human-readable subject from a certificate (extract CN if available)
 */
fun displaySubject(cert: X509Certificate): String {
    val subject = cert.subjectX500Principal.name
    val cnMatch = Regex("CN=([^,]+)").find(subject)
    return cnMatch?.groupValues?.get(1) ?: subject
}

/**
 * Represents metadata for a client certificate used for mTLS.
 * The actual certificate and private key are stored in a PKCS#12 file.
 */
data class ClientCertificate(
    val baseUrl: String,           // Server URL this client cert is used for
    val alias: String,             // Filename prefix for PKCS#12
    val fingerprint: String,       // SHA-256 fingerprint of the certificate
    val subject: String,           // Subject DN
    val issuer: String,            // Issuer DN
    val notBefore: Long,           // Validity start (Unix timestamp in millis)
    val notAfter: Long,            // Validity end (Unix timestamp in millis)
    val password: String? = null   // Password for PKCS#12 files
) {
    companion object {
        /**
         * Generate a unique alias for storing
         */
        fun generateAlias(baseUrl: String): String {
            val timestamp = System.currentTimeMillis()
            val sanitizedUrl = baseUrl.replace(Regex("[^a-zA-Z0-9]"), "_")
            return "ntfy_client_${sanitizedUrl}_$timestamp"
        }

        /**
         * Create ClientCertificate metadata from an X509Certificate
         */
        fun fromX509Certificate(
            baseUrl: String,
            alias: String,
            cert: X509Certificate,
            password: String? = null
        ): ClientCertificate {
            return ClientCertificate(
                baseUrl = baseUrl,
                alias = alias,
                fingerprint = calculateFingerprint(cert),
                subject = cert.subjectX500Principal.name,
                issuer = cert.issuerX500Principal.name,
                notBefore = cert.notBefore.time,
                notAfter = cert.notAfter.time,
                password = password
            )
        }
    }

    /**
     * Check if the certificate is currently valid (not expired)
     */
    fun isValid(): Boolean {
        val now = System.currentTimeMillis()
        return now in notBefore..notAfter
    }

    /**
     * Get a human-readable subject (extract CN if available)
     */
    fun displaySubject(): String {
        val cnMatch = Regex("CN=([^,]+)").find(subject)
        return cnMatch?.groupValues?.get(1) ?: subject
    }
}
