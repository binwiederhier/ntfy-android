package io.heckel.ntfy.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.CertUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen dialog fragment for viewing and trusting/deleting server certificates.
 *
 * Modes:
 * - UNKNOWN: Shows certificate from SSL error with "Trust" action (from AddFragment)
 * - ADD: Shows certificate from file picker with "Trust" action (from CertificateSettingsFragment)
 * - VIEW: Shows certificate details with "Delete" action (from CertificateSettingsFragment)
 */
class TrustedCertificateFragment : DialogFragment() {
    private lateinit var repository: Repository
    private var listener: TrustedCertificateListener? = null

    private var mode: Mode = Mode.ADD
    private var cert: X509Certificate? = null
    private var fingerprint: String? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var trustMenuItem: MenuItem
    private lateinit var deleteMenuItem: MenuItem
    private lateinit var descriptionText: TextView
    private lateinit var warningText: TextView
    private lateinit var subjectText: TextView
    private lateinit var issuerText: TextView
    private lateinit var fingerprintText: TextView
    private lateinit var validFromText: TextView
    private lateinit var validUntilText: TextView

    interface TrustedCertificateListener {
        fun onCertificateTrusted(certificate: X509Certificate)
        fun onCertificateRejected()
        fun onCertificateDeleted()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is TrustedCertificateListener -> parentFragment as TrustedCertificateListener
            context is TrustedCertificateListener -> context
            else -> null // Listener is optional
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        repository = Repository.getInstance(requireContext())

        // Determine mode from arguments
        mode = Mode.valueOf(arguments?.getString(ARG_MODE) ?: Mode.ADD.name)

        // Get certificate data based on mode
        when (mode) {
            Mode.UNKNOWN, Mode.ADD -> {
                val certBytes = arguments?.getByteArray(ARG_CERTIFICATE)
                    ?: throw IllegalArgumentException("Certificate bytes required for ADD/UNKNOWN mode")
                val certFactory = CertificateFactory.getInstance("X.509")
                cert = certFactory.generateCertificate(java.io.ByteArrayInputStream(certBytes)) as X509Certificate
                fingerprint = CertUtil.calculateFingerprint(cert!!)
            }
            Mode.VIEW -> {
                fingerprint = arguments?.getString(ARG_FINGERPRINT)
                    ?: throw IllegalArgumentException("Fingerprint required for VIEW mode")
            }
        }

        // Build the view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_trusted_certificate_dialog, null)
        setupView(view)

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

    private fun setupView(view: android.view.View) {
        // Setup toolbar
        toolbar = view.findViewById(R.id.trusted_certificate_toolbar)
        toolbar.setNavigationOnClickListener {
            if (mode == Mode.ADD || mode == Mode.UNKNOWN) {
                listener?.onCertificateRejected()
            }
            dismiss()
        }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.trusted_certificate_action_trust -> {
                    trustCertificate()
                    true
                }
                R.id.trusted_certificate_action_delete -> {
                    deleteCertificate()
                    true
                }
                else -> false
            }
        }
        trustMenuItem = toolbar.menu.findItem(R.id.trusted_certificate_action_trust)
        deleteMenuItem = toolbar.menu.findItem(R.id.trusted_certificate_action_delete)

        // Setup views
        descriptionText = view.findViewById(R.id.trusted_certificate_description)
        warningText = view.findViewById(R.id.trusted_certificate_warning)
        subjectText = view.findViewById(R.id.trusted_certificate_subject)
        issuerText = view.findViewById(R.id.trusted_certificate_issuer)
        fingerprintText = view.findViewById(R.id.trusted_certificate_fingerprint)
        validFromText = view.findViewById(R.id.trusted_certificate_valid_from)
        validUntilText = view.findViewById(R.id.trusted_certificate_valid_until)

