// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Assert.*
import org.junit.Test

class StandaloneDeviceProtocolTest {
    @Test fun distinguishReadbackFromTransportAndPartialReplies() {
        assertEquals(DeviceReadback.FAILED, DeviceReadback.from(false, false))
        assertEquals(DeviceReadback.STATUS_ONLY, DeviceReadback.from(true, false))
        assertEquals(DeviceReadback.SETTINGS_ONLY, DeviceReadback.from(false, true))
        assertEquals(DeviceReadback.VERIFIED, DeviceReadback.from(true, true))
        assertEquals(1.toByte(), ProtocolCodec.pairingIntent(false))
        assertEquals(2.toByte(), ProtocolCodec.pairingIntent(true))
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
