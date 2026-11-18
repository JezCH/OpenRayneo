// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject

internal object ConversationPrompts {
    fun render(raw: String, fallback: String): String {
        if (raw.isBlank()) return fallback
        if (!raw.trim().startsWith("{")) return raw
        val value = JSONObject(raw)
        return buildString {
            append(value.optString("role")); append('\n'); append(value.optString("objective"))
            value.optJSONArray("rules")?.let { a -> repeat(a.length()) { append("\n- "); append(a.getString(it)) } }
        }
    }
    val memory = """너는 원문 출처가 있는 기록을 정리한다. summary 문자열과 memories 배열을 담은 JSON 객체를 출력한다. 각 memory에는 source_ids(원문 문장 ID 배열), speaker_id(원 화자 ID 또는 null), subject_id(주어진 인물 ID 또는 null), subject(서술 대상 이름), text, kind(사실 진술/계획/전언/불확실)를 포함한다. 모든 텍스트는 한국어로 작성한다.
        |각 항목은 주어진 원문에서만 나와야 하며 유효한 source_ids와 연결되어야 한다. '누가 말했는지'와 '누구에 대한 말인지'를 구분한다. 다른 사람이 말한 '나'는 그 화자를 가리키며, 내가 철수의 계획을 전한 것은 철수에 대한 전언이다. 추측, 인용, 계획은 원래 어조를 유지하고 이미 일어난 사실로 격상하지 않는다.
        |모든 모드에는 본인, 타인, 미상 및 혼합 발화가 포함될 수 있다. 유용한 계획, 사안, 정보는 남기고 모든 말버릇을 사실로 기록하지 않는다. 각자의 발언, 이견, 할 일을 요약하되 담당자는 원문 근거가 있어야 하며, 미상이나 혼합 발화를 억지로 본인에게 귀속하지 않는다.
        |인물 관계는 원문에 명시된 내용만 기록하고 성격, 감정, 친밀도를 자동으로 추론하지 않는다. 근거가 없으면 null을 쓰거나 추출하지 않는다.""".trimMargin()
}
