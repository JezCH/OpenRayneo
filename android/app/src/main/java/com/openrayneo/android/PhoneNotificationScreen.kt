// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneNotificationScreen(model: AppModel, actions: UiActions) {
    val context = LocalContext.current.applicationContext
    var enabled by remember { mutableStateOf(PhoneNotificationForwarding.enabled(context)) }
    var allowed by remember { mutableStateOf(PhoneNotificationForwarding.allowed(context)) }
    var lockedMetadata by remember { mutableStateOf(PhoneNotificationForwarding.lockedMetadata(context)) }
    var previews by remember { mutableStateOf(PhoneNotificationForwarding.previews(context)) }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    val apps = remember {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (pkg, _) -> !pkg.startsWith("com.openrayneo.") && pkg != NavigationFeed.PACKAGE }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }
    val choices = (apps + allowed.filter { pkg -> apps.none { it.first == pkg } }.map { it to it })
        .filter { (pkg, name) -> (!selectedOnly || pkg in allowed) && (name.contains(search, true) || pkg.contains(search, true)) }
        .sortedWith(compareBy<Pair<String, String>> { it.second.lowercase() }.thenBy { it.first })
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OpticalPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("휴대폰 알림 전달", style = MaterialTheme.typography.titleMedium)
                        Note(if (enabled) "사용 허용 · 선택한 앱 ${allowed.size}개" else "사용 안 함 · 선택 설정 유지")
                    }
                    Switch(checked = enabled, onCheckedChange = {
                        PhoneNotificationForwarding.setEnabled(context, it)
                        enabled = it
                    })
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("현재 전달 조건", style = MaterialTheme.typography.titleSmall)
                Note(when {
                    !enabled -> "알림 전달 사용 안 함"
                    !model.notificationAllowed -> "알림 접근 권한 필요"
                    !model.state.notificationConnected -> "알림 리스너 연결 대기"
                    !model.state.available -> "서비스 상태 미확인"
                    model.state.glasses.link.phase != LinkPhase.FULL || model.state.glasses.disconnecting -> "안경 통신 연결 필요"
                    model.state.focusMode -> "집중 모드 · 전달 제한"
                    allowed.isEmpty() -> "전달할 앱 선택 필요"
                    else -> "기본 전달 조건 확인 · 실제 알림 전송은 새 알림 및 실행 중인 기능에 따라 결정"
                })
                Note(if (!model.notificationAllowed) "Android 알림 접근 미허용" else if (model.state.notificationConnected) "Android 알림 리스너 연결됨" else "Android 알림 리스너 연결 대기")
                if (!model.notificationAllowed) ToolButton(onClick = actions.notificationAccess) { Text("알림 접근 허용") }
                else if (!model.state.notificationConnected) ToolOutlinedButton(onClick = {
                    model.attempt { NavigationNotifications.requestConnection(context) }
                }) { Text("알림 연결 복구") }
            }
        }
        item {
            SectionLabel("개인정보 및 잠금 화면")
            OpticalPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("휴대폰 잠금 중 앱 이름 알림")
                        Note("화면이 꺼져도 앱 이름만 표시. 본문은 표시하지 않습니다.")
                    }
                    Switch(checked = lockedMetadata, onCheckedChange = {
                        PhoneNotificationForwarding.setLockedMetadata(context, it)
                        lockedMetadata = it
                    })
                }
                Note("안경 연결, 집중 모드 해제, 안경 화면·녹음 등 다른 기능이 쉬는 중일 때만 새 알림을 보냅니다. 지난 알림은 다시 보내지 않습니다. 앱 이름도 개인정보가 될 수 있습니다.")
                Note("기본은 앱 이름만 표시. 본문 미리보기는 앱마다 선택하며 인증·비밀번호 알림 감지는 완전하지 않습니다. 민감한 앱에서는 미리보기를 끄세요. 외부 AI 전송 및 앱 내 알림 기록 저장은 하지 않습니다.")
            }
        }
        item {
            SectionLabel("전달할 앱 선택")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!selectedOnly, { selectedOnly = false }, label = { Text("전체") })
                FilterChip(selectedOnly, { selectedOnly = true }, label = { Text("선택한 앱 ${allowed.size}") })
            }
            OutlinedTextField(
                search, { search = it }, Modifier.fillMaxWidth(), label = { Text("앱 검색") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Outlined.Clear, "검색어 지우기") } },
                singleLine = true,
            )
            Note("표시된 앱 ${choices.size}개")
        }
        items(choices, key = { it.first }) { (pkg, name) ->
            OpticalPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Note(pkg)
                    }
                    Switch(checked = pkg in allowed, onCheckedChange = { selected ->
                        allowed = if (selected) allowed + pkg else allowed - pkg
                        PhoneNotificationForwarding.setAllowed(context, allowed)
                        previews = PhoneNotificationForwarding.previews(context)
                    })
                }
                if (pkg in allowed) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = pkg in previews, onCheckedChange = {
                        PhoneNotificationForwarding.setPreview(context, pkg, it)
                        previews = PhoneNotificationForwarding.previews(context)
                    })
                    Text("잠금 해제 시 본문 미리보기 허용", Modifier.weight(1f))
                }
            }
        }
        if (choices.isEmpty()) item {
            Note(when {
                search.isNotBlank() -> "검색 결과가 없습니다."
                selectedOnly -> "선택한 앱이 없습니다."
                else -> "선택할 앱이 없습니다. 설치된 앱을 확인하세요."
            })
            if (search.isNotBlank()) TextButton(onClick = { search = "" }) { Text("검색어 지우기") }
            if (selectedOnly) TextButton(onClick = { selectedOnly = false }) { Text("전체 앱 보기") }
        }
    }
}
