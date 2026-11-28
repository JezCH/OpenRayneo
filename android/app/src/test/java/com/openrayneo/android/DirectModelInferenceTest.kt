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

    @Test fun explicitToolProposalUsesActualExecutorAndReturnsObservedResult() {
        val server = MockWebServer()
        fun response(content: String) = MockResponse().setBody(JSONObject().put("model", "test-model").put("choices",
            org.json.JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", content)))).toString())
        server.enqueue(response("""{"tool":"device_read","arguments":{}}"""))
        server.enqueue(response("""{"answer":"배터리 73%"}"""))
        val inference = DirectModelInference()
        server.start()
        try {
            val profile = AiModelSelection.Profile("custom-12345678-1234-1234-1234-123456789abc", "Local", server.url("/v1").toString(), "test-model", "")
            val done = CountDownLatch(1)
            val answer = AtomicReference<DirectModelInference.Answer>()
            val calls = java.util.concurrent.atomic.AtomicInteger()
            inference.submit("tool-1", profile, "안경 배터리 확인해줘", "", null, null, { true },
                toolExecutor = { name, args, allowed, completion ->
                    assertEquals("device_read", name); assertEquals(0, args.length()); assertTrue(allowed())
                    calls.incrementAndGet()
                    completion(Result.success(JSONObject().put("battery", 73)))
                    val cancel: () -> Unit = {}
                    cancel
                }) { answer.set(it.getOrNull()); done.countDown() }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(1, calls.get()); assertEquals("배터리 73%", answer.get()?.text)
            assertTrue(answer.get()?.usedTools == true)
            server.takeRequest(2, TimeUnit.SECONDS)
            val second = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
            assertTrue(second.contains("73"))
            assertTrue(runCatching { SelectedModelToolTransport.decode("""{"tool":"device_brightness","arguments":{"value":"17"}}""", "밝기 조절") }.isFailure)
            assertTrue(runCatching { SelectedModelToolTransport.decode("""{"tool":"record_query","arguments":{}}""", "질문") }.isFailure)
            assertTrue(runCatching { SelectedModelToolTransport.decode("""{"tool":"native_navigation_start","arguments":{"destination":"버거킹"}}""", "오늘 날씨") }.isFailure)
            assertNotNull(SelectedModelToolTransport.decode("""{"tool":"native_navigation_start","arguments":{"destination":"버거킹"}}""", "버거킹을 길 안내 해줘").call)
            assertEquals("그냥 답변", SelectedModelToolTransport.decode("그냥 답변", "질문").answer)
            assertTrue(runCatching { SelectedModelToolTransport.decode("""{"tool":"device_read","arguments":{}} {"answer":"other"}""", "안경 상태") }.isFailure)
            assertTrue(runCatching { SelectedModelToolTransport.decode("""{"tool":"todo_add","arguments":{"title":"x","request_id":"fake"}}""", "할 일") }.isFailure)
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
