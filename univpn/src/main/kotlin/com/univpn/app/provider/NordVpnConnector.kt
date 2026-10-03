package com.univpn.app.provider

import android.text.InputType
import android.util.Log
import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class NordVpnConnector : VpnProviderConnector {
    override val id = "nordvpn"
    override val displayName = "NordVPN"
    override val signUpUrl = "https://nordvpn.com/sign-up/"
    override val credentialFields = listOf(
        CredentialField(
            key = "username",
            label = "Email address",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        ),
        CredentialField(
            key = "password",
            label = "Password",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
    )

    override suspend fun fetchServers(credentials: ProviderCredentials): ConnectorResult<List<VpnServer>> {
        val tokenResult = authenticate(credentials)
        if (tokenResult !is ConnectorResult.Success) return tokenResult as ConnectorResult<List<VpnServer>>
        val token = tokenResult.value

        return try {
            // Fetch recommended WireGuard servers, limit to top 200 by load
            val url = URL(
                "https://api.nordvpn.com/v1/servers/recommendations" +
                "?filters[servers_technologies][identifier]=wireguard_udp" +
                "&limit=200"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            if (code == 401) return ConnectorResult.AuthError("Token expired")
            if (code != 200) return ConnectorResult.UnknownError(IOException("Status $code"))
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val arr = org.json.JSONArray(body)
            val servers = mutableListOf<VpnServer>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val load = obj.optInt("load", 100)
                if (load >= 80) continue
                val locations = obj.optJSONArray("locations") ?: continue
                if (locations.length() == 0) continue
                val loc = locations.getJSONObject(0)
                val country = loc.optJSONObject("country")?.optString("name", "Unknown") ?: "Unknown"
                val city = loc.optJSONObject("country")?.optJSONObject("city")?.optString("name", "") ?: ""
                val hostname = obj.optString("hostname", "")
                val serverName = obj.optString("name")
                if (hostname.isEmpty()) continue
                // Extract WireGuard endpoint from technologies
                val techs = obj.optJSONArray("technologies") ?: continue
                var endpoint = ""
                for (j in 0 until techs.length()) {
                    val tech = techs.getJSONObject(j)
                    if (tech.optString("identifier") == "wireguard_udp") {
                        val metadata = tech.optJSONArray("metadata") ?: continue
                        for (k in 0 until metadata.length()) {
                            val m = metadata.getJSONObject(k)
                            if (m.optString("name") == "public_key") {
                                endpoint = m.optString("value", "")
                            }
                        }
                    }
                }
                if (endpoint.isEmpty()) continue
                servers.add(VpnServer(
                    id = "$hostname|$endpoint",  // hostname|serverPubKey
                    country = country,
                    city = city.ifEmpty { country },
                    hostname = hostname,
                    serverName = serverName,
                    load = load
                ))
            }
            ConnectorResult.Success(servers.sortedBy { it.load })
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
        val tokenResult = authenticate(credentials)
        if (tokenResult !is ConnectorResult.Success) return tokenResult as ConnectorResult<Pair<String, String>>
        val token = tokenResult.value

        return try {
            val keyPair = KeyPair()
            val publicKey = keyPair.publicKey.toBase64()
            val privateKey = keyPair.privateKey.toBase64()

            // Register WireGuard public key
            val regUrl = URL("https://api.nordvpn.com/v1/users/keys")
            val regConn = regUrl.openConnection() as HttpURLConnection
            regConn.requestMethod = "POST"
            regConn.doOutput = true
            regConn.connectTimeout = 10_000
            regConn.setRequestProperty("Authorization", "Bearer $token")
            regConn.setRequestProperty("Content-Type", "application/json")
            val regBody = "{\"public_key\":\"$publicKey\"}"
            regConn.outputStream.write(regBody.toByteArray())
            val regCode = regConn.responseCode
            if (regCode == 401) return ConnectorResult.AuthError("Token expired")
            if (regCode == 429) return ConnectorResult.ApiQuota(null)
            if (regCode !in 200..201) return ConnectorResult.UnknownError(IOException("Key reg status $regCode"))
            val regResponse = regConn.inputStream.bufferedReader().readText()
            regConn.disconnect()

            val regJson = JSONObject(regResponse)
            val clientIp = regJson.optString("ipv4_address", "")
            val interfaceIpv6 = regJson.optString("ipv6_address", "")

            // server.id = "hostname|serverPubKey"
            val parts = server.id.split("|")
            val serverPubKey = if (parts.size == 2) parts[1] else ""

            val config = buildString {
                appendLine("[Interface]")
                appendLine("PrivateKey = $privateKey")
                if (clientIp.isNotEmpty()) appendLine("Address = $clientIp/32")
                if (interfaceIpv6.isNotEmpty()) appendLine("Address = $interfaceIpv6/128")
                appendLine("DNS = 103.86.96.100, 103.86.99.100")
                appendLine()
                appendLine("[Peer]")
                appendLine("PublicKey = $serverPubKey")
                appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
                appendLine("Endpoint = ${server.hostname}:51820")
                appendLine("PersistentKeepalive = 25")
            }
            Log.i(TAG, "NordVPN config generated for ${server.city}")
            ConnectorResult.Success(Pair(config, publicKey))
        } catch (e: IOException) {
            ConnectorResult.NetworkError(e)
        } catch (e: Exception) {
            ConnectorResult.UnknownError(e)
        }
    }

    override suspend fun fetchAccountInfo(credentials: ProviderCredentials): ConnectorResult<AccountInfo> {
        val tokenResult = authenticate(credentials)
        if (tokenResult !is ConnectorResult.Success) return tokenResult as ConnectorResult<AccountInfo>
        return ConnectorResult.Success(AccountInfo(usernameHint = credentials.username))
    }

    private fun authenticate(credentials: ProviderCredentials): ConnectorResult<String> {
        return try {
            val url = URL("https://api.nordvpn.com/v1/users/auth")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.setRequestProperty("Content-Type", "application/json")
            val body = "{\"username\":\"${credentials.username}\",\"password\":\"${credentials.password}\"}"
            conn.outputStream.write(body.toByteArray())
            val code = conn.responseCode
            if (code == 401) return ConnectorResult.AuthError("Invalid email or password")
            if (code == 429) return ConnectorResult.ApiQuota(null)
            if (code != 200) return ConnectorResult.UnknownError(IOException("Auth status $code"))
            val resp = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val token = JSONObject(resp).optString("token", "")
            if (token.isEmpty()) ConnectorResult.UnknownError(IOException("No token in response"))
            else ConnectorResult.Success(token)
        } catch (e: IOException) {
            ConnectorResult.NetworkError(e)
        } catch (e: Exception) {
            ConnectorResult.UnknownError(e)
        }
    }

    companion object { private const val TAG = "NordVpnConnector" }
}
