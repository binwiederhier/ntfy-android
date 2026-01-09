package io.heckel.ntfy.util

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import io.heckel.ntfy.db.ClientCertificate
import io.heckel.ntfy.db.Repository
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.X509TrustManager

/**
 * TLS config:
 * - For each baseUrl, either use the pinned certificate (if one exists) OR system trust
 * - Pinned cert = ONLY that exact certificate is trusted (strict pinning)
 * - Hostname verification is bypassed for pinned certificates (the fingerprint match is the trust anchor)
 * - Optional mTLS via per-baseUrl PKCS#12 client cert
 */
class CertUtil private constructor(context: Context) {
    private val appContext: Context = context.applicationContext
    private val repository: Repository by lazy { Repository.getInstance(appContext) }

    suspend fun withTLSConfig(builder: OkHttpClient.Builder, baseUrl: String): OkHttpClient.Builder {
        try {
            val pinnedCert = repository.getTrustedCertificate(baseUrl)
            val clientCert = repository.getClientCertificate(baseUrl)
            val clientCertKeyManagers = clientCert?.let { clientCertKeyManagers(it) }

            // Determine which trust manager to use
            val trustManager: X509TrustManager = if (pinnedCert != null) {
                // Strict pinning: only trust the exact pinned certificate
                try {
                    val cert = parsePemCertificate(pinnedCert.pem)
                    pinnedCertTrustManager(cert)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse pinned certificate for $baseUrl, falling back to system trust", e)
                    systemTrustManager()
                }
            } else {
                systemTrustManager()
            }

            // Only override SSL config if we have a pinned cert or client cert
            if (pinnedCert != null || clientCertKeyManagers != null) {
                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(clientCertKeyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
                }
                builder.sslSocketFactory(sslContext.socketFactory, trustManager)

                // Bypass hostname verification for pinned certificates
                // The certificate fingerprint match is the trust anchor, not the hostname
                if (pinnedCert != null) {
                    builder.hostnameVerifier(trustAllHostnameVerifier())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure SSL for $baseUrl", e)
        }
        return builder
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
                if (!chain.isNullOrEmpty()) capturedCert = chain[0]
                throw SSLException("Certificate captured for inspection")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }

        try {
            val url = URL(baseUrl)
            val host = url.host
            val port = when {
                url.port != -1 -> url.port
                url.protocol == "https" -> 443
                else -> 80
            }

            val socket = sslContext.socketFactory.createSocket(host, port) as SSLSocket
            socket.soTimeout = 10_000
            try {
                socket.startHandshake()
            } catch (_: Exception) {
                // expected
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch certificate from $baseUrl", e)
        }

        return capturedCert
    }

    /**
     * mTLS client auth via PKCS#12 from DB.
     */
    private fun clientCertKeyManagers(clientCert: ClientCertificate): Array<KeyManager>? {
        return try {
            val p12Data = Base64.decode(clientCert.p12Base64, Base64.DEFAULT)
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                ByteArrayInputStream(p12Data).use { load(it, clientCert.password.toCharArray()) }
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, clientCert.password.toCharArray())
            }
            kmf.keyManagers
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load PKCS#12 client certificate for ${clientCert.baseUrl}", e)
            null
        }
    }

    private fun systemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }
        return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    /**
     * Hostname verifier that accepts any hostname.
     * Used for pinned certificates where the fingerprint match is the trust anchor.
     */
    @SuppressLint("BadHostnameVerifier")
    private fun trustAllHostnameVerifier(): HostnameVerifier {
        return HostnameVerifier { _, _ -> true }
    }

    /**
     * Create a trust manager that ONLY trusts the exact pinned certificate.
     * This implements strict certificate pinning - system CAs are not trusted.
     */
    @SuppressLint("CustomX509TrustManager")
    private fun pinnedCertTrustManager(pinnedCert: X509Certificate): X509TrustManager {
        val pinnedFingerprint = calculateFingerprint(pinnedCert)
        return object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(pinnedCert)

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client authentication not supported with pinned certificate")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("Empty certificate chain")
                }
                val serverCert = chain[0]
                val serverFingerprint = calculateFingerprint(serverCert)
                if (serverFingerprint != pinnedFingerprint) {
                    throw CertificateException(
                        "Certificate fingerprint mismatch. Expected: $pinnedFingerprint, Got: $serverFingerprint"
                    )
                }
                // Optionally verify certificate validity
                serverCert.checkValidity()
            }
        }
    }

    companion object {
        private const val TAG = "NtfyCertUtil"

        @Volatile
        @SuppressLint("StaticFieldLeak")
        private var instance: CertUtil? = null

        fun getInstance(context: Context): CertUtil =
            instance ?: synchronized(this) { instance ?: CertUtil(context).also { instance = it } }

        fun calculateFingerprint(cert: X509Certificate): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(cert.encoded)
            return digest.joinToString(":") { "%02X".format(it) }
        }

        fun parsePemCertificate(pem: String): X509Certificate {
            val factory = CertificateFactory.getInstance("X.509")
            return factory.generateCertificate(pem.byteInputStream()) as X509Certificate
        }

        fun encodeCertificateToPem(cert: X509Certificate): String {
            val base64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
            return buildString {
                append("-----BEGIN CERTIFICATE-----\n")
                var i = 0
                while (i < base64.length) {
                    val end = minOf(i + 64, base64.length)
                    append(base64.substring(i, end))
                    append("\n")
                    i += 64
                }
                append("-----END CERTIFICATE-----")
            }
        }

        fun parsePkcs12Certificate(p12Base64: String, password: String): X509Certificate {
            val p12Data = Base64.decode(p12Base64, Base64.DEFAULT)
            val keyStore = KeyStore.getInstance("PKCS12")
            ByteArrayInputStream(p12Data).use { keyStore.load(it, password.toCharArray()) }
            val alias = keyStore.aliases().nextElement()
            return keyStore.getCertificate(alias) as X509Certificate
        }
    }
}
