// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Official OSS Sign in with ChatGPT. Separate encrypted storage; never exposes
 * OAuth tokens through profiles, diagnostics, exports, DSH, or another provider.
 * Single account registration in this initial Android integration. */
internal object ChatGptSubscription {
    private const val AUTH = "https://auth.openai.com"
    private const val RESOURCE = "https://api.openai.com/v1"
    private const val SCOPE = "chatgpt.tokens.use.direct"
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()
    private val lock = Any()
    private var store: AppSettings? = null
    @Volatile private var saved = JSONObject()
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var cancelling = false
    @Volatile private var exchanging = false
    @Volatile private var active = false
    private val random = SecureRandom()
    data class Model(val id: String, val name: String)
    data class Status(val registered: Boolean, val connected: Boolean, val models: List<Model>)

    fun initialize(context: Context) = synchronized(lock) {
        if (store == null) {
            val settings = AppSettings(context.applicationContext, "chatgpt_subscription")
            val value = settings.readStored()
            if (!value.has("host")) {
                value.put("host", "urn:uuid:" + UUID.randomUUID())
                settings.save(value)
            }
            saved = value
            store = settings
        }
    }
    private fun persist(value: JSONObject) {
        checkNotNull(store) { "ChatGPT 인증 저장소 초기화 실패" }.save(value)
        saved = value
    }
    private fun copy() = JSONObject(saved.toString())
    private fun JSONObject.scopeGranted() = optString("scope").split(Regex("\\s+")).contains(SCOPE)
    private fun catalog(value: JSONObject): List<Model> {
        val list = value.optJSONArray("catalog") ?: JSONArray()
        return (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let { row ->
                val id = row.optString("slug")
                val name = row.optString("display_name")
                if (id.isNotBlank() && name.isNotBlank() && id.length <= 256 &&
                    !id.contains("ultrafast", true)) Model(id, name.take(100)) else null
            }
        }.distinctBy { it.id }
    }
    fun status(): Status {
        val value = saved
        val connected = value.scopeGranted() && value.optString("access").isNotBlank() &&
            value.optString("refresh").isNotBlank()
        return Status(value.optString("client").isNotBlank(), connected,
            if (connected) catalog(value) else emptyList())
    }
    fun hasModel(id: String): Boolean = !active && status().models.any { it.id == id }
    private fun randomValue() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun decode(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
    private fun encode(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun readJson(request: Request): JSONObject = http.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "ChatGPT 연결 실패 (HTTP ${response.code})" }
        val source = checkNotNull(response.body).source()
        check(!source.request(2L * 1024 * 1024 + 1)) { "ChatGPT 응답 크기 초과" }
        JSONObject(source.readUtf8())
    }
    private fun form(url: String, fields: Map<String, String>): Request {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return Request.Builder().url(url).post(body).header("Accept", "application/json").build()
    }
    private fun verifyIdentity(token: String, client: String, nonce: String): String {
        val parts = token.split('.')
        check(parts.size == 3 && token.length <= 32_768) { "ID 토큰 형식 오류" }
        val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
        check(header.optString("alg") == "RS256" && header.optString("kid").isNotBlank()) { "지원하지 않는 ID 토큰 서명" }
        val jwks = readJson(Request.Builder().url("$AUTH/.well-known/jwks.json").build()).getJSONArray("keys")
        val matches = (0 until jwks.length()).map { jwks.getJSONObject(it) }.filter {
            it.optString("kid") == header.optString("kid") && it.optString("kty") == "RSA" &&
                it.optString("use", "sig") == "sig" && it.optString("alg", "RS256") == "RS256"
        }
        check(matches.size == 1) { "ID 토큰 서명키 확인 실패" }
        val jwk = matches.single()
        val n = BigInteger(1, decode(jwk.getString("n")))
        check(n.bitLength() >= 2048) { "ID 토큰 서명키 길이 오류" }
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, BigInteger(1, decode(jwk.getString("e")))))
        check(Signature.getInstance("SHA256withRSA").run {
            initVerify(key); update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII)); verify(decode(parts[2]))
        }) { "ID 토큰 서명 불일치" }
        val claims = JSONObject(String(decode(parts[1]), Charsets.UTF_8))
        val audience = claims.optJSONArray("aud")
        val accepted = if (audience == null) claims.optString("aud") == client
            else (0 until audience.length()).any { audience.optString(it) == client } &&
                (audience.length() == 1 || claims.optString("azp") == client)
        val now = System.currentTimeMillis() / 1000
        check(claims.optString("iss") == AUTH && accepted && claims.optString("nonce") == nonce &&
            claims.optLong("exp") > now && claims.optLong("nbf", 0) <= now &&
            claims.optString("sub").isNotBlank()) { "ChatGPT 계정 인증 검증 실패" }
        return claims.getString("sub")
    }
    private fun updatedTokens(value: JSONObject, result: JSONObject, previousScope: String = ""): JSONObject {
        val access = result.opt("access_token") as? String ?: error("ChatGPT 액세스 토큰 없음")
        val refresh = result.opt("refresh_token") as? String ?: error("ChatGPT 갱신 토큰 없음")
        val scope = result.optString("scope", previousScope)
        val expires = result.getLong("expires_in")
        check(access.isNotBlank() && refresh.isNotBlank() && expires in 1..31_536_000 &&
            scope.split(Regex("\\s+")).contains(SCOPE)) { "ChatGPT 구독 추론 권한 또는 갱신 정보 없음" }
        return value.put("access", access).put("refresh", refresh).put("scope", scope)
            .put("expires", System.currentTimeMillis() + expires * 1000L)
    }
    private fun clearTokens(value: JSONObject): JSONObject {
        for (key in listOf("access", "refresh", "expires", "scope", "catalog")) value.remove(key)
        return value
    }

    /** Only this method supplies a bearer for the fixed OpenAI Responses endpoint. */
    fun accessFor(model: String): String = synchronized(lock) {
        check(hasModel(model)) { "ChatGPT 구독 모델을 다시 확인하세요" }
        freshAccess()
    }
    private fun freshAccess(): String {
        val value = copy()
        check(value.scopeGranted() && value.optString("refresh").isNotBlank()) { "ChatGPT 구독 로그인 필요" }
        if (value.optLong("expires") > System.currentTimeMillis() + 180_000 && value.optString("access").isNotBlank())
            return value.getString("access")
        val request = form("$AUTH/api/accounts/oauth/token", mapOf("grant_type" to "refresh_token",
            "client_id" to value.getString("client"), "refresh_token" to value.getString("refresh"), "resource" to RESOURCE))
        val result = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val code = runCatching {
                    val source = checkNotNull(response.body).source()
                    check(!source.request(65_537))
                    val error = JSONObject(source.readUtf8()).opt("error")
                    if (error is JSONObject) error.optString("code") else error as? String
                }.getOrNull()
                if (code in setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")) {
                    persist(clearTokens(value))
                    error("ChatGPT 구독 재로그인 필요")
                }
                error("ChatGPT 갱신 실패 (HTTP ${response.code})")
            }
            val source = checkNotNull(response.body).source()
            check(!source.request(1_048_577)) { "ChatGPT 갱신 응답 크기 초과" }
            JSONObject(source.readUtf8())
        }
        val renewed = try { updatedTokens(value, result, value.optString("scope")) }
            catch (_: Exception) { persist(clearTokens(value)); error("ChatGPT 구독 권한 갱신 실패 · 다시 로그인하세요") }
        persist(renewed)
        return renewed.getString("access")
    }
    private fun fetchCatalog(access: String): JSONArray {
        val models = readJson(Request.Builder().url("$RESOURCE/models")
            .header("Authorization", "Bearer $access").build()).getJSONArray("models")
        val visible = JSONArray()
        for (i in 0 until minOf(models.length(), 500)) {
            val m = models.getJSONObject(i)
            if (m.optString("visibility") == "list") visible.put(JSONObject()
                .put("slug", m.getString("slug")).put("display_name", m.getString("display_name")))
        }
        return visible
    }
    fun refreshCatalog() = synchronized(lock) {
        check(!active) { "ChatGPT 로그인 이미 진행 중" }
        active = true
        try { val access = freshAccess(); persist(copy().put("catalog", fetchCatalog(access))) }
        finally { active = false }
    }

    fun cancelSignIn() {
        if (!exchanging) { cancelling = true; runCatching { listener?.close() } }
    }
    /** Run off UI thread. Browser opens only after a loopback listener is bound.
     * No copied codes, cookie access, passwords, or backend-api endpoints. */
    fun signIn(openBrowser: (String) -> Unit) {
        synchronized(lock) {
            check(store != null && !active) { "ChatGPT 로그인 이미 진행 중" }
            active = true; cancelling = false; exchanging = false
        }
        try {
            val old = copy()
            val verifier = randomValue(); val nonce = randomValue(); val state = randomValue()
            val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
            val client = old.optString("client").ifBlank { "dynamic_agent_client" }
            val url = "$AUTH/api/accounts/authorize".toHttpUrl().newBuilder().apply {
                val params = mapOf("client_id" to client, "ext_agent_host_id" to old.getString("host"),
                    "response_type" to "code", "redirect_uri" to redirect, "resource" to RESOURCE,
                    "scope" to "openid profile email offline_access resource.invoke $SCOPE",
                    "state" to state, "nonce" to nonce, "code_challenge_method" to "S256",
                    "code_challenge" to encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))))
                params.forEach { (k, v) -> addQueryParameter(k, v) }
                if (client == "dynamic_agent_client") addQueryParameter("agent_name_hint", "OpenRayneo")
            }.build()
            val callback = server.use {
                listener = server
                check(!cancelling) { "ChatGPT 로그인 취소" }
                server.soTimeout = 1000
                openBrowser(url.toString())
                receive(server, redirect, state)
            }
            listener = null
            check(!cancelling) { "ChatGPT 로그인 취소" }
            exchanging = true
            val issued = callback.second.ifBlank { client }
            check(issued.startsWith("oaiapp_") && issued.length <= 256 &&
                (client == "dynamic_agent_client" || issued == client)) { "ChatGPT 발급 클라이언트 확인 실패" }
            val result = readJson(form("$AUTH/api/accounts/oauth/token", mapOf("grant_type" to "authorization_code",
                "client_id" to issued, "code" to callback.first, "code_verifier" to verifier,
                "redirect_uri" to redirect, "resource" to RESOURCE)))
            val subject = verifyIdentity(result.getString("id_token"), issued, nonce)
            check(old.optString("subject").let { it.isBlank() || it == subject }) { "기존 등록 계정과 다른 ChatGPT 계정" }
            val next = updatedTokens(JSONObject().put("host", old.getString("host")).put("client", issued)
                .put("subject", subject), result)
            synchronized(lock) {
                // A catalog failure must not make the validated, renewable grant disappear.
                persist(next)
                persist(copy().put("catalog", fetchCatalog(next.getString("access"))))
            }
        } finally {
            listener = null
            synchronized(lock) { active = false; exchanging = false }
        }
    }
    private fun line(input: InputStream, limit: Int): String {
        val result = StringBuilder()
        while (result.length < limit) {
            val n = input.read()
            if (n == -1) error("HTTP callback EOF")
            if (n == 10) return result.toString().trimEnd('\r')
            result.append(n.toChar())
        }
        error("HTTP callback too long")
    }
    private fun receive(server: ServerSocket, redirect: String, state: String): Pair<String, String> {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15 * 60_000
        while (!cancelling && android.os.SystemClock.elapsedRealtime() < deadline) {
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use {
                socket.soTimeout = 1500
                val callback = runCatching {
                    val input = socket.getInputStream()
                    val parts = line(input, 8192).split(' ')
                    check(parts.size == 3 && parts[0] == "GET" && parts[1].startsWith("/auth/callback?"))
                    val target = (redirect.substringBefore("/auth/callback") + parts[1]).toHttpUrl()
                    check(target.encodedPath == "/auth/callback" && target.queryParameterValues("state").singleOrNull() == state)
                    check(target.queryParameter("error") == null)
                    val code = target.queryParameterValues("code").singleOrNull()
                    check(!code.isNullOrBlank())
                    code to target.queryParameterValues("client_id").singleOrNull().orEmpty()
                }.getOrNull()
                val body = if (callback != null) "Authorization received. Return to OpenRayneo." else "Invalid callback."
                val bytes = body.toByteArray(Charsets.UTF_8)
                runCatching { socket.getOutputStream().apply {
                    write(("HTTP/1.1 " + (if (callback != null) "200 OK" else "400 Bad Request") +
                        "\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\nContent-Length: " +
                        bytes.size + "\r\n\r\n").toByteArray(Charsets.US_ASCII)); write(bytes); flush()
                } }
                if (callback != null) return callback
            }
        }
        error("ChatGPT 로그인 취소 또는 시간 초과")
    }

    /** Stops the local grant even if the server is unreachable. Keep registration. */
    fun signOut(): Boolean = synchronized(lock) {
        check(!active) { "로그인을 먼저 종료하세요" }
        active = true
        try {
        val value = copy()
        val refresh = value.optString("refresh")
        val revoked = refresh.isBlank() || runCatching {
            val endpoint = readJson(Request.Builder().url("$AUTH/.well-known/openid-configuration").build())
                .getString("revocation_endpoint").toHttpUrl()
            check(endpoint.scheme == "https" && endpoint.host == "auth.openai.com" && endpoint.port == 443 &&
                endpoint.username.isEmpty() && endpoint.password.isEmpty())
            http.newCall(form(endpoint.toString(), mapOf("token" to refresh, "token_type_hint" to "refresh_token",
                "client_id" to value.getString("client")))).execute().use { it.code == 200 }
        }.getOrDefault(false)
        persist(clearTokens(value))
        revoked
        } finally { active = false }
    }
}
