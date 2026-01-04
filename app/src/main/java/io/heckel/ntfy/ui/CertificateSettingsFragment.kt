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
        trustedCategory.title = getString(R.string.settings_certificates_prefs_trusted_header)
        preferenceScreen.addPreference(trustedCategory)

        certs.forEach { trustedCert ->
            try {
                val cert = CertUtil.parseCertificate(trustedCert.pem)
                val pref = Preference(preferenceScreen.context)
                pref.title = getDisplaySubject(cert)
                pref.summary = if (isValid(cert)) {
                    getString(R.string.settings_certificates_prefs_expires_after,
                        dateFormat.format(cert.notAfter))
                } else {
                    getString(R.string.settings_certificates_prefs_expired)
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
        addTrustedPref.title = getString(R.string.settings_certificates_prefs_trusted_add_title)
        addTrustedPref.summary = getString(R.string.settings_certificates_prefs_trusted_add_summary)
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
        clientCategory.title = getString(R.string.settings_certificates_prefs_client_header)
        preferenceScreen.addPreference(clientCategory)

        certs.forEach { clientCert ->
            val pref = Preference(preferenceScreen.context)
            try {
                val x509Cert = CertUtil.parsePkcs12Certificate(clientCert.p12Base64, clientCert.password)
                pref.title = getDisplaySubject(x509Cert)
                val expires = if (isValid(x509Cert)) {
                    getString(R.string.settings_certificates_prefs_expires_after,
                        dateFormat.format(x509Cert.notAfter))
                } else {
                    getString(R.string.settings_certificates_prefs_expired)
                }
                pref.summary = getString(R.string.settings_certificates_prefs_client_summary,
                    shortUrl(clientCert.baseUrl), expires)
            } catch (e: Exception) {
                pref.title = shortUrl(clientCert.baseUrl)
                pref.summary = getString(R.string.settings_certificates_prefs_client_configured)
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
        addClientPref.title = getString(R.string.settings_certificates_prefs_client_add_title)
        addClientPref.summary = getString(R.string.settings_certificates_prefs_client_add_summary)
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
                val cert = CertUtil.parseCertificate(content)
                TrustedCertificateFragment.newInstanceAdd(cert)
                    .show(childFragmentManager, TrustedCertificateFragment.TAG)
            } else {
                Toast.makeText(context, R.string.certificate_dialog_error_invalid_cert, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read certificate file", e)
            Toast.makeText(context, R.string.certificate_dialog_error_invalid_cert, Toast.LENGTH_SHORT).show()
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
                Toast.makeText(context, R.string.certificate_dialog_error_invalid_p12, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read PKCS#12 file", e)
            Toast.makeText(context, R.string.certificate_dialog_error_invalid_p12, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getDisplaySubject(cert: X509Certificate): String {
        val subject = cert.subjectX500Principal.name
        val cnMatch = Regex("CN=([^,]+)").find(subject)
        return cnMatch?.groupValues?.get(1) ?: subject
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
