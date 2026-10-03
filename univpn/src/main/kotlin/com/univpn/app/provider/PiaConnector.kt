package com.univpn.app.provider

import android.text.InputType
import android.util.Log
import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory

class PiaConnector : VpnProviderConnector {
    override val id = "pia"
    override val displayName = "Private Internet Access"
    override val signUpUrl = "https://www.privateinternetaccess.com/pages/create-account"
    override val credentialFields = listOf(
        CredentialField(
            key = "username",
            label = "Username",
            inputType = InputType.TYPE_CLASS_TEXT
        ),
        CredentialField(
            key = "password",
            label = "Password",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
    )

    override suspend fun fetchServers(credentials: ProviderCredentials): ConnectorResult<List<VpnServer>> {
        return try {
            val url = URL("https://serverlist.piaservers.net/vpninfo/servers/v6")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            if (code != 200) return ConnectorResult.UnknownError(IOException("Status $code"))
            val raw = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            // Response format: JSON followed by a signature, separated by a newline
            val jsonPart = raw.substringBefore("\n\n").ifEmpty { raw.substringBefore("\n") }.ifEmpty { raw }
            val root = JSONObject(jsonPart.trim())
            val regions = root.optJSONArray("regions") ?: return ConnectorResult.Success(emptyList())

            val servers = mutableListOf<VpnServer>()
            for (i in 0 until regions.length()) {
                val region = regions.getJSONObject(i)
                val country = region.optString("country", "")
                val regionName = region.optString("name", country)
                val serversObj = region.optJSONObject("servers") ?: continue
                val wgServers = serversObj.optJSONArray("wg") ?: continue
                for (j in 0 until wgServers.length()) {
                    val s = wgServers.getJSONObject(j)
                    val ip = s.optString("ip", "")
                    val pubKey = s.optString("cn", "")
                    if (ip.isEmpty()) continue
                    servers.add(VpnServer(
                        id = "$ip|$pubKey",
                        country = regionName,
                        city = regionName,
                        hostname = ip,
                        serverName = pubKey,
                        load = -1
                    ))
                    break  // one server per region is enough for display
                }
            }
            ConnectorResult.Success(servers.sortedBy { it.country })
        } catch (e: IOException) {
            ConnectorResult.NetworkError(e)
        } catch (e: Exception) {
            ConnectorResult.UnknownError(e)
        }
    }

    override suspend fun generateConfig(
        server: VpnServer,
        credentials: ProviderCredentials,
        existingPublicKey: String?,
        existingPrivateKey: String?
    ): ConnectorResult<Pair<String, String>> {
        return try {
            // Get auth token
            val tokenResult = getToken(credentials)
            if (tokenResult !is ConnectorResult.Success) return tokenResult as ConnectorResult<Pair<String, String>>
            val token = tokenResult.value

            val keyPair = KeyPair()
            val publicKey = keyPair.publicKey.toBase64()
            val privateKey = keyPair.privateKey.toBase64()

            // server.id = "ip|cn" — cn is the server's TLS hostname (e.g. "amsterdam439")
            val parts = server.id.split("|")
            val serverIp = parts[0]
            val serverCn = if (parts.size == 2) parts[1] else ""
            if (serverCn.isEmpty()) {
                return ConnectorResult.UnknownError(IllegalStateException("PIA server has no hostname; refresh the server list"))
            }

            // addKey is served on the raw IP with a cert issued by PIA's private CA for the
            // region hostname. Mirror pia-foss/manual-connections (curl --cacert ca.rsa.4096.crt
            // --connect-to cn::ip:): trust only PIA's CA and require the cert to name serverCn.
            val regUrl = URL("https://$serverIp:1337/addKey?pt=${URLEncoder.encode(token, "UTF-8")}&pubkey=${URLEncoder.encode(publicKey, "UTF-8")}")
            val conn = (regUrl.openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = piaSslContext.socketFactory
                setHostnameVerifier { _, session -> certNamesHost(session, serverCn) }
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            if (code == 401) return ConnectorResult.AuthError("Invalid credentials or token expired")
            if (code != 200) return ConnectorResult.UnknownError(IOException("Key reg status $code"))
            val resp = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(resp)
            Log.d(TAG, "addKey response keys: ${json.keys().asSequence().toList()}")
            Log.d(TAG, "addKey response: $resp")

            // PIA uses "server_key" in their open-source scripts; fall back to "server_pubkey"
            val peerPubKey = json.optString("server_key", "")
                .ifEmpty { json.optString("server_pubkey", "") }
            if (peerPubKey.isEmpty()) {
                return ConnectorResult.UnknownError(
                    IllegalStateException("addKey response missing server public key. Response: $resp")
                )
            }

            // peer_ip from PIA already includes the subnet (e.g. "10.8.0.1/32") — use as-is
            val clientIp = json.optString("peer_ip", "")
            val dnsArr = json.optJSONArray("dns_servers")
            val dnsServer = if (dnsArr != null && dnsArr.length() > 0)
                dnsArr.getString(0)
            else
                json.optString("dns_servers", "10.0.0.241").split(",").firstOrNull()?.trim() ?: "10.0.0.241"
            val wgEndpointIp = json.optString("server_ip", serverIp)
            val wgPort = json.optInt("server_port", 1337)

            val config = buildString {
                appendLine("[Interface]")
                appendLine("PrivateKey = $privateKey")
                if (clientIp.isNotEmpty()) appendLine("Address = $clientIp")
                appendLine("DNS = $dnsServer")
                appendLine()
                appendLine("[Peer]")
                appendLine("PublicKey = $peerPubKey")
                appendLine("AllowedIPs = 0.0.0.0/0")
                appendLine("Endpoint = $wgEndpointIp:$wgPort")
                appendLine("PersistentKeepalive = 25")
            }
            Log.i(TAG, "PIA config generated for ${server.country}, endpoint=$wgEndpointIp:$wgPort, peer=$peerPubKey")
            ConnectorResult.Success(Pair(config, publicKey))
        } catch (e: IOException) {
            ConnectorResult.NetworkError(e)
        } catch (e: Exception) {
            ConnectorResult.UnknownError(e)
        }
    }

    override suspend fun fetchAccountInfo(credentials: ProviderCredentials): ConnectorResult<AccountInfo> {
        return ConnectorResult.Success(AccountInfo(usernameHint = credentials.username))
    }

    private fun getToken(credentials: ProviderCredentials): ConnectorResult<String> {
        return try {
            val url = URL("https://www.privateinternetaccess.com/api/client/v2/token")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = "username=${URLEncoder.encode(credentials.username, "UTF-8")}&password=${URLEncoder.encode(credentials.password, "UTF-8")}"
            conn.outputStream.write(body.toByteArray())
            val code = conn.responseCode
            if (code == 401) return ConnectorResult.AuthError("Invalid username or password")
            if (code == 429) return ConnectorResult.ApiQuota(null)
            if (code != 200) return ConnectorResult.UnknownError(IOException("Auth status $code"))
            val resp = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val token = JSONObject(resp).optString("token", "")
            if (token.isEmpty()) ConnectorResult.UnknownError(IOException("No token"))
            else ConnectorResult.Success(token)
        } catch (e: IOException) {
            ConnectorResult.NetworkError(e)
        } catch (e: Exception) {
            ConnectorResult.UnknownError(e)
        }
    }

    companion object {
        private const val TAG = "PiaConnector"
        private const val CA_RESOURCE = "/pia/ca.rsa.4096.crt"

        /** TLS context that trusts only PIA's CA (bundled from pia-foss/manual-connections). */
        private val piaSslContext: SSLContext by lazy {
            val ca = PiaConnector::class.java.getResourceAsStream(CA_RESOURCE)
                ?.use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
                ?: error("Missing $CA_RESOURCE")
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("pia", ca)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)
            SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        }

        /** True if the leaf cert's DNS SANs (or CN, as a fallback) equal [host]. */
        private fun certNamesHost(session: SSLSession, host: String): Boolean {
            val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return false
            val sanNames = leaf.subjectAlternativeNames.orEmpty()
                .filter { it[0] == 2 }          // dNSName
                .map { it[1] as String }
            if (sanNames.isNotEmpty()) return sanNames.any { it.equals(host, ignoreCase = true) }
            val cn = leaf.subjectX500Principal.name.split(",")
                .firstOrNull { it.trim().startsWith("CN=") }?.substringAfter("CN=")?.trim()
            return cn.equals(host, ignoreCase = true)
        }
    }
}
