// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// Real production component previews. Fictional sample labels, no AppModel,
// service, permissions, networking, storage, or Bluetooth side effects.
@Composable
private fun OpticalComponentSample(appearance: String) {
    CompanionTheme(appearance) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OpticalHeroPanel {
                    OpticalActivityHeader("대기 중")
                    Note("예시 · 실제 기기 연결과 기록 상태가 아님")
                    ToolButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("일상 기록 시작") }
                }
                SectionLabel("바로 사용")
                OpticalQuickPair(
                    first = { OpticalQuickTile("AI에게 질문", "질문 · PC 작업", Icons.AutoMirrored.Outlined.Chat, it) {} },
                    second = { OpticalQuickTile("회의 기록", "전사 · 요약", Icons.Outlined.GraphicEq, it) {} },
                )
                OpticalQuickPair(
                    first = { OpticalQuickTile("실시간 힌트", "대화 중 짧은 도움", Icons.Outlined.AutoAwesome, it) {} },
                    second = { OpticalQuickTile("한국어 호출", "내 호출어 · AI 사용", Icons.Outlined.Mic, it) {} },
                )
                OpticalQuickPair(
                    first = { OpticalQuickTile("영상 자막", "휴대폰 소리 → 한국어", Icons.Outlined.Translate, it) {} },
                    second = { OpticalQuickTile("한영 통역", "안경 마이크 · 내장 번역", Icons.Outlined.Translate, it) {} },
                )
            }
        }
    }
}

@Preview(name = "Optical dark / 390dp", widthDp = 390, heightDp = 800)
@Composable private fun OpticalDarkPreview() = OpticalComponentSample("dark")

@Preview(name = "Optical light / 390dp", widthDp = 390, heightDp = 800)
@Composable private fun OpticalLightPreview() = OpticalComponentSample("light")

@Preview(name = "Optical large text / 320dp", widthDp = 320, heightDp = 800, fontScale = 2f)
@Composable private fun OpticalLargeTextPreview() = OpticalComponentSample("dark")

@Preview(name = "Optical AI state / dark", widthDp = 390, heightDp = 760)
@Composable
private fun OpticalAiStatesPreview() {
    CompanionTheme("dark") {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AI 상태 예시", style = MaterialTheme.typography.headlineSmall)
                OpticalAiPhase.entries.forEach { phase ->
                    OpticalPanel { OpticalAiStatus(phase, "샘플 · 실제 서비스 상태가 아님") }
                }
            }
        }
    }
}

@Preview(name = "Optical AI state / large text", widthDp = 320, heightDp = 800, fontScale = 2f)
@Composable
private fun OpticalAiStatesLargePreview() {
    CompanionTheme("light") {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OpticalAiPhase.entries.forEach { phase ->
                    OpticalPanel { OpticalAiStatus(phase, "상태 설명 예시") }
                }
            }
        }
    }
}

@Preview(name = "Optical frame / dark", widthDp = 390, heightDp = 840)
@Composable private fun OpticalFrameDarkPreview() = OpticalFullFrameSample("dark")

@Preview(name = "Optical frame / light", widthDp = 390, heightDp = 840)
@Composable private fun OpticalFrameLightPreview() = OpticalFullFrameSample("light")

@Composable
private fun OpticalFullFrameSample(appearance: String) {
    CompanionTheme(appearance) {
        Scaffold(topBar = { OpticalFrameHeader("홈", false, false, {}, {}) },
            bottomBar = { OpticalFrameNavigation(0, 0) {} },
            containerColor = MaterialTheme.colorScheme.background) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OpticalHeroPanel {
                    OpticalActivityHeader("대기 중")
                    Note("예시 · 실제 연결 상태가 아님")
                    ToolButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("안경 연결") }
                }
                SectionLabel("바로 사용")
                OpticalQuickPair({ OpticalQuickTile("AI에게 질문", "질문 · PC 작업", Icons.AutoMirrored.Outlined.Chat, it) {} },
                    { OpticalQuickTile("회의 기록", "전사 · 요약", Icons.Outlined.GraphicEq, it) {} })
                OpticalQuickPair({ OpticalQuickTile("실시간 힌트", "대화 중 도움", Icons.Outlined.AutoAwesome, it) {} },
                    { OpticalQuickTile("한국어 호출", "내 호출어", Icons.Outlined.Mic, it) {} })
                OpticalQuickPair({ OpticalQuickTile("영상 자막", "휴대폰 영상", Icons.Outlined.Translate, it) {} },
                    { OpticalQuickTile("한영 통역", "내장 번역", Icons.Outlined.Translate, it) {} })
            }
        }
    }
}
