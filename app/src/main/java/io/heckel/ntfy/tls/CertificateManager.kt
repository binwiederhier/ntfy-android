package io.heckel.ntfy.tls

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.heckel.ntfy.util.Log
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Manages trusted server certificates and client certificates for mTLS.
 * 
 * - Trusted server certificates are stored in SharedPreferences as JSON
 * - Client certificate private keys are stored in Android KeyStore
 * - Client certificate metadata is stored in SharedPreferences
 */
class CertificateManager private constructor(private val context: Context) {
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    // ==================== Trusted Server Certificates ====================

    /**
     * Get all trusted server certificates
     */
    fun getTrustedCertificates(): List<TrustedCertificate> {
        val json = sharedPrefs.getString(PREF_TRUSTED_CERTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TrustedCertificate>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse trusted certificates", e)
            emptyList()
        }
    }

    /**
     * Get trusted certificates for a specific server
     */
    fun getTrustedCertificatesForServer(baseUrl: String): List<TrustedCertificate> {
        return getTrustedCertificates().filter { it.baseUrl == baseUrl }
    }

    /**
     * Add a trusted certificate
     */
    fun addTrustedCertificate(cert: TrustedCertificate) {
        val certs = getTrustedCertificates().toMutableList()
        // Remove existing cert with same fingerprint for same baseUrl
        certs.removeAll { it.baseUrl == cert.baseUrl && it.fingerprint == cert.fingerprint }
        certs.add(cert)
        saveTrustedCertificates(certs)
    }

    /**
     * Add a trusted certificate from X509Certificate
     */
    fun addTrustedCertificate(baseUrl: String, cert: X509Certificate) {
        addTrustedCertificate(TrustedCertificate.fromX509Certificate(baseUrl, cert))
    }

    /**
     * Remove a trusted certificate
     */
    fun removeTrustedCertificate(cert: TrustedCertificate) {
        val certs = getTrustedCertificates().toMutableList()
        certs.removeAll { it.baseUrl == cert.baseUrl && it.fingerprint == cert.fingerprint }
        saveTrustedCertificates(certs)
    }

    /**
     * Parse a PEM-encoded certificate string to X509Certificate
     */
    fun parsePemCertificate(pem: String): X509Certificate {
        val cleanPem = pem
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\\s".toRegex(), "")
        val decoded = android.util.Base64.decode(cleanPem, android.util.Base64.DEFAULT)
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(decoded)) as X509Certificate
    }

    private fun saveTrustedCertificates(certs: List<TrustedCertificate>) {
        sharedPrefs.edit {
            if (certs.isEmpty()) {
                remove(PREF_TRUSTED_CERTS)
            } else {
                putString(PREF_TRUSTED_CERTS, gson.toJson(certs))
            }
        }
    }

    // ==================== Client Certificates (mTLS) ====================

    /**
     * Get all client certificate metadata
     */
    fun getClientCertificates(): List<ClientCertificate> {
        val json = sharedPrefs.getString(PREF_CLIENT_CERTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ClientCertificate>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse client certificates", e)
            emptyList()
        }
    }

    /**
     * Get client certificate for a specific server (only one per server)
     */
    fun getClientCertificateForServer(baseUrl: String): ClientCertificate? {
        return getClientCertificates().find { it.baseUrl == baseUrl }
    }

    /**
     * Add a client certificate from PEM files
     * 
     * @param baseUrl Server URL this certificate is for
     * @param certPem PEM-encoded certificate
     * @param keyPem PEM-encoded private key (PKCS#8 format)
     */
    fun addClientCertificate(baseUrl: String, certPem: String, keyPem: String) {
        val cert = parsePemCertificate(certPem)
        val privateKey = parsePemPrivateKey(keyPem)
        val alias = ClientCertificate.generateAlias(baseUrl)

        // Store in Android KeyStore
        val certChain = arrayOf(cert)
        keyStore.setKeyEntry(alias, privateKey, null, certChain)

        // Store metadata
        val clientCert = ClientCertificate.fromX509Certificate(baseUrl, alias, cert)
        val certs = getClientCertificates().toMutableList()
        // Remove existing cert for same baseUrl (only one client cert per server)
        val oldCert = certs.find { it.baseUrl == baseUrl }
        if (oldCert != null) {
            // Remove old key from keystore
            try {
                keyStore.deleteEntry(oldCert.alias)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete old client certificate from keystore", e)
            }
            certs.removeAll { it.baseUrl == baseUrl }
        }
        certs.add(clientCert)
        saveClientCertificates(certs)
    }

    /**
     * Get the private key for a client certificate
     */
    fun getClientPrivateKey(alias: String): PrivateKey? {
        return try {
            keyStore.getKey(alias, null) as? PrivateKey
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get private key for alias $alias", e)
            null
        }
    }

    /**
     * Get the certificate chain for a client certificate
     */
    fun getClientCertificateChain(alias: String): Array<X509Certificate>? {
        return try {
            @Suppress("UNCHECKED_CAST")
            keyStore.getCertificateChain(alias) as? Array<X509Certificate>
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get certificate chain for alias $alias", e)
            null
        }
    }

    /**
     * Remove a client certificate
     */
    fun removeClientCertificate(cert: ClientCertificate) {
        // Remove from KeyStore
        try {
            keyStore.deleteEntry(cert.alias)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete client certificate from keystore", e)
        }

        // Remove metadata
        val certs = getClientCertificates().toMutableList()
        certs.removeAll { it.alias == cert.alias }
        saveClientCertificates(certs)
    }

    /**
     * Parse a PEM-encoded private key (PKCS#8 format)
     */
    fun parsePemPrivateKey(pem: String): PrivateKey {
        val cleanPem = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN EC PRIVATE KEY-----", "")
            .replace("-----END EC PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        
        val decoded = android.util.Base64.decode(cleanPem, android.util.Base64.DEFAULT)
        val keySpec = java.security.spec.PKCS8EncodedKeySpec(decoded)
        
        // Try RSA first, then EC
        return try {
            val keyFactory = java.security.KeyFactory.getInstance("RSA")
            keyFactory.generatePrivate(keySpec)
        } catch (e: Exception) {
            try {
                val keyFactory = java.security.KeyFactory.getInstance("EC")
                keyFactory.generatePrivate(keySpec)
            } catch (e2: Exception) {
                throw IllegalArgumentException("Failed to parse private key. Ensure it's in PKCS#8 format.", e2)
            }
        }
    }

    private fun saveClientCertificates(certs: List<ClientCertificate>) {
        sharedPrefs.edit {
            if (certs.isEmpty()) {
                remove(PREF_CLIENT_CERTS)
            } else {
                putString(PREF_CLIENT_CERTS, gson.toJson(certs))
            }
        }
    }

    companion object {
        private const val TAG = "NtfyCertManager"
        private const val PREFS_NAME = "NtfyCertificates"
        private const val PREF_TRUSTED_CERTS = "trusted_certificates"
        private const val PREF_CLIENT_CERTS = "client_certificates"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        @SuppressLint("StaticFieldLeak") // Using applicationContext, so no leak
        @Volatile
        private var instance: CertificateManager? = null

        fun getInstance(context: Context): CertificateManager {
            return instance ?: synchronized(this) {
                instance ?: CertificateManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
