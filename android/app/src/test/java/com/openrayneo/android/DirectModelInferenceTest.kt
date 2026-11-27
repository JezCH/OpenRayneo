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

    @Test fun subscriptionRequestUsesSupportedStatelessStreamingContract() {
        val messages = org.json.JSONArray().put(JSONObject().put("role", "system").put("content", "instruction"))
            .put(JSONObject().put("role", "user").put("content", "question"))
        val body = SelectedModelTransport.subscriptionBody("account-model", messages)
        assertEquals("account-model", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("developer", body.getJSONArray("input").getJSONObject(0).getString("role"))
        assertEquals(setOf("model", "input", "store", "stream"), body.keys().asSequence().toSet())
    }

    @Test fun subscriptionRequiresCompletedEventAndDoesNotReturnPartialOrQuotaFailure() {
        fun sse(vararg events: String) = okio.Buffer().writeUtf8(events.joinToString("") { "data: " + it + "\n\n" })
        val partial = """{"type":"response.output_text.delta","delta":"partial"}"""
        val complete = """{"type":"response.completed","response":{"status":"completed","model":"real-model","output":[{"type":"message","content":[{"type":"output_text","text":"actual answer"}]}]}}"""
        val answer = SelectedModelTransport.readSubscription(sse(partial, complete))
        assertEquals("actual answer", answer.text)
        assertEquals("real-model", answer.reportedModel)
        assertTrue(runCatching { SelectedModelTransport.readSubscription(sse(partial)) }.isFailure)
        val quota = """{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}"""
        val failed = runCatching { SelectedModelTransport.readSubscription(sse(partial, quota)) }.exceptionOrNull()
        assertTrue(failed?.message?.contains("한도") == true)
        val incomplete = """{"type":"response.incomplete","response":{"status":"incomplete"}}"""
        assertTrue(runCatching { SelectedModelTransport.readSubscription(sse(partial, incomplete)) }.isFailure)
    }

    @Test fun subscriptionCannotBeConfiguredWithAUserSuppliedKeyOrUnauthenticatedCatalog() {
        val profile = AiModelSelection.Profile(AiModelSelection.CHATGPT_PLAN, "ChatGPT 구독", AiModelSelection.GPT_URL, "unverified-model", "test-key")
        assertFalse(profile.configured)
        assertFalse(profile.copy(key = "").configured)
        assertTrue(runCatching { SelectedModelTransport.request(profile, org.json.JSONArray()) }.isFailure)
        assertTrue(runCatching { SelectedModelTransport.request(profile.copy(key = ""), org.json.JSONArray()) }.isFailure)
    }
}
