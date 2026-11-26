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
    val updates = model.updateCheck
    val candidate = updates.candidate
    val currentCandidate = candidate != null && updates.error.isBlank() && !updates.checking
    var pendingInstall by remember { mutableStateOf<AppUpdate.Candidate?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("업데이트를 확인하세요") }
    var signer by remember { mutableStateOf("") }
    var openConfirmation by remember { mutableStateOf(false) }
    val busy = job != null || updates.downloading

    fun idle(): Boolean {
        val s = model.state
        return model.recommendedEditable && !s.deviceSettings.busy && !s.captionActive && !s.mediaCaption.busy &&
            !s.mediaTranslation.preparing && !s.offlinePreparing && !s.speechPlaying &&
            !s.chatContinuityBusy && s.approvals.isEmpty() && s.agentReviews.isEmpty() &&
            !s.agentServer.enabled && !s.recordSync.enabled && !s.glasses.navigation.enabled &&
            !s.glasses.disconnecting && !s.glasses.link.reconnectWaiting && !s.conversation.summarizing &&
            s.glasses.link.phase in setOf(LinkPhase.DISCONNECTED, LinkPhase.FULL, LinkPhase.BLE_ONLY) &&
            !LocalModels.zipformer.downloading && !LocalModels.senseVoice.downloading && !LocalModels.nemotron.downloading &&
            !KoreanKeywordModels.model.downloading
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

    fun requestPermission() {
        status = "이 앱의 APK 설치를 허용한 뒤 다시 눌러 주세요"
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + app.packageName))) }
            .onFailure { status = "외부 APK 설치 설정을 열 수 없습니다" }
    }

    suspend fun requestInstall(item: AppUpdate.Candidate, apk: File) {
        if (updates.checking || updates.error.isNotBlank() || updates.candidate != item) {
            status = "업데이트 정보가 변경되었습니다. 다시 확인한 뒤 설치하세요."
            return
        }
        if (!idle()) {
            status = "다운로드 완료 · 새 작업이 시작되어 설치를 보류합니다"
            return
        }
        openConfirmation = true
        try { AppUpdateInstall.install(app, item, apk) { idle() && !updates.checking && updates.error.isBlank() && updates.candidate == item } }
        catch (e: Exception) { openConfirmation = false; throw e }
    }

    // A download belongs to the app model, not to the screen. Only a still-visible
    // explicit install action may continue into Android's confirmation UI.
    LaunchedEffect(pendingInstall, updates.downloading, updates.downloaded, updates.candidate, updates.checking) {
        val item = pendingInstall ?: return@LaunchedEffect
        if (updates.downloading) return@LaunchedEffect
        pendingInstall = null
        val apk = updates.downloaded?.takeIf { updates.downloadCandidate == item } ?: return@LaunchedEffect
        if (updates.candidate != item || updates.checking || updates.error.isNotBlank()) return@LaunchedEffect
        if (!idle()) { status = "다운로드 완료 · 사용 중인 기능 종료 후 설치할 수 있습니다"; return@LaunchedEffect }
        launchWork { requestInstall(item, apk) }
    }

    val pendingConfirmation = AppUpdateInstall.confirmation
    LaunchedEffect(pendingConfirmation, busy) {
        // Only the current, explicit in-app install action may open Android's approval UI.
        // On recreation or when another activity is running, the manual confirmation button remains.
        if (pendingConfirmation != null && !busy && openConfirmation) {
            openConfirmation = false
            if (idle()) runCatching { context.startActivity(pendingConfirmation) }
                .onFailure { status = "Android 설치 확인 버튼으로 다시 시도하세요" }
        }
    }

    LaunchedEffect(Unit) {
        AppUpdateInstall.restore(app)
        signer = withContext(Dispatchers.IO) { runCatching { AppUpdate.installedSigner(app) }.getOrElse { "확인 실패" } }
    }
    DisposableEffect(Unit) {
        onDispose { job?.cancel() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("검증 앱 업데이트", style = MaterialTheme.typography.headlineSmall)
        Text("현재 버전: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text("설치 인증서 SHA-256: " + signer, style = MaterialTheme.typography.bodySmall)
        }
        Text("동일 패키지·서명·높은 버전만 설치합니다. 기존 설정과 기록을 지우지 않습니다.")
        Note("자동 확인 대상은 공개 GitHub Releases의 검증 채널입니다. Actions에만 생성된 APK는 자동으로 검색하거나 설치할 수 없습니다.")
        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/JezCH/OpenRayneo/actions/workflows/android-apk.yml"))) }.onFailure { status = "빌드 목록을 열 수 없습니다" } }) { Text("별도 검증 빌드 목록 열기") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = updates.automatic, onCheckedChange = updates::changeAutomatic,
                enabled = !busy && AppUpdateInstall.sessionId == null)
            Column { Text("자동 업데이트 확인"); Text("앱 실행·복귀 시, 6시간 간격", style = MaterialTheme.typography.bodySmall) }
        }
        Text("최근 성공: " + updates.lastCheckedLabel())
        Text(updates.summary)
        if (updates.error.isNotBlank()) Text(updates.error, style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            pendingInstall = null; status = ""
            updates.refresh(true)
        }, enabled = !busy && !updates.checking && AppUpdateInstall.sessionId == null) { Text("업데이트 확인") }
        if (updates.downloading) Note("화면을 나가도 앱이 실행되는 동안 다운로드를 계속합니다.")
        candidate?.let { item ->
            Text(item.name + " (" + item.code + ")", style = MaterialTheme.typography.titleMedium)
            Text(item.notes.ifBlank { "배포 변경사항 없음" })
            Text("전체 APK: " + (item.size / 1024 / 1024) + " MiB")
            item.delta?.let { patch -> Note("변경 패치: " + ((patch.size + 1048575) / 1048576) + " MiB · 기존 모델 재사용") }
            val cached = AppUpdate.hasDownloaded(app, item)
            Button(onClick = {
                if (!app.packageManager.canRequestPackageInstalls()) requestPermission()
                else model.attempt {
                    pendingInstall = item
                    updates.startDownload(item)
                }
            }, enabled = !busy && currentCandidate && idle() && AppUpdateInstall.sessionId == null) {
                Text(if (cached) "받아 둔 APK 검증 및 설치" else if (item.delta != null) "변경분 업데이트" else "다운로드 및 설치")
            }
            if (!cached) TextButton(onClick = { model.attempt { updates.startDownload(item) } },
                enabled = !busy && currentCandidate && AppUpdateInstall.sessionId == null) { Text("다운로드만") }
            if (!cached && item.delta != null) TextButton(onClick = {
                // Explicit opt-in for the much larger fallback; never silently download it.
                model.attempt { updates.startDownload(item, forceFull = true) }
            }, enabled = !busy && currentCandidate && AppUpdateInstall.sessionId == null) {
                Text("전체 APK 다운로드 (복구용)")
            }
            if (updates.downloadCandidate == item && updates.downloadProgress > 0 && updates.downloadTotal > 0)
                LinearProgressIndicator(progress = { (updates.downloadProgress.toDouble() / updates.downloadTotal).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
            if (cached) Note("이미 받은 설치 파일은 다시 다운로드하지 않고 검증 후 재사용합니다.")
        }
        if (updates.downloading) TextButton(onClick = { pendingInstall = null; updates.cancelDownload() }) { Text("다운로드 취소") }
        if (job != null) TextButton(onClick = { job?.cancel() }) { Text("설치 준비 취소") }
        if (updates.downloadStatus.isNotBlank()) Text(updates.downloadStatus)
        AppUpdateInstall.confirmation?.let { confirm ->
            Button(onClick = {
                if (idle()) runCatching { context.startActivity(confirm) }
                    .onFailure { status = "설치 확인 화면을 열 수 없습니다. 설치 요청을 취소하고 다시 시도하세요." }
            }, enabled = !busy && idle()) { Text("Android 설치 확인") }
        }
        if (AppUpdateInstall.sessionId != null && !busy)
            TextButton(onClick = { AppUpdateInstall.cancel(app) }) { Text("설치 요청 취소") }
        if (!idle()) {
            val s = model.state
            Text("설치 차단 원인: " + buildList {
                if (!s.available) add("연결 서비스 준비 중")
                if (s.deviceSettings.busy) add("안경 상태·설정 동기화")
                if (s.glasses.link.reconnectWaiting) add("자동 재연결 대기")
                if (s.glasses.disconnecting || s.glasses.link.phase !in setOf(LinkPhase.DISCONNECTED, LinkPhase.FULL, LinkPhase.BLE_ONLY)) add("연결 전환")
                if (s.conversation.running || s.conversation.stopping || s.conversation.summarizing || s.alwaysOnResumePending) add("기록·요약·자동 재개")
                if (s.nativeWakeAvailable || s.wakeActive || s.wakePending) add("음성 호출")
                if (s.translationActive || s.captionActive || s.mediaCaption.busy || s.mediaTranslation.preparing || s.offlinePreparing) add("통역·자막·모델 준비")
                if (s.phoneRunning || s.computerRunning || s.speechPlaying || s.chatContinuityBusy || s.approvals.isNotEmpty() || s.agentReviews.isNotEmpty()) add("AI·음성 출력·승인 작업")
                if (s.agentServer.enabled) add("외부 Agent 서버")
                if (s.recordSync.enabled) add("기록 동기화 서버")
                if (s.glasses.intent != DisplayIntent.Idle || s.glasses.session != null || s.glasses.navigation.enabled || s.teleprompter.busy || s.teleprompter.active != null) add("안경 화면·길 안내·프롬프터")
                if (s.migration.busy) add("기록 이전")
                if (LocalModels.zipformer.downloading || LocalModels.senseVoice.downloading || LocalModels.nemotron.downloading || KoreanKeywordModels.model.downloading) add("음성 모델 다운로드")
            }.joinToString(" · ").ifBlank { "현재 작업 상태 확인 필요" })
            if (s.available && s.glasses.link.reconnectWaiting) TextButton(onClick = {
                model.attempt { model.service().disconnect() }
            }) { Text("자동 재연결 중지 후 설치") }
        }
        if (status.isNotBlank()) Text(status)
        if (AppUpdateInstall.status.isNotBlank()) Text(AppUpdateInstall.status)
        Text("자동 설치는 아직 활성화하지 않았습니다. Android 또는 Galaxy 보안 정책에서 설치 승인을 요청할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall)
    }
}
