// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import kotlin.math.sqrt

/** A fresh user-approved MediaProjection owns exactly one playback-only capture.
 * No video frames, audio files, playback control, or persistent projection token. */
internal class MediaPlaybackCaptureService : Service() {
    companion object {
        private const val ACTION_START = "openrayneo.MEDIA_START"
        private const val ACTION_STOP = "openrayneo.MEDIA_STOP"
        private const val CHANNEL = "media_caption_capture"
        private const val NOTIFICATION = 72
        private const val RATE = 16_000
        private const val CHUNK = 1_600
        const val YOUTUBE = "com.google.android.youtube"

        fun youtubeUid(context: Context): Int = try {
            context.packageManager.getApplicationInfo(YOUTUBE, PackageManager.ApplicationInfoFlags.of(0)).uid
        } catch (_: PackageManager.NameNotFoundException) {
            error("YouTube 앱을 찾지 못했습니다. 허용된 다른 미디어 모드를 선택하세요.")
        }
        fun startIntent(context: Context, id: String, scope: MediaCaptionScope, code: Int, consent: Intent) =
            Intent(context, MediaPlaybackCaptureService::class.java).setAction(ACTION_START)
                .putExtra("id", id).putExtra("scope", scope.name).putExtra("resultCode", code).putExtra("consent", consent)
    }

