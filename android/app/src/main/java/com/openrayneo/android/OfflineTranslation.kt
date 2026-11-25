// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/** Explicit Wi-Fi model preparation, then on-device translation. No API key or cloud inference fallback. */
internal class OfflineTranslation(private val main: Handler, private val changed: () -> Unit) {
    private val koEn = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.KOREAN).setTargetLanguage(TranslateLanguage.ENGLISH).build())
    private val enKo = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.KOREAN).build())
    var ready = false; private set
    var preparing = false; private set
    var status = "한영 번역 모델 준비 필요"; private set
    private var closed = false
    fun prepare() {
        if (closed || preparing || ready) return
        preparing = true; status = "Wi-Fi에서 번역 모델 준비 중"; changed()
        val conditions = DownloadConditions.Builder().requireWifi().build()
        koEn.downloadModelIfNeeded(conditions).addOnSuccessListener {
            main.post {
                if (closed) return@post
                enKo.downloadModelIfNeeded(conditions).addOnSuccessListener { main.post {
                    if (!closed) { preparing = false; ready = true; status = "오프라인 한영 번역 준비됨"; changed() }
                } }.addOnFailureListener { main.post { failed() } }
            }
        }.addOnFailureListener { main.post { failed() } }
    }
    private fun failed() { if (!closed) { preparing = false; ready = false; status = "모델 준비 실패 · Wi-Fi 및 저장공간 확인"; changed() } }
    fun translate(text: String, korean: Boolean, result: (Result<String>) -> Unit) {
        if (!ready || closed) { result(Result.failure(IllegalStateException("번역 모델 준비 필요"))); return }
        (if (korean) koEn else enKo).translate(text).addOnSuccessListener { translated ->
            main.post { if (!closed) result(Result.success(translated)) }
        }.addOnFailureListener { error -> main.post { if (!closed) result(Result.failure(error)) } }
    }
    fun close() { closed = true; koEn.close(); enKo.close() }
}
