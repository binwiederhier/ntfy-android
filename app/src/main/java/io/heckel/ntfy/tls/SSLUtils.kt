package io.heckel.ntfy.tls

import android.annotation.SuppressLint
import io.heckel.ntfy.util.Log
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Stateless utility object for SSL/TLS configuration.
 * Inspired by gotify-android's CertUtils implementation.
 */
object SSLUtils {
    private const val TAG = "NtfySSLUtils"

    const val CA_CERT_FILENAME = "ca-cert.crt"
    const val CLIENT_CERT_FILENAME = "client-cert.p12"

    @SuppressLint("CustomX509TrustManager")
    private val trustAll = object : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /**
     * Get the trust-all manager for disabling SSL validation.
     * WARNING: Only use for development/testing.
     */
    fun trustAllManager(): X509TrustManager = trustAll

    /**
     * Parse a certificate from an input stream (PEM or DER format)
     */
    fun parseCertificate(inputStream: InputStream): Certificate {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        return certificateFactory.generateCertificate(inputStream)
    }

    /**
     * Apply SSL settings to an OkHttpClient.Builder
     */
    fun applySslSettings(builder: OkHttpClient.Builder, settings: SSLSettings) {
        try {
            val trustManagers = mutableSetOf<TrustManager>()
            val keyManagers = mutableSetOf<KeyManager>()

            if (settings.validateSSL) {
                // Custom SSL validation with CA cert if provided
                settings.caCertPath?.let { path ->
                    trustManagers.addAll(certPathToTrustManagers(path))
                }
            } else {
                // Disable SSL validation
                trustManagers.add(trustAll)
                builder.hostnameVerifier { _, _ -> true }
            }

            // Client certificate for mTLS
            settings.clientCertPath?.let { path ->
                keyManagers.addAll(certPathToKeyManagers(path, settings.clientCertPassword))
            }

            if (trustManagers.isNotEmpty() || keyManagers.isNotEmpty()) {
                // Fall back to system trust managers if none configured
                if (trustManagers.isEmpty()) {
                    trustManagers.addAll(getSystemTrustManagers())
                }

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(
                    keyManagers.toTypedArray().ifEmpty { null },
                    trustManagers.toTypedArray(),
                    SecureRandom()
                )
                builder.sslSocketFactory(
                    sslContext.socketFactory,
                    trustManagers.first() as X509TrustManager
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply SSL settings", e)
        }
    }

    /**
     * Create TrustManagers from a CA certificate file path.
     * Uses TrustManagerFactory (standard approach) instead of custom X509TrustManager.
     */
    private fun certPathToTrustManagers(certPath: String): Array<TrustManager> {
        val file = File(certPath)
        if (!file.exists()) {
            Log.w(TAG, "CA certificate file not found: $certPath")
            return arrayOf()
        }

        val certificateFactory = CertificateFactory.getInstance("X.509")
        val certificates = FileInputStream(file).use { inputStream ->
            certificateFactory.generateCertificates(inputStream)
        }

        if (certificates.isEmpty()) {
            Log.w(TAG, "No certificates found in: $certPath")
            return arrayOf()
        }

        // Create a KeyStore containing the trusted CA certificates
        val caKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        certificates.forEachIndexed { index, certificate ->
            caKeyStore.setCertificateEntry("ca$index", certificate)
        }

        // Create TrustManagerFactory with the custom KeyStore
        val trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        trustManagerFactory.init(caKeyStore)
        return trustManagerFactory.trustManagers
    }

    /**
     * Create KeyManagers from a PKCS#12 client certificate file.
     * Uses KeyManagerFactory (standard approach) instead of custom X509KeyManager.
     */
    private fun certPathToKeyManagers(certPath: String, password: String?): Array<KeyManager> {
        val file = File(certPath)
        if (!file.exists()) {
            Log.w(TAG, "Client certificate file not found: $certPath")
            return arrayOf()
        }

        if (password == null) {
            Log.w(TAG, "Client certificate password not provided")
            return arrayOf()
        }

        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(file).use { inputStream ->
            keyStore.load(inputStream, password.toCharArray())
        }

        val keyManagerFactory = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm()
        )
        keyManagerFactory.init(keyStore, password.toCharArray())
        return keyManagerFactory.keyManagers
    }

    /**
     * Get the default system TrustManagers
     */
    fun getSystemTrustManagers(): Array<TrustManager> {
        val trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        trustManagerFactory.init(null as KeyStore?)
        return trustManagerFactory.trustManagers
    }

    /**
     * Get the system X509TrustManager
     */
    fun getSystemTrustManager(): X509TrustManager {
        return getSystemTrustManagers().filterIsInstance<X509TrustManager>().first()
    }

    /**
     * Fetch a server's certificate without trusting it.
     * Used to display certificate details before the user decides to trust it.
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    fun fetchServerCertificate(baseUrl: String): X509Certificate? {
        var capturedCert: X509Certificate? = null

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (!chain.isNullOrEmpty()) {
                    capturedCert = chain[0]
                }
                // Always throw to prevent actual connection
                throw javax.net.ssl.SSLException("Certificate captured for inspection")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)

        try {
            val url = java.net.URL(baseUrl)
            val host = url.host
            val port = when {
                url.port != -1 -> url.port
                url.protocol == "https" -> 443
                else -> 80
            }

            val socket = sslContext.socketFactory.createSocket(host, port) as SSLSocket
            socket.soTimeout = 10000
            try {
                socket.startHandshake()
            } catch (_: Exception) {
                // Expected - we throw from the trust manager
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch certificate from $baseUrl", e)
        }

        return capturedCert
    }

    /**
     * Get the Common Name (CN) from a certificate's subject
     */
    fun getCertificateCN(cert: X509Certificate): String {
        val subject = cert.subjectX500Principal.name
        val cnMatch = Regex("CN=([^,]+)").find(subject)
        return cnMatch?.groupValues?.get(1) ?: subject
    }

    /**
     * Calculate SHA-256 fingerprint of a certificate
     */
    fun calculateFingerprint(cert: X509Certificate): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}

