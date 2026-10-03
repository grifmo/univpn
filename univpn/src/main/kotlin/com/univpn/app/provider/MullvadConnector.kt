package com.univpn.app.provider

import android.text.InputType
import android.util.Log
import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MullvadConnector : VpnProviderConnector {
    override val id = "mullvad"
    override val displayName = "Mullvad"
    override val signUpUrl = "https://mullvad.net/account/create"
    override val supportsKeyReuse = true
    override val credentialFields = listOf(
        CredentialField(
            key = "username",
            label = "Account number",
            inputType = InputType.TYPE_CLASS_NUMBER,
            hint = "16-digit account number"
        )
    )

    override suspend fun fetchServers(credentials: ProviderCredentials): ConnectorResult<List<VpnServer>> {
        return try {
            val url = URL("https://api.mullvad.net/www/relays/wireguard/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            if (code != 200) {
                return ConnectorResult.UnknownError(IOException("Unexpected status $code"))
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val arr = org.json.JSONArray(body)
            val servers = mutableListOf<VpnServer>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (!obj.optBoolean("active", false)) continue
                val load = obj.optInt("load", -1)
                if (load >= 80) continue  // filter overloaded
                val hostname = obj.getString("hostname")
                val pubkey = obj.optString("pubkey", "")
                val ipv4 = obj.optString("ipv4_addr_in", "")
                servers.add(VpnServer(
                    id = "$hostname|$pubkey",
                    country = obj.optString("country_name", "Unknown"),
                    city = obj.optString("city_name", "Unknown"),
                    hostname = ipv4.ifEmpty { hostname },
                    serverName = hostname,
                    load = load,
                    owned = obj.optBoolean("owned", true)
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
        return try {
            // Use the provided keypair when the caller is reusing it across profiles,
            // otherwise generate a fresh one.
            val privateKey: String
            val publicKey: String
            if (existingPublicKey != null && existingPrivateKey != null) {
                publicKey  = existingPublicKey
                privateKey = existingPrivateKey
            } else {
                val kp = KeyPair()
                publicKey  = kp.publicKey.toBase64()
                privateKey = kp.privateKey.toBase64()
            }

            val url = URL("https://api.mullvad.net/wg/")
            val payload = "account=${URLEncoder.encode(credentials.username, "UTF-8")}" +
                          "&pubkey=${URLEncoder.encode(publicKey, "UTF-8")}"
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.write(payload.toByteArray())

            val code = conn.responseCode
            if (code == 400) return ConnectorResult.AuthError("Invalid account number or public key")
            if (code == 429) return ConnectorResult.ApiQuota(null)
            if (code !in 200..201) return ConnectorResult.UnknownError(IOException("Status $code"))

            // Response is plain comma-separated IPs: "10.x.x.x,fc00::x/128"
            val responseText = conn.inputStream.bufferedReader().readText().trim()
            conn.disconnect()

            val addresses = responseText.split(",").map { addr ->
                val trimmed = addr.trim()
                when {
                    trimmed.contains('/') -> trimmed
                    trimmed.contains(':') -> "$trimmed/128"
                    else -> "$trimmed/32"
                }
            }.filter { it.isNotEmpty() }

            // Server public key is known from the relay list (stored in server.id as "hostname|pubkey")
            val serverPubKey = server.id.substringAfter("|", "")
            if (serverPubKey.isEmpty()) {
                return ConnectorResult.UnknownError(
                    IllegalStateException("No server public key available for ${server.hostname}")
                )
            }

            val config = buildString {
                appendLine("[Interface]")
                appendLine("PrivateKey = $privateKey")
                if (addresses.isNotEmpty()) appendLine("Address = ${addresses.joinToString(", ")}")
                appendLine("DNS = 10.64.0.1")
                appendLine()
                appendLine("[Peer]")
                appendLine("PublicKey = $serverPubKey")
                appendLine("Endpoint = ${server.hostname}:51820")
                appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            }
            Log.i(TAG, "Mullvad config generated for ${server.city}")
            ConnectorResult.Success(Pair(config, publicKey))
        } catch (e: IOException) {
            ConnectorResult.NetworkError(e)
        } catch (e: Exception) {
            ConnectorResult.UnknownError(e)
        }
    }

    override suspend fun fetchAccountInfo(credentials: ProviderCredentials): ConnectorResult<AccountInfo> {
        return try {
            // Step 1: exchange account number for a short-lived access token.
            // /auth/v1/token "expiry" is the JWT lifetime (~24h), NOT the subscription expiry.
            val tokenConn = URL("https://api.mullvad.net/auth/v1/token")
                .openConnection() as HttpURLConnection
            tokenConn.requestMethod = "POST"
            tokenConn.doOutput = true
            tokenConn.connectTimeout = 10_000
            tokenConn.readTimeout = 15_000
            tokenConn.setRequestProperty("Content-Type", "application/json")
            tokenConn.outputStream.write("""{"account_number":"${credentials.username}"}""".toByteArray())

            val tokenCode = tokenConn.responseCode
            if (tokenCode == 400 || tokenCode == 401) {
                val body = runCatching { tokenConn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                Log.w(TAG, "fetchAccountInfo token $tokenCode: $body")
                return ConnectorResult.AuthError("Invalid account number")
            }
            if (tokenCode !in 200..201) {
                val body = runCatching { tokenConn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                Log.w(TAG, "fetchAccountInfo token $tokenCode: $body")
                return ConnectorResult.UnknownError(IOException("Token status $tokenCode"))
            }
            val accessToken = JSONObject(tokenConn.inputStream.bufferedReader().readText())
                .getString("access_token")
            tokenConn.disconnect()

            // Step 2: fetch account info to get the real subscription expiry.
            val accountConn = URL("https://api.mullvad.net/accounts/v1/accounts/me")
                .openConnection() as HttpURLConnection
            accountConn.connectTimeout = 10_000
            accountConn.readTimeout = 15_000
            accountConn.setRequestProperty("Authorization", "Bearer $accessToken")

            val accountCode = accountConn.responseCode
            if (accountCode != 200) {
                val body = runCatching { accountConn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                Log.w(TAG, "fetchAccountInfo account $accountCode: $body")
                return ConnectorResult.UnknownError(IOException("Account status $accountCode"))
            }
            val accountObj = JSONObject(accountConn.inputStream.bufferedReader().readText())
            accountConn.disconnect()

            val expiry = accountObj.optString("expiry", "")
            val expiryMs = if (expiry.isNotEmpty()) parseIso8601(expiry) else null
            ConnectorResult.Success(AccountInfo(
                usernameHint = credentials.username.takeLast(4).padStart(credentials.username.length, '*'),
                expiryEpochMs = expiryMs
            ))
        } catch (e: IOException) {
            ConnectorResult.NetworkError(e)
        } catch (e: Exception) {
            ConnectorResult.UnknownError(e)
        }
    }

    private fun parseIso8601(s: String): Long? =
        // New API returns "2025-12-01T00:00:00+00:00"; old returned "...Z"
        runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(s)?.time
            }.getOrNull()

    companion object { private const val TAG = "MullvadConnector" }
}
