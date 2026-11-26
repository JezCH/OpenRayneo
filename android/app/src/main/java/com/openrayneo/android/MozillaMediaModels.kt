// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** Model-only APK asset I/O. No network, captured audio, transcript or credentials. */
internal class MozillaMediaModels(private val context: Context) {
    private data class Component(val role: String, val name: String, val wireBytes: Long, val bytes: Long,
                                 val hash: String, val url: String, val gzip: Boolean) {
        val installedName get() = hash + if (name.endsWith(".spm")) ".spm" else ".bin"
    }
    private data class Model(val id: String, val source: String, val files: List<Component>)
    private val catalog by lazy { readCatalog() }
    private val root get() = File(context.noBackupFilesDir, "mozilla-media-models-v1")
    fun configurations(language: String, cancelled: () -> Boolean,
                       progress: (LocalMediaTranslationPhase, Long, Long) -> Unit): LinkedHashMap<String, String> {
        val models = MediaTranslationRoute.steps(language).map { checkNotNull(catalog[it]) }
        require(models.isNotEmpty()) { "번역이 필요한 원문 언어를 선택하세요" }
        val directory = root
        check(directory.isDirectory || directory.mkdirs()) { "번역 모델 저장 공간을 준비하지 못했습니다" }
        val files = models.flatMap { it.files }.distinctBy { it.installedName }
        progress(LocalMediaTranslationPhase.VERIFYING, 0, 0)
        val missing = files.filterNot { file -> verify(File(directory, file.installedName), file, cancelled) }
        val total = missing.sumOf { it.bytes }
        if (missing.isNotEmpty()) {
            check(directory.usableSpace > total + 8 * 1024 * 1024L) { "번역 모델을 준비할 저장 공간이 부족합니다" }
            var completed = 0L
            for (file in missing) {
                checkCancelled(cancelled)
                installBundled(directory, file, cancelled) { received ->
                    progress(LocalMediaTranslationPhase.VERIFYING, completed + received, total)
                }
                completed += file.bytes
                progress(LocalMediaTranslationPhase.VERIFYING, completed, total)
            }
        }
        checkCancelled(cancelled)
        return linkedMapOf<String, String>().also { configs ->
            for (model in models) {
                val prefix = File(directory, "prefix-${model.source}.txt")
                val prefixData = if (model.source in setOf("en", "zh"))
                    context.assets.open("mozilla/prefixes/" + model.source + ".txt").use { it.readBytes() }
                else ByteArray(0)
                if (!prefix.isFile || !prefix.readBytes().contentEquals(prefixData)) atomicWrite(prefix, prefixData)
                fun path(role: String) = JSONObject.quote(File(directory, model.files.single { it.role == role }.installedName).absolutePath)
                val sv = if (model.files.any { it.role == "vocab" }) "vocab" else "srcvocab"
                val tv = if (sv == "vocab") "vocab" else "trgvocab"
                // Follows Ngoroth v0.2.0 MozillaTranslationCatalog, including separate EN-KO vocabularies.
                val yaml = """
                    models: [${path("model")}]
                    vocabs: [${path(sv)}, ${path(tv)}]
                    shortlist: [${path("lex")}, false]
                    ssplit-prefix-file: ${JSONObject.quote(prefix.absolutePath)}
                    ssplit-mode: paragraph
                    beam-size: 1
                    normalize: 1.0
                    word-penalty: 0.0
                    max-length-factor: 2.0
                    max-length-break: 128
                    mini-batch-words: 1024
                    workspace: 128
                    cpu-threads: 4
                    gemm-precision: int8shiftAll
                    skip-cost: true
                    alignment: soft
                    quiet: true
                    quiet-translation: true
                """.trimIndent() + "\n"
                val config = File(directory, "route-${model.id}.yml")
                atomicWrite(config, yaml.toByteArray(Charsets.UTF_8))
                configs[model.id] = config.absolutePath
            }
        }
    }

