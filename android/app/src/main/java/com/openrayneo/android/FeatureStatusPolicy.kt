// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Presentation contract: permission is separate from observed runtime. */
internal object FeatureStatusPolicy {
    enum class Phase(val label: String) {
        OFF("꺼짐"), SETUP("설정 필요"), READY("준비됨"), WAITING("대기 중"),
        ACTIVE("작동 중"), STOPPING("중지 중"), BLOCKED("차단"), ERROR("오류")
    }
    data class Status(val phase: Phase, val detail: String)
    fun resolve(allowed: Boolean, ready: Boolean, active: Boolean = false,
                waiting: Boolean = false, stopping: Boolean = false,
                blocker: String? = null, error: String? = null,
                readyDetail: String = "사용 가능", setupDetail: String = "설정을 확인하세요",
                activeDetail: String = "실행 중", waitingDetail: String = "실행 조건 대기"): Status = when {
        stopping -> Status(Phase.STOPPING, "종료 및 정리 중")
        error != null -> Status(Phase.ERROR, error)
        active -> Status(Phase.ACTIVE, activeDetail)
        !allowed -> Status(Phase.OFF, "사용 허용 안 됨")
        blocker != null -> Status(Phase.BLOCKED, blocker)
        !ready -> Status(Phase.SETUP, setupDetail)
        waiting -> Status(Phase.WAITING, waitingDetail)
        else -> Status(Phase.READY, readyDetail)
    }
}
