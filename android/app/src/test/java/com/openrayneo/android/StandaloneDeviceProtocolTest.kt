// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Assert.*
import org.junit.Test

class StandaloneDeviceProtocolTest {
    @Test fun nativeRecorderStateIsNotLifeLogOrSocketState() {
        assertEquals(NativeRecorderState.RECORDING, NativeRecorderState.from(1))
        assertEquals(NativeRecorderState.PAUSED, NativeRecorderState.from(2))
        assertEquals(NativeRecorderState.IDLE, NativeRecorderState.from(3))
        for (code in listOf(null, -1, 0, 4, 255)) assertEquals(NativeRecorderState.UNKNOWN, NativeRecorderState.from(code))
        assertTrue(NativeRecorderState.RECORDING.occupied)
        assertTrue(NativeRecorderState.PAUSED.occupied)
        assertFalse(NativeRecorderState.IDLE.occupied)
        assertFalse(NativeRecorderState.UNKNOWN.occupied)
    }
    @Test fun firmwareVersionRequestUsesEmptyFieldsExactlyAsUpstream() {
        val f = ProtocolCodec.StreamDecoder().append(ProtocolCodec.display(0x09, 1, 1, "", byteArrayOf())).single()
        assertEquals(0x09, f.protocol)
        assertArrayEquals(byteArrayOf(8, 1, 16, 1, 26, 0, 34, 0), f.payload)
    }
    @Test fun capabilityAndRecordingVisibilityUseSeparateWireContracts() {
        val capability = ProtocolCodec.StreamDecoder().append(ProtocolCodec.emptyCapabilityReply(0x6900)).single()
        assertEquals(6, capability.protocol)
        val query = ProtocolCodec.StreamDecoder().append(ProtocolCodec.capabilityQuery(0x6901)).single()
        assertEquals(6, query.protocol)
        assertEquals("""{"type":1}""", query.payload.toString(Charsets.UTF_8))
        assertEquals("""{"type":2,"ret":"OK","capabilities":[]}""", capability.payload.toString(Charsets.UTF_8))
        for (visible in listOf(true, false)) {
            val f = ProtocolCodec.StreamDecoder().append(ProtocolCodec.recordingVisibility(0x6800, visible)).single()
            assertEquals(0x0e, f.protocol)
            val msg = ProtocolCodec.message(f.payload)
            assertEquals(14, msg.type)
            assertEquals("""{"isAppForeground":$visible}""", msg.json)
            assertArrayEquals(byteArrayOf(), msg.audio)
        }
    }