    private val main = Handler(Looper.getMainLooper())
    private var id: String? = null
    private var scope = MediaCaptionScope.YOUTUBE
    private var resultCode = Activity.RESULT_CANCELED
    private var consent: Intent? = null
    private var projection: MediaProjection? = null
    private var callback: MediaProjection.Callback? = null
    private var bridge: BridgeService? = null
    private var bound = false
    private var worker: Thread? = null
    private var finished = false
    @Volatile private var stopping = false
    @Volatile private var stopReason: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val owner = (binder as? BridgeService.LocalBinder)?.service
            val session = id
            if (finished || stopping || session == null || owner == null ||
                !owner.attachMediaCapture(session, ::requestStop)) {
                requestStop("캡처 시작 요청이 취소되었거나 안경 상태가 변경되었습니다.")
                return
            }
            bridge = owner
            try {
                val data = checkNotNull(consent) { "캡처 권한이 만료되었습니다." }
                consent = null
                // Android 14+: the mediaProjection foreground service is already running.
                val token = checkNotNull(getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(resultCode, data)) { "시스템 재생음 수집 권한이 없습니다." }
                projection = token
                val revoked = object : MediaProjection.Callback() {
                    override fun onStop() { requestStop("시스템에서 미디어 캡처를 종료했습니다. 다시 승인해야 합니다.") }
                }
                callback = revoked
                token.registerCallback(revoked, main)
                val capture = AudioPlaybackCaptureConfiguration.Builder(token)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                if (scope == MediaCaptionScope.YOUTUBE) capture.addMatchingUid(youtubeUid(this@MediaPlaybackCaptureService))
                else capture.addMatchingUsage(AudioAttributes.USAGE_GAME).excludeUid(Process.myUid())
                val config = capture.build()
                worker = Thread({ readAudio(owner, session, config) }, "openrayneo-media-pcm").also { it.start() }
            } catch (failure: Exception) { finish(failure.message ?: "미디어 캡처 시작 실패") }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bridge = null
            requestStop("안경 연결 서비스가 종료되었습니다.")
        }
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) = requestStop("안경 연결 서비스를 사용할 수 없습니다.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (id != null && intent.getStringExtra("id") == id) requestStop(null)
            else if (id == null) stopSelf()
            return START_NOT_STICKY
        }
        if (id != null) return START_NOT_STICKY
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        id = intent.getStringExtra("id")
        resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        consent = intent.getParcelableExtra("consent", Intent::class.java)
        if (id.isNullOrBlank() || resultCode != Activity.RESULT_OK || consent == null) {
            finish("새 시스템 캡처 승인이 필요합니다."); return START_NOT_STICKY
        }
        try {
            scope = MediaCaptionScope.valueOf(checkNotNull(intent.getStringExtra("scope")))
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "미디어 자막", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, NOTIFICATION,
                Intent(this, MediaPlaybackCaptureService::class.java).setAction(ACTION_STOP).putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle("OpenRayneo 미디어 자막")
                .setContentText("휴대폰 내부 재생음 처리 · 음원 저장 안 함")
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "중지", stop).build())
                .build()
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            bound = bindService(Intent(this, BridgeService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(bound) { "안경 연결 서비스에 접근할 수 없습니다." }
        } catch (failure: Exception) { finish(failure.message ?: "미디어 서비스 시작 실패") }
        return START_NOT_STICKY
    }

    private fun readAudio(owner: BridgeService, session: String, config: AudioPlaybackCaptureConfiguration) {
        var recorder: AudioRecord? = null
        var failure: String? = null
        try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "이 기기에서 16kHz 내부 재생음 수집을 준비할 수 없습니다 ($min)." }
            val record = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(min * 2, CHUNK * 2 * 4))
                .setAudioPlaybackCaptureConfig(config).build()
            recorder = record
            check(record.state == AudioRecord.STATE_INITIALIZED && record.sampleRate == RATE && record.channelCount == 1) {
                "내부 재생음 형식이 16kHz 모노와 일치하지 않습니다."
            }
            if (stopping) return
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "내부 재생음 수집 시작 실패" }
            val started = SystemClock.elapsedRealtime()
            main.post { if (!finished && !stopping) owner.mediaCaptureStarted(session) }
            val pcm = ShortArray(CHUNK)
            var samples = 0L
            var dropped = 0L
            var lastReport = 0L
            var firstPcm: Long? = null
            var nonzero = false
            while (!stopping) {
                val count = record.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                if (count < 0) error("내부 재생음 읽기 오류 ($count)")
                if (count == 0) { Thread.sleep(10); continue }
                if (stopping) break
                val now = SystemClock.elapsedRealtime()
                if (firstPcm == null) firstPcm = now - started
                samples += count
                var energy = 0.0
                for (i in 0 until count) { val v = pcm[i] / 32768.0; energy += v * v }
                val rms = sqrt(energy / count)
                nonzero = nonzero || rms > 0.0001
                if (!owner.acceptMediaPcm(session, pcm, count)) dropped += count
                if (now - lastReport >= 500) {
                    lastReport = now
                    val total = samples; val lost = dropped; val first = firstPcm; val heard = nonzero
                    main.post { if (!finished) owner.mediaCaptureStats(session, total, lost, rms, first, heard) }
                }
            }
        } catch (e: Exception) { if (!stopping) failure = e.message ?: "내부 오디오 처리 실패" }
        finally {
            recorder?.let { record ->
                runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
                record.release()
            }
            val reason = failure ?: stopReason
            main.post { finish(reason) }
        }
    }

    /** Main-thread stop; worker owns AudioRecord teardown, never join it on UI thread. */
    private fun requestStop(reason: String?) {
        if (finished) return
        if (reason != null) stopReason = reason
        stopping = true
        consent = null
        if (worker == null) finish(stopReason)
    }

    private fun finish(reason: String?, stop: Boolean = true) {
        if (finished) return
        finished = true
        stopping = true
        consent = null
        projection?.let { token ->
            callback?.let { runCatching { token.unregisterCallback(it) } }
            runCatching { token.stop() }
        }
        callback = null; projection = null
        id?.let { session -> bridge?.mediaCaptureEnded(session, reason) }
        bridge = null
        if (bound) { bound = false; unbindService(connection) }
        if (stop) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    override fun onDestroy() {
        finish(stopReason ?: "미디어 캡처 서비스 종료", stop = false)
        super.onDestroy()
    }
}
