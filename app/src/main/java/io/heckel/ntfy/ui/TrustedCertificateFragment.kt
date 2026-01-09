package io.heckel.ntfy.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.AfterChangedTextWatcher
import io.heckel.ntfy.util.CertUtil
import io.heckel.ntfy.util.validUrl
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
 * - UNKNOWN: Shows certificate from SSL error with "Trust" action (from AddFragment).
 *            baseUrl is passed as argument, goes directly to page 2.
 * - ADD: Two-page flow - first enter Service URL, then view details and trust.
 *        Certificate is passed as argument.
 * - VIEW: Shows certificate details with "Delete" action (from CertificateSettingsFragment).
 *         baseUrl is passed as argument.
 */
class TrustedCertificateFragment : DialogFragment() {
    private lateinit var repository: Repository
    private var listener: TrustedCertificateListener? = null

    private var mode: Mode = Mode.ADD
    private var currentPage: Int = 1
    private var cert: X509Certificate? = null
    private var baseUrl: String? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var nextMenuItem: MenuItem
    private lateinit var trustMenuItem: MenuItem
    private lateinit var deleteMenuItem: MenuItem

    // Page 1 views
    private lateinit var page1Layout: LinearLayout
    private lateinit var baseUrlLayout: TextInputLayout
    private lateinit var baseUrlText: TextInputEditText
    private lateinit var errorText: TextView

    // Page 2 views
    private lateinit var page2Layout: LinearLayout
    private lateinit var securityWarningLayout: LinearLayout
    private lateinit var descriptionText: TextView
    private lateinit var warningText: TextView
    private lateinit var baseUrlValueLabel: TextView
    private lateinit var baseUrlValueText: TextView
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

