package com.minwook.pleoswork

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class DeviceGrant(@SerializedName("device_code") val deviceCode: String,
    @SerializedName("user_code") val userCode: String, @SerializedName("verification_uri") val verificationUri: String,
    @SerializedName("verification_uri_complete") val verificationUriComplete: String?,
    @SerializedName("expires_in") val expiresIn: Long, val interval: Long = 5)
data class WorkTokens(@SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String?, @SerializedName("expires_in") val expiresIn: Long,
    @SerializedName("token_type") val tokenType: String, @SerializedName("account_id") val accountId: String,
    var storedAt: Long = 0, var issuer: String = "")
class WorkApiFailure(val code: String, val httpStatus: Int = 0) : Exception(code)

/** Credential encryption is separate from retained work records. No client secret in the APK. */
class WorkCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("work-credentials-v2", Context.MODE_PRIVATE)
    private val alias = "pleos-work-device-credentials-v2"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized fun save(tokens: WorkTokens) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(OperationsCodec.gson.toJson(tokens).toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    @Synchronized fun load(): WorkTokens? = runCatching {
        val raw = prefs.getString("data", null) ?: return null
        val iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        OperationsCodec.gson.fromJson(String(cipher.doFinal(Base64.decode(raw, Base64.NO_WRAP)), Charsets.UTF_8), WorkTokens::class.java)
    }.getOrNull()
    @Synchronized fun clear() { prefs.edit().clear().commit() }
}

/** Carrier adapter, not a Pleos authentication API. HTTP is restricted to the debug loopback fixture. */
class WorkServiceClient(val baseUrl: String) {
    companion object {
        fun allowedBase(url: String, debug: Boolean): Boolean = runCatching {
            val u = URI(url)
            u.host != null && u.userInfo == null && u.rawQuery == null && u.rawFragment == null &&
                (u.scheme == "https" || debug && u.scheme == "http" && u.host in setOf("127.0.0.1", "localhost"))
        }.getOrDefault(false)
    }
    private suspend fun request(path: String, form: Map<String, String>? = null, token: String? = null): JsonObject = withContext(Dispatchers.IO) {
        if (!allowedBase(baseUrl, BuildConfig.DEBUG)) throw WorkApiFailure("endpoint_not_configured")
        val c = URI(baseUrl.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000; c.readTimeout = 5_000; c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (form != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(form.entries.joinToString("&") { (k,v) ->
                    URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val input = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = ByteArrayOutputStream()
            input?.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = stream.read(buffer); if (n < 0) break
                    if (bytes.size() + n > 4_000_000) throw WorkApiFailure("response_too_large")
                    bytes.write(buffer,0,n)
                }
            }
            val data = runCatching { OperationsCodec.gson.fromJson(bytes.toString("UTF-8"), JsonObject::class.java) }.getOrNull() ?: JsonObject()
            if (code !in 200..299) throw WorkApiFailure(data.get("error")?.asString?.take(80) ?: "http_$code", code)
            data
        } finally { c.disconnect() }
    }
    suspend fun begin(): DeviceGrant {
        val grant = OperationsCodec.gson.fromJson(request("/oauth/device_authorization", mapOf("client_id" to "pleos-work-car",
            "scope" to "work:read cargo:read")), DeviceGrant::class.java)
        require(grant.deviceCode.isNotBlank() && grant.userCode.isNotBlank() && grant.expiresIn in 30..1800 && grant.interval in 1..60)
        require(allowedBase(grant.verificationUri, BuildConfig.DEBUG))
        grant.verificationUriComplete?.let { require(URI(it).host == URI(grant.verificationUri).host && URI(it).scheme == URI(grant.verificationUri).scheme && URI(it).port == URI(grant.verificationUri).port && URI(it).userInfo == null) }
        return grant
    }
    private fun tokens(json: JsonObject): WorkTokens {
        val result = OperationsCodec.gson.fromJson(json, WorkTokens::class.java)
        require(result.accessToken.isNotBlank() && result.tokenType.equals("Bearer", true) && result.expiresIn in 30..86_400 && result.accountId.isNotBlank())
        return result.copy(storedAt = System.currentTimeMillis(), issuer = baseUrl)
    }
    suspend fun poll(grant: DeviceGrant) = tokens(request("/oauth/token", mapOf("client_id" to "pleos-work-car",
        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to grant.deviceCode)))
    suspend fun refresh(token: WorkTokens) = tokens(request("/oauth/token", mapOf("client_id" to "pleos-work-car",
        "grant_type" to "refresh_token", "refresh_token" to (token.refreshToken ?: throw WorkApiFailure("invalid_grant")))))
    suspend fun snapshot(token: WorkTokens): WorkLedger {
        val result = OperationsCodec.decode(request("/v1/work/snapshot", token = token.accessToken).toString())
            ?: throw WorkApiFailure("invalid_work_payload")
        require(result.accountId == token.accountId)
        return result
    }
    suspend fun changes(token: WorkTokens, cursor: Long): WorkBatch = OperationsCodec.gson.fromJson(
        request("/v1/work/events?after=$cursor", token = token.accessToken), WorkBatch::class.java)
    suspend fun revoke(token: WorkTokens) { request("/oauth/revoke", mapOf("token" to (token.refreshToken ?: token.accessToken))) }
}
