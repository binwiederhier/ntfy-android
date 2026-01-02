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
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Manages SSL/TLS configuration for OkHttpClient instances.
 * 
 * This is a thin wrapper around SSLUtils that handles Context-dependent operations
 * like accessing CertificateManager for per-URL certificate trust.
 * 
 * Supports:
 * 1. Per-URL trusted CA certificates (for self-signed servers)
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
            var bypassHostnameVerification = false

            // Get user-trusted CA certificates for this URL
            val trustedCerts = certManager.getTrustedCertificatesForServer(baseUrl)
            if (trustedCerts.isNotEmpty()) {
                trustManagers.addAll(createCombinedTrustManagers(trustedCerts))
                bypassHostnameVerification = true
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
                    trustManagers.addAll(SSLUtils.getSystemTrustManagers().toList())
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

                // Bypass hostname verification for user-trusted certs
                if (bypassHostnameVerification) {
                    builder.hostnameVerifier { hostname, session ->
                        val defaultVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                        if (defaultVerifier.verify(hostname, session)) {
                            true
                        } else {
                            Log.d(TAG, "Hostname verification bypassed for $baseUrl due to user-trusted certificate")
                            true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure SSL for $baseUrl", e)
        }
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
        SSLUtils.getSystemTrustManager().acceptedIssuers.forEachIndexed { index, cert ->
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
     * Fetch the server certificate without trusting it.
     * Used to display certificate details before user decides to trust.
     */
    fun fetchServerCertificate(baseUrl: String): X509Certificate? {
        return SSLUtils.fetchServerCertificate(baseUrl)
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
