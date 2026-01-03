package io.heckel.ntfy.ui

import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.TrustedCertificate
import io.heckel.ntfy.tls.SSLManager
import io.heckel.ntfy.util.AfterChangedTextWatcher
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.validUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dialog for adding or viewing certificates.
 */
class CertificateFragment : DialogFragment() {
    private lateinit var listener: CertificateDialogListener
    private lateinit var repository: Repository

    private var mode: Mode = Mode.ADD_TRUSTED
    private var trustedCertificate: TrustedCertificate? = null
    private var clientCertBaseUrl: String? = null

    // File contents
    private var certPem: String? = null
    private var pkcs12Data: ByteArray? = null

    // Views
    private lateinit var toolbar: MaterialToolbar
    private lateinit var saveMenuItem: MenuItem
    private lateinit var deleteMenuItem: MenuItem
    private lateinit var descriptionText: TextView
    private lateinit var baseUrlLayout: TextInputLayout
    private lateinit var baseUrlText: TextInputEditText
    private lateinit var certFileLayout: View
    private lateinit var selectCertButton: MaterialButton
    private lateinit var certFileName: TextView
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var passwordText: TextInputEditText
    private lateinit var detailsLayout: View
    private lateinit var subjectText: TextView
    private lateinit var issuerText: TextView
    private lateinit var fingerprintText: TextView
    private lateinit var validFromText: TextView
    private lateinit var validUntilText: TextView
    private lateinit var errorText: TextView