    @Test fun distinguishReadbackFromTransportAndPartialReplies() {
        assertEquals(DeviceReadback.FAILED, DeviceReadback.from(false, false))
        assertEquals(DeviceReadback.STATUS_ONLY, DeviceReadback.from(true, false))
        assertEquals(DeviceReadback.SETTINGS_ONLY, DeviceReadback.from(false, true))
        assertEquals(DeviceReadback.VERIFIED, DeviceReadback.from(true, true))
        assertEquals(1.toByte(), ProtocolCodec.pairingIntent(false))
        assertEquals(2.toByte(), ProtocolCodec.pairingIntent(true))
    }
    @Test fun manualExistingBondUsesReconnectAndFreshRegistrationRemainsSeparate() {
        assertEquals(2.toByte(), ProtocolCodec.pairingIntentForLink(false, true))
        assertEquals(2.toByte(), ProtocolCodec.pairingIntentForLink(true, true))
        assertEquals(1.toByte(), ProtocolCodec.pairingIntentForLink(false, false))
        assertTrue(runCatching { ProtocolCodec.pairingIntentForLink(true, false) }.isFailure)
        assertEquals(2, ProtocolCodec.pairingStatus(byteArrayOf(23, 0, 1, 2)))
        assertEquals(1, ProtocolCodec.pairingStatus(byteArrayOf(22, 0, 1, 1)))
        assertNull(ProtocolCodec.pairingStatus(byteArrayOf(23, 0, 2, 2)))
        // Exact observed upstream raw notification, not a synthetic TLV.
        assertEquals(listOf(2, 0), ProtocolCodec.pairingStatusBytes(byteArrayOf(0x17, 0, 2, 2, 0)))
        assertEquals(2, ProtocolCodec.pairingStatus(byteArrayOf(0x17, 0, 2, 2, 0)))
        assertEquals(listOf(1, 255), ProtocolCodec.pairingStatusBytes(byteArrayOf(22, 0, 2, 1, -1)))
        assertNull(ProtocolCodec.pairingStatusBytes(byteArrayOf(31, 0, 1, 2)))
        assertNull(ProtocolCodec.pairingStatusBytes(byteArrayOf(23, 0, 1, 2, 0)))
    }
    @Test fun bleControlAllowsOnlyReadAndExplicitWakeEnable() {
        assertTrue(ProtocolCodec.allowedBleLauncher(1, "request_general_status", 0, 0, ""))
        assertFalse(ProtocolCodec.allowedBleLauncher(1, "request_general_status", 1, 0, ""))
        assertFalse(ProtocolCodec.allowedBleLauncher(1, "request_general_settings", 0, 0, ""))
        assertTrue(ProtocolCodec.allowedBleLauncher(4, "request_general_settings", 0, 0, ""))
        assertTrue(ProtocolCodec.allowedBleLauncher(16, "set_ai_state", 1, 0, ""))
        assertTrue(ProtocolCodec.allowedBleLauncher(16, "set_ai_voice_wakeup", 1, 0, ""))
        assertFalse(ProtocolCodec.allowedBleLauncher(16, "set_ai_voice_wakeup", 0, 0, ""))
        assertFalse(ProtocolCodec.allowedBleLauncher(16, "set_ai_voice_wakeup", "1", 0, ""))
        assertFalse(ProtocolCodec.allowedBleLauncher(5, "privacy_config", 1, 0, ""))
        assertFalse(ProtocolCodec.allowedBleLauncher(16, "set_ai_state", 1, 0, "unexpected"))
        assertFalse(ProtocolCodec.allowedBleLauncher(4, "request_general_settings", 1, 0, ""))
    }
    @Test fun hostVisibilityUsesDocumentedRawCommand() {
        for (visible in listOf(true, false)) {
            val frame = ProtocolCodec.StreamDecoder().append(ProtocolCodec.hostVisibility(0x6800, visible)).single()
            assertEquals(0x10, frame.protocol)
            assertArrayEquals(byteArrayOf(0x1f, 0, 1, if (visible) 2 else 1), frame.payload)
            assertFalse(frame.acknowledgement)
        }
    }
    @Test fun independentDeviceCommandDoesNotEnableAmbientAuthorization() {
        val q = "안경 배터리 알려줘"
        assertEquals(LocalDeviceQuery.Kind.BATTERY, LocalDeviceQuery.parseExplicit(q)?.kind)
        assertEquals(LocalDeviceQuery.Kind.BATTERY, LocalDeviceQuery.parse(q, ConversationSource.NATIVE, false)?.kind)
        assertNull(LocalDeviceQuery.parse(q, ConversationSource.ALWAYS_ON, false))
        assertNull(LocalDeviceQuery.parse(q, ConversationSource.REALTIME, false))
        assertNull(LocalDeviceQuery.parse(q, ConversationSource.NATIVE, true))
        assertEquals(LocalDeviceQuery.Kind.BRIGHTNESS_SET, LocalDeviceQuery.parseExplicit("안경 밝기 5단계로 설정해줘")?.kind)
        assertNull(LocalDeviceQuery.parseExplicit("배터리"))
    }

