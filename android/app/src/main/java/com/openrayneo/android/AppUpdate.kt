// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object AppUpdate {
    const val PACKAGE = "com.openrayneo.android.validation"
    private const val ROOT = "https://github.com/JezCH/OpenRayneo/releases/download/"
    private const val LIST = "https://api.github.com/repos/JezCH/OpenRayneo/releases?per_page=30"
    private const val APK = "OpenRayneo-validation.apk"
    private const val MANIFEST = "update-manifest.json"
    private const val MAX_APK = 500L * 1024 * 1024
    private const val MAX_JSON = 2L * 1024 * 1024
    private val tagPattern = Regex("validation-v([1-9][0-9]{0,9})")
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val hosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com",
        "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES).build()
    val supported get() = BuildConfig.APPLICATION_ID == PACKAGE

    data class Candidate(val code: Long, val name: String, val notes: String, val url: String,
        val size: Long, val digest: String, val signer: String)

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun response(url: String): Response {
        var next = url.toHttpUrl()
        repeat(6) {
            check(next.isHttps && next.host in hosts && next.port == 443 &&
                next.username.isEmpty() && next.password.isEmpty()) { "허용되지 않은 다운로드 주소" }
            val res = http.newCall(Request.Builder().url(next).header("User-Agent", "OpenRayneo-validation-updater")
                .header("Accept", if (next.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream").build()).execute()
            if (res.code in listOf(301, 302, 303, 307, 308)) {
                val location = res.header("Location")
                res.close()
                next = checkNotNull(location?.let { next.resolve(it) }) { "잘못된 리디렉션" }
            } else {
                if (!res.isSuccessful) {
                    val code = res.code
                    res.close()
                    error(if (code == 403 || code == 429) "GitHub 요청 제한 또는 접근 불가 ($code). 잠시 후 재시도하세요." else "업데이트 서버 오류 ($code)")
                }
                return res
            }
        }
        error("다운로드 주소 전환 횟수 초과")
    }

    private fun json(url: String): String = response(url).use { res ->
        val body = checkNotNull(res.body)
        check(body.contentLength() <= MAX_JSON) { "업데이트 정보가 너무 큽니다" }
        val source = body.source()
        check(!source.request(MAX_JSON + 1)) { "업데이트 정보가 너무 큽니다" }
        source.readUtf8()
    }

    @Suppress("DEPRECATION")
    fun installedSigner(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = checkNotNull(info.signingInfo).apkContentsSigners
        check(signers.size == 1) { "지원되지 않는 서명 구성" }
        return sha(signers.single().toByteArray())
    }

    private fun asset(release: JSONObject, name: String, tag: String): JSONObject {
        val assets = release.getJSONArray("assets")
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name") == name && it.optString("state") == "uploaded" }
        val a = matches.singleOrNull() ?: error("배포 파일이 없거나 중복됩니다: " + name)
        check(a.getString("browser_download_url") == ROOT + tag + "/" + name) { "배포 주소 불일치" }
        return a
    }

    suspend fun checkLatest(context: Context): Candidate? = withContext(Dispatchers.IO) {
        check(supported && context.packageName == PACKAGE)
        val releases = mutableListOf<Pair<Long, JSONObject>>()
        var exhausted = false
        for (page in 1..5) {
            currentCoroutineContext().ensureActive()
            val list = JSONArray(json(LIST + "&page=" + page))
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                if (item.optBoolean("draft") || !item.optBoolean("prerelease")) continue
                val tag = item.optString("tag_name")
                val code = tagPattern.matchEntire(tag)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                if (code <= Int.MAX_VALUE && code > BuildConfig.VERSION_CODE.toLong()) releases.add(code to item)
            }
            if (list.length() < 30) { exhausted = true; break }
        }
        check(exhausted) { "배포 목록 조회 범위를 초과했습니다. 배포 목록을 정리한 뒤 다시 확인하세요." }
        val (code, release) = releases.maxByOrNull { it.first } ?: return@withContext null
        val tag = release.getString("tag_name")
        val metadata = asset(release, MANIFEST, tag)
        check(metadata.getLong("size") in 1..MAX_JSON)
        val m = JSONObject(json(metadata.getString("browser_download_url")))
        check(m.getInt("schemaVersion") == 1 && m.getString("channel") == "validation" &&
            m.getString("packageName") == PACKAGE && m.getLong("versionCode") == code &&
            m.getString("apkAssetName") == APK && m.getInt("minSdk") <= Build.VERSION.SDK_INT &&
            m.getString("abi") in Build.SUPPORTED_ABIS) { "호환되지 않는 업데이트 정보" }
        val signer = m.getString("signerSha256")
        val digest = m.getString("apkSha256")
        check(digestPattern.matches(signer) && digestPattern.matches(digest) && signer == installedSigner(context)) {
            "현재 앱과 업데이트의 서명이 다릅니다. 기존 앱을 삭제하지 마세요."
        }
        val a = asset(release, APK, tag)
        val size = m.getLong("apkSize")
        check(size in 1..MAX_APK && a.getLong("size") == size) { "APK 크기 불일치" }
        val githubDigest = a.optString("digest", "")
        check(githubDigest.isEmpty() || githubDigest == "null" || githubDigest == "sha256:" + digest) { "APK 배포 해시 불일치" }
        Candidate(code, m.getString("versionName").take(100), release.optString("body").take(4000),
            a.getString("browser_download_url"), size, digest, signer)
    }

    suspend fun download(context: Context, candidate: Candidate, progress: suspend (Long) -> Unit): File = withContext(Dispatchers.IO) {
        check(supported && candidate.code > BuildConfig.VERSION_CODE && candidate.signer == installedSigner(context))
        val dir = File(context.cacheDir, "app-update").apply { mkdirs() }
        check(dir.usableSpace > candidate.size * 2 + 16 * 1024 * 1024) { "설치에 필요한 저장 공간이 부족합니다" }
        val part = File.createTempFile("validation-", ".part", dir)
        val apk = File(dir, part.name.removeSuffix(".part") + ".apk")
        var complete = false
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            response(candidate.url).use { res ->
                val body = checkNotNull(res.body)
                check(body.contentLength() < 0 || body.contentLength() == candidate.size) { "APK 다운로드 크기 불일치" }
                body.byteStream().use { input ->
                    FileOutputStream(part).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var reported = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= candidate.size) { "APK 크기 초과" }
                            hash.update(buffer, 0, count)
                            out.write(buffer, 0, count)
                            if (total - reported >= 512 * 1024 || total == candidate.size) {
                                reported = total
                                progress(total)
                            }
                        }
                        out.fd.sync()
                        check(total == candidate.size && hex(hash.digest()) == candidate.digest) { "APK 파일 무결성 불일치" }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            check(part.renameTo(apk)) { "다운로드 파일 저장 실패" }
            verifyArchive(context, candidate, apk)
            complete = true
            apk
        } finally {
            part.delete()
            if (!complete) apk.delete()
        }
    }

    @Suppress("DEPRECATION")
    fun verifyArchive(context: Context, candidate: Candidate, apk: File) {
        check(apk.length() == candidate.size)
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("APK 파일을 읽을 수 없습니다")
        val signers = checkNotNull(info.signingInfo).apkContentsSigners
        check(info.packageName == PACKAGE && info.longVersionCode == candidate.code &&
            info.longVersionCode > BuildConfig.VERSION_CODE && info.versionName == candidate.name &&
            signers.size == 1 && sha(signers.single().toByteArray()) == candidate.signer &&
            candidate.signer == installedSigner(context)) { "APK 패키지·버전·서명 검증 실패" }
    }
}