    // File pickers
    private val certFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleCertFileSelected(it) }
    }

    private val pkcs12FilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handlePkcs12FileSelected(it) }
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

        repository = Repository.getInstance(requireContext())

        // Determine mode from arguments
        mode = Mode.valueOf(arguments?.getString(ARG_MODE) ?: Mode.ADD_TRUSTED.name)

        // Get existing certificate data if viewing
        arguments?.getString(ARG_TRUSTED_CERT_FINGERPRINT)?.let { fingerprint ->
            lifecycleScope.launch(Dispatchers.IO) {
                trustedCertificate = repository.getTrustedCertificates().find { it.fingerprint == fingerprint }
            }
        }
        arguments?.getString(ARG_CLIENT_CERT_BASE_URL)?.let { baseUrl ->
            clientCertBaseUrl = baseUrl
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
        // Setup toolbar
        toolbar = view.findViewById(R.id.certificate_dialog_toolbar)
        toolbar.setNavigationOnClickListener { dismiss() }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.certificate_dialog_action_save -> {
                    saveClicked()
                    true
                }
                R.id.certificate_dialog_action_delete -> {
                    deleteClicked()
                    true
                }
                else -> false
            }
        }
        saveMenuItem = toolbar.menu.findItem(R.id.certificate_dialog_action_save)
        deleteMenuItem = toolbar.menu.findItem(R.id.certificate_dialog_action_delete)

        // Setup views
        descriptionText = view.findViewById(R.id.certificate_dialog_description)
        baseUrlLayout = view.findViewById(R.id.certificate_dialog_base_url_layout)
        baseUrlText = view.findViewById(R.id.certificate_dialog_base_url_text)
        certFileLayout = view.findViewById(R.id.certificate_dialog_cert_file_layout)
        selectCertButton = view.findViewById(R.id.certificate_dialog_select_cert_button)
        certFileName = view.findViewById(R.id.certificate_dialog_cert_file_name)
        passwordLayout = view.findViewById(R.id.certificate_dialog_password_layout)
        passwordText = view.findViewById(R.id.certificate_dialog_password_text)
        detailsLayout = view.findViewById(R.id.certificate_dialog_details_layout)
        subjectText = view.findViewById(R.id.certificate_dialog_subject)
        issuerText = view.findViewById(R.id.certificate_dialog_issuer)
        fingerprintText = view.findViewById(R.id.certificate_dialog_fingerprint)
        validFromText = view.findViewById(R.id.certificate_dialog_valid_from)
        validUntilText = view.findViewById(R.id.certificate_dialog_valid_until)
        errorText = view.findViewById(R.id.certificate_dialog_error_text)

        // Validate input when typing
        val textWatcher = AfterChangedTextWatcher { validateInput() }
        baseUrlText.addTextChangedListener(textWatcher)
        passwordText.addTextChangedListener(textWatcher)

        // Configure based on mode
        when (mode) {
            Mode.ADD_TRUSTED -> setupAddTrustedMode()
            Mode.ADD_CLIENT -> setupAddClientMode()
            Mode.VIEW_TRUSTED -> setupViewTrustedMode()
            Mode.VIEW_CLIENT -> setupViewClientMode()
        }

        // Initial validation
        validateInput()
    }

    private fun setupAddTrustedMode() {
        toolbar.setTitle(R.string.certificate_dialog_title_add_trusted)
        descriptionText.text = getString(R.string.certificate_dialog_description_add_trusted)
        baseUrlLayout.isVisible = false
        certFileLayout.isVisible = true
        passwordLayout.isVisible = false
        detailsLayout.isVisible = false
        saveMenuItem.setTitle(R.string.certificate_dialog_button_add)
        deleteMenuItem.isVisible = false

        selectCertButton.text = getString(R.string.certificate_dialog_select_cert_file)
        selectCertButton.setOnClickListener { certFilePicker.launch("*/*") }
    }

    private fun setupAddClientMode() {
        toolbar.setTitle(R.string.certificate_dialog_title_add_client)
        descriptionText.text = getString(R.string.certificate_dialog_description_add_client)
        baseUrlLayout.isVisible = true
        certFileLayout.isVisible = true
        passwordLayout.isVisible = true
        detailsLayout.isVisible = false
        saveMenuItem.setTitle(R.string.certificate_dialog_button_add)
        deleteMenuItem.isVisible = false

        selectCertButton.text = getString(R.string.certificate_dialog_select_p12_file)
        selectCertButton.setOnClickListener { pkcs12FilePicker.launch("*/*") }
    }

    private fun setupViewTrustedMode() {
        val cert = trustedCertificate ?: run {
            // Certificate will be loaded asynchronously, set up UI after it loads
            lifecycleScope.launch(Dispatchers.IO) {
                val fingerprint = arguments?.getString(ARG_TRUSTED_CERT_FINGERPRINT)
                trustedCertificate = fingerprint?.let {
                    repository.getTrustedCertificates().find { c -> c.fingerprint == it }
                }
                withContext(Dispatchers.Main) {
                    trustedCertificate?.let { displayTrustedCertDetails(it) } ?: dismiss()
                }
            }
            return
        }
        displayTrustedCertDetails(cert)
    }

    private fun displayTrustedCertDetails(trustedCert: TrustedCertificate) {
        toolbar.setTitle(R.string.certificate_dialog_title_view)
        descriptionText.isVisible = false
        baseUrlLayout.isVisible = false
        certFileLayout.isVisible = false
        passwordLayout.isVisible = false
        detailsLayout.isVisible = true
        saveMenuItem.isVisible = false
        deleteMenuItem.isVisible = true

        try {
            val x509Cert = SSLManager.parsePemCertificate(trustedCert.pem)
            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            subjectText.text = x509Cert.subjectX500Principal.name
            issuerText.text = x509Cert.issuerX500Principal.name
            fingerprintText.text = trustedCert.fingerprint
            validFromText.text = dateFormat.format(x509Cert.notBefore)
            validUntilText.text = dateFormat.format(x509Cert.notAfter)
        } catch (e: Exception) {
            fingerprintText.text = trustedCert.fingerprint
        }
    }

    private fun setupViewClientMode() {
        val baseUrl = clientCertBaseUrl ?: run {
            dismiss()
            return
        }

        toolbar.setTitle(R.string.certificate_dialog_title_view)
        descriptionText.isVisible = false
        baseUrlLayout.isVisible = false
        certFileLayout.isVisible = false
        passwordLayout.isVisible = false
        detailsLayout.isVisible = true
        saveMenuItem.isVisible = false
        deleteMenuItem.isVisible = true

        // Display baseUrl as the identifier
        subjectText.text = baseUrl
        issuerText.isVisible = false
        fingerprintText.isVisible = false
        validFromText.isVisible = false
        validUntilText.isVisible = false
    }

    private fun validateInput() {
        if (!this::saveMenuItem.isInitialized) return

        val baseUrl = baseUrlText.text?.toString()?.trim() ?: ""
        val password = passwordText.text?.toString() ?: ""

        when (mode) {
            Mode.ADD_TRUSTED -> {
                saveMenuItem.isEnabled = certPem != null
            }
            Mode.ADD_CLIENT -> {
                saveMenuItem.isEnabled = validUrl(baseUrl) && pkcs12Data != null && password.isNotEmpty()
            }
            else -> {
                // View modes don't need save validation
            }
        }
    }

    private fun handleCertFileSelected(uri: Uri) {
        try {
            val content = requireContext().contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            }
            if (content != null && content.contains("-----BEGIN CERTIFICATE-----")) {
                certPem = content
                certFileName.text = uri.lastPathSegment ?: "certificate.pem"
                errorText.isVisible = false
                validateInput()
            } else {
                showError(getString(R.string.certificate_dialog_error_invalid_cert))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read certificate file", e)
            showError(getString(R.string.certificate_dialog_error_invalid_cert))
        }
    }

    private fun handlePkcs12FileSelected(uri: Uri) {
        try {
            val data = requireContext().contentResolver.openInputStream(uri)?.use {
                it.readBytes()
            }
            if (data != null && data.isNotEmpty()) {
                pkcs12Data = data
                certFileName.text = uri.lastPathSegment ?: "client.p12"
                errorText.isVisible = false
                validateInput()
            } else {
                showError(getString(R.string.certificate_dialog_error_invalid_p12))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read PKCS#12 file", e)
            showError(getString(R.string.certificate_dialog_error_invalid_p12))
        }
    }

    private fun saveClicked() {
        when (mode) {
            Mode.ADD_TRUSTED -> addTrustedCertificate()
            Mode.ADD_CLIENT -> addClientCertificate()
            else -> { /* View modes don't have save */ }
        }
    }

    private fun deleteClicked() {
        when (mode) {
            Mode.VIEW_TRUSTED -> trustedCertificate?.let { confirmDeleteTrustedCertificate(it.fingerprint) }
            Mode.VIEW_CLIENT -> clientCertBaseUrl?.let { confirmDeleteClientCertificate(it) }
            else -> { /* Add modes don't have delete */ }
        }
    }

    private fun addTrustedCertificate() {
        val certContent = certPem

        // Validate
        if (certContent == null) {
            showError(getString(R.string.certificate_dialog_error_missing_cert))
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val x509Cert = SSLManager.parsePemCertificate(certContent)
                val fingerprint = SSLManager.calculateFingerprint(x509Cert)
                val pem = SSLManager.encodeToPem(x509Cert)
                repository.addTrustedCertificate(fingerprint, pem)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.certificate_dialog_added_toast, Toast.LENGTH_SHORT).show()
                    listener.onCertificateAdded()
                    dismiss()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add trusted certificate", e)
                withContext(Dispatchers.Main) {
                    showError(getString(R.string.certificate_dialog_error_invalid_cert))
                }
            }
        }
    }

    private fun addClientCertificate() {
        val baseUrl = baseUrlText.text.toString().trim()
        val data = pkcs12Data
        val password = passwordText.text.toString()

        // Validate
        if (baseUrl.isEmpty()) {
            showError(getString(R.string.certificate_dialog_error_missing_url))
            return
        }
        if (!validUrl(baseUrl)) {
            showError(getString(R.string.certificate_dialog_error_invalid_url))
            return
        }
        if (data == null) {
            showError(getString(R.string.certificate_dialog_error_missing_p12))
            return
        }
        if (password.isEmpty()) {
            showError(getString(R.string.certificate_dialog_error_missing_password))
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Verify the PKCS#12 can be loaded with the password
                val keyStore = KeyStore.getInstance("PKCS12")
                ByteArrayInputStream(data).use { keyStore.load(it, password.toCharArray()) }

                // Store as base64
                val p12Base64 = Base64.encodeToString(data, Base64.NO_WRAP)
                repository.addClientCertificate(baseUrl, p12Base64, password)

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.certificate_dialog_added_toast, Toast.LENGTH_SHORT).show()
                    listener.onCertificateAdded()
                    dismiss()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add client certificate", e)
                withContext(Dispatchers.Main) {
                    showError(getString(R.string.certificate_dialog_error_invalid_p12_password))
                }
            }
        }
    }

    private fun confirmDeleteTrustedCertificate(fingerprint: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.certificate_dialog_delete_confirm)
            .setPositiveButton(R.string.certificate_dialog_button_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.removeTrustedCertificate(fingerprint)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.certificate_dialog_deleted_toast, Toast.LENGTH_SHORT).show()
                        listener.onCertificateDeleted()
                        dismiss()
                    }
                }
            }
            .setNegativeButton(R.string.certificate_dialog_button_cancel, null)
            .show()
    }

    private fun confirmDeleteClientCertificate(baseUrl: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.certificate_dialog_delete_confirm)
            .setPositiveButton(R.string.certificate_dialog_button_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.removeClientCertificate(baseUrl)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.certificate_dialog_deleted_toast, Toast.LENGTH_SHORT).show()
                        listener.onCertificateDeleted()
                        dismiss()
                    }
                }
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
        private const val ARG_CLIENT_CERT_BASE_URL = "client_cert_base_url"

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

        fun newInstanceViewTrusted(fingerprint: String): CertificateFragment {
            return CertificateFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, Mode.VIEW_TRUSTED.name)
                    putString(ARG_TRUSTED_CERT_FINGERPRINT, fingerprint)
                }
            }
        }

        fun newInstanceViewClient(baseUrl: String): CertificateFragment {
            return CertificateFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, Mode.VIEW_CLIENT.name)
                    putString(ARG_CLIENT_CERT_BASE_URL, baseUrl)
                }
            }
        }
    }
}
