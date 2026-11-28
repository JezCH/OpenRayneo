// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Renders the logical cells from the exact payload, without generating a second arrow or road
 * label.
 */
@Composable
internal fun NavigationGrid(frame: String, modifier: Modifier = Modifier) {
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
    }
    Canvas(
        modifier.fillMaxWidth().aspectRatio(20f / 7f).semantics {
            contentDescription =
                "안경 내비게이션 레이아웃, 20열 5행." + frame.replace('█', ' ').replace('　', ' ').trim()
        }
    ) {
        val cellWidth = size.width / 20
        val cellHeight = size.height / 5
        paint.color = SignalGreen.toArgb()
        paint.textSize = cellWidth * .95f
        val baselineOffset = -(paint.ascent() + paint.descent()) / 2
        frame.split('\n').forEachIndexed { row, text ->
            text.forEachIndexed { column, character ->
                if (character == '█') {
                    drawRect(
                        SignalGreen,
                        Offset(column * cellWidth, row * cellHeight),
                        Size(cellWidth, cellHeight),
                    )
                } else if (character != '　' && character != ' ') {
                    drawContext.canvas.nativeCanvas.drawText(
                        character.toString(),
                        (column + .5f) * cellWidth,
                        (row + .5f) * cellHeight + baselineOffset,
                        paint,
                    )
                }
            }
        }
    }
}

@Composable
internal fun GridPreview(frame: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Note(label)
        Surface(
            color = LensBlack,
            contentColor = SignalGreen,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.padding(16.dp)) { NavigationGrid(frame) }
        }
    }
}

@Composable
internal fun GlassesOutputPreview(state: GlassesState) {
    val session = state.session
    val content = session?.writtenContent
    val available = state.link.phase == LinkPhase.FULL &&
        !state.disconnecting && state.ownership == DisplayOwnership.APP && content != null
    val title = when {
        state.disconnecting -> "연결 종료 중"
        state.ownership == DisplayOwnership.YIELDED -> "표시를 양보함"
        state.link.phase != LinkPhase.FULL -> "안경 표시 미확인"
        state.error != null -> "표시 세션 오류"
        session == null -> "앱 표시 유휴"
        session.phase == PagePhase.CLOSING -> if (state.intent == DisplayIntent.Idle) "화면 닫는 중" else "화면 전환 중"
        session.nativePaused -> "순정 세션 일시정지"
        content == null -> "첫 화면 전송 대기"
        session.content != content -> "내용 업데이트 중"
        session.mode == PageMode.NAVIGATION -> "길 안내"
        state.mediaCaptionMode -> "미디어 자막"
        state.captionMode -> "주변 대화 자막"
        state.intent is DisplayIntent.Answer -> "AI 답변"
        state.intent == DisplayIntent.Translation -> "실시간 번역"
        state.intent == DisplayIntent.BlankPrompt -> "화면 지우기"
        else -> "실시간 힌트"
    }
    // The green optical plane is reserved for the exact written payload.
    // Phone-side connection/status text is never presented as HUD content.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = if (state.error != null && state.link.phase == LinkPhase.FULL) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface)
        Note(state.displayDescription)
        if (available && session != null && content != null) {
            Note("마지막 전송 내용 · 실제 안경 표시 미확인")
            Surface(
                color = LensBlack,
                contentColor = SignalGreen,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (session.mode == PageMode.NAVIGATION) NavigationGrid(content.text)
                    else Column(
                        Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (content.text.isNotEmpty()) Text(content.text, style = MaterialTheme.typography.titleMedium)
                        if (!content.answer.isNullOrEmpty()) {
                            if (content.text.isNotEmpty()) HorizontalDivider(color = SignalGreen.copy(alpha = .2f))
                            Text(content.answer.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                        }
                        if (content.text.isEmpty() && content.answer.isNullOrEmpty()) Spacer(Modifier.height(40.dp))
                    }
                }
            }
            if (content.text.isEmpty() && content.answer.isNullOrEmpty()) Note("전송된 텍스트 없음")
        } else {
            Note(when {
                state.disconnecting -> "연결 종료 중에는 현재 출력 내용을 확인할 수 없습니다."
                state.ownership == DisplayOwnership.YIELDED -> "기기가 제어하는 페이지는 휴대폰에서 읽을 수 없습니다."
                state.link.phase != LinkPhase.FULL -> "완전히 연결되면 이번 세션에서 전송한 내용을 표시합니다."
                session != null -> "아직 확인된 전송 내용이 없습니다."
                else -> "현재 앱에서 전송한 표시 세션이 없습니다."
            })
        }
        Note("전송 기록 미리보기이며 실시간 화면 캡처가 아닙니다.")
    }
}
