// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExternalHudScreen(model: AppModel, phoneInputActive: Boolean) {
    val draft = model.externalHudDraft
    val pages = remember(draft) { ExternalHudText.pages(draft) }
    var page by remember(draft) { mutableIntStateOf(0) }
    var status by remember(draft) { mutableStateOf("") }
    val incoming = model.externalHudIncoming
    val s = model.state
    val problem = when {
        phoneInputActive -> "휴대폰 음성 입력을 먼저 종료하세요."
        !s.available -> "연결 서비스 준비 중"
        else -> runCatching { model.service().checkExternalHudReady() }.exceptionOrNull()?.message
    }
    val ready = problem == null && incoming == null
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OpticalPanel {
            Text("외부 AI 답변 · 일반 텍스트", style = MaterialTheme.typography.titleLarge)
            Text(deviceConnectionStatus(s).label, style = MaterialTheme.typography.labelLarge)
            Note("추가 AI 요청 없이 공유하거나 붙여넣은 텍스트를 페이지별로 표시합니다.")
            Note(problem ?: "전송 조건 확인됨 · 실제 안경 표시 미확인")
        }
        incoming?.let { text ->
            OpticalPanel {
                Text("공유받은 텍스트", style = MaterialTheme.typography.titleMedium)
                Note("${text.length}자 · ${ExternalHudText.pages(text).size}페이지 · 아직 전송하지 않음")
                Text(ExternalHudText.pages(text).firstOrNull().orEmpty())
                ToolButton(onClick = { model.externalHudDraft = text; model.externalHudIncoming = null; status = "" },
                    modifier = Modifier.fillMaxWidth()) { Text(if (draft.isBlank()) "편집창에 넣기" else "현재 입력 대신 이 텍스트 사용") }
                TextButton(onClick = { model.externalHudIncoming = null }) { Text("공유 취소 · 기존 입력 유지") }
            }
        }
        SectionLabel("표시할 텍스트")
        OutlinedTextField(draft, { value ->
            if (value.length <= ExternalHudText.MAX_INPUT) model.externalHudDraft = value
            else model.notice = "4000자 이하의 텍스트를 선택하세요."
        }, Modifier.fillMaxWidth(), label = { Text("복사한 답변 붙여넣기") }, minLines = 3, maxLines = 8)
        Note("${draft.length}/${ExternalHudText.MAX_INPUT}자 · 줄바꿈 등 제어문자는 공백으로 표시")
        if (pages.isNotEmpty()) OpticalPanel {
            Text("미리보기 ${page + 1}/${pages.size}", style = MaterialTheme.typography.titleMedium)
            Text(pages[page])
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolOutlinedButton(onClick = { page--; status = "" }, enabled = page > 0) { Text("이전") }
                ToolOutlinedButton(onClick = { page++; status = "" }, enabled = page < pages.lastIndex) { Text("다음") }
            }
            ToolButton(onClick = {
                model.attempt {
                    check(!phoneInputActive && model.externalHudIncoming == null) { "현재 공유 또는 음성 입력을 먼저 확인하세요." }
                    model.service().sendExternalHudText(pages[page])
                    status = "전송 요청됨 · 전송 완료 및 안경 표시는 미확인"
                }
            }, enabled = ready && ExternalHudText.validPage(pages[page]), modifier = Modifier.fillMaxWidth()) { Text("이 페이지 안경에 표시") }
            if (status.isNotBlank()) Note(status)
        }
        TextButton(onClick = { model.externalHudDraft = "" }, enabled = draft.isNotEmpty()) { Text("편집창 비우기") }
        Note("일반 텍스트만 사용합니다. 링크를 열거나 첨부 파일을 읽지 않습니다. AI 요청, 장기 기억 저장, 자동 페이지 전환을 하지 않습니다. 공유받은 내용은 직접 확인하고 전송하세요.")
    }
}
