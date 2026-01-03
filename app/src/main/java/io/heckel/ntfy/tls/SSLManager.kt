package io.heckel.ntfy.tls

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import io.heckel.ntfy.db.ClientCertificate
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.TrustedCertificate
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
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
 * Manages SSL/TLS configuration for OkHttpClient instances.
 *
 * Supports:
 * 1. Global trusted CA certificates (for self-signed servers)
 * 2. Per-URL client certificates for mTLS (PKCS#12 format)
 *
 * Uses standard TrustManagerFactory and KeyManagerFactory (not custom implementations).
 */
class SSLManager private constructor(context: Context) {
    private val appContext: Context = context.applicationContext
    private val repository: Repository by lazy { Repository.getInstance(appContext) }

    /**
     * Get an OkHttpClient.Builder configured with custom SSL for a specific server
     */
    fun getOkHttpClientBuilder(baseUrl: String): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
        applySSLConfiguration(builder, baseUrl)
        return builder
    }

    /**
     * Apply SSL configuration to an OkHttpClient.Builder for a specific server
     */
    fun applySSLConfiguration(builder: OkHttpClient.Builder, baseUrl: String) {
        try {
            val trustManagers = mutableListOf<TrustManager>()
            val keyManagers = mutableListOf<KeyManager>()

            // Get all user-trusted certificates from database
            val trustedCerts = runBlocking { repository.getTrustedCertificates() }
            val trustedFingerprints = trustedCerts.map { it.fingerprint }.toSet()
            if (trustedCerts.isNotEmpty()) {
                trustManagers.addAll(createCombinedTrustManagers(trustedCerts))
            }

            // Get client certificate for mTLS
            val clientCert = runBlocking { repository.getClientCertificate(baseUrl) }
            if (clientCert != null) {
                createKeyManagers(clientCert)?.let { keyManagers.addAll(it.toList()) }
            }

            // Apply SSL configuration if we have custom trust or key managers
            if (trustManagers.isNotEmpty() || keyManagers.isNotEmpty()) {
                // Fall back to system trust if no custom trust managers
                if (trustManagers.isEmpty()) {
                    trustManagers.addAll(getSystemTrustManagers().toList())
                }

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(
                    keyManagers.toTypedArray().ifEmpty { null },
                    trustManagers.toTypedArray(),
                    SecureRandom()
                )
                builder.sslSocketFactory(
                    sslContext.socketFactory,
                    trustManagers.filterIsInstance<X509TrustManager>().first()
                )

                // Custom hostname verifier that bypasses only for user-trusted certs
                if (trustedFingerprints.isNotEmpty()) {
                    builder.hostnameVerifier { hostname, session ->
                        val defaultVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                        if (defaultVerifier.verify(hostname, session)) {
                            return@hostnameVerifier true
                        }

                        // Check if the server's certificate is user-trusted
                        try {
                            val serverCerts = session.peerCertificates
                            if (serverCerts.isNotEmpty()) {
                                val serverCert = serverCerts[0] as? X509Certificate
                                if (serverCert != null) {
                                    val serverFingerprint = calculateFingerprint(serverCert)
                                    if (trustedFingerprints.contains(serverFingerprint)) {
                                        Log.d(TAG, "Hostname verification bypassed for $hostname - certificate is user-trusted")
                                        return@hostnameVerifier true
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to check server certificate fingerprint", e)
                        }

                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure SSL for $baseUrl", e)
        }
    }

    /**
     * Fetch the server certificate without trusting it.
     * Used to display certificate details before user decides to trust.
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
     * Create TrustManagers that trust both user-added certs and system CAs.
     * Uses TrustManagerFactory (standard approach).
     */
    private fun createCombinedTrustManagers(trustedCerts: List<TrustedCertificate>): Array<TrustManager> {
        // Create a KeyStore with all certificates
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }

        // Add user-trusted certificates
        trustedCerts.forEachIndexed { index, trustedCert ->
            try {
                val cert = parsePemCertificate(trustedCert.pem)
                keyStore.setCertificateEntry("user$index", cert)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse trusted certificate: ${trustedCert.fingerprint}", e)
            }
        }

        // Add system CA certificates for combined trust
        getSystemTrustManager().acceptedIssuers.forEachIndexed { index, cert ->
            keyStore.setCertificateEntry("system$index", cert)
        }

        val trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        trustManagerFactory.init(keyStore)
        return trustManagerFactory.trustManagers
    }

    /**
     * Create KeyManagers for mTLS client authentication using PKCS#12 data from database.
     * Uses KeyManagerFactory (standard approach).
     */
    private fun createKeyManagers(clientCert: ClientCertificate): Array<KeyManager>? {
        return try {
            val p12Data = Base64.decode(clientCert.p12Base64, Base64.DEFAULT)
            val keyStore = KeyStore.getInstance("PKCS12")
            ByteArrayInputStream(p12Data).use { keyStore.load(it, clientCert.password.toCharArray()) }

            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, clientCert.password.toCharArray())
            keyManagerFactory.keyManagers
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load PKCS#12 client certificate for ${clientCert.baseUrl}", e)
            null
        }
    }

    /**
     * Get the default system TrustManagers
     */
    private fun getSystemTrustManagers(): Array<TrustManager> {
        val trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        trustManagerFactory.init(null as KeyStore?)
        return trustManagerFactory.trustManagers
    }

    /**
     * Get the system X509TrustManager
     */
    private fun getSystemTrustManager(): X509TrustManager {
        return getSystemTrustManagers().filterIsInstance<X509TrustManager>().first()
    }

    companion object {
        private const val TAG = "NtfySSLManager"

        @Volatile
        @SuppressLint("StaticFieldLeak") // Only holds applicationContext
        private var instance: SSLManager? = null

        fun getInstance(context: Context): SSLManager {
            return instance ?: synchronized(this) {
                instance ?: SSLManager(context).also { instance = it }
            }
        }

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

        fun parsePemCertificate(pem: String): X509Certificate {
            val factory = CertificateFactory.getInstance("X.509")
            return factory.generateCertificate(pem.byteInputStream()) as X509Certificate
        }
    }
}
