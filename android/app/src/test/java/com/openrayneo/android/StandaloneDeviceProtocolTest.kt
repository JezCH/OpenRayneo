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
        assertEquals(2.toByte(), ProtocolCodec.pairingIntentForLink(false, true, true))
        assertEquals(2.toByte(), ProtocolCodec.pairingIntentForLink(true, true, true))
        assertEquals(1.toByte(), ProtocolCodec.pairingIntentForLink(false, false, false))
        assertEquals(1.toByte(), ProtocolCodec.pairingIntentForLink(false, true, false))
        assertEquals(1.toByte(), ProtocolCodec.pairingIntentForLink(false, false, true))
        assertTrue(runCatching { ProtocolCodec.pairingIntentForLink(true, true, false) }.isFailure)
        assertTrue(runCatching { ProtocolCodec.pairingIntentForLink(true, false, true) }.isFailure)
        assertEquals(2, ProtocolCodec.pairingStatus(byteArrayOf(23, 0, 1, 2)))
        assertEquals(1, ProtocolCodec.pairingStatus(byteArrayOf(22, 0, 1, 1)))
        assertNull(ProtocolCodec.pairingStatus(byteArrayOf(23, 0, 2, 2)))
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
}
