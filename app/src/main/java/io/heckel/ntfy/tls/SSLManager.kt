package io.heckel.ntfy.tls

import android.annotation.SuppressLint
import android.content.Context
import io.heckel.ntfy.util.Log
import okhttp3.OkHttpClient
import java.io.FileInputStream
import java.security.KeyStore
import java.security.SecureRandom
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
    private val certManager: CertificateManager by lazy { CertificateManager.getInstance(appContext) }

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

            // Get all user-trusted certificates
            val trustedCerts = certManager.getTrustedCertificates()
            val trustedFingerprints = trustedCerts.map { it.fingerprint }.toSet()
            if (trustedCerts.isNotEmpty()) {
                trustManagers.addAll(createCombinedTrustManagers(trustedCerts))
            }

            // Get client certificate for mTLS
            val clientCert = certManager.getClientCertificateForServer(baseUrl)
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
                                    val serverFingerprint = TrustedCertificate.calculateFingerprint(serverCert)
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
    private fun createCombinedTrustManagers(userCerts: List<TrustedCertificate>): Array<TrustManager> {
        // Create a KeyStore with all certificates
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }

        // Add user-trusted certificates
        userCerts.forEachIndexed { index, trustedCert ->
            try {
                val cert = certManager.parsePemCertificate(trustedCert.pemEncoded)
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
     * Create KeyManagers for mTLS client authentication using PKCS#12 file.
     * Uses KeyManagerFactory (standard approach).
     */
    private fun createKeyManagers(clientCert: ClientCertificate): Array<KeyManager>? {
        val p12File = certManager.getClientCertificatePath(clientCert.alias)
        if (!p12File.exists()) {
            Log.w(TAG, "PKCS#12 file not found: ${p12File.absolutePath}")
            return null
        }
        if (clientCert.password == null) {
            Log.w(TAG, "No password for PKCS#12 client certificate: ${clientCert.alias}")
            return null
        }

        return try {
            val keyStore = KeyStore.getInstance("PKCS12")
            FileInputStream(p12File).use { keyStore.load(it, clientCert.password.toCharArray()) }

            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, clientCert.password.toCharArray())
            keyManagerFactory.keyManagers
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load PKCS#12 client certificate", e)
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
    }
}
