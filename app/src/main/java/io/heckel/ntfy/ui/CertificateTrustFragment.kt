package io.heckel.ntfy.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.google.android.material.appbar.MaterialToolbar
import io.heckel.ntfy.R
import io.heckel.ntfy.tls.CertificateManager
import io.heckel.ntfy.tls.calculateFingerprint
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dialog fragment for trusting a server's SSL certificate.
 * Shown when connecting to a server with a self-signed or untrusted certificate.
 */
class CertificateTrustFragment : DialogFragment() {
    private lateinit var listener: CertificateTrustListener
    private lateinit var certificate: X509Certificate
    private lateinit var baseUrl: String

    private lateinit var toolbar: MaterialToolbar
    private lateinit var trustMenuItem: MenuItem

    interface CertificateTrustListener {
        fun onCertificateTrusted(baseUrl: String, certificate: X509Certificate)
        fun onCertificateRejected()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // The listener can be the activity or the parent fragment
        listener = when {
            parentFragment is CertificateTrustListener -> parentFragment as CertificateTrustListener
            context is CertificateTrustListener -> context
            else -> throw IllegalStateException("Activity or parent fragment must implement CertificateTrustListener")
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        // Get certificate data from arguments
        val certBytes = arguments?.getByteArray(ARG_CERTIFICATE)
            ?: throw IllegalArgumentException("Certificate bytes required")
        baseUrl = arguments?.getString(ARG_BASE_URL)
            ?: throw IllegalArgumentException("Base URL required")

        // Parse the certificate
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        certificate = certFactory.generateCertificate(java.io.ByteArrayInputStream(certBytes)) as X509Certificate

        // Build the view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_certificate_trust_dialog, null)
        
        // Setup toolbar
        toolbar = view.findViewById(R.id.certificate_trust_toolbar)
        toolbar.setNavigationOnClickListener {
            listener.onCertificateRejected()
            dismiss()
        }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.certificate_trust_action_trust -> {
                    trustCertificate()
                    true
                }
                else -> false
            }
        }
        trustMenuItem = toolbar.menu.findItem(R.id.certificate_trust_action_trust)
        
        setupCertificateDetails(view)

        // Build dialog
        val dialog = Dialog(requireContext(), R.style.Theme_App_FullScreenDialog)
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(false)

        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun setupCertificateDetails(view: android.view.View) {
        val subjectText = view.findViewById<TextView>(R.id.certificate_trust_subject)
        val issuerText = view.findViewById<TextView>(R.id.certificate_trust_issuer)
        val fingerprintText = view.findViewById<TextView>(R.id.certificate_trust_fingerprint)
        val validFromText = view.findViewById<TextView>(R.id.certificate_trust_valid_from)
        val validUntilText = view.findViewById<TextView>(R.id.certificate_trust_valid_until)
        val warningText = view.findViewById<TextView>(R.id.certificate_trust_warning)

        // Format dates
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

        // Populate certificate details
        subjectText.text = certificate.subjectX500Principal.name
        issuerText.text = certificate.issuerX500Principal.name
        fingerprintText.text = calculateFingerprint(certificate)
        validFromText.text = dateFormat.format(certificate.notBefore)
        validUntilText.text = dateFormat.format(certificate.notAfter)

        // Show warning if certificate is expired or not yet valid
        val now = Date()
        when {
            now.after(certificate.notAfter) -> {
                warningText.text = getString(R.string.certificate_trust_dialog_expired_warning)
                warningText.isVisible = true
            }
            now.before(certificate.notBefore) -> {
                warningText.text = getString(R.string.certificate_trust_dialog_not_yet_valid_warning)
                warningText.isVisible = true
            }
            else -> {
                warningText.isVisible = false
            }
        }
    }
    
    private fun trustCertificate() {
        // Save the certificate to global trust store
        val certManager = CertificateManager.getInstance(requireContext())
        certManager.addTrustedCertificate(certificate)
        listener.onCertificateTrusted(baseUrl, certificate)
        dismiss()
    }

    companion object {
        const val TAG = "NtfyCertTrustFragment"
        private const val ARG_CERTIFICATE = "certificate"
        private const val ARG_BASE_URL = "baseUrl"

        fun newInstance(baseUrl: String, certificate: X509Certificate): CertificateTrustFragment {
            val fragment = CertificateTrustFragment()
            fragment.arguments = Bundle().apply {
                putByteArray(ARG_CERTIFICATE, certificate.encoded)
                putString(ARG_BASE_URL, baseUrl)
            }
            return fragment
        }
    }
}
