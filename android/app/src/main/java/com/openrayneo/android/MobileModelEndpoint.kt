// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import java.net.URI
import java.net.URISyntaxException

/** Matches the app's existing cleartext policy: only phone loopback can use HTTP. */
internal object MobileModelEndpoint {
    private val loopbackHosts = setOf("127.0.0.1", "localhost")

    fun isLocalAddress(address: String): Boolean {
        val uri = try { URI(address.trim()) } catch (_: URISyntaxException) { return false }
        return uri.scheme?.lowercase() in setOf("http", "https") &&
            uri.host?.lowercase() in loopbackHosts && uri.userInfo == null && uri.query == null && uri.fragment == null
    }

    fun isConfigured(address: String, model: String, apiKey: String): Boolean =
        address.isNotBlank() && model.isNotBlank() && (apiKey.isNotBlank() || isLocalAddress(address))

    fun resolve(address: String, apiKey: String): String {
        val base = address.trim().trimEnd('/')
        val uri = URI(base)
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "모델 주소는 전체 서비스 주소여야 하며 키나 쿼리 매개변수를 포함하면 안 됨"
        }
        require(uri.scheme?.lowercase() == "https" || uri.scheme?.lowercase() == "http" && isLocalAddress(base)) {
            "원격 모델은 HTTPS를 사용하세요. 휴대폰 로컬 서비스는 http://127.0.0.1 또는 http://localhost"
        }
        require(apiKey.isNotBlank() || isLocalAddress(base)) { "원격 모델 서비스의 다음 값을 입력하세요: API Key" }
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }
}
