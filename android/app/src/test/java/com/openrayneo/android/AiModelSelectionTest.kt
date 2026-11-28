// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiModelSelectionTest {
    private val gpt = AiModelSelection.Profile(AiModelSelection.GPT, "GPT", AiModelSelection.GPT_URL, "gpt-test", "test-gpt-key")
    private val custom = AiModelSelection.Profile("custom-12345678-1234-1234-1234-123456789abc", "Local", "http://127.0.0.1:12345/v1", "local-model", "")

    @Test fun savedProfilesSwitchEveryInferenceEndpointWithoutMutatingLegacy() {
        val cfg = JSONObject().put("modelUrl", "https://legacy.example/v1").put("modelName", "legacy-model").put("modelKey", "test-legacy-key")
        assertTrue(AiModelSelection.legacy(cfg))
        assertEquals("legacy-model", AiModelSelection.endpoint(cfg)?.model)
        cfg.put(AiModelSelection.PROFILES, AiModelSelection.withProfile(cfg, gpt))
        cfg.put(AiModelSelection.PROFILES, AiModelSelection.withProfile(cfg, custom))
        cfg.put(AiModelSelection.KEY, gpt.id)
        assertEquals(gpt, AiModelSelection.direct(cfg))
        assertEquals(gpt, AiModelSelection.endpoint(cfg))
        cfg.put(AiModelSelection.KEY, custom.id)
        assertEquals(custom, AiModelSelection.direct(cfg))
        assertEquals(custom, AiModelSelection.endpoint(cfg))
        assertEquals("legacy-model", cfg.getString("modelName"))
        assertEquals("test-legacy-key", cfg.getString("modelKey"))
        cfg.put(AiModelSelection.KEY, AiModelSelection.LEGACY)
        assertNull(AiModelSelection.direct(cfg))
        assertEquals("legacy-model", AiModelSelection.endpoint(cfg)?.model)
    }

    @Test fun unavailableOrUnknownSelectionCannotFallBackToAnotherProvider() {
        val cfg = JSONObject().put("modelUrl", "https://legacy.example/v1").put("modelName", "legacy-model").put("modelKey", "test-key")
        cfg.put(AiModelSelection.PROFILES, AiModelSelection.withProfile(cfg, gpt))
        for (id in listOf(AiModelSelection.RAYNEO, "missing", custom.id)) {
            cfg.put(AiModelSelection.KEY, id)
            assertFalse(AiModelSelection.legacy(cfg))
            assertNull(AiModelSelection.direct(cfg))
            assertNull(AiModelSelection.endpoint(cfg))
        }
    }

    @Test fun gptHostIsPinnedAndCredentialsAreNotInProfileString() {
        assertFalse(gpt.toString().contains(gpt.key))
        assertTrue(runCatching { AiModelSelection.withProfile(JSONObject(), gpt.copy(url = "https://wrong.example/v1")) }.isFailure)
        assertTrue(runCatching { AiModelSelection.withProfile(JSONObject(), gpt.copy(key = "")) }.isFailure)
        val cfg = JSONObject().put(AiModelSelection.KEY, gpt.id)
        cfg.put(AiModelSelection.PROFILES, "[{\"id\":\"gpt\",\"label\":\"GPT\",\"url\":\"https://wrong.example/v1\",\"model\":\"gpt-test\",\"key\":\"test-gpt-key\"}]")
        assertEquals(AiModelSelection.GPT_URL, AiModelSelection.direct(cfg)?.url)
    }

    @Test fun nativeExplicitVoiceRequiresStructuredToolRouteAndDoesNotRequirePrefix() {
        val p = NativeExplicitVoicePolicy
        assertEquals("안녕하세요", p.decode("안녕하세요", true)?.answer)
        assertEquals("안녕하세요", p.decode("{\"route\":\"answer\",\"answer\":\"안녕하세요\"}", true)?.answer)
        assertTrue(p.decode("{\"route\":\"agent\"}", true)?.agent == true)
        assertFalse(p.decode("{\"route\":\"agent\"}", false)?.agent == true)
        assertNull(p.decode("{\"route\":\"agent\",\"text\":\"다른 작업\"}", true))
        assertNull(p.decode("{\"route\":\"unknown\"}", true))
        assertNull(p.decode("[PASS]", true))
        assertFalse(p.prompt(true).contains("업무 실행"))
    }

    @Test fun stockSpeechMetadataFollowsEffectivePhoneRecognition() {
        val defaults = JSONObject()
        assertEquals("ko-KR", NativeSpeechLanguage.source(defaults))
        val caption = NativeSubtitleStart("test", "classic", "en-US", "zh-CN", "ahead")
        assertEquals("ko-KR", caption.withPhoneLanguage(defaults).source)
        assertEquals("", caption.withPhoneLanguage(defaults).target)
        val explicitPair = NativeSubtitleStart("test", "bilingual", "en-US", "ko-KR", "ahead")
        assertEquals(explicitPair, explicitPair.withPhoneLanguage(defaults))
        val multilingual = JSONObject().put("asrEngine", "local").put("asrLanguage", "ko,en")
        assertEquals("auto", NativeSpeechLanguage.source(multilingual))
        val hint = NativeHintStart("test", "en-US", "zh-CN", "ahead", true)
        val effective = hint.withPhoneLanguage(defaults).reply().getJSONObject("final_settings")
        assertEquals("ko-KR", effective.getString("src_lang"))
        assertEquals("ko-KR", effective.getString("dst_lang"))
        assertFalse(effective.has("source_language"))
    }

}
