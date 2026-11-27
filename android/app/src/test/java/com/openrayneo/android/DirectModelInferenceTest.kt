// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DirectModelInferenceTest {
    @Test fun sendsOnlyToTheChosenModelAndReadsProviderReportedModel() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"model":"test-resolved-model","choices":[{"finish_reason":"stop","message":{"content":"answer"}}]}"""))
        val inference = DirectModelInference()
        server.start()
        try {
            val profile = AiModelSelection.Profile("custom-12345678-1234-1234-1234-123456789abc", "Local", server.url("/v1").toString(), "test-selected-model", "")
            val done = CountDownLatch(1)
            val answer = AtomicReference<DirectModelInference.Answer>()
            inference.submit("test-1", profile, "question", "instruction", JSONObject().put("code", "TEST"), null, { true }) {
                answer.set(it.getOrNull()); done.countDown()
            }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("test-selected-model", JSONObject(request.body.readUtf8()).getString("model"))
            assertEquals("answer", answer.get()?.text)
            assertEquals("test-resolved-model", answer.get()?.reportedModel)
            val rejected = CountDownLatch(1)
            val failed = AtomicReference<Boolean>()
            inference.submit("test-2", profile, "stale", "", null, null, { false }) {
                failed.set(it.isFailure); rejected.countDown()
            }
            assertTrue(rejected.await(10, TimeUnit.SECONDS))
            assertEquals(true, failed.get())
            assertEquals(1, server.requestCount)
        } finally {
            inference.close()
            server.shutdown()
        }
    }
}
