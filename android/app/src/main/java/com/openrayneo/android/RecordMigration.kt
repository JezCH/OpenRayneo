// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class MigrationState(
    val busy: Boolean = false, val prepared: Boolean = false,
    val records: Int = 0, val people: Int = 0, val skipped: Int = 0,
    val audioBytes: Long = 0, val importedRecords: Int = 0, val importedPeople: Int = 0,
    val failed: Int = 0, val status: String = "이전할 기존 앱을 연결하세요"
)

/** Explicit, same-phone read-only sync client. No token persistence, no overwrite or source mutation. */
internal class RecordMigration(private val main: Handler, private val repository: ConversationRepository,
    private val audio: MeetingAudioStore, private val changed: () -> Unit, private val refresh: () -> Unit) {
    private data class Plan(val port: Int, val token: String, val records: List<ConversationRecord>,
        val people: List<VoicePerson>, val skipped: Int, val audioBytes: Long)
    private val worker = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
        .followSslRedirects(false).connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private var plan: Plan? = null
    @Volatile private var cancelled = false
    @Volatile private var closed = false
    @Volatile private var activeCall: Call? = null
    var state = MigrationState(); private set

    private fun publish(next: MigrationState) { if (!closed) { state = next; changed() } }
    private fun onMain(action: () -> Unit) { main.post { if (!closed) action() } }
    private fun checkRunning() { check(!cancelled && !closed) { "이전 중지" } }
    private fun request(port: Int, token: String, path: String, body: JSONObject? = null): Response {
        checkRunning()
        val builder = Request.Builder().url("http://127.0.0.1:$port$path").header("Authorization", "Bearer $token")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        val call = http.newCall(builder.build())
        activeCall = call
        if (cancelled || closed) call.cancel()
        val response = call.execute()
        if (!response.isSuccessful) { val code = response.code; response.close(); error("기존 앱 응답 오류 ($code)") }
        return response
    }
    fun preview(port: Int, token: String) {
        check(!closed && !state.busy)
        require(port in 1024..65535 && token.length in 16..256 && token.all { it.code in 33..126 }) { "기존 앱 포트와 전용 토큰을 확인하세요" }
        plan = null; cancelled = false
        publish(MigrationState(busy = true, status = "기존 기록 목록 확인 중"))
        worker.execute {
            try {
                val records = linkedMapOf<String, ConversationRecord>()
                val people = linkedMapOf<String, VoicePerson>()
                var cursor = 0L
                var totalBytes = 0L
                var pages = 0
                do {
                    checkRunning(); check(++pages <= 2000) { "이전 목록이 너무 큽니다" }
                    val query = JSONObject().put("protocolVersion", 1).put("cursor", cursor).put("limit", 10)
                    val data = request(port, token, "/v1/sync/exchange", query).use { response ->
                        val source = checkNotNull(response.body).source()
                        source.request(8_388_609L)
                        check(source.buffer.size <= 8_388_608L) { "한 페이지의 기록 크기 제한 초과" }
                        totalBytes += source.buffer.size
                        check(totalBytes <= 67_108_864L) { "한 번에 이전할 기록 목록 크기 제한 초과" }
                        JSONObject(source.readUtf8())
                    }
                    check(data.getInt("protocolVersion") == 1)
                    val rs = data.getJSONArray("records")
                    val ps = data.getJSONArray("people")
                    check(rs.length() + ps.length() <= 10)
                    for (i in 0 until rs.length()) {
                        val row = ConversationRecord.parse(rs.getJSONObject(i))
                        require(row.id.matches(Regex("[A-Za-z0-9-]{1,80}")) && row.revision > 0)
                        check(records.put(row.id, row) == null) { "기존 앱 기록이 변경되었습니다. 다시 확인하세요" }
                    }
                    for (i in 0 until ps.length()) {
                        val person = VoicePerson.parse(ps.getJSONObject(i))
                        require(person.id.matches(Regex("[A-Za-z0-9-]{1,80}")) && person.revision > 0)
                        check(people.put(person.id, person) == null) { "기존 앱 인물 정보가 변경되었습니다. 다시 확인하세요" }
                    }
                    check(records.size + people.size <= 5000) { "한 번에 이전할 항목 수 제한 초과" }
                    val next = data.getLong("cursor")
                    val more = data.getBoolean("hasMore")
                    check(next >= cursor && (!more || next > cursor)) { "기록 페이지 순서 오류" }
                    cursor = next
                } while (more)
                val eligible = records.values.filter { row ->
                    row.mode != ConversationMode.ENROLL && row.endedAt != null && !row.audioPending && !row.processingPending &&
                        row.audioChannels in 1..2 && row.audioSamples >= 0 &&
                        (row.transcriptOnly || row.audioSamples <= (Int.MAX_VALUE - 44L) / (2 * row.audioChannels)) &&
                        !repository.hasRecord(row.id)
                }
                val persons = people.values.filterNot { repository.hasPerson(it.id) }
                val bytes = eligible.filterNot { it.transcriptOnly }.sumOf { it.audioSamples * 2 * it.audioChannels }
                val next = Plan(port, token, eligible, persons, records.size - eligible.size + people.size - persons.size, bytes)
                checkRunning()
                onMain {
                    if (cancelled) publish(MigrationState(status = "목록 확인 중지"))
                    else { plan = next; publish(MigrationState(prepared = true, records = eligible.size, people = persons.size,
                        skipped = next.skipped, audioBytes = bytes, status = "이전할 항목을 확인한 후 시작하세요")) }
                }
            } catch (_: Exception) {
                onMain { publish(MigrationState(status = if (cancelled) "목록 확인 중지" else "목록 확인 실패 · 기존 앱 동기화, 토큰, 기록 크기를 확인하세요")) }
            } finally { activeCall = null }
        }
    }
    fun transfer() {
        check(!closed && !state.busy)
        val selected = checkNotNull(plan) { "먼저 이전 목록을 확인하세요" }
        plan = null; cancelled = false
        val initial = state.copy(busy = true, prepared = false, status = "기존 기록 가져오는 중")
        publish(initial)
        worker.execute {
            var importedPeople = 0; var importedRecords = 0; var failed = 0; var skipped = selected.skipped
            fun update(status: String, busy: Boolean = true) {
                val next = initial.copy(busy = busy, importedPeople = importedPeople, importedRecords = importedRecords,
                    failed = failed, skipped = skipped, status = status)
                onMain { publish(next); refresh() }
            }
            try {
                for (person in selected.people) {
                    checkRunning()
                    try { if (repository.importPerson(person)) importedPeople++ else skipped++ }
                    catch (_: Exception) { failed++ }
                }
                for (record in selected.records) {
                    checkRunning()
                    if (repository.hasRecord(record.id)) { skipped++; continue }
                    var newAudio: String? = null
                    var committed = false
                    try {
                        if (!record.transcriptOnly && record.audioSamples > 0L) {
                            val id = UUID.randomUUID().toString()
                            request(selected.port, selected.token, "/v1/sync/audio?recordId=" + record.id).use { response ->
                                val body = checkNotNull(response.body)
                                require(body.contentLength() == 44L + record.audioSamples * 2 * record.audioChannels)
                                body.byteStream().use { audio.importWav(id, it, record.audioSamples, record.audioChannels) { cancelled || closed } }
                            }
                            newAudio = id
                        }
                        checkRunning()
                        committed = repository.importCompleted(record.copy(audioId = newAudio))
                        if (committed) importedRecords++ else skipped++
                    } catch (_: Exception) { if (!cancelled && !closed) failed++ }
                    finally { if (!committed) newAudio?.let { audio.discardUncommittedImport(it) }; activeCall = null }
                    update("기록 가져오는 중")
                }
            } catch (_: Exception) { if (!cancelled && !closed) failed++ }
            finally {
                update(if (cancelled) "이전 중지 · 이미 저장한 항목은 유지" else if (failed > 0) "일부 이전 실패 · 기존 앱 유지 후 다시 확인" else "선택 가능한 기록 이전 완료", false)
            }
        }
    }
    fun cancel() {
        cancelled = true; plan = null; activeCall?.cancel()
        if (!state.busy) publish(MigrationState(status = "이전 목록 지움"))
        else publish(state.copy(status = "이전 중지 요청 · 저장 중인 항목 정리"))
    }
    fun close() {
        closed = true; cancelled = true; plan = null; activeCall?.cancel()
        worker.shutdownNow(); http.dispatcher.cancelAll(); http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}
