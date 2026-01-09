package io.heckel.ntfy.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.Base64
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
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Full-screen dialog fragment for adding and viewing client certificates (mTLS).
 *
 * Modes:
 * - ADD: Two-page flow - first enter password and base URL, then view details and save
 * - VIEW: Shows certificate details with Delete action
 */
class ClientCertificateFragment : DialogFragment() {
    private lateinit var repository: Repository
    private var listener: ClientCertificateListener? = null

    private var mode: Mode = Mode.ADD
    private var currentPage: Int = 1
    private var pkcs12Data: ByteArray? = null
    private var baseUrl: String? = null
    private var password: String? = null
    private var extractedCert: X509Certificate? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var nextMenuItem: MenuItem
    private lateinit var saveMenuItem: MenuItem
    private lateinit var deleteMenuItem: MenuItem

    // Page 1 views
    private lateinit var page1Layout: LinearLayout
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var passwordText: TextInputEditText
    private lateinit var baseUrlLayout: TextInputLayout
    private lateinit var baseUrlText: TextInputEditText
    private lateinit var errorLayout: LinearLayout
    private lateinit var errorText: TextView

    // Page 2 views
    private lateinit var page2Layout: LinearLayout
    private lateinit var descriptionPage2Text: TextView
    private lateinit var baseUrlValueText: TextView
    private lateinit var subjectText: TextView
    private lateinit var issuerText: TextView
    private lateinit var fingerprintText: TextView
    private lateinit var validFromText: TextView
    private lateinit var validUntilText: TextView

    interface ClientCertificateListener {
        fun onCertificateAdded()
        fun onCertificateDeleted()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is ClientCertificateListener -> parentFragment as ClientCertificateListener
            context is ClientCertificateListener -> context
            else -> null
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        repository = Repository.getInstance(requireContext())

        // Determine mode from arguments
        mode = Mode.valueOf(arguments?.getString(ARG_MODE) ?: Mode.ADD.name)

        when (mode) {
            Mode.ADD -> {
                pkcs12Data = arguments?.getByteArray(ARG_PKCS12_DATA)
                    ?: throw IllegalArgumentException("PKCS#12 data required for ADD mode")
            }
            Mode.VIEW -> {
                baseUrl = arguments?.getString(ARG_BASE_URL)
                    ?: throw IllegalArgumentException("Base URL required for VIEW mode")
            }
        }

