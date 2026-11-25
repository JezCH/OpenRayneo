// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun PhoneNotificationScreen(model: AppModel, actions: UiActions) {
    val context = LocalContext.current.applicationContext
    var enabled by remember { mutableStateOf(PhoneNotificationForwarding.enabled(context)) }
    var allowed by remember { mutableStateOf(PhoneNotificationForwarding.allowed(context)) }
    var lockedMetadata by remember { mutableStateOf(PhoneNotificationForwarding.lockedMetadata(context)) }
    var previews by remember { mutableStateOf(PhoneNotificationForwarding.previews(context)) }
    var search by remember { mutableStateOf("") }
    val apps = remember {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (pkg, _) -> !pkg.startsWith("com.openrayneo.") && pkg != NavigationFeed.PACKAGE }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }
    val choices = (apps + allowed.filter { pkg -> apps.none { it.first == pkg } }.map { it to it })
        .filter { (pkg, name) -> name.contains(search, true) || pkg.contains(search, true) }
        .sortedWith(compareByDescending<Pair<String, String>> { it.first in allowed }.thenBy { it.second.lowercase() })
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ContentCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("휴대폰 알림 전달", style = MaterialTheme.typography.titleMedium)
                        Note(if (enabled) "선택한 앱 ${allowed.size}개" else "꺼짐")
                    }
                    Switch(checked = enabled, onCheckedChange = {
                        PhoneNotificationForwarding.setEnabled(context, it)
                        enabled = it
                    })
                }
                Note(if (model.state.notificationConnected) "안드로이드 알림 접근 연결됨" else "알림 접근 연결 필요")
                if (!model.notificationAllowed) ToolButton(onClick = actions.notificationAccess) { Text("알림 접근 허용") }
                else if (!model.state.notificationConnected) ToolOutlinedButton(onClick = {
                    model.attempt { NavigationNotifications.requestConnection(context) }
                }) { Text("알림 연결 복구") }
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
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("앱 검색") }, singleLine = true)
            SectionLabel("전달할 앱 선택")
        }
        items(choices, key = { it.first }) { (pkg, name) ->
            ContentCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
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
                    Text("잠금 해제 시 본문 미리보기 허용")
                }
            }
        }
        if (choices.isEmpty()) item { Note("선택할 앱이 없습니다. 검색어를 바꾸거나 설치된 앱을 확인하세요.") }
    }
}
