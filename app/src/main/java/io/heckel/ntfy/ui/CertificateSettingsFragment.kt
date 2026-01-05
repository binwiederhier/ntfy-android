package io.heckel.ntfy.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import io.heckel.ntfy.R
import io.heckel.ntfy.db.ClientCertificate
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.TrustedCertificate
import io.heckel.ntfy.util.CertUtil
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.shortUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fragment for managing trusted certificates and client certificates.
 */
class CertificateSettingsFragment : BasePreferenceFragment(),
    TrustedCertificateFragment.TrustedCertificateListener,
    ClientCertificateFragment.ClientCertificateListener {

    private lateinit var repository: Repository

    // File pickers
    private val trustedCertFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleTrustedCertFileSelected(it) }
    }

    private val clientCertFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleClientCertFileSelected(it) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.certificate_preferences, rootKey)
        repository = Repository.getInstance(requireActivity())
        reload()
    }

    fun reload() {
        preferenceScreen.removeAll()
        lifecycleScope.launch(Dispatchers.IO) {
            val trustedCerts = repository.getTrustedCertificates()
            val clientCerts = repository.getClientCertificates()

            activity?.runOnUiThread {
                addTrustedCertPreferences(trustedCerts)
                addClientCertPreferences(clientCerts)
            }
        }
    }

    private fun addTrustedCertPreferences(certs: List<TrustedCertificate>) {
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

        // Trusted certificates header
        val trustedCategory = PreferenceCategory(preferenceScreen.context)
        trustedCategory.title = getString(R.string.settings_advanced_certificates_trusted_header)
        preferenceScreen.addPreference(trustedCategory)

        certs.forEach { trustedCert ->
            try {
                val cert = CertUtil.parsePemCertificate(trustedCert.pem)
                val subject = parseCommonName(cert.subjectX500Principal.name)
                val issuer = parseCommonName(cert.issuerX500Principal.name)
                val isSelfSigned = cert.subjectX500Principal == cert.issuerX500Principal
                val isExpired = !isValid(cert)

                val pref = Preference(preferenceScreen.context)
                pref.title = subject
                pref.summary = when {
                    isSelfSigned && isExpired -> getString(R.string.settings_advanced_certificates_trusted_item_summary_ca_expired)
                    isSelfSigned -> getString(R.string.settings_advanced_certificates_trusted_item_summary_ca, dateFormat.format(cert.notAfter))
                    isExpired -> getString(R.string.settings_advanced_certificates_trusted_item_summary_leaf_expired, issuer)
                    else -> getString(R.string.settings_advanced_certificates_trusted_item_summary_leaf, issuer, dateFormat.format(cert.notAfter))
                }
                pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    TrustedCertificateFragment.newInstanceView(trustedCert.fingerprint)
                        .show(childFragmentManager, TrustedCertificateFragment.TAG)
                    true
                }
                trustedCategory.addPreference(pref)
            } catch (e: Exception) {
                // Skip invalid certificates
            }
        }

        // Add trusted certificate - launches file picker directly
        val addTrustedPref = Preference(preferenceScreen.context)
        addTrustedPref.title = getString(R.string.settings_advanced_certificates_trusted_add_title)
        addTrustedPref.summary = getString(R.string.settings_advanced_certificates_trusted_add_summary)
        addTrustedPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            trustedCertFilePicker.launch("*/*")
            true
        }
        trustedCategory.addPreference(addTrustedPref)
    }

    private fun addClientCertPreferences(certs: List<ClientCertificate>) {
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

        // Client certificates header
        val clientCategory = PreferenceCategory(preferenceScreen.context)
        clientCategory.title = getString(R.string.settings_advanced_certificates_client_header)
        preferenceScreen.addPreference(clientCategory)

        certs.forEach { clientCert ->
            val pref = Preference(preferenceScreen.context)
            try {
                val cert = CertUtil.parsePkcs12Certificate(clientCert.p12Base64, clientCert.password)
                val issuer = parseCommonName(cert.issuerX500Principal.name)
                pref.title = parseCommonName(cert.subjectX500Principal.name)
                pref.summary = if (isValid(cert)) {
                    getString(R.string.settings_advanced_certificates_client_item_summary, issuer, dateFormat.format(cert.notAfter), shortUrl(clientCert.baseUrl))
                } else {
                    getString(R.string.settings_advanced_certificates_client_item_summary_expired, issuer, shortUrl(clientCert.baseUrl))
                }
            } catch (_: Exception) {
                pref.title = shortUrl(clientCert.baseUrl)
            }
            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                ClientCertificateFragment.newInstanceView(clientCert.baseUrl)
                    .show(childFragmentManager, ClientCertificateFragment.TAG)
                true
            }
            clientCategory.addPreference(pref)
        }

        // Add client certificate - launches file picker directly
        val addClientPref = Preference(preferenceScreen.context)
        addClientPref.title = getString(R.string.settings_advanced_certificates_client_add_title)
        addClientPref.summary = getString(R.string.settings_advanced_certificates_client_add_summary)
        addClientPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            clientCertFilePicker.launch("*/*")
            true
        }
        clientCategory.addPreference(addClientPref)
    }

    private fun handleTrustedCertFileSelected(uri: Uri) {
        try {
            val content = requireContext().contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            }
            if (content != null && content.contains("-----BEGIN CERTIFICATE-----")) {
                val cert = CertUtil.parsePemCertificate(content)
                TrustedCertificateFragment.newInstanceAdd(cert)
                    .show(childFragmentManager, TrustedCertificateFragment.TAG)
            } else {
                Toast.makeText(context, R.string.settings_advanced_certificates_error_invalid_cert, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read certificate file", e)
            Toast.makeText(context, R.string.settings_advanced_certificates_error_invalid_cert, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleClientCertFileSelected(uri: Uri) {
        try {
            val data = requireContext().contentResolver.openInputStream(uri)?.use {
                it.readBytes()
            }
            if (data != null && data.isNotEmpty()) {
                ClientCertificateFragment.newInstance(data)
                    .show(childFragmentManager, ClientCertificateFragment.TAG)
            } else {
                Toast.makeText(context, R.string.settings_advanced_certificates_error_invalid_p12, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read PKCS#12 file", e)
            Toast.makeText(context, R.string.settings_advanced_certificates_error_invalid_p12, Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseCommonName(name: String): String {
        val cnMatch = Regex("CN=([^,]+)").find(name)
        return cnMatch?.groupValues?.get(1) ?: name
    }

    private fun isValid(cert: X509Certificate): Boolean {
        val now = Date()
        return now.after(cert.notBefore) && now.before(cert.notAfter)
    }

    // TrustedCertificateFragment.TrustedCertificateListener implementation
    override fun onCertificateTrusted(certificate: X509Certificate) {
        reload()
    }

    override fun onCertificateRejected() {
        // Nothing to do
    }

    override fun onCertificateDeleted() {
        reload()
    }

    // ClientCertificateFragment.ClientCertificateListener implementation
    override fun onCertificateAdded() {
        reload()
    }

    companion object {
        private const val TAG = "NtfyCertSettingsFragment"
    }
}
