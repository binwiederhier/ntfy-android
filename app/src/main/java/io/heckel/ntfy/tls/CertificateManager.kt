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
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Manages trusted server certificates and client certificates for mTLS.
 * 
 * - Trusted server certificates are stored in SharedPreferences as JSON
 * - Client certificates (PKCS#12) are stored in app's private files directory
 * - Client certificate metadata is stored in SharedPreferences
 */
class CertificateManager private constructor(private val context: Context) {
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

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
     * Remove a client certificate
     */
    fun removeClientCertificate(cert: ClientCertificate) {
        // Remove PKCS#12 file
        val p12File = java.io.File(context.filesDir, "${cert.alias}.p12")
        if (p12File.exists()) {
            p12File.delete()
        }

        // Remove metadata
        val certs = getClientCertificates().toMutableList()
        certs.removeAll { it.alias == cert.alias }
        saveClientCertificates(certs)
    }

    /**
     * Add a client certificate from a PKCS#12 file
     * 
     * @param baseUrl Server URL this certificate is for
     * @param pkcs12Data PKCS#12 file contents
     * @param password Password for the PKCS#12 file
     */
    fun addClientCertificatePkcs12(baseUrl: String, pkcs12Data: ByteArray, password: String) {
        // Load the PKCS#12 to verify and extract certificate info
        val pkcs12KeyStore = KeyStore.getInstance("PKCS12")
        pkcs12KeyStore.load(ByteArrayInputStream(pkcs12Data), password.toCharArray())

        // Get the first certificate from the PKCS#12
        val alias = pkcs12KeyStore.aliases().nextElement()
        val cert = pkcs12KeyStore.getCertificate(alias) as X509Certificate

        // Generate a unique alias for storage
        val storageAlias = ClientCertificate.generateAlias(baseUrl)

        // Save the PKCS#12 file to app's private storage
        val p12File = java.io.File(context.filesDir, "$storageAlias.p12")
        java.io.FileOutputStream(p12File).use { it.write(pkcs12Data) }

        // Store metadata (including password for PKCS#12)
        val clientCert = ClientCertificate.fromX509Certificate(baseUrl, storageAlias, cert, password)
        val certs = getClientCertificates().toMutableList()
        
        // Remove existing cert for same baseUrl
        val oldCert = certs.find { it.baseUrl == baseUrl }
        if (oldCert != null) {
            removeClientCertificate(oldCert)
            certs.removeAll { it.baseUrl == baseUrl }
        }
        
        certs.add(clientCert)
        saveClientCertificates(certs)
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
