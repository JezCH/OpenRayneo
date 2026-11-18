// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

/** Facts for the external phone Agent; the companion performs no semantic routing. */
internal class VoiceModePolicy(context: Context) {
    private val prompts = context.assets.open("assistant/voice-modes.json").bufferedReader().use { JSONObject(it.readText()) }
    val agentPrompt: String = prompts.getString("agent")
    fun input(source: ConversationSource, segments: List<SpeechSegment>, people: List<VoicePerson>,
              previous: List<String>, previousReply: String): JSONObject {
        val now = System.currentTimeMillis()
        return JSONObject().put("mode", source.name.lowercase())
            .put("capture_delay_estimate_seconds", if (source == ConversationSource.ALWAYS_ON) 20 else 0)
            .put("now", ZonedDateTime.now().toString())
            .put("segments", JSONArray(segments.map { segment ->
                val person = people.firstOrNull { it.id == segment.personId }
                JSONObject().put("id", segment.id).put("beginMS", segment.beginMS).put("endMS", segment.endMS)
                    .put("text", segment.text).put("sourceChannel", segment.sourceChannel).put("identityBasis", segment.identityBasis).put("speaker", segment.speaker).put("mixed", segment.mixed).put("personId", segment.personId)
                    .put("person_name", person?.name ?: JSONObject.NULL)
                    .put("is_self", person?.self == true && segment.stable)
                    .put("identity_note", if (segment.identityBasis == "channel1_default_self") "2채널 중 채널 1은 사용자 정책상 기본 본인이며 음성 지문으로 확인한 것이 아님. 다른 채널에 같은 사람이 녹음될 수 있으니 텍스트가 비슷하다는 이유만으로 원문을 삭제하지 말 것" else "이 구간의 신원 출처에 따라 이해할 것").put("identity_stable", segment.stable && person != null)
                    .put("timelineAt", segment.timelineAt.takeIf { it > 0 } ?: segment.receivedAt).put("timeBasis", segment.timeBasis)
                    .put("processingMode", segment.processingMode).put("asrCompletedAt", segment.receivedAt)
                    .put("age_seconds", (now - (segment.timelineAt.takeIf { it > 0 } ?: segment.receivedAt)).coerceAtLeast(0) / 1000)
                    .put("processing_delay_seconds", (segment.receivedAt - (segment.timelineAt.takeIf { it > 0 } ?: segment.receivedAt)).coerceAtLeast(0) / 1000)
            })).put("previous_request", JSONArray(previous)).put("previous_reply", previousReply)
    }
}
