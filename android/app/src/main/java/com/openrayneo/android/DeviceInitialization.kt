// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Native recorder (0x0e), distinct from LifeLog and assistant capture (0x0d). */
internal enum class NativeRecorderState {
    UNKNOWN, RECORDING, PAUSED, IDLE;
    val occupied get() = this == RECORDING || this == PAUSED
    val label get() = when (this) {
        UNKNOWN -> "상태 미확인"
        RECORDING -> "안경 자체 녹음 중"
        PAUSED -> "안경 자체 녹음 일시정지"
        IDLE -> "안경 자체 녹음 대기"
    }
    companion object {
        fun from(code: Int?) = when(code) {
            1 -> RECORDING; 2 -> PAUSED; 3 -> IDLE; else -> UNKNOWN
        }
    }
}

internal data class DeviceInitialization(
    val attempted: Boolean = false,
    val clockSent: Boolean? = null,
    val firmwareVersion: String? = null,
    val recorder: NativeRecorderState = NativeRecorderState.UNKNOWN
) {
    val clockLabel get() = when (clockSent) {
        true -> "시간 전송됨 · 적용 미확인"
        false -> "시간 전송 실패"
        null -> "시간 전송 대기"
    }
}
