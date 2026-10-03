package com.univpn.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.univpn.app.R
import com.univpn.app.provider.ProviderRegistry
import com.univpn.app.provider.VpnServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ProviderSetupFragment : Fragment(R.layout.fragment_provider_setup) {

    private val viewModel: ProviderSetupViewModel by viewModels()
    private lateinit var serverAdapter: ServerAdapter
    private lateinit var countryAdapter: CountryAdapter
    private var allServers: List<VpnServer> = emptyList()
    private val fieldViews = mutableMapOf<String, EditText>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val providerId = requireArguments().getString(ARG_PROVIDER_ID)!!
        val accountId = requireArguments().getString(ARG_ACCOUNT_ID)
        val connector = ProviderRegistry.get(providerId)!!

        // Build credential fields dynamically
        val container = view.findViewById<LinearLayout>(R.id.credentialFields)
        view.findViewById<TextView>(R.id.providerTitle).text = connector.displayName
        val fields = connector.credentialFields
        fields.forEachIndexed { index, field ->
            val label = TextView(requireContext()).apply {
                text = field.label
                setTextColor(Color.parseColor("#888888"))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 4.dp }
            }
            val isLast = index == fields.lastIndex
            val edit = EditText(requireContext()).apply {
                inputType = field.inputType
                hint = field.hint
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#555555"))
                imeOptions = if (isLast)
                    android.view.inputmethod.EditorInfo.IME_ACTION_DONE or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                else
                    android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 16.dp }
            }
            edit.id = View.generateViewId()
            label.labelFor = edit.id
            container.addView(label)
            container.addView(edit)
            fieldViews[field.key] = edit
        }

        val connectBtn = view.findViewById<Button>(R.id.btnConnect)
        fieldViews.values.lastOrNull()?.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val imm = v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                connectBtn.performClick()
                true
            } else false
        }

        connectBtn.setOnClickListener {
            val values = fieldViews.mapValues { it.value.text.toString() }
            viewModel.connect(connector, values)
        }

        view.findViewById<TextView>(R.id.btnNoAccount).setOnClickListener {
            showQrCode(view, connector.signUpUrl, "Scan to sign up for ${connector.displayName}")
        }

        serverAdapter = ServerAdapter(onClick = { server -> viewModel.selectServer(server) })
        view.findViewById<RecyclerView>(R.id.serverRecycler).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = serverAdapter
        }

        countryAdapter = CountryAdapter { country ->
            val countryServers = allServers.filter { it.country == country.name }
            serverAdapter.submitList(countryServers)
            view.findViewById<LinearLayout>(R.id.serverDrillDown).visibility = View.VISIBLE
            view.findViewById<RecyclerView>(R.id.countryRecycler).visibility = View.GONE
            view.findViewById<TextView>(R.id.drillDownCountryLabel).text =
                "${country.name} — press Back to return to countries"
            view.findViewById<RecyclerView>(R.id.serverRecycler).requestFocus()
        }
        view.findViewById<RecyclerView>(R.id.countryRecycler).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = countryAdapter
        }

        view.findViewById<Button>(R.id.btnQrDismiss).setOnClickListener {
            view.findViewById<LinearLayout>(R.id.viewQr).visibility = View.GONE
            view.findViewById<LinearLayout>(R.id.viewCredentials).requestFocus()
        }

        view.findViewById<Button>(R.id.btnViewProfile).setOnClickListener {
            requireActivity().finish()
        }

        // If returning to an already-configured provider, skip credential entry
        if (accountId != null && viewModel.uiState.value == ProviderSetupUiState.CredentialEntry) {
            viewModel.connectWithStoredCredentials(connector, accountId)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state -> renderState(view, state) }
        }
    }

    private fun renderState(view: View, state: ProviderSetupUiState) {
        val vCreds = view.findViewById<LinearLayout>(R.id.viewCredentials)
        val vLoad = view.findViewById<LinearLayout>(R.id.viewLoading)
        val vPicker = view.findViewById<LinearLayout>(R.id.viewServerPicker)
        val vSuccess = view.findViewById<LinearLayout>(R.id.viewSuccess)
        val errBanner = view.findViewById<TextView>(R.id.errorBanner)

        vCreds.visibility = View.GONE
        vLoad.visibility = View.GONE
        vPicker.visibility = View.GONE
        vSuccess.visibility = View.GONE
        errBanner.visibility = View.GONE

        when (state) {
            is ProviderSetupUiState.CredentialEntry -> {
                vCreds.visibility = View.VISIBLE
                fieldViews.values.firstOrNull()?.requestFocus()
            }
            is ProviderSetupUiState.FetchingServers -> {
                vLoad.visibility = View.VISIBLE
                view.findViewById<TextView>(R.id.loadingText).text = "Fetching servers…"
            }
            is ProviderSetupUiState.AccountWarning -> {
                vPicker.visibility = View.VISIBLE
                view.findViewById<TextView>(R.id.warningBanner).apply {
                    text = state.message
                    visibility = View.VISIBLE
                }
                showServerPicker(view, state.servers)
                view.findViewById<RecyclerView>(R.id.countryRecycler).requestFocus()
            }
            is ProviderSetupUiState.ServerPicker -> {
                vPicker.visibility = View.VISIBLE
                showServerPicker(view, state.servers)
                view.findViewById<RecyclerView>(R.id.countryRecycler).requestFocus()
            }
            is ProviderSetupUiState.GeneratingConfig -> {
                vLoad.visibility = View.VISIBLE
                view.findViewById<TextView>(R.id.loadingText).text = "Generating configuration…"
            }
            is ProviderSetupUiState.Success -> {
                vSuccess.visibility = View.VISIBLE
                view.findViewById<TextView>(R.id.successDetail).text = state.profileName
                val btnView = view.findViewById<Button>(R.id.btnViewProfile)
                btnView.requestFocus()
                viewLifecycleOwner.lifecycleScope.launch {
                    for (seconds in 3 downTo 1) {
                        btnView.text = "View Profile ($seconds)"
                        delay(1_000)
                    }
                    if (isAdded) requireActivity().finish()
                }
            }
            is ProviderSetupUiState.ErrorBadCredentials -> {
                vCreds.visibility = View.VISIBLE
                errBanner.text = "Incorrect credentials. Please try again."
                errBanner.visibility = View.VISIBLE
                fieldViews["password"]?.apply { text.clear(); requestFocus() }
                    ?: fieldViews["username"]?.requestFocus()
            }
            is ProviderSetupUiState.ErrorNetworkOffline -> {
                vCreds.visibility = View.VISIBLE
                errBanner.text = "No internet connection. Please check your network and try again."
                errBanner.visibility = View.VISIBLE
                view.findViewById<Button>(R.id.btnConnect).requestFocus()
            }
            is ProviderSetupUiState.ErrorApiQuota -> {
                vCreds.visibility = View.VISIBLE
                errBanner.text = "Too many attempts. Please wait a few minutes and try again."
                errBanner.visibility = View.VISIBLE
                view.findViewById<Button>(R.id.btnConnect).requestFocus()
            }
            is ProviderSetupUiState.ErrorGeneric -> {
                vCreds.visibility = View.VISIBLE
                errBanner.text = "Error: ${state.message}"
                errBanner.visibility = View.VISIBLE
                view.findViewById<Button>(R.id.btnConnect).requestFocus()
            }
        }
    }

    private fun showServerPicker(view: View, servers: List<VpnServer>) {
        allServers = servers
        val noServers = view.findViewById<TextView>(R.id.noServersText)
        if (servers.isEmpty()) {
            noServers.visibility = View.VISIBLE
            view.findViewById<RecyclerView>(R.id.countryRecycler).visibility = View.GONE
            return
        }
        noServers.visibility = View.GONE
        // Reset to country level
        view.findViewById<RecyclerView>(R.id.countryRecycler).visibility = View.VISIBLE
        view.findViewById<LinearLayout>(R.id.serverDrillDown).visibility = View.GONE
        // Build country list sorted alphabetically
        val countries = servers.groupBy { it.country }
            .map { (name, list) -> CountryItem(name, list.size) }
            .sortedBy { it.name }
        countryAdapter.submitList(countries)
    }

    fun onBackPressed(): Boolean {
        val drillDown = view?.findViewById<LinearLayout>(R.id.serverDrillDown) ?: return false
        val countryList = view?.findViewById<RecyclerView>(R.id.countryRecycler) ?: return false
        return if (drillDown.visibility == View.VISIBLE) {
            drillDown.visibility = View.GONE
            countryList.visibility = View.VISIBLE
            countryList.requestFocus()
            true
        } else false
    }

    private fun showQrCode(view: View, url: String, hint: String) {
        try {
            val size = 600
            val bits = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) for (y in 0 until size) {
                bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
            }
            view.findViewById<ImageView>(R.id.qrImage).setImageBitmap(bmp)
            view.findViewById<TextView>(R.id.qrHint).text = hint
            view.findViewById<LinearLayout>(R.id.viewQr).visibility = View.VISIBLE
            view.findViewById<Button>(R.id.btnQrDismiss).requestFocus()
        } catch (_: Exception) { }
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_PROVIDER_ID = "provider_id"
        private const val ARG_ACCOUNT_ID = "account_id"
        fun newInstance(providerId: String, accountId: String? = null) = ProviderSetupFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PROVIDER_ID, providerId)
                if (accountId != null) putString(ARG_ACCOUNT_ID, accountId)
            }
        }
    }
}
