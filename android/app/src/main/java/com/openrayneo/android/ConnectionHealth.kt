// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded local transport evidence; no raw messages, MACs, tokens or microphone content. */
internal class ConnectionHealth(private val context: Context) {
    private val prefs = context.getSharedPreferences("connection_health", Context.MODE_PRIVATE)
    init {
        // A persisted FULL marker without a normal teardown is evidence of an interrupted
        // service session, not proof of an OS kill (app update/reboot are also possible).
        if (prefs.getBoolean("active", false)) event("이전 서비스 종료 기록 누락", active = false)
    }
    fun clearActive() { runCatching { prefs.edit().putBoolean("active", false).apply() } }
    fun event(kind: String, reason: String = "", seconds: Long? = null, active: Boolean? = null) {
      runCatching {
        val power = context.getSystemService(PowerManager::class.java)
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        val category = when {
            reason == "휴대폰 블루투스 꺼짐" -> "휴대폰 블루투스 꺼짐"
            reason.startsWith("BLE 연결 종료:") -> "BLE 종료 " + Regex("status=\\d+, state=\\d+").find(reason)?.value.orEmpty()
            reason.startsWith("BLE 전송 요청 실패:") -> "BLE 요청 " + Regex("code=\\d+").find(reason)?.value.orEmpty()
            reason.startsWith("BLE 쓰기 콜백 실패:") -> "BLE 쓰기 " + Regex("status=\\d+").find(reason)?.value.orEmpty()
            reason.startsWith("BLE 쓰기 응답") -> "BLE 쓰기 시간 초과"
            reason.startsWith("데이터 연결 종료") -> "SPP 수신 종료"
            reason.startsWith("데이터 전송 실패") -> "SPP 송신 실패"
            reason.startsWith("SPP") -> "SPP 연결 실패"
            reason.startsWith("연결 시간 초과") -> "연결 협상 시간 초과"
            reason.startsWith("자동 감지") -> "BLE 자동 감지 실패"
            reason.contains("페어링") -> "기존 페어링 확인 필요"
            reason == "연결 끊김" -> "수동 종료 또는 서비스 종료"
            reason.isBlank() -> ""
            else -> "기타 연결 오류 (상세 로그 참조)"
        }
        val line = listOf(time, kind, category, seconds?.let { "연결 ${it}초" }.orEmpty(),
            if (power.isInteractive) "화면 켜짐" else "화면 꺼짐",
            if (power.isPowerSaveMode) "절전 켜짐" else "절전 꺼짐").filter { it.isNotBlank() }.joinToString(" · ")
        val rows = prefs.getString("events", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        val editor = prefs.edit().putString("events", (rows + line).takeLast(20).joinToString("\n"))
        if (active != null) editor.putBoolean("active", active)
        editor.apply()
      }.onFailure { android.util.Log.w("OpenRayneo", "연결 진단 저장 실패", it) }
    }
    fun summary(): String = prefs.getString("events", "").orEmpty().ifBlank { "아직 연결 진단 기록이 없습니다." }
}