    private fun readCatalog(): Map<String, Model> {
        val root = JSONObject(context.assets.open("mozilla-media-models.json").bufferedReader().use { it.readText() })
        val base = root.getString("baseUrl")
        require(base == "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data")
        val raw = root.optJSONObject("rawAttachments") ?: JSONObject()
        val routes = root.getJSONArray("routes")
        return (0 until routes.length()).associate { index ->
            val row = routes.getJSONObject(index)
            val source = row.getString("source").let { if (it == "zh-Hans") "zh" else it }
            val id = source + "-" + row.getString("target")
            require(id in setOf("en-ko", "ja-en", "zh-en", "ko-en"))
            require(row.getString("version") == "3.1")
            val directory = row.getString("directory")
            require(directory.startsWith("models/") && directory.endsWith("/exported/") &&
                directory.matches(Regex("[A-Za-z0-9_./-]+")) && directory.split('/').none { it == "." || it == ".." })
            val array = row.getJSONArray("files")
            val files = (0 until array.length()).map { i ->
                val f = array.getJSONArray(i)
                val role = f.getString(0).lowercase(java.util.Locale.ROOT).let { if (it == "lexicalshortlist") "lex" else it }
                val name = f.getString(1)
                val compressed = f.getLong(2)
                val bytes = f.getLong(3)
                val hash = f.getString(4)
                val generation = f.getString(5)
                require(role in setOf("model", "lex", "vocab", "srcvocab", "trgvocab"))
                require(name.matches(Regex("[A-Za-z0-9_.-]+")) && name.endsWith(if (role.endsWith("vocab")) ".spm" else ".bin"))
                require(bytes in 1..134217728L && compressed in 1..134217728L && hash.matches(Regex("[a-f0-9]{64}")) &&
                    generation.matches(Regex("[0-9]+")))
                val attachment = raw.optString(hash).takeIf { it.isNotBlank() }
                if (attachment != null) require(attachment.matches(Regex("https://firefox-settings-attachments\\.cdn\\.mozilla\\.net/main-workspace/translations-models/[A-Za-z0-9_-]+\\.(bin|spm)")))
                Component(role, name, if (attachment == null) compressed else bytes, bytes, hash,
                    attachment ?: "$base/$directory$name.gz?generation=$generation", attachment == null)
            }
            require(files.map { it.role }.toSet().let { roles -> roles.size == files.size &&
                roles in setOf(setOf("model", "lex", "vocab"), setOf("model", "lex", "srcvocab", "trgvocab")) })
            id to Model(id, source, files)
        }.also { require(it.keys == setOf("en-ko", "ja-en", "zh-en", "ko-en")) }
    }

    private fun verify(target: File, file: Component, cancelled: () -> Boolean): Boolean {
        if (!target.isFile || target.length() != file.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        target.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                checkCancelled(cancelled)
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest()) == file.hash
    }

    /** Copy the exact signed APK model. Never contact a network or silently change models. */
    private fun installBundled(directory: File, file: Component, cancelled: () -> Boolean, progress: (Long) -> Unit) {
        val target = File(directory, file.installedName)
        val temp = File.createTempFile("model-", ".part", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            context.assets.open("bundled-translation/" + file.installedName).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkCancelled(cancelled)
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        check(written <= file.bytes) { "번역 모델 크기 초과" }
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        if (written % 262144L < count || written == file.bytes) progress(written)
                    }
                    check(written == file.bytes && hex(digest.digest()) == file.hash) { "번역 모델 무결성 검증 실패" }
                    output.fd.sync()
                }
            }
            checkCancelled(cancelled)
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temp = File.createTempFile("config-", ".part", target.parentFile)
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    companion object {
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
        private fun checkCancelled(cancelled: () -> Boolean) {
            if (cancelled()) throw java.util.concurrent.CancellationException()
        }
    }
}