    @Test fun nativeSubtitleStartAdoptsOnlyKnownStockRequest() {
        fun request(mode: String = "classic", source: String = "ko-KR", target: String = "", sid: String = "device-session",
                    protocol: Int = 0x13, type: Int = 1): ProtocolCodec.Frame {
            val body = org.json.JSONObject().put("sid", sid).put("code", 0).put("settings",
                org.json.JSONObject().put("mode", mode).put("source_language", source).put("target_language", target).put("save_audio", true))
            return ProtocolCodec.StreamDecoder().append(ProtocolCodec.display(protocol, 1, type, body.toString())).single()
        }
        val accepted = checkNotNull(NativeSubtitleStart.parse(request()))
        assertTrue(accepted.captions)
        assertEquals("device-session", accepted.reply().getString("sid"))
        assertEquals(1, accepted.reply().getInt("code"))
        assertFalse(accepted.reply().getJSONObject("final_settings").getBoolean("save_audio"))
        assertFalse(checkNotNull(NativeSubtitleStart.parse(request("bilingual", "ko-KR", "en-US"))).captions)
        assertNull(NativeSubtitleStart.parse(request("multi_transcript")))
        assertNull(NativeSubtitleStart.parse(request("bilingual", "ko", "ja-JP")))
        assertNull(NativeSubtitleStart.parse(request("bilingual", "ko", "ko-KR")))
        assertNull(NativeSubtitleStart.parse(request(sid = "")))
        assertNull(NativeSubtitleStart.parse(request(protocol = 0x20)))
        assertNull(NativeSubtitleStart.parse(request(type = 2)))
    }

    @Test fun nativeSubtitleAdoptionKeepsDeviceSidAndOnlySendsActualCaptionText() {
        val request = NativeSubtitleStart("device-sid", "classic", "ko-KR", "", "ahead")
        val before = GlassesState(link = LinkState(LinkPhase.FULL), ownership = DisplayOwnership.YIELDED)
        val adoption = GlassesCoordinator.reduce(before, GlassesEvent.StartNativeSubtitle(request))
        val session = checkNotNull(adoption.state.session)
        assertEquals(request, session.nativeSubtitle)
        assertTrue(adoption.state.captionMode)
        assertTrue(session.content.text.isEmpty())
        assertEquals(session, adoption.effects.filterIsInstance<GlassesEffect.StartPage>().single().session)
        assertTrue(GlassesCoordinator.reduce(before.copy(link = LinkState(LinkPhase.BLE_ONLY)), GlassesEvent.StartNativeSubtitle(request)).effects.isEmpty())
        assertTrue(GlassesCoordinator.reduce(adoption.state, GlassesEvent.StartNativeSubtitle(request)).effects.isEmpty())
        val caption = GlassesCoordinator.reduce(adoption.state, GlassesEvent.TranslationText(TranslationView("실시간 자막", "한국어 문장", false)))
        val updated = caption.effects.filterIsInstance<GlassesEffect.UpdatePage>().single().content
        assertEquals("한국어 문장", updated.text)
        assertFalse(updated.final)
        val frames = mutableListOf<ProtocolCodec.Frame>()
        val signals = mutableListOf<PageSignal>()
        val timers = mutableListOf<Runnable>()
        val display = GlassesDisplay({ bytes, done ->
            frames.add(ProtocolCodec.StreamDecoder().append(bytes).single()); done(Result.success(Unit))
        }, { task, _ -> timers.add(task); Unit }, { task -> timers.remove(task); Unit }, {}, { _, signal -> signals.add(signal); Unit }, { _, _ -> }, {})
        display.start(session)
        assertEquals(1, frames.size)
        assertEquals(0x13, frames.single().protocol)
        assertEquals(2, ProtocolCodec.message(frames.single().payload).type)
        assertEquals("device-sid", org.json.JSONObject(ProtocolCodec.message(frames.single().payload).json!!).getString("sid"))
        assertTrue(signals.any { it is PageSignal.AudioAwaitingPeer })
        assertFalse(signals.any { it is PageSignal.AudioAccepted })
        timers.toList().forEach { it.run() }
        assertEquals(1, frames.size)
        display.update(session.id, updated)
        val m = ProtocolCodec.message(frames.last().payload)
        assertEquals(5, m.type)
        val body = org.json.JSONObject(m.json!!)
        assertEquals("device-sid", body.getString("sid"))
        assertEquals(3, body.getInt("mode"))
        assertEquals(0, body.getInt("status"))
        assertEquals("한국어 문장", body.getJSONObject("content").getString("source_transcript"))
        assertFalse(body.getJSONObject("content").has("target_translation"))
        display.stop(session.id)
        val stop = ProtocolCodec.message(frames.last().payload)
        assertEquals(3, stop.type)
        assertEquals(10, org.json.JSONObject(stop.json!!).getInt("reason_code"))
    }
}
