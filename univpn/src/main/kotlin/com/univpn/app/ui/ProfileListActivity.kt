package com.univpn.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.univpn.app.R
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.model.TunnelType
import com.univpn.app.data.model.VpnProfile
import com.univpn.app.server.ConfigImportServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

class ProfileListActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var adapter: ProfileAdapter
    private lateinit var emptyText: TextView
    private lateinit var serverBanner: LinearLayout
    private lateinit var serverUrlText: TextView

    private var importServer: ConfigImportServer? = null

    private val dropDir: File get() = getExternalFilesDir(null) ?: filesDir

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_list)

        emptyText = findViewById(R.id.emptyText)
        serverBanner = findViewById(R.id.serverBanner)
        serverUrlText = findViewById(R.id.serverUrl)

        adapter = ProfileAdapter { profile ->
            scope.launch(Dispatchers.IO) {
                AppDatabase.get(this@ProfileListActivity).vpnProfileDao().delete(profile)
            }
        }

        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@ProfileListActivity)
            adapter = this@ProfileListActivity.adapter
        }

        findViewById<Button>(R.id.importButton).setOnClickListener { scanDropDir() }

        observeProfiles()
    }

    override fun onStart() {
        super.onStart()
        startImportServer()
    }

    override fun onStop() {
        stopImportServer()
        super.onStop()
    }

    private fun startImportServer() {
        val urls = localIpAddresses().map { "http://$it:${ConfigImportServer.PORT}" }
        if (urls.isEmpty()) return

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
                        AppDatabase.get(this@ProfileListActivity).vpnProfileDao().insert(profile)
                    }
                }
            },
            onCredentialsReceived = { _, _, _ ->
                Result.failure(UnsupportedOperationException("Use the Profiles screen to add provider accounts"))
            }
        ).also { it.start() }

        serverUrlText.text = urls.joinToString("\n") + "\nPIN: ${importServer?.pin}"
        serverBanner.visibility = View.VISIBLE
    }

    private fun stopImportServer() {
        importServer?.stop()
        importServer = null
        serverBanner.visibility = View.GONE
    }

    private fun observeProfiles() {
        scope.launch {
            AppDatabase.get(this@ProfileListActivity).vpnProfileDao()
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
            AlertDialog.Builder(this)
                .setTitle("No WireGuard configs found")
                .setMessage(
                    "Either use the web importer above, or push files manually:\n\n" +
                    "adb push myconfig.conf \\\n  /sdcard/Android/data/com.univpn.app/files/\n\n" +
                    "Then tap Scan again."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Found ${files.size} config file(s)")
            .setItems(names) { _, which -> offerImport(files[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun offerImport(file: File) {
        scope.launch(Dispatchers.IO) {
            val content = runCatching { file.readText() }.getOrNull()
            if (content == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ProfileListActivity, "Could not read ${file.name}", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (!content.contains("[Interface]")) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ProfileListActivity, "${file.name} doesn't look like a WireGuard config", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) { promptForName(file.name, content, file) }
        }
    }

    private fun promptForName(filename: String, content: String, sourceFile: File) {
        val defaultName = filename.substringBeforeLast(".")
        val input = EditText(this).apply {
            setText(defaultName); selectAll()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or
                         android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        AlertDialog.Builder(this)
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
        val profile = VpnProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            type = TunnelType.WIREGUARD,
            configContent = content
        )
        scope.launch(Dispatchers.IO) {
            AppDatabase.get(this@ProfileListActivity).vpnProfileDao().insert(profile)
            runCatching { sourceFile.delete() }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ProfileListActivity, "\"$name\" saved", Toast.LENGTH_SHORT).show()
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
