// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Match the existing notification preview size; never silently truncate the rest. */
internal object ExternalHudText {
    const val MAX_INPUT = 4000
    const val PAGE_LENGTH = 100
    fun pages(input: String): List<String> {
        require(input.length <= MAX_INPUT)
        val clean = input.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
        if (clean.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        while (start < clean.length) {
            var end = minOf(start + PAGE_LENGTH, clean.length)
            if (end < clean.length && Character.isHighSurrogate(clean[end - 1]) && Character.isLowSurrogate(clean[end])) end--
            result.add(clean.substring(start, end))
            start = end
        }
        return result
    }
}

@Composable
internal fun ExternalHudScreen(model: AppModel) {
    val draft = model.externalHudDraft
    val pages = remember(draft) { ExternalHudText.pages(draft) }
    var page by remember(draft) { mutableIntStateOf(0) }
    var status by remember(draft) { mutableStateOf("") }
    val s = model.state
    val connected = s.glasses.link.phase == LinkPhase.FULL && !s.glasses.disconnecting
    val ready = connected && model.recommendedEditable && !s.mediaCaption.busy && !s.captionActive &&
        !s.focusMode && s.appNotificationsEnabled && !s.speechPlaying
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("외부 AI 답변 · 일반 텍스트", style = MaterialTheme.typography.headlineSmall)
        Text("ChatGPT 등에서 답변 텍스트를 복사해 붙여넣거나 Android 공유 메뉴에서 검증 앱을 선택하세요. 링크만 공유된 경우 실제 답변을 복사하세요.")
        OutlinedTextField(value = draft, onValueChange = {
            if (it.length <= ExternalHudText.MAX_INPUT) model.externalHudDraft = it
            else model.notice = "4000자 이하의 텍스트를 선택하세요."
        }, modifier = Modifier.fillMaxWidth(), label = { Text("표시할 텍스트") }, minLines = 3, maxLines = 8)
        Text("${draft.length}/${ExternalHudText.MAX_INPUT}")
        if (pages.isNotEmpty()) {
            Text("표시할 페이지 ${page + 1}/${pages.size}", style = MaterialTheme.typography.titleMedium)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text(pages[page], Modifier.padding(16.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { page--; status = "" }, enabled = page > 0) { Text("이전") }
                OutlinedButton(onClick = { page++; status = "" }, enabled = page < pages.lastIndex) { Text("다음") }
            }
            Button(onClick = {
                model.attempt {
                    model.service().sendExternalHudText(pages[page])
                    status = "전송 요청 완료 · 안경에서 실제 표시를 확인하세요."
                }
            }, enabled = ready && pages[page].isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("이 페이지 안경에 표시") }
        }
        if (!ready) Text("안경 완전 연결 및 현재 화면·음성 작업 종료, 집중 모드와 알림 설정을 확인하세요.")
        if (status.isNotBlank()) Text(status)
        TextButton(onClick = { model.externalHudDraft = "" }) { Text("텍스트 지우기") }
        Text("텍스트는 이 화면의 임시 입력에만 사용합니다. AI 요청, 장기 기억 등록, 외부 서비스 전송, 자동 페이지 전환을 하지 않습니다. 안경 표시 길이와 실제 표시 여부는 기기에서 확인이 필요합니다.", style = MaterialTheme.typography.bodySmall)
    }
}
