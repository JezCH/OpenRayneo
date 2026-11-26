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
internal fun MediaCaptionScreen(model: AppModel, start: (MediaCaptionOptions) -> Unit, voiceSettings: () -> Unit, connect: () -> Unit) {
    val s = model.state
    val capture = s.mediaCaption
    val mt = s.mediaTranslation
    var scope by rememberSaveable { mutableStateOf(MediaCaptionScope.YOUTUBE.name) }
    var language by rememberSaveable { mutableStateOf("en") }
    var korean by rememberSaveable { mutableStateOf(true) }
    var details by rememberSaveable { mutableStateOf(false) }
    val options = MediaCaptionOptions(MediaCaptionScope.valueOf(scope), language, korean)
    val needsTranslation = MediaTranslationRoute.required(language, korean)
    val problem = if (capture.busy) null else if (!s.available) "연결 서비스 준비 중" else
        runCatching { model.service().checkMediaCaption(options) }.exceptionOrNull()?.let { it.message ?: "미디어 자막 준비 상태를 확인할 수 없습니다." }
    val labels = listOf("en" to "영어", "ja" to "일본어", "zh" to "중국어", "ko" to "한국어")
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ContentCard {
            Text("유튜브·미디어 자막", style = MaterialTheme.typography.headlineSmall)
            Note("안경 연결: " + s.glasses.link.detail)
            if (s.glasses.link.phase != LinkPhase.FULL || s.glasses.disconnecting) {
                s.glasses.link.lastFailure?.let { Note("최근 원인: $it") }
                ToolOutlinedButton(onClick = connect) { Text("안경 연결 확인") }
            }
            Note("휴대폰 영상 소리를 글자로 바꿔 안경에 표시합니다. 이어폰 소리는 그대로 들을 수 있고, 별도 AI 구독이나 서버는 필요하지 않습니다.")
            Note(capture.detail)
            if (capture.busy) {
                val translating = MediaTranslationRoute.required(capture.options.language, capture.options.translateToKorean)
                Note((if (capture.options.scope == MediaCaptionScope.YOUTUBE) "YouTube만" else "허용된 전체 미디어") +
                    " · " + labels.first { it.first == capture.options.language }.second +
                    (if (translating) " → 한국어" else " 원문"))
                Text("현재 진행 상태", style = MaterialTheme.typography.titleMedium)
                Note("1. 영상 소리: " + if (capture.heardNonzero) "수신됨" else "재생음 대기 · 영상을 재생하세요")
                Note("2. 음성 인식: " + if (s.mediaFinalCount > 0L) "완료 ${s.mediaFinalCount}문장" else "문장 인식 대기")
                if (translating) Note("3. 한국어 번역: " + if (mt.completed > 0) "완료 ${mt.completed}문장" else "번역 대기")
                Note("안경 화면: " + s.glasses.displayDescription + " · 실제 표시를 확인하세요")
                TextButton(onClick = { details = !details }) { Text(if (details) "상세 진단 접기" else "상세 진단 보기 (소리·인식·번역)") }
                if (details) {
                Note("PCM 수집: " + (capture.samples / 16000) + "초 · ASR 미수용: " + capture.dropped + "샘플")
                Note("첫 PCM: " + (capture.firstPcmMs?.let { "$it ms" } ?: "대기") +
                    " · " + if (capture.heardNonzero) "재생음 감지됨" else "유효한 재생음 대기")
                if (translating) {
                    Note(mt.detail)
                    Note("번역 완료 ${mt.completed}문장 · 지연으로 생략 ${mt.skipped}문장 · 마지막 번역 " + (mt.lastMs?.let { "$it ms" } ?: "대기"))
                }
                Note("ASR 입력 수락: " + ((capture.samples - capture.dropped).coerceAtLeast(0) / 16000) + "초")
                Note("음성 인식: " + s.mediaAsrStatus + " · 확정 문장 " + s.mediaFinalCount + "개")
                }
                if (capture.heardNonzero && s.mediaFinalCount == 0L) Note("소리는 들어오고 있지만 아직 확정 문장이 없습니다. 원문 언어와 설치된 음성 모델을 확인하세요.")
                ToolButton(onClick = { model.attempt { model.service().stopMediaCaption() } },
                    enabled = capture.phase != MediaCapturePhase.STOPPING, modifier = Modifier.fillMaxWidth()) { Text("미디어 자막 종료") }
            } else {
                Text(if (problem == null) "시작 준비 완료" else "시작 전 확인 필요", style = MaterialTheme.typography.titleMedium)
                if (problem != null) Note(problem)
                Text("1. 어떤 영상 소리를 들을까요?", style = MaterialTheme.typography.titleMedium)
                listOf(MediaCaptionScope.YOUTUBE to "YouTube만", MediaCaptionScope.ALL_MEDIA to "캡처가 허용된 미디어·게임").forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(scope == value.name, { scope = value.name }, enabled = !mt.preparing); Text(label)
                    }
                }
                Text("2. 영상에서 나오는 언어", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    labels.forEach { (code, label) ->
                        FilterChip(language == code, { language = code }, label = { Text(label) }, enabled = !mt.preparing)
                    }
                }
                Text("3. 안경에 표시할 언어", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(korean, { korean = true }, enabled = !mt.preparing); Text("한국어 번역")
                    RadioButton(!korean, { korean = false }, enabled = !mt.preparing); Text("원문 자막")
                }
                Note("영어·일본어·중국어에는 SenseVoice 모델을 준비하세요. 설치된 Nemotron은 영어, 한국어 모델은 한국어에 재사용할 수 있습니다. 일상 음성 설정은 변경하지 않습니다.")
                ToolOutlinedButton(onClick = voiceSettings, enabled = !mt.preparing, modifier = Modifier.fillMaxWidth()) { Text("음성 모델 다운로드 화면 열기") }
                if (language == "ko" && korean) Note("한국어 음성은 번역 모델 없이 원문을 표시합니다.")
                if (needsTranslation || mt.preparing) {
                    Text("내장 번역 모델", style = MaterialTheme.typography.titleMedium)
                    Note("Mozilla 번역 모델 · 영어→한국어 직접, 일본어·중국어는 영어 경유. 최초 전체 다운로드 영어 약 38MB, 일본어·중국어 약 76MB. 설치한 공통 파일은 다시 내려받지 않습니다.")
                    Note(if (mt.language == language || mt.preparing) mt.detail else "선택한 언어 모델 준비 필요")
                    if (mt.preparing) {
                        if (mt.total > 0) {
                            LinearProgressIndicator(progress = { (mt.received.toDouble() / mt.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Note("${mt.received / 1_000_000} / ${mt.total / 1_000_000} MB")
                        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        ToolOutlinedButton(onClick = { model.attempt { model.service().cancelMediaTranslationPreparation() } },
                            enabled = mt.phase != LocalMediaTranslationPhase.CANCELLING) { Text("모델 준비 취소") }
                    } else if (!mt.readyFor(language)) {
                        ToolButton(onClick = { model.attempt { model.service().prepareMediaTranslation(language) } },
                            enabled = s.available) { Text("모델 다운로드·준비") }
                    }
                }
                if (mt.phase == LocalMediaTranslationPhase.READY && !mt.preparing)
                    TextButton(onClick = { model.attempt { model.service().releaseMediaTranslation() } }) { Text("번역 모델 메모리 해제") }
                if (capture.phase == MediaCapturePhase.ERROR) Note("최근 종료: " + capture.detail + " · 수집 " + capture.samples + "샘플 · 확정 문장 " + s.mediaFinalCount + "개")
                if (problem != null) Note("시작할 수 없는 이유: " + problem)
                ToolButton(onClick = { start(options) }, enabled = problem == null && s.available && !mt.preparing,
                    modifier = Modifier.fillMaxWidth()) { Text("시스템 승인 후 자막 시작") }
            }
            Note("Mozilla Translations / Bergamot 및 Ngoroth의 공개 Android 구현 기반. 모델 MPL-2.0. 설치 모델로 번역하며 음성·전사·번역은 저장하거나 외부로 전송하지 않습니다. 오류 시 원문을 표시하고 유료 서버로 전환하지 않습니다.")
            Note("Android 오디오 및 화면 공유 승인창은 재생음 수집 권한을 얻기 위해 필요합니다. 안경 마이크 패킷은 앱에서 사용하지 않지만 펌웨어 자체 마이크 상태는 아직 실기기 확인이 필요합니다.")
            Note("앱의 재생음 캡처 제한, 통화, 보호 콘텐츠는 지원되지 않을 수 있습니다. 화면 잠금·시스템 승인 취소·안경 연결 해제 시 종료되며 새 시작에는 다시 승인이 필요합니다.")
        } }
        item { GlassesOutputPreview(s.glasses) }
    }
}