        when (mode) {
            Mode.UNKNOWN -> setupUnknownMode()
            Mode.ADD -> setupAddMode()
            Mode.VIEW -> setupViewMode()
        }
    }

    private fun setupUnknownMode() {
        toolbar.setTitle(R.string.trusted_certificate_dialog_title_unknown)
        descriptionText.setText(R.string.trusted_certificate_dialog_description_unknown)
        descriptionText.isVisible = true
        trustMenuItem.isVisible = true
        deleteMenuItem.isVisible = false

        displayCertificateDetails(cert!!)
    }

    private fun setupAddMode() {
        toolbar.setTitle(R.string.trusted_certificate_dialog_title_add)
        descriptionText.setText(R.string.trusted_certificate_dialog_description_add)
        descriptionText.isVisible = true
        trustMenuItem.isVisible = true
        deleteMenuItem.isVisible = false

        displayCertificateDetails(cert!!)
    }

    private fun setupViewMode() {
        toolbar.setTitle(R.string.trusted_certificate_dialog_title)
        descriptionText.isVisible = false
        trustMenuItem.isVisible = false
        deleteMenuItem.isVisible = true

        // Load certificate from repository
        lifecycleScope.launch(Dispatchers.IO) {
            val trustedCert = repository.getTrustedCertificates().find { it.fingerprint == fingerprint }
            if (trustedCert != null) {
                try {
                    val x509Cert = CertUtil.parsePemCertificate(trustedCert.pem)
                    cert = x509Cert
                    withContext(Dispatchers.Main) {
                        displayCertificateDetails(x509Cert)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        fingerprintText.text = fingerprint
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    dismiss()
                }
            }
        }
    }

    private fun displayCertificateDetails(certificate: X509Certificate) {
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

        subjectText.text = certificate.subjectX500Principal.name
        issuerText.text = certificate.issuerX500Principal.name
        fingerprintText.text = CertUtil.calculateFingerprint(certificate)
        validFromText.text = dateFormat.format(certificate.notBefore)
        validUntilText.text = dateFormat.format(certificate.notAfter)

        // Show warning if certificate is expired or not yet valid
        val now = Date()
        when {
            now.after(certificate.notAfter) -> {
                warningText.text = getString(R.string.trusted_certificate_dialog_expired_warning)
                warningText.isVisible = true
            }
            now.before(certificate.notBefore) -> {
                warningText.text = getString(R.string.trusted_certificate_dialog_not_yet_valid_warning)
                warningText.isVisible = true
            }
            else -> {
                warningText.isVisible = false
            }
        }
    }

    private fun trustCertificate() {
        val certificate = cert ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val fingerprint = CertUtil.calculateFingerprint(certificate)
            val pem = CertUtil.encodeCertificateToPem(certificate)
            repository.addTrustedCertificate(fingerprint, pem)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.trusted_certificate_dialog_added_toast, Toast.LENGTH_SHORT).show()
                listener?.onCertificateTrusted(certificate)
                dismiss()
            }
        }
    }

    private fun deleteCertificate() {
        val fp = fingerprint ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            repository.removeTrustedCertificate(fp)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.trusted_certificate_dialog_deleted_toast, Toast.LENGTH_SHORT).show()
                listener?.onCertificateDeleted()
                dismiss()
            }
        }
    }

    enum class Mode {
        UNKNOWN,
        ADD,
        VIEW
    }

    companion object {
        const val TAG = "NtfyTrustedCertFragment"
        private const val ARG_MODE = "mode"
        private const val ARG_CERTIFICATE = "certificate"
        private const val ARG_FINGERPRINT = "fingerprint"

        /**
         * Create fragment for UNKNOWN mode - showing unknown server certificate with Trust action
         * Used when connecting to a server with an untrusted certificate (from AddFragment)
         */
        fun newInstanceUnknown(certificate: X509Certificate): TrustedCertificateFragment {
            return TrustedCertificateFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, Mode.UNKNOWN.name)
                    putByteArray(ARG_CERTIFICATE, certificate.encoded)
                }
            }
        }

        /**
         * Create fragment for ADD mode - showing certificate details with Trust action
         * Used when adding a certificate from file picker (from CertificateSettingsFragment)
         */
        fun newInstanceAdd(certificate: X509Certificate): TrustedCertificateFragment {
            return TrustedCertificateFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, Mode.ADD.name)
                    putByteArray(ARG_CERTIFICATE, certificate.encoded)
                }
            }
        }

        /**
         * Create fragment for VIEW mode - showing certificate details with Delete action
         */
        fun newInstanceView(fingerprint: String): TrustedCertificateFragment {
            return TrustedCertificateFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, Mode.VIEW.name)
                    putString(ARG_FINGERPRINT, fingerprint)
                }
            }
        }
    }
}
