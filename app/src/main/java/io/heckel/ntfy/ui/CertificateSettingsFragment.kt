package io.heckel.ntfy.ui

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import io.heckel.ntfy.R
import io.heckel.ntfy.db.ClientCertificate
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.TrustedCertificate
import io.heckel.ntfy.util.CertUtil
import io.heckel.ntfy.util.shortUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Fragment for managing trusted certificates and client certificates.
 */
class CertificateSettingsFragment : BasePreferenceFragment(), CertificateFragment.CertificateDialogListener {
    private lateinit var repository: Repository

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
                    getString(R.string.settings_certificates_prefs_expires, dateFormat.format(cert.notAfter))
                } else {
                    getString(R.string.settings_certificates_prefs_expired)
                }
                pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    CertificateFragment.newInstanceViewTrusted(trustedCert.fingerprint)
                        .show(childFragmentManager, CertificateFragment.TAG)
                    true
                }
                trustedCategory.addPreference(pref)
            } catch (e: Exception) {
                // Skip invalid certificates
            }
        }

        // Add trusted certificate
        val addTrustedPref = Preference(preferenceScreen.context)
        addTrustedPref.title = getString(R.string.settings_certificates_prefs_trusted_add_title)
        addTrustedPref.summary = getString(R.string.settings_certificates_prefs_trusted_add_summary)
        addTrustedPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            CertificateFragment.newInstanceAddTrusted()
                .show(childFragmentManager, CertificateFragment.TAG)
            true
        }
        trustedCategory.addPreference(addTrustedPref)
    }

    private fun addClientCertPreferences(certs: List<ClientCertificate>) {
        // Client certificates header
        val clientCategory = PreferenceCategory(preferenceScreen.context)
        clientCategory.title = getString(R.string.settings_certificates_prefs_client_header)
        preferenceScreen.addPreference(clientCategory)

        certs.forEach { cert ->
            val pref = Preference(preferenceScreen.context)
            pref.title = shortUrl(cert.baseUrl)
            pref.summary = getString(R.string.settings_certificates_prefs_client_configured)
            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                CertificateFragment.newInstanceViewClient(cert.baseUrl)
                    .show(childFragmentManager, CertificateFragment.TAG)
                true
            }
            clientCategory.addPreference(pref)
        }

        // Add client certificate
        val addClientPref = Preference(preferenceScreen.context)
        addClientPref.title = getString(R.string.settings_certificates_prefs_client_add_title)
        addClientPref.summary = getString(R.string.settings_certificates_prefs_client_add_summary)
        addClientPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            CertificateFragment.newInstanceAddClient()
                .show(childFragmentManager, CertificateFragment.TAG)
            true
        }
        clientCategory.addPreference(addClientPref)
    }

    private fun getDisplaySubject(cert: java.security.cert.X509Certificate): String {
        val subject = cert.subjectX500Principal.name
        val cnMatch = Regex("CN=([^,]+)").find(subject)
        return cnMatch?.groupValues?.get(1) ?: subject
    }

    private fun isValid(cert: java.security.cert.X509Certificate): Boolean {
        val now = Date()
        return now.after(cert.notBefore) && now.before(cert.notAfter)
    }

    // CertificateFragment.CertificateDialogListener implementation
    override fun onCertificateAdded() {
        reload()
    }

    override fun onCertificateDeleted() {
        reload()
    }
}
