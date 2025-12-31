package io.heckel.ntfy.ui

import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.R
import io.heckel.ntfy.tls.CertificateManager
import io.heckel.ntfy.tls.ClientCertificate
import io.heckel.ntfy.tls.TrustedCertificate
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.validUrl
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dialog for adding or viewing certificates.
 */
class CertificateFragment : DialogFragment() {
    private lateinit var listener: CertificateDialogListener
    private lateinit var certManager: CertificateManager
    
    private var mode: Mode = Mode.ADD_TRUSTED
    private var trustedCertificate: TrustedCertificate? = null
    private var clientCertificate: ClientCertificate? = null
    
    // File contents
    private var certPem: String? = null
    private var keyPem: String? = null
    
    // Views
    private lateinit var titleText: TextView
    private lateinit var baseUrlLayout: TextInputLayout
    private lateinit var baseUrlText: TextInputEditText
    private lateinit var certFileLayout: View
    private lateinit var selectCertButton: MaterialButton
    private lateinit var certFileName: TextView
    private lateinit var keyFileLayout: View
    private lateinit var selectKeyButton: MaterialButton
    private lateinit var keyFileName: TextView
    private lateinit var detailsLayout: View
    private lateinit var subjectText: TextView
    private lateinit var issuerText: TextView
    private lateinit var fingerprintText: TextView
    private lateinit var validFromText: TextView
    private lateinit var validUntilText: TextView
    private lateinit var errorText: TextView
    private lateinit var deleteButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var addButton: MaterialButton
    
