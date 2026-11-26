// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun MediaCaptionScreen(model: AppModel, start: (MediaCaptionOptions) -> Unit, voiceSettings: () -> Unit) {
    val s = model.state
    val capture = s.mediaCaption
    var scope by rememberSaveable { mutableStateOf(MediaCaptionScope.YOUTUBE.name) }
    var language by rememberSaveable { mutableStateOf("en") }
    val options = MediaCaptionOptions(MediaCaptionScope.valueOf(scope), language)
    val problem = if (capture.busy) null else if (!s.available) "연결 서비스 준비 중" else
        runCatching { model.service().checkMediaCaption(options) }.exceptionOrNull()?.let { it.message ?: "미디어 자막 준비 상태를 확인할 수 없습니다." }
    val labels = listOf("en" to "영어", "ja" to "일본어", "zh" to "중국어", "ko" to "한국어")
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ContentCard {
            Text("휴대폰 미디어 원문 자막", style = MaterialTheme.typography.headlineSmall)
            Note("이어폰으로 원본 소리를 듣는 동안 휴대폰 재생음을 로컬 ASR로 인식해 안경에 표시합니다. 현재는 원문 자막이며 한국어 번역은 아직 지원하지 않습니다.")
            Note(capture.detail)
            if (capture.busy) {
                Note((if (capture.options.scope == MediaCaptionScope.YOUTUBE) "YouTube만" else "허용된 전체 미디어") +
                    " · " + labels.first { it.first == capture.options.language }.second)
                Note("PCM 수집: " + (capture.samples / 16000) + "초 · ASR 미수용: " + capture.dropped + "샘플")
                Note("첫 PCM: " + (capture.firstPcmMs?.let { "$it ms" } ?: "대기") +
                    " · " + if (capture.heardNonzero) "재생음 감지됨" else "유효한 재생음 대기")
                Note(s.glasses.displayDescription)
                ToolButton(onClick = { model.attempt { model.service().stopMediaCaption() } },
                    enabled = capture.phase != MediaCapturePhase.STOPPING, modifier = Modifier.fillMaxWidth()) { Text("미디어 자막 종료") }
            } else {
                Text("수집 범위", style = MaterialTheme.typography.titleMedium)
                listOf(MediaCaptionScope.YOUTUBE to "YouTube만", MediaCaptionScope.ALL_MEDIA to "캡처가 허용된 미디어·게임").forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(scope == value.name, { scope = value.name }); Text(label)
                    }
                }
                Text("원문 언어", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    labels.forEach { (code, label) ->
                        FilterChip(language == code, { language = code }, label = { Text(label) })
                    }
                }
                Note("영어·일본어·중국어에는 SenseVoice 모델을 준비하세요. 설치된 Nemotron은 영어, 한국어 모델은 한국어에 재사용할 수 있습니다. 일상 음성 설정은 변경하지 않습니다.")
                ToolOutlinedButton(onClick = voiceSettings) { Text("음성 모델 준비") }
                if (problem != null) Note(problem)
                ToolButton(onClick = { start(options) }, enabled = problem == null && s.available,
                    modifier = Modifier.fillMaxWidth()) { Text("시스템 승인 후 자막 시작") }
            }
            Note("Android 오디오 및 화면 공유 승인창은 재생음 수집 권한을 얻기 위해 필요합니다. 화면 영상과 음원은 저장·외부 전송하지 않습니다. 안경 마이크 패킷은 앱에서 사용하지 않지만 펌웨어 자체 마이크 상태는 아직 실기기 확인이 필요합니다.")
            Note("앱의 재생음 캡처 제한, 통화, 보호 콘텐츠는 지원되지 않을 수 있습니다. 화면 잠금·시스템 승인 취소·안경 연결 해제 시 종료되며 새 시작에는 다시 승인이 필요합니다.")
        } }
        item { GlassesOutputPreview(s.glasses) }
    }
}