        // Get data based on mode
        when (mode) {
            Mode.UNKNOWN -> {
                val certBytes = arguments?.getByteArray(ARG_CERTIFICATE)
                    ?: throw IllegalArgumentException("Certificate bytes required for UNKNOWN mode")
                baseUrl = arguments?.getString(ARG_BASE_URL)
                    ?: throw IllegalArgumentException("Base URL required for UNKNOWN mode")
                val certFactory = CertificateFactory.getInstance("X.509")
                cert = certFactory.generateCertificate(java.io.ByteArrayInputStream(certBytes)) as X509Certificate
            }
            Mode.ADD -> {
                val certBytes = arguments?.getByteArray(ARG_CERTIFICATE)
                    ?: throw IllegalArgumentException("Certificate bytes required for ADD mode")
                val certFactory = CertificateFactory.getInstance("X.509")
                cert = certFactory.generateCertificate(java.io.ByteArrayInputStream(certBytes)) as X509Certificate
            }
            Mode.VIEW -> {
                baseUrl = arguments?.getString(ARG_BASE_URL)
                    ?: throw IllegalArgumentException("Base URL required for VIEW mode")
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

    private fun setupView(view: View) {
        // Setup toolbar
        toolbar = view.findViewById(R.id.trusted_certificate_toolbar)
        toolbar.setNavigationOnClickListener { handleBack() }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.trusted_certificate_action_next -> {
                    nextClicked()
                    true
                }
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
        nextMenuItem = toolbar.menu.findItem(R.id.trusted_certificate_action_next)
        trustMenuItem = toolbar.menu.findItem(R.id.trusted_certificate_action_trust)
        deleteMenuItem = toolbar.menu.findItem(R.id.trusted_certificate_action_delete)

        // Page 1 views
        page1Layout = view.findViewById(R.id.trusted_certificate_page1)
        baseUrlLayout = view.findViewById(R.id.trusted_certificate_base_url_layout)
        baseUrlText = view.findViewById(R.id.trusted_certificate_base_url_text)
        errorText = view.findViewById(R.id.trusted_certificate_error_text)

        // Page 2 views
        page2Layout = view.findViewById(R.id.trusted_certificate_page2)
        securityWarningLayout = view.findViewById(R.id.trusted_certificate_security_warning)
        descriptionText = view.findViewById(R.id.trusted_certificate_description)
        warningText = view.findViewById(R.id.trusted_certificate_warning)
        baseUrlValueLabel = view.findViewById(R.id.trusted_certificate_base_url_value_label)
        baseUrlValueText = view.findViewById(R.id.trusted_certificate_base_url_value)
        subjectText = view.findViewById(R.id.trusted_certificate_subject)
        issuerText = view.findViewById(R.id.trusted_certificate_issuer)
        fingerprintText = view.findViewById(R.id.trusted_certificate_fingerprint)
        validFromText = view.findViewById(R.id.trusted_certificate_valid_from)
        validUntilText = view.findViewById(R.id.trusted_certificate_valid_until)

        // Validate input when typing
        val textWatcher = AfterChangedTextWatcher { validatePage1() }
        baseUrlText.addTextChangedListener(textWatcher)

        when (mode) {
            Mode.UNKNOWN -> setupUnknownMode()
            Mode.ADD -> setupAddMode()
            Mode.VIEW -> setupViewMode()
        }
    }

    private fun setupUnknownMode() {
        // Go directly to page 2 with details and security warning
        toolbar.setTitle(R.string.trusted_certificate_dialog_title_unknown)
        toolbar.setNavigationIcon(R.drawable.ic_close_white_24dp)
        page1Layout.isVisible = false
        page2Layout.isVisible = true
        nextMenuItem.isVisible = false
        trustMenuItem.isVisible = true
        deleteMenuItem.isVisible = false

        // Show security warning banner instead of description
        securityWarningLayout.isVisible = true
        descriptionText.isVisible = false
        baseUrlValueLabel.isVisible = true
        baseUrlValueText.isVisible = true
        baseUrlValueText.text = baseUrl

        displayCertificateDetails(cert!!)
    }

    private fun setupAddMode() {
        toolbar.setTitle(R.string.trusted_certificate_dialog_title_add)
        toolbar.setNavigationIcon(R.drawable.ic_close_white_24dp)
        showPage1()
    }

    private fun setupViewMode() {
        toolbar.setTitle(R.string.trusted_certificate_dialog_title)
        toolbar.setNavigationIcon(R.drawable.ic_close_white_24dp)
        page1Layout.isVisible = false
        page2Layout.isVisible = true
        nextMenuItem.isVisible = false
        trustMenuItem.isVisible = false
        deleteMenuItem.isVisible = true

        descriptionText.isVisible = false
        baseUrlValueLabel.isVisible = true
        baseUrlValueText.isVisible = true
        baseUrlValueText.text = baseUrl

        // Load certificate from repository
        lifecycleScope.launch(Dispatchers.IO) {
            val trustedCert = repository.getTrustedCertificate(baseUrl!!)
            if (trustedCert != null) {
                try {
                    val x509Cert = CertUtil.parsePemCertificate(trustedCert.pem)
                    cert = x509Cert
                    withContext(Dispatchers.Main) {
                        displayCertificateDetails(x509Cert)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showError(getString(R.string.trusted_certificate_dialog_error_parse, e.message ?: "Unknown error"))
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    dismiss()
                }
            }
        }
    }

    private fun showPage1() {
        currentPage = 1
        page1Layout.isVisible = true
        page2Layout.isVisible = false
        toolbar.setNavigationIcon(R.drawable.ic_close_white_24dp)
        nextMenuItem.isVisible = true
        trustMenuItem.isVisible = false
        deleteMenuItem.isVisible = false
        validatePage1()
    }

    private fun showPage2() {
        currentPage = 2
        page1Layout.isVisible = false
        page2Layout.isVisible = true
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white_24dp)
        nextMenuItem.isVisible = false
        trustMenuItem.isVisible = true
        deleteMenuItem.isVisible = false

        descriptionText.setText(R.string.trusted_certificate_dialog_description_add)
        descriptionText.isVisible = true
        baseUrlValueLabel.isVisible = true
        baseUrlValueText.isVisible = true
        baseUrlValueText.text = baseUrl

        displayCertificateDetails(cert!!)
    }

    private fun validatePage1() {
        val url = baseUrlText.text?.toString()?.trim() ?: ""
        nextMenuItem.isEnabled = validUrl(url)
    }

    private fun nextClicked() {
        val url = baseUrlText.text?.toString()?.trim() ?: ""

        if (!validUrl(url)) {
            showError(getString(R.string.trusted_certificate_dialog_error_invalid_url))
            return
        }

        baseUrl = url
        errorText.isVisible = false
        showPage2()
    }

    private fun handleBack() {
        when {
            mode == Mode.VIEW -> dismiss()
            mode == Mode.UNKNOWN -> {
                listener?.onCertificateRejected()
                dismiss()
            }
            currentPage == 2 -> showPage1()
            else -> dismiss()
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

    private fun showError(message: String) {
        errorText.text = message
        errorText.isVisible = true
    }

    private fun trustCertificate() {
        val certificate = cert ?: return
        val url = baseUrl ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val pem = CertUtil.encodeCertificateToPem(certificate)
            repository.addTrustedCertificate(url, pem)
            withContext(Dispatchers.Main) {
                if (mode != Mode.UNKNOWN) {
                    Toast.makeText(context, R.string.trusted_certificate_dialog_added_toast, Toast.LENGTH_SHORT).show()
                }
                listener?.onCertificateTrusted(certificate)
                dismiss()
            }
        }
    }

    private fun deleteCertificate() {
        val url = baseUrl ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            repository.removeTrustedCertificate(url)
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
        private const val ARG_BASE_URL = "base_url"

        /**
         * Create fragment for UNKNOWN mode - showing unknown server certificate with Trust action.
         * Used when connecting to a server with an untrusted certificate (from AddFragment).
         * The baseUrl is provided so it goes directly to the details page.
         */
        fun newInstanceUnknown(certificate: X509Certificate, baseUrl: String): TrustedCertificateFragment {
            return TrustedCertificateFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, Mode.UNKNOWN.name)
                    putByteArray(ARG_CERTIFICATE, certificate.encoded)
                    putString(ARG_BASE_URL, baseUrl)
                }
            }
        }

        /**
         * Create fragment for ADD mode - two-page flow to add a trusted certificate.
         * Page 1: Enter Service URL
         * Page 2: View certificate details and trust
         * Used when adding a certificate from file picker (from CertificateSettingsFragment).
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
         * Create fragment for VIEW mode - showing certificate details with Delete action.
         * baseUrl is used to look up the certificate from the repository.
         */
        fun newInstanceView(baseUrl: String): TrustedCertificateFragment {
            return TrustedCertificateFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, Mode.VIEW.name)
                    putString(ARG_BASE_URL, baseUrl)
                }
            }
        }
    }
}