    // File pickers
    private val certFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleCertFileSelected(it) }
    }
    
    private val keyFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleKeyFileSelected(it) }
    }

    interface CertificateDialogListener {
        fun onCertificateAdded()
        fun onCertificateDeleted()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is CertificateDialogListener -> parentFragment as CertificateDialogListener
            context is CertificateDialogListener -> context
            else -> throw IllegalStateException("Activity or parent fragment must implement CertificateDialogListener")
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }
        
        certManager = CertificateManager.getInstance(requireContext())
        
        // Determine mode from arguments
        mode = Mode.valueOf(arguments?.getString(ARG_MODE) ?: Mode.ADD_TRUSTED.name)
        
        // Get existing certificate data if viewing
        arguments?.getString(ARG_TRUSTED_CERT_FINGERPRINT)?.let { fingerprint ->
            arguments?.getString(ARG_TRUSTED_CERT_BASE_URL)?.let { baseUrl ->
                trustedCertificate = certManager.getTrustedCertificatesForServer(baseUrl)
                    .find { it.fingerprint == fingerprint }
            }
        }
        arguments?.getString(ARG_CLIENT_CERT_ALIAS)?.let { alias ->
            clientCertificate = certManager.getClientCertificates().find { it.alias == alias }
        }
        
        // Build the view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_certificate_dialog, null)
        setupView(view)
        
        // Build dialog
        val dialog = Dialog(requireContext(), R.style.Theme_App_FullScreenDialog)
        dialog.setContentView(view)
        
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
        titleText = view.findViewById(R.id.certificate_dialog_title)
        baseUrlLayout = view.findViewById(R.id.certificate_dialog_base_url_layout)
        baseUrlText = view.findViewById(R.id.certificate_dialog_base_url_text)
        certFileLayout = view.findViewById(R.id.certificate_dialog_cert_file_layout)
        selectCertButton = view.findViewById(R.id.certificate_dialog_select_cert_button)
        certFileName = view.findViewById(R.id.certificate_dialog_cert_file_name)
        keyFileLayout = view.findViewById(R.id.certificate_dialog_key_file_layout)
        selectKeyButton = view.findViewById(R.id.certificate_dialog_select_key_button)
        keyFileName = view.findViewById(R.id.certificate_dialog_key_file_name)
        detailsLayout = view.findViewById(R.id.certificate_dialog_details_layout)
        subjectText = view.findViewById(R.id.certificate_dialog_subject)
        issuerText = view.findViewById(R.id.certificate_dialog_issuer)
        fingerprintText = view.findViewById(R.id.certificate_dialog_fingerprint)
        validFromText = view.findViewById(R.id.certificate_dialog_valid_from)
        validUntilText = view.findViewById(R.id.certificate_dialog_valid_until)
        errorText = view.findViewById(R.id.certificate_dialog_error_text)
        deleteButton = view.findViewById(R.id.certificate_dialog_delete_button)
        cancelButton = view.findViewById(R.id.certificate_dialog_cancel_button)
        addButton = view.findViewById(R.id.certificate_dialog_add_button)
        
        // Configure based on mode
        when (mode) {
            Mode.ADD_TRUSTED -> setupAddTrustedMode()
            Mode.ADD_CLIENT -> setupAddClientMode()
            Mode.VIEW_TRUSTED -> setupViewTrustedMode()
            Mode.VIEW_CLIENT -> setupViewClientMode()
        }
        
        // Common button handlers
        cancelButton.setOnClickListener { dismiss() }
        selectCertButton.setOnClickListener { certFilePicker.launch("*/*") }
        selectKeyButton.setOnClickListener { keyFilePicker.launch("*/*") }
    }
    
    private fun setupAddTrustedMode() {
        titleText.text = getString(R.string.certificate_dialog_title_add_trusted)
        baseUrlLayout.isVisible = true
        certFileLayout.isVisible = true
        keyFileLayout.isVisible = false
        detailsLayout.isVisible = false
        deleteButton.isVisible = false
        addButton.text = getString(R.string.certificate_dialog_button_add)
        
        addButton.setOnClickListener { addTrustedCertificate() }
    }
    
    private fun setupAddClientMode() {
        titleText.text = getString(R.string.certificate_dialog_title_add_client)
        baseUrlLayout.isVisible = true
        certFileLayout.isVisible = true
        keyFileLayout.isVisible = true
        detailsLayout.isVisible = false
        deleteButton.isVisible = false
        addButton.text = getString(R.string.certificate_dialog_button_add)
        
        addButton.setOnClickListener { addClientCertificate() }
    }
    
    private fun setupViewTrustedMode() {
        val cert = trustedCertificate ?: run {
            dismiss()
            return
        }
        
        titleText.text = getString(R.string.certificate_dialog_title_view)
        baseUrlLayout.isVisible = false
        certFileLayout.isVisible = false
        keyFileLayout.isVisible = false
        detailsLayout.isVisible = true
        deleteButton.isVisible = true
        addButton.isVisible = false
        
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        subjectText.text = cert.subject
        issuerText.text = cert.issuer
        fingerprintText.text = cert.fingerprint
        validFromText.text = dateFormat.format(Date(cert.notBefore))
        validUntilText.text = dateFormat.format(Date(cert.notAfter))
        
        deleteButton.setOnClickListener { confirmDeleteTrustedCertificate(cert) }
    }
    
    private fun setupViewClientMode() {
        val cert = clientCertificate ?: run {
            dismiss()
            return
        }
        
        titleText.text = getString(R.string.certificate_dialog_title_view)
        baseUrlLayout.isVisible = false
        certFileLayout.isVisible = false
        keyFileLayout.isVisible = false
        detailsLayout.isVisible = true
        deleteButton.isVisible = true
        addButton.isVisible = false
        
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        subjectText.text = cert.subject
        issuerText.text = cert.issuer
        fingerprintText.text = cert.fingerprint
        validFromText.text = dateFormat.format(Date(cert.notBefore))
        validUntilText.text = dateFormat.format(Date(cert.notAfter))
        
        deleteButton.setOnClickListener { confirmDeleteClientCertificate(cert) }
    }
    
    private fun handleCertFileSelected(uri: Uri) {
        try {
            val content = requireContext().contentResolver.openInputStream(uri)?.use { 
                it.bufferedReader().readText() 
            }
            if (content != null && content.contains("-----BEGIN CERTIFICATE-----")) {
                certPem = content
                certFileName.text = getString(R.string.certificate_dialog_cert_file_selected, uri.lastPathSegment ?: "certificate.pem")
                errorText.isVisible = false
            } else {
                showError(getString(R.string.certificate_dialog_error_invalid_cert))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read certificate file", e)
            showError(getString(R.string.certificate_dialog_error_invalid_cert))
        }
    }
    
    private fun handleKeyFileSelected(uri: Uri) {
        try {
            val content = requireContext().contentResolver.openInputStream(uri)?.use { 
                it.bufferedReader().readText() 
            }
            if (content != null && (content.contains("-----BEGIN PRIVATE KEY-----") ||
                        content.contains("-----BEGIN RSA PRIVATE KEY-----") ||
                        content.contains("-----BEGIN EC PRIVATE KEY-----"))) {
                keyPem = content
                keyFileName.text = getString(R.string.certificate_dialog_key_file_selected, uri.lastPathSegment ?: "key.pem")
                errorText.isVisible = false
            } else {
                showError(getString(R.string.certificate_dialog_error_invalid_key))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read key file", e)
            showError(getString(R.string.certificate_dialog_error_invalid_key))
        }
    }
    
    private fun addTrustedCertificate() {
        val baseUrl = baseUrlText.text.toString().trim()
        val certContent = certPem
        
        // Validate
        if (baseUrl.isEmpty()) {
            showError(getString(R.string.certificate_dialog_error_missing_url))
            return
        }
        if (!validUrl(baseUrl)) {
            showError(getString(R.string.certificate_dialog_error_invalid_url))
            return
        }
        if (certContent == null) {
            showError(getString(R.string.certificate_dialog_error_missing_cert))
            return
        }
        
        try {
            val cert = certManager.parsePemCertificate(certContent)
            certManager.addTrustedCertificate(baseUrl, cert)
            Toast.makeText(context, R.string.certificate_dialog_added_toast, Toast.LENGTH_SHORT).show()
            listener.onCertificateAdded()
            dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add trusted certificate", e)
            showError(getString(R.string.certificate_dialog_error_invalid_cert))
        }
    }
    
    private fun addClientCertificate() {
        val baseUrl = baseUrlText.text.toString().trim()
        val certContent = certPem
        val keyContent = keyPem
        
        // Validate
        if (baseUrl.isEmpty()) {
            showError(getString(R.string.certificate_dialog_error_missing_url))
            return
        }
        if (!validUrl(baseUrl)) {
            showError(getString(R.string.certificate_dialog_error_invalid_url))
            return
        }
        if (certContent == null) {
            showError(getString(R.string.certificate_dialog_error_missing_cert))
            return
        }
        if (keyContent == null) {
            showError(getString(R.string.certificate_dialog_error_missing_key))
            return
        }
        
        try {
            certManager.addClientCertificate(baseUrl, certContent, keyContent)
            Toast.makeText(context, R.string.certificate_dialog_added_toast, Toast.LENGTH_SHORT).show()
            listener.onCertificateAdded()
            dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add client certificate", e)
            showError(getString(R.string.certificate_dialog_error_invalid_key))
        }
    }
    
    private fun confirmDeleteTrustedCertificate(cert: TrustedCertificate) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.certificate_dialog_delete_confirm)
            .setPositiveButton(R.string.certificate_dialog_button_delete) { _, _ ->
                certManager.removeTrustedCertificate(cert)
                Toast.makeText(context, R.string.certificate_dialog_deleted_toast, Toast.LENGTH_SHORT).show()
                listener.onCertificateDeleted()
                dismiss()
            }
            .setNegativeButton(R.string.certificate_dialog_button_cancel, null)
            .show()
    }
    
    private fun confirmDeleteClientCertificate(cert: ClientCertificate) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.certificate_dialog_delete_confirm)
            .setPositiveButton(R.string.certificate_dialog_button_delete) { _, _ ->
                certManager.removeClientCertificate(cert)
                Toast.makeText(context, R.string.certificate_dialog_deleted_toast, Toast.LENGTH_SHORT).show()
                listener.onCertificateDeleted()
                dismiss()
            }
            .setNegativeButton(R.string.certificate_dialog_button_cancel, null)
            .show()
    }
    
    private fun showError(message: String) {
        errorText.text = message
        errorText.isVisible = true
    }

    enum class Mode {
        ADD_TRUSTED,
        ADD_CLIENT,
        VIEW_TRUSTED,
        VIEW_CLIENT
    }

    companion object {
        const val TAG = "NtfyCertFragment"
        private const val ARG_MODE = "mode"
        private const val ARG_TRUSTED_CERT_FINGERPRINT = "trusted_cert_fingerprint"
        private const val ARG_TRUSTED_CERT_BASE_URL = "trusted_cert_base_url"
        private const val ARG_CLIENT_CERT_ALIAS = "client_cert_alias"
        
        fun newInstanceAddTrusted(): CertificateFragment {
            return CertificateFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, Mode.ADD_TRUSTED.name)
                }
            }
        }
        
        fun newInstanceAddClient(): CertificateFragment {
            return CertificateFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, Mode.ADD_CLIENT.name)
                }
            }
        }
        
        fun newInstanceViewTrusted(cert: TrustedCertificate): CertificateFragment {
            return CertificateFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, Mode.VIEW_TRUSTED.name)
                    putString(ARG_TRUSTED_CERT_FINGERPRINT, cert.fingerprint)
                    putString(ARG_TRUSTED_CERT_BASE_URL, cert.baseUrl)
                }
            }
        }
        
        fun newInstanceViewClient(cert: ClientCertificate): CertificateFragment {
            return CertificateFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, Mode.VIEW_CLIENT.name)
                    putString(ARG_CLIENT_CERT_ALIAS, cert.alias)
                }
            }
        }
    }
}

