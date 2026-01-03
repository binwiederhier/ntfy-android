package io.heckel.ntfy.ui

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import io.heckel.ntfy.R
import io.heckel.ntfy.tls.CertificateManager
import io.heckel.ntfy.tls.ClientCertificate
import io.heckel.ntfy.tls.calculateFingerprint
import io.heckel.ntfy.tls.displaySubject
import io.heckel.ntfy.util.shortUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*

/**
 * Fragment for managing trusted certificates and client certificates.
 */
class CertificateSettingsFragment : BasePreferenceFragment(), CertificateFragment.CertificateDialogListener {
    private lateinit var certManager: CertificateManager

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.certificate_preferences, rootKey)
        certManager = CertificateManager.getInstance(requireActivity())
        reload()
    }

    fun reload() {
        preferenceScreen.removeAll()
        lifecycleScope.launch(Dispatchers.IO) {
            val trustedCerts = certManager.getTrustedCertificates()

            val clientCerts = certManager.getClientCertificates()
                .groupBy { it.baseUrl }
                .toSortedMap()

            activity?.runOnUiThread {
                addTrustedCertPreferences(trustedCerts)
                addClientCertPreferences(clientCerts)
            }
        }
    }

    private fun addTrustedCertPreferences(certs: List<X509Certificate>) {
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

        // Trusted certificates header
        val trustedCategory = PreferenceCategory(preferenceScreen.context)
        trustedCategory.title = getString(R.string.settings_certificates_prefs_trusted_header)
        preferenceScreen.addPreference(trustedCategory)

        if (certs.isEmpty()) {
            val emptyPref = Preference(preferenceScreen.context)
            emptyPref.title = getString(R.string.settings_certificates_prefs_trusted_empty)
            emptyPref.isEnabled = false
            trustedCategory.addPreference(emptyPref)
        } else {
            certs.forEach { cert ->
                val fingerprint = calculateFingerprint(cert)
                val pref = Preference(preferenceScreen.context)
                pref.title = displaySubject(cert)
                pref.summary = if (isValid(cert)) {
                    getString(R.string.settings_certificates_prefs_expires, dateFormat.format(cert.notAfter))
                } else {
                    getString(R.string.settings_certificates_prefs_expired)
                }
                pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    CertificateFragment.newInstanceViewTrusted(fingerprint)
                        .show(childFragmentManager, CertificateFragment.TAG)
                    true
                }
                trustedCategory.addPreference(pref)
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

    private fun addClientCertPreferences(certsByBaseUrl: Map<String, List<ClientCertificate>>) {
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

        // Client certificates header
        val clientCategory = PreferenceCategory(preferenceScreen.context)
        clientCategory.title = getString(R.string.settings_certificates_prefs_client_header)
        preferenceScreen.addPreference(clientCategory)

        if (certsByBaseUrl.isEmpty()) {
            val emptyPref = Preference(preferenceScreen.context)
            emptyPref.title = getString(R.string.settings_certificates_prefs_client_empty)
            emptyPref.isEnabled = false
            clientCategory.addPreference(emptyPref)
        } else {
            certsByBaseUrl.forEach { (baseUrl, certs) ->
                certs.forEach { cert ->
                    val pref = Preference(preferenceScreen.context)
                    pref.title = "${cert.displaySubject()} (${shortUrl(baseUrl)})"
                    pref.summary = if (cert.isValid()) {
                        getString(R.string.settings_certificates_prefs_expires, dateFormat.format(Date(cert.notAfter)))
                    } else {
                        getString(R.string.settings_certificates_prefs_expired)
                    }
                    pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                        CertificateFragment.newInstanceViewClient(cert)
                            .show(childFragmentManager, CertificateFragment.TAG)
                        true
                    }
                    clientCategory.addPreference(pref)
                }
            }
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

    private fun isValid(cert: X509Certificate): Boolean {
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
