// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Socket authentication, application readback and hardware function evidence are
 * independent. Never infer official-app-free operation from any one of them. */
internal enum class DeviceReadback {
    NONE, PENDING, STATUS_ONLY, SETTINGS_ONLY, VERIFIED, FAILED;

    val label: String get() = when (this) {
        NONE -> "기기 응답 미확인"
        PENDING -> "기기 상태·설정 회신 대기"
        STATUS_ONLY -> "상태 회신 확인 · 설정 미확인"
        SETTINGS_ONLY -> "설정 회신 확인 · 상태 미확인"
        VERIFIED -> "상태·설정 회신 확인"
        FAILED -> "상태·설정 회신 실패"
    }
    companion object {
        fun from(status: Boolean, settings: Boolean) = when {
            status && settings -> VERIFIED
            status -> STATUS_ONLY
            settings -> SETTINGS_ONLY
            else -> FAILED
        }
    }
}
