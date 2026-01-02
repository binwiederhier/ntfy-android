package io.heckel.ntfy.tls

import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.heckel.ntfy.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Manages trusted server certificates and client certificates for mTLS.
 * 
 * All certificates are stored in app's private files directory:
 * - Trusted certificates: certs/trusted/{fingerprint}.pem + metadata in certs/trusted.json
 * - Client certificates: certs/client/{alias}.p12 + metadata in certs/client.json
 */
class CertificateManager private constructor(private val context: Context) {
    private val gson = Gson()
    private val certsDir = File(context.filesDir, CERTS_DIR).apply { mkdirs() }
    private val trustedDir = File(certsDir, TRUSTED_DIR).apply { mkdirs() }
    private val clientDir = File(certsDir, CLIENT_DIR).apply { mkdirs() }
    private val trustedMetadataFile = File(certsDir, TRUSTED_METADATA_FILE)
    private val clientMetadataFile = File(certsDir, CLIENT_METADATA_FILE)

    // ==================== Trusted Server Certificates ====================

    /**
     * Get all trusted server certificates
     */
    fun getTrustedCertificates(): List<TrustedCertificate> {
        if (!trustedMetadataFile.exists()) return emptyList()
        return try {
            val json = trustedMetadataFile.readText()
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
        // Save PEM file
        val pemFile = File(trustedDir, "${cert.fingerprint.replace(":", "")}.pem")
        pemFile.writeText(cert.pemEncoded)

        // Update metadata
        val certs = getTrustedCertificates().toMutableList()
        certs.removeAll { it.baseUrl == cert.baseUrl && it.fingerprint == cert.fingerprint }
        certs.add(cert)
        saveTrustedMetadata(certs)
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
        // Delete PEM file
        val pemFile = File(trustedDir, "${cert.fingerprint.replace(":", "")}.pem")
        if (pemFile.exists()) {
            pemFile.delete()
        }

        // Update metadata
        val certs = getTrustedCertificates().toMutableList()
        certs.removeAll { it.baseUrl == cert.baseUrl && it.fingerprint == cert.fingerprint }
        saveTrustedMetadata(certs)
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

    private fun saveTrustedMetadata(certs: List<TrustedCertificate>) {
        if (certs.isEmpty()) {
            trustedMetadataFile.delete()
        } else {
            trustedMetadataFile.writeText(gson.toJson(certs))
        }
    }

    // ==================== Client Certificates (mTLS) ====================

    /**
     * Get all client certificate metadata
     */
    fun getClientCertificates(): List<ClientCertificate> {
        if (!clientMetadataFile.exists()) return emptyList()
        return try {
            val json = clientMetadataFile.readText()
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
        val p12File = File(clientDir, "${cert.alias}.p12")
        if (p12File.exists()) {
            p12File.delete()
        }

        // Update metadata
        val certs = getClientCertificates().toMutableList()
        certs.removeAll { it.alias == cert.alias }
        saveClientMetadata(certs)
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

        // Save the PKCS#12 file
        val p12File = File(clientDir, "$storageAlias.p12")
        p12File.writeBytes(pkcs12Data)

        // Update metadata
        val clientCert = ClientCertificate.fromX509Certificate(baseUrl, storageAlias, cert, password)
        val certs = getClientCertificates().toMutableList()
        
        // Remove existing cert for same baseUrl
        val oldCert = certs.find { it.baseUrl == baseUrl }
        if (oldCert != null) {
            removeClientCertificate(oldCert)
            certs.removeAll { it.baseUrl == baseUrl }
        }
        
        certs.add(clientCert)
        saveClientMetadata(certs)
    }

    /**
     * Get the path to a client certificate's PKCS#12 file
     */
    fun getClientCertificatePath(alias: String): File {
        return File(clientDir, "$alias.p12")
    }

    private fun saveClientMetadata(certs: List<ClientCertificate>) {
        if (certs.isEmpty()) {
            clientMetadataFile.delete()
        } else {
            clientMetadataFile.writeText(gson.toJson(certs))
        }
    }

    companion object {
        private const val TAG = "NtfyCertManager"
        private const val CERTS_DIR = "certs"
        private const val TRUSTED_DIR = "trusted"
        private const val CLIENT_DIR = "client"
        private const val TRUSTED_METADATA_FILE = "trusted.json"
        private const val CLIENT_METADATA_FILE = "client.json"

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
