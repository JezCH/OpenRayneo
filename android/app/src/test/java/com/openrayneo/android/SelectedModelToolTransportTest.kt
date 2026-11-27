// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SelectedModelToolTransportTest {
    private fun toolTurn(id: String = "call-1", name: String = "todo_add", args: String = """{"title":"test"}"""): String =
        JSONObject().put("model", "local-model").put("choices", JSONArray().put(JSONObject()
            .put("finish_reason", "tool_calls").put("message", JSONObject().put("role", "assistant")
                .put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject()
                    .put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args))))))).toString()
    private val answer = """{"model":"local-model","choices":[{"finish_reason":"stop","message":{"content":"done"}}]}"""

    @Test fun supportedSchemasAreNamespacedAndBadArgumentsCannotReachTools() {
        val groups = SelectedModelToolTransport.tools(true)
        val groupNames = (0 until groups.length()).map { groups.getJSONObject(it).getString("name") }
        assertEquals(setOf("device", "organizer", "context", "display"), groupNames.toSet())
        assertTrue((0 until groups.length()).all { groups.getJSONObject(it).getJSONArray("tools").length() <= 10 })
        assertEquals("todo_add", SelectedModelToolTransport.validate("call-1", "todo_add", """{"title":"x"}""", "organizer").name)
        for ((name,args,namespace) in listOf(
            Triple("todo_add", "{}", "organizer"),
            Triple("todo_add", """{"title":1}""", "organizer"),
            Triple("todo_add", """{"title":"x","request_id":"forged"}""", "organizer"),
            Triple("todo_add", """{"title":"x"}""", "device"),
            Triple("device_brightness", """{"value":1.5}""", "device"),
            Triple("record_query", "{}", "context"),
            Triple("shell", "{}", "device")
        )) assertTrue(runCatching { SelectedModelToolTransport.validate("call-1", name, args, namespace) }.isFailure)
    }

    @Test fun responsesCompletedToolCallIsParsedAndReplayedWithoutPaidFallback() {
        val terminal = SelectedModelTransport.Terminal(JSONObject("""{"status":"completed","model":"account-model","output":[{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"opaque"},{"type":"function_call","name":"todo_read","namespace":"organizer","call_id":"call_1","arguments":"{}","status":"completed"}]}"""), "")
        val turn = SelectedModelToolTransport.fromResponses(terminal)
        assertNull(turn.answer)
        assertEquals("todo_read", turn.calls.single().name)
        val p = AiModelSelection.Profile(AiModelSelection.CHATGPT_PLAN, "ChatGPT 구독", AiModelSelection.GPT_URL, "account-model", "")
        val history = JSONArray()
        SelectedModelToolTransport.append(history, turn)
        SelectedModelToolTransport.appendResult(p, history, turn.calls.single(), JSONObject().put("count", 2))
        assertEquals(3, history.length())
        assertEquals("opaque", history.getJSONObject(0).getString("encrypted_content"))
        assertEquals("function_call_output", history.getJSONObject(2).getString("type"))
        assertEquals("call_1", history.getJSONObject(2).getString("call_id"))
    }

    private fun runLoop(deny: Boolean, duplicate: Boolean) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(toolTurn()))
        if (!deny) server.enqueue(MockResponse().setBody(if (duplicate) toolTurn() else answer))
        server.start()
        val inference = DirectModelInference()
        try {
            val profile = AiModelSelection.Profile("custom-12345678-1234-1234-1234-123456789abc", "Local", server.url("/v1").toString(), "local-model", "")
            val executions = AtomicInteger()
            val done = CountDownLatch(1)
            val outcome = AtomicReference<Result<DirectModelInference.Answer>?>()
            val tool: SelectedToolExecutor = { name, args, allowed, complete ->
                assertEquals("todo_add", name); assertTrue(allowed()); assertTrue(args.getString("request_id").isNotBlank())
                executions.incrementAndGet()
                if (deny) complete(Result.failure(IllegalStateException("거절")))
                else complete(Result.success(JSONObject().put("readBack", true)))
                ({})
            }
            inference.submit("job", profile, "add a task", "instruction", null, null, { true }, toolExecutor = tool) {
                outcome.set(it); done.countDown()
            }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(1, executions.get())
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val payload = JSONObject(request.body.readUtf8())
            assertEquals("local-model", payload.getString("model"))
            assertTrue(payload.getJSONArray("tools").length() > 0)
            if (deny) {
                assertTrue(outcome.get()?.isFailure == true)
                assertEquals(1, server.requestCount)
            } else {
                val second = JSONObject(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8())
                assertEquals("local-model", second.getString("model"))
                val messages = second.getJSONArray("messages")
                val result = messages.getJSONObject(messages.length() - 1)
                assertEquals("tool", result.getString("role"))
                assertTrue(JSONObject(result.getString("content")).getBoolean("readBack"))
                if (duplicate) assertTrue(outcome.get()?.isFailure == true)
                else assertEquals("done", outcome.get()?.getOrNull()?.text)
                assertEquals(2, server.requestCount)
            }
        } finally { inference.close(); server.shutdown() }
    }
    @Test fun resultReturnedToSameModel() = runLoop(false, false)
    @Test fun deniedCallStopsWithoutAnotherModelRequest() = runLoop(true, false)
    @Test fun duplicateCallDoesNotExecuteTwice() = runLoop(false, true)
}
