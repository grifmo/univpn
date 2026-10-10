package com.univpn.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.leanback.app.BrowseSupportFragment
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.univpn.app.R
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.model.ProviderAccount
import com.univpn.app.data.model.TunnelType
import com.univpn.app.data.model.VpnProfile
import com.univpn.app.provider.CredentialStore
import com.univpn.app.provider.ProviderCredentials
import com.univpn.app.provider.ProviderRegistry
import com.univpn.app.server.ConfigImportServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

class ProfilesFragment : Fragment(R.layout.fragment_profiles),
                         BrowseSupportFragment.MainFragmentAdapterProvider {

    private val mainFragAdapter by lazy { BrowseSupportFragment.MainFragmentAdapter(this) }
    override fun getMainFragmentAdapter() = mainFragAdapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var adapter: ProfileAdapter
    private var importServer: ConfigImportServer? = null

    private val dropDir: File
        get() = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        adapter = ProfileAdapter { profile ->
            scope.launch(Dispatchers.IO) {
                AppDatabase.get(ctx).vpnProfileDao().delete(profile)
            }
        }

        view.findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = this@ProfilesFragment.adapter
        }

        view.findViewById<Button>(R.id.importButton).setOnClickListener { scanDropDir() }
        view.findViewById<Button>(R.id.providersButton).setOnClickListener {
            startActivity(Intent(requireContext(), ProviderListActivity::class.java))
        }

        observeProfiles(view)
    }

    override fun onStart() {
        super.onStart()
        startImportServer()
    }

    override fun onStop() {
        stopImportServer()
        super.onStop()
    }

    override fun onDestroyView() {
        scope.cancel()
        super.onDestroyView()
    }

    private fun startImportServer() {
        val view = view ?: return
        val ctx = context ?: return
        val urls = localIpAddresses().map { "http://$it:${ConfigImportServer.PORT}" }
        if (urls.isEmpty()) return

        val credStore = CredentialStore(ctx)
        importServer = ConfigImportServer(
            port = ConfigImportServer.PORT,
            onConfigReceived = { name, content ->
                runCatching {
                    val profile = VpnProfile(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        type = TunnelType.WIREGUARD,
                        configContent = content
                    )
                    runBlocking(Dispatchers.IO) {
                        AppDatabase.get(ctx).vpnProfileDao().insert(profile)
                    }
                }
            },
            onCredentialsReceived = { providerId, username, password ->
                runCatching {
                    val connector = ProviderRegistry.get(providerId)
                        ?: error("Unknown provider: $providerId")
                    val credentials = ProviderCredentials(username, password)
                    val info = runBlocking(Dispatchers.IO) {
                        connector.fetchAccountInfo(credentials)
                    }
                    when (info) {
                        is com.univpn.app.provider.ConnectorResult.Success -> {
                            val accountId = UUID.randomUUID().toString()
                            val account = ProviderAccount(
                                accountId = accountId,
                                providerId = providerId,
                                usernameHint = info.value.usernameHint,
                                expiryEpochMs = info.value.expiryEpochMs
                            )
                            runBlocking(Dispatchers.IO) {
                                AppDatabase.get(ctx).providerAccountDao().upsert(account)
                            }
                            credStore.save(accountId, credentials)
                            "${connector.displayName} account saved (${info.value.usernameHint})"
                        }
                        is com.univpn.app.provider.ConnectorResult.AuthError ->
                            error("Authentication failed: ${info.message}")
                        is com.univpn.app.provider.ConnectorResult.NetworkError ->
                            error("Network error — check your internet connection")
                        is com.univpn.app.provider.ConnectorResult.ApiQuota ->
                            error("Rate limited by provider — try again later")
                        is com.univpn.app.provider.ConnectorResult.UnknownError ->
                            error(info.cause.message ?: "Unknown error")
                    }
                }
            }
        ).also { it.start() }

        view.findViewById<TextView>(R.id.serverUrl).text =
            urls.joinToString("\n") + "\nPIN: ${importServer?.pin}"
        view.findViewById<LinearLayout>(R.id.serverBanner).visibility = View.VISIBLE
    }

    private fun stopImportServer() {
        importServer?.stop()
        importServer = null
        view?.findViewById<LinearLayout>(R.id.serverBanner)?.visibility = View.GONE
    }

    private fun observeProfiles(view: View) {
        val ctx = requireContext()
        val emptyText = view.findViewById<TextView>(R.id.emptyText)
        scope.launch {
            AppDatabase.get(ctx).vpnProfileDao()
                .getAll()
                .flowOn(Dispatchers.IO)
                .collect { profiles ->
                    adapter.submitList(profiles)
                    emptyText.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }

    private fun scanDropDir() {
        val files = dropDir.listFiles { f ->
            f.isFile && f.name.endsWith(".conf", ignoreCase = true)
        }?.sortedBy { it.name } ?: emptyList()

        if (files.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("No WireGuard configs found")
                .setMessage(
                    "Use web import above, or push files via:\n\n" +
                    "adb push myconfig.conf \\\n  /sdcard/Android/data/com.univpn.app/files/\n\n" +
                    "Then tap Scan again."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Found ${files.size} config file(s)")
            .setItems(names) { _, which -> offerImport(files[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun offerImport(file: File) {
        val ctx = requireContext()
        scope.launch(Dispatchers.IO) {
            val content = runCatching { file.readText() }.getOrNull()
            if (content == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "Could not read ${file.name}", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (!content.contains("[Interface]")) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "${file.name} doesn't look like a WireGuard config", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) { promptForName(file.name, content, file) }
        }
    }

    private fun promptForName(filename: String, content: String, sourceFile: File) {
        val ctx = requireContext()
        val defaultName = filename.substringBeforeLast(".")
        val input = EditText(ctx).apply {
            setText(defaultName); selectAll()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or
                         android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        AlertDialog.Builder(ctx)
            .setTitle("Name this WireGuard profile")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { defaultName }
                saveProfile(name, content, sourceFile)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveProfile(name: String, content: String, sourceFile: File) {
        val ctx = requireContext()
        val profile = VpnProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            type = TunnelType.WIREGUARD,
            configContent = content
        )
        scope.launch(Dispatchers.IO) {
            AppDatabase.get(ctx).vpnProfileDao().insert(profile)
            runCatching { sourceFile.delete() }
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "\"$name\" saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun localIpAddresses(): List<String> =
        runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.filter { !it.isLoopback && it.isUp }
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.mapNotNull { it.hostAddress }
                ?.toList()
        }.getOrNull() ?: emptyList()
}
