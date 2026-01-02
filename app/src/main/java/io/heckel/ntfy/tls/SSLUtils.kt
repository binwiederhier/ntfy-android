package io.heckel.ntfy.tls

import android.annotation.SuppressLint
import io.heckel.ntfy.util.Log
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Stateless utility object for SSL/TLS operations.
 */
object SSLUtils {
    private const val TAG = "NtfySSLUtils"

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
}