        // Build the view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_client_certificate_dialog, null)
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
        toolbar = view.findViewById(R.id.client_certificate_toolbar)
        toolbar.setNavigationOnClickListener { handleBack() }
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.client_certificate_action_next -> {
                    nextClicked()
                    true
                }
                R.id.client_certificate_action_save -> {
                    saveClicked()
                    true
                }
                R.id.client_certificate_action_delete -> {
                    deleteCertificate()
                    true
                }
                else -> false
            }
        }
        nextMenuItem = toolbar.menu.findItem(R.id.client_certificate_action_next)
        saveMenuItem = toolbar.menu.findItem(R.id.client_certificate_action_save)
        deleteMenuItem = toolbar.menu.findItem(R.id.client_certificate_action_delete)

        // Page 1 views
        page1Layout = view.findViewById(R.id.client_certificate_page1)
        passwordLayout = view.findViewById(R.id.client_certificate_password_layout)
        passwordText = view.findViewById(R.id.client_certificate_password_text)
        baseUrlLayout = view.findViewById(R.id.client_certificate_base_url_layout)
        baseUrlText = view.findViewById(R.id.client_certificate_base_url_text)
        errorLayout = view.findViewById(R.id.client_certificate_error_layout)
        errorText = view.findViewById(R.id.client_certificate_error_text)

        // Page 2 views
        page2Layout = view.findViewById(R.id.client_certificate_page2)
        descriptionPage2Text = view.findViewById(R.id.client_certificate_description_page2)
        baseUrlValueText = view.findViewById(R.id.client_certificate_base_url_value)
        subjectText = view.findViewById(R.id.client_certificate_subject)
        issuerText = view.findViewById(R.id.client_certificate_issuer)
        fingerprintText = view.findViewById(R.id.client_certificate_fingerprint)
        validFromText = view.findViewById(R.id.client_certificate_valid_from)
        validUntilText = view.findViewById(R.id.client_certificate_valid_until)

        // Validate input when typing
        val textWatcher = AfterChangedTextWatcher { validatePage1() }
        passwordText.addTextChangedListener(textWatcher)
        baseUrlText.addTextChangedListener(textWatcher)

        when (mode) {
            Mode.ADD -> setupAddMode()
            Mode.VIEW -> setupViewMode()
        }
    }

    private fun setupAddMode() {
        toolbar.setTitle(R.string.client_certificate_dialog_title_add)
        toolbar.setNavigationIcon(R.drawable.ic_close_white_24dp)
        showPage1()
    }

    private fun setupViewMode() {
        toolbar.setTitle(R.string.client_certificate_dialog_title)
        toolbar.setNavigationIcon(R.drawable.ic_close_white_24dp)
        page1Layout.isVisible = false
        page2Layout.isVisible = true
        nextMenuItem.isVisible = false
        saveMenuItem.isVisible = false
        deleteMenuItem.isVisible = true

        // Hide description for view mode
        descriptionPage2Text.isVisible = false

        // Load certificate from repository
        lifecycleScope.launch(Dispatchers.IO) {
            val clientCert = repository.getClientCertificate(baseUrl!!)
            if (clientCert != null) {
                try {
                    val p12Data = Base64.decode(clientCert.p12Base64, Base64.DEFAULT)
                    val keyStore = KeyStore.getInstance("PKCS12")
                    ByteArrayInputStream(p12Data).use { keyStore.load(it, clientCert.password.toCharArray()) }
                    val alias = keyStore.aliases().nextElement()
                    val cert = keyStore.getCertificate(alias) as X509Certificate

                    withContext(Dispatchers.Main) {
                        displayCertificateDetails(baseUrl!!, cert)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        baseUrlValueText.text = baseUrl
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
        saveMenuItem.isVisible = false
        deleteMenuItem.isVisible = false
        validatePage1()
    }

    private fun showPage2() {
        currentPage = 2
        page1Layout.isVisible = false
        page2Layout.isVisible = true
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white_24dp)
        nextMenuItem.isVisible = false
        saveMenuItem.isVisible = true
        deleteMenuItem.isVisible = false

        displayCertificateDetails(baseUrl!!, extractedCert!!)
    }

    private fun validatePage1() {
        val url = baseUrlText.text?.toString()?.trim() ?: ""
        val pwd = passwordText.text?.toString() ?: ""
        nextMenuItem.isEnabled = validUrl(url) && pwd.isNotEmpty()
    }

    private fun nextClicked() {
        val url = baseUrlText.text?.toString()?.trim() ?: ""
        val pwd = passwordText.text?.toString() ?: ""

        if (!validUrl(url)) {
            showError(getString(R.string.client_certificate_dialog_error_invalid_url))
            return
        }

        // Try to load the PKCS#12 with the provided password
        try {
            val keyStore = KeyStore.getInstance("PKCS12")
            ByteArrayInputStream(pkcs12Data!!).use { keyStore.load(it, pwd.toCharArray()) }
            val alias = keyStore.aliases().nextElement()
            extractedCert = keyStore.getCertificate(alias) as X509Certificate
            baseUrl = url
            password = pwd
            errorLayout.isVisible = false
            showPage2()
        } catch (e: Exception) {
            showError(getString(R.string.client_certificate_dialog_error_wrong_password))
        }
    }

    private fun saveClicked() {
        val url = baseUrl ?: return
        val data = pkcs12Data ?: return
        val pwd = password ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val p12Base64 = Base64.encodeToString(data, Base64.NO_WRAP)
                repository.addClientCertificate(url, p12Base64, pwd)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.common_certificate_added_toast, Toast.LENGTH_SHORT).show()
                    listener?.onCertificateAdded()
                    dismiss()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError(getString(R.string.client_certificate_dialog_error_invalid_p12_password))
                }
            }
        }
    }

    private fun deleteCertificate() {
        val url = baseUrl ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            repository.removeClientCertificate(url)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.common_certificate_deleted_toast, Toast.LENGTH_SHORT).show()
                listener?.onCertificateDeleted()
                dismiss()
            }
        }
    }

    private fun handleBack() {
        when {
            mode == Mode.VIEW -> dismiss()
            currentPage == 2 -> showPage1()
            else -> dismiss()
        }
    }

    private fun displayCertificateDetails(url: String, certificate: X509Certificate) {
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

        baseUrlValueText.text = url
        subjectText.text = certificate.subjectX500Principal.name
        issuerText.text = certificate.issuerX500Principal.name
        fingerprintText.text = CertUtil.calculateFingerprint(certificate)
        validFromText.text = dateFormat.format(certificate.notBefore)
        validUntilText.text = dateFormat.format(certificate.notAfter)
    }

    private fun showError(message: String) {
        errorText.text = message
        errorLayout.isVisible = true
    }

    enum class Mode {
        ADD,
        VIEW
    }

    companion object {
        const val TAG = "NtfyClientCertFragment"
        private const val ARG_MODE = "mode"
        private const val ARG_PKCS12_DATA = "pkcs12_data"
        private const val ARG_BASE_URL = "base_url"

        /**
         * Create fragment for ADD mode - two-page flow to add a client certificate
         */
        fun newInstanceAdd(pkcs12Data: ByteArray): ClientCertificateFragment {
            return ClientCertificateFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, Mode.ADD.name)
                    putByteArray(ARG_PKCS12_DATA, pkcs12Data)
                }
            }
        }

        /**
         * Create fragment for VIEW mode - showing certificate details with Delete action
         */
        fun newInstanceView(baseUrl: String): ClientCertificateFragment {
            return ClientCertificateFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, Mode.VIEW.name)
                    putString(ARG_BASE_URL, baseUrl)
                }
            }
        }
    }
}

