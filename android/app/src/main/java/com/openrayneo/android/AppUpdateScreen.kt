// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun AppUpdateScreen(model: AppModel) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var candidate by remember { mutableStateOf<AppUpdate.Candidate?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("업데이트를 확인하세요") }
    var progress by remember { mutableStateOf(0L) }
    var signer by remember { mutableStateOf("") }
    val busy = job != null

    fun idle(): Boolean {
        val s = model.state
        return model.recommendedEditable && !s.captionActive && !s.mediaCaption.busy &&
            !s.mediaTranslation.preparing && !s.offlinePreparing && !s.speechPlaying &&
            !s.chatContinuityBusy && s.approvals.isEmpty() && s.agentReviews.isEmpty() &&
            !s.agentServer.enabled && !s.recordSync.enabled && !s.glasses.navigation.enabled &&
            s.glasses.link.phase in setOf(LinkPhase.DISCONNECTED, LinkPhase.FULL, LinkPhase.BLE_ONLY) &&
            !LocalModels.zipformer.downloading && !LocalModels.senseVoice.downloading && !LocalModels.nemotron.downloading
    }

    fun launchWork(work: suspend () -> Unit) {
        if (job != null) return
        val workJob = scope.launch(start = CoroutineStart.LAZY) {
            try { work() }
            catch (e: CancellationException) { status = "작업 취소"; throw e }
            catch (e: Exception) { status = e.message?.take(300) ?: "업데이트 작업 실패" }
            finally { job = null }
        }
        job = workJob
        workJob.start()
    }

    LaunchedEffect(Unit) {
        AppUpdateInstall.restore(app)
        signer = withContext(Dispatchers.IO) { runCatching { AppUpdate.installedSigner(app) }.getOrElse { "확인 실패" } }
    }
    DisposableEffect(Unit) {
        onDispose { job?.cancel(); downloaded?.delete() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("검증 앱 업데이트", style = MaterialTheme.typography.headlineSmall)
        Text("현재 버전: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text("설치 인증서 SHA-256: " + signer, style = MaterialTheme.typography.bodySmall)
        }
        Text("동일 패키지·서명·높은 버전만 설치합니다. 기존 설정과 기록을 지우지 않습니다.")
        Button(onClick = {
            downloaded?.delete(); downloaded = null; candidate = null; progress = 0
            status = "최신 검증 버전 확인 중"
            launchWork {
                candidate = AppUpdate.checkLatest(app)
                status = if (candidate == null) "더 높은 검증 배포본이 없습니다" else "새 버전이 있습니다"
            }
        }, enabled = !busy && AppUpdateInstall.sessionId == null) { Text("업데이트 확인") }
        candidate?.let { item ->
            Text(item.name + " (" + item.code + ")", style = MaterialTheme.typography.titleMedium)
            Text(item.notes.ifBlank { "배포 변경사항 없음" })
            Text("APK 크기: " + (item.size / 1024 / 1024) + " MB")
            if (downloaded == null) Button(onClick = {
                status = "APK 다운로드 및 무결성 확인 중"
                launchWork {
                    downloaded = AppUpdate.download(app, item) { count -> withContext(Dispatchers.Main) { progress = count } }
                    status = "다운로드 및 인증서 검증 완료"
                }
            }, enabled = !busy && AppUpdateInstall.sessionId == null) { Text("다운로드") }
            if (progress > 0) LinearProgressIndicator(progress = { (progress.toDouble() / item.size).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth())
            downloaded?.let { apk ->
                Button(onClick = {
                    if (!app.packageManager.canRequestPackageInstalls()) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + app.packageName))) }
                            .onFailure { status = "외부 APK 설치 설정을 열 수 없습니다" }
                    } else launchWork { AppUpdateInstall.install(app, item, apk, ::idle) }
                }, enabled = !busy && idle() && AppUpdateInstall.sessionId == null) { Text("설치 요청") }
            }
        }
        if (busy) TextButton(onClick = { job?.cancel() }) { Text("작업 취소") }
        AppUpdateInstall.confirmation?.let { confirm ->
            Button(onClick = {
                if (idle()) runCatching { context.startActivity(confirm) }
                    .onFailure { status = "설치 확인 화면을 열 수 없습니다. 설치 요청을 취소하고 다시 시도하세요." }
            }, enabled = !busy && idle()) { Text("Android 설치 확인") }
        }
        if (AppUpdateInstall.sessionId != null && !busy)
            TextButton(onClick = { AppUpdateInstall.cancel(app) }) { Text("설치 요청 취소") }
        if (!idle()) Text("설치 전 진행 중인 기록·번역·AI·모델 다운로드 및 외부 Agent/동기화 서비스를 종료하세요.")
        Text(status)
        if (AppUpdateInstall.status.isNotBlank()) Text(AppUpdateInstall.status)
        Text("자동 설치는 아직 활성화하지 않았습니다. Android 또는 Galaxy 보안 정책에서 설치 승인을 요청할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall)
    }
}
