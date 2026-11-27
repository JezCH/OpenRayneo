// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DirectModelInferenceTest {
    @Test fun sendsOnlyToTheChosenModelAndReadsProviderReportedModel() {
        val hits = AtomicInteger()
        val sent = AtomicReference<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            hits.incrementAndGet()
            sent.set(JSONObject(exchange.requestBody.bufferedReader().use { it.readText() }))
            val body = """{"model":"test-resolved-model","choices":[{"finish_reason":"stop","message":{"content":"answer"}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val inference = DirectModelInference()
        server.start()
        try {
            val profile = AiModelSelection.Profile("custom-12345678-1234-1234-1234-123456789abc", "Local", "http://127.0.0.1:${server.address.port}/v1", "test-selected-model", "")
            val done = CountDownLatch(1)
            val answer = AtomicReference<DirectModelInference.Answer>()
            inference.submit("test-1", profile, "question", "instruction", JSONObject().put("code", "TEST"), null, { true }) {
                answer.set(it.getOrNull()); done.countDown()
            }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals("test-selected-model", sent.get().getString("model"))
            assertEquals("answer", answer.get()?.text)
            assertEquals("test-resolved-model", answer.get()?.reportedModel)
            val rejected = CountDownLatch(1)
            val failed = AtomicReference<Boolean>()
            inference.submit("test-2", profile, "stale", "", null, null, { false }) {
                failed.set(it.isFailure); rejected.countDown()
            }
            assertTrue(rejected.await(10, TimeUnit.SECONDS))
            assertEquals(true, failed.get())
            assertEquals(1, hits.get())
        } finally {
            inference.close()
            server.stop(0)
        }
    }
}
