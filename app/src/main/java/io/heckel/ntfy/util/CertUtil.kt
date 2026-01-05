package io.heckel.ntfy.util

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import io.heckel.ntfy.db.ClientCertificate
import io.heckel.ntfy.db.Repository
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS config:
 * - Trust system roots
 * - Also trust user-added certs (leaf and/or CA; chains to user-added CAs)
 * - Hostname verify ONLY when chain is system-trusted; skip when only user-trusted
 * - Optional mTLS via per-baseUrl PKCS#12 client cert
 */
class CertUtil private constructor(context: Context) {
    private val appContext: Context = context.applicationContext
    private val repository: Repository by lazy { Repository.getInstance(appContext) }

    suspend fun withTLSConfig(builder: OkHttpClient.Builder, baseUrl: String): OkHttpClient.Builder {
        try {
            val trustedCerts = repository.getTrustedCertificates().mapNotNull {
                try {
                    parsePemCertificate(it.pem)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse trusted certificate: ${it.fingerprint}", e)
                    null
                }
            }
            val clientCert = repository.getClientCertificate(baseUrl)
            val clientCertKeyManagers = clientCert?.let { clientCertKeyManagers(it) }

            // Always include system trust; add user trust if present.
            val systemTm = systemTrustManager()
            val userTm = if (trustedCerts.isNotEmpty()) trustManagerForUserCerts(trustedCerts) else null
            val compositeTm = if (userTm != null) compositeTrustManager(systemTm, userTm) else systemTm

            // Only override SSL config if we actually have something to add (user trust or mTLS).
            if (userTm != null || clientCertKeyManagers != null) {
                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(clientCertKeyManagers, arrayOf<TrustManager>(compositeTm), SecureRandom())
                }
                builder.sslSocketFactory(sslContext.socketFactory, compositeTm)

                // Hostname rules only matter if we have custom trust. If not, keep default verifier.
                if (userTm != null) {
                    builder.hostnameVerifier(selectiveHostnameVerifier(systemTm, userTm))
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

    private fun trustManagerForUserCerts(trustedCerts: List<X509Certificate>): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        trustedCerts.forEachIndexed { idx, cert ->
            ks.setCertificateEntry("added-$idx", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(ks)
        }
        return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    /**
     * Trust if either system OR custom accepts the chain.
     */
    private fun compositeTrustManager(systemTm: X509TrustManager, userTm: X509TrustManager): X509TrustManager =
        object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                (systemTm.acceptedIssuers + userTm.acceptedIssuers)
                    .distinctBy { it.subjectX500Principal.name }
                    .toTypedArray()

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                try {
                    systemTm.checkClientTrusted(chain, authType)
                } catch (_: Exception) {
                    userTm.checkClientTrusted(chain, authType)
                }
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                try {
                    systemTm.checkServerTrusted(chain, authType)
                } catch (_: Exception) {
                    userTm.checkServerTrusted(chain, authType)
                }
            }
        }

    /**
     * Hostname verification:
     * - if system-trusted => enforce hostname verification
     * - else if custom-trusted => skip hostname verification
     * - else => fail
     */
    private fun selectiveHostnameVerifier(systemTm: X509TrustManager, userTm: X509TrustManager) =
        HostnameVerifier { hostname, session ->
            val chain = try {
                session.peerCertificates.map { it as X509Certificate }.toTypedArray()
            } catch (_: Exception) {
                return@HostnameVerifier false
            }
            if (isTrustedBy(systemTm, chain)) {
                OkHostnameVerifier.verify(hostname, session)
            } else {
                isTrustedBy(userTm, chain)
            }
        }

    private fun isTrustedBy(tm: X509TrustManager, chain: Array<X509Certificate>): Boolean {
        // authType not reliably available here; try common ones.
        return try {
            tm.checkServerTrusted(chain, "RSA")
            true
        } catch (_: Exception) {
            try {
                tm.checkServerTrusted(chain, "EC")
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    companion object {
        private const val TAG = "NtfySSLManager"

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
