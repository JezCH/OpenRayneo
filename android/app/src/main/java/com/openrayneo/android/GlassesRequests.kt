// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One device-operation queue for both UI and Agent calls; wire callbacks remain on the main thread. */
internal class GlassesRequests(private val main: Handler, private val write: (ByteArray, (Result<Unit>) -> Unit) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    private var operationGeneration = 0
    private var schedulingAllowed: () -> Boolean = { true }
    private var operationAllowed: () -> Boolean = { true }
    fun permission(): () -> Boolean = schedulingAllowed
    fun <T> withPermission(allowed: () -> Boolean, action: () -> T): T {
        val old = schedulingAllowed; schedulingAllowed = allowed
        return try { action() } finally { schedulingAllowed = old }
    }
    private var sequence = 0x6200
    private data class Pending(val protocol: Int, val matches: (Int, JSONObject) -> Boolean,
        val latch: CountDownLatch, var result: JSONObject? = null, var error: Throwable? = null)
    private var pending: Pending? = null
    var observe: (Int, Int, JSONObject) -> Unit = { _, _, _ -> }
    fun <T> perform(work: () -> T, completion: (Result<T>) -> Unit) {
        val token = generation; val allowed = schedulingAllowed
        worker.execute {
            operationGeneration = token; operationAllowed = allowed
            val result = runCatching { check(token == generation && allowed()) { "기기 작업이 취소됨" }; work() }
            main.post { completion(result) }
        }
    }
    fun request(protocol: Int, type: Int, body: JSONObject, wait: Boolean = true,
                matches: (Int, JSONObject) -> Boolean = { reply, _ -> reply == type }): JSONObject? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "기기 요청이 UI를 막아서는 안 됨" }
        val operation = Pending(protocol, matches, CountDownLatch(1))
        val token = operationGeneration; val allowed = operationAllowed
        main.post {
            if (token != generation || !allowed()) { operation.error = IllegalStateException("기기 연결이 전환되어 이전 작업이 취소됨"); operation.latch.countDown(); return@post }
            pending = operation
            try { write(ProtocolCodec.display(protocol, sequence++ and 65535, type, body.toString())) { result ->
                if (result.isFailure) { operation.error = result.exceptionOrNull(); operation.latch.countDown() }
                else if (!wait) operation.latch.countDown()
            } } catch (error: Exception) { operation.error = error; operation.latch.countDown() }
        }
        val replied = operation.latch.await(6, TimeUnit.SECONDS)
        main.post { if (pending === operation) pending = null }
        operation.error?.let { throw it }
        if (!replied) throw IllegalStateException("기기가 제때 응답하지 않았습니다. 변경 결과는 재조회 확인 대기")
        return operation.result
    }
    fun sendRaw(frame: ByteArray) {
        check(Looper.myLooper() != Looper.getMainLooper())
        val token = operationGeneration; val allowed = operationAllowed
        val latch = CountDownLatch(1); var error: Throwable? = null
        main.post {
            if (token != generation || !allowed()) { error = IllegalStateException("연결이 전환되어 전송 취소됨"); latch.countDown(); return@post }
            try { write(frame) { result -> error = result.exceptionOrNull(); latch.countDown() } }
            catch (failure: Exception) { error = failure; latch.countDown() }
        }
        check(latch.await(6, TimeUnit.SECONDS)) { "데이터 전송 시간 초과" }
        error?.let { throw it }
    }
    fun receive(frame: ProtocolCodec.Frame) {
        if (frame.protocol !in setOf(0x0f, 0x16, 0x14, 0x0e)) return
        val message = ProtocolCodec.message(frame.payload)
        val body = message.json?.let(::JSONObject) ?: return
        observe(frame.protocol, message.type, body)
        val current = pending ?: return
        if (current.protocol == frame.protocol && current.matches(message.type, body)) {
            current.result = body; pending = null; current.latch.countDown()
        }
    }
    fun disconnected() { generation++; pending?.let { it.error = IllegalStateException("안경 연결이 끊김"); it.latch.countDown() }; pending = null }
    fun close() { disconnected(); worker.shutdownNow() }
}
