package io.heckel.ntfy.tls

import android.content.Context
import io.heckel.ntfy.util.Log
import okhttp3.OkHttpClient
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * Manages SSL/TLS configuration for OkHttpClient instances.
 * 
 * Provides custom TrustManager that trusts:
 * 1. System CA certificates
 * 2. User-trusted certificates (self-signed or custom CA)
 * 
 * And custom KeyManager for mTLS client certificates.
 */
class SSLManager private constructor(private val context: Context) {
    private val certManager: CertificateManager by lazy { CertificateManager.getInstance(context) }

    // System trust manager (cached)
    private val systemTrustManager: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * Get an OkHttpClient.Builder configured with custom SSL for a specific server
     */
    fun getOkHttpClientBuilder(baseUrl: String): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
        configureSSL(builder, baseUrl)
        return builder
    }

    /**
     * Configure an existing OkHttpClient.Builder with custom SSL
     */
    private fun configureSSL(builder: OkHttpClient.Builder, baseUrl: String) {
        val trustManager = createTrustManager(baseUrl)
        val keyManager = createKeyManager(baseUrl)
        
        val sslContext = SSLContext.getInstance("TLS")
        val keyManagers = if (keyManager != null) arrayOf(keyManager) else null
        sslContext.init(keyManagers, arrayOf(trustManager), null)
        
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        builder.hostnameVerifier(createHostnameVerifier(baseUrl))
    }

    /**
     * Create a custom TrustManager that trusts system CAs + user-trusted certs
     */
    private fun createTrustManager(baseUrl: String): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // We don't validate client certs
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("Empty certificate chain")
                }

                // First try system trust
                try {
                    systemTrustManager.checkServerTrusted(chain, authType)
                    return // System trusted, we're good
                } catch (e: CertificateException) {
                    // Not system trusted, check user-trusted certs
                    Log.d(TAG, "Certificate not system-trusted for $baseUrl, checking user trust")
                }

                // Check if the certificate (or any in the chain) is user-trusted
                val trustedCerts = certManager.getTrustedCertificatesForServer(baseUrl)
                if (trustedCerts.isEmpty()) {
                    throw CertificateException("Certificate not trusted and no user-trusted certificates configured for $baseUrl")
                }

                val serverCertFingerprint = TrustedCertificate.calculateFingerprint(chain[0])
                val isTrusted = trustedCerts.any { trusted ->
                    // Check if server cert matches a trusted cert
                    trusted.fingerprint == serverCertFingerprint ||
                    // Or if any cert in chain is trusted (for CA certs)
                    chain.any { cert -> 
                        TrustedCertificate.calculateFingerprint(cert) == trusted.fingerprint 
                    }
                }

                if (!isTrusted) {
                    throw CertificateException("Server certificate not in user's trusted certificates for $baseUrl")
                }

                Log.d(TAG, "Certificate trusted by user for $baseUrl")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return systemTrustManager.acceptedIssuers
            }
        }
    }

    /**
     * Create a KeyManager for mTLS client authentication (if configured)
     */
    private fun createKeyManager(baseUrl: String): X509KeyManager? {
        val clientCert = certManager.getClientCertificateForServer(baseUrl) ?: return null
        
        val privateKey = certManager.getClientPrivateKey(clientCert.alias)
        val certChain = certManager.getClientCertificateChain(clientCert.alias)
        
        if (privateKey == null || certChain == null) {
            Log.w(TAG, "Client certificate configured but key/chain not found for $baseUrl")
            return null
        }

        return object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> {
                return arrayOf(clientCert.alias)
            }

            override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String {
                return clientCert.alias
            }

            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
                return null
            }

            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? {
                return null
            }

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                return if (alias == clientCert.alias) certChain else null
            }

            override fun getPrivateKey(alias: String?): PrivateKey? {
                return if (alias == clientCert.alias) privateKey else null
            }
        }
    }

    /**
     * Create a HostnameVerifier that allows trusted self-signed certs
     */
    private fun createHostnameVerifier(baseUrl: String): HostnameVerifier {
        return HostnameVerifier { hostname, session ->
            // First try default verification
            val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            if (defaultVerifier.verify(hostname, session)) {
                return@HostnameVerifier true
            }

            // If we have user-trusted certs for this host, allow it
            val trustedCerts = certManager.getTrustedCertificatesForServer(baseUrl)
            if (trustedCerts.isNotEmpty()) {
                Log.d(TAG, "Hostname verification bypassed for $baseUrl due to user-trusted certificate")
                return@HostnameVerifier true
            }

            false
        }
    }

    /**
     * Fetch the server certificate without trusting it.
     * Used to display certificate details before user decides to trust.
     */
    fun fetchServerCertificate(baseUrl: String): X509Certificate? {
        var capturedCert: X509Certificate? = null
        
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain != null && chain.isNotEmpty()) {
                    capturedCert = chain[0]
                }
                // Always throw to prevent connection
                throw CertificateException("Certificate captured for inspection")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)

        try {
            val url = java.net.URL(baseUrl)
            val host = url.host
            val port = if (url.port == -1) {
                if (url.protocol == "https") 443 else 80
            } else {
                url.port
            }

            val socketFactory = sslContext.socketFactory
            val socket = socketFactory.createSocket(host, port) as SSLSocket
            socket.soTimeout = 10000
            try {
                socket.startHandshake()
            } catch (e: Exception) {
                // Expected - we throw from the trust manager
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch certificate from $baseUrl", e)
        }

        return capturedCert
    }

    companion object {
        private const val TAG = "NtfySSLManager"

        @Volatile
        private var instance: SSLManager? = null

        fun getInstance(context: Context): SSLManager {
            return instance ?: synchronized(this) {
                instance ?: SSLManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
