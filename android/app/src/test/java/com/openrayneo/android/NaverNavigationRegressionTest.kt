// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Assert.*
import org.junit.Test

class NaverNavigationRegressionTest {
    @Test fun reciprocalNavigationCapabilityUsesExactHostIdAndWithdrawsWhenUnavailable() {
        fun decode(reply: Boolean, supported: Boolean): org.json.JSONObject {
            val frame = ProtocolCodec.StreamDecoder().append(
                ProtocolCodec.navigationHostCapabilities(0x6901, reply, supported)).single()
            assertEquals(0x06, frame.protocol)
            return org.json.JSONObject(frame.payload.toString(Charsets.UTF_8))
        }
        val available = decode(true, true)
        assertEquals(2, available.getInt("type"))
        assertEquals("OK", available.getString("ret"))
        val item = available.getJSONArray("capabilities").getJSONObject(0)
        assertEquals(1, item.length())
        assertEquals("1.0.0.0", item.getString("app.navigation.main"))
        val unavailable = decode(true, false)
        assertEquals(0, unavailable.getJSONArray("capabilities").length())
        val change = decode(false, true)
        assertEquals(3, change.getInt("type"))
        assertFalse(change.has("ret"))
        assertEquals(1, change.getJSONArray("capabilities").length())
        val withdrawn = decode(false, false)
        assertEquals(3, withdrawn.getInt("type"))
        assertEquals(0, withdrawn.getJSONArray("capabilities").length())
    }

    @Test fun explicitCommandsAndNonCommands() {
        val c = NaverVoiceCommand
        val expected = mapOf(
            "안경 길 안내 종료해 줘" to NaverVoiceCommand.Control.STOP_NATIVE,
            "안경 내비게이션 종료!" to NaverVoiceCommand.Control.STOP_NATIVE,
            "안경 안내 꺼 줘" to NaverVoiceCommand.Control.STOP_NATIVE,
            "안경 길 안내 시작해 줘" to NaverVoiceCommand.Control.START_NATIVE,
            "안경 길 안내 재개해 줘" to NaverVoiceCommand.Control.START_NATIVE,
            "안경 네비를 켜봐" to NaverVoiceCommand.Control.START_NATIVE,
            "안경 내비 켜줘" to NaverVoiceCommand.Control.START_NATIVE,
            "안경 알림 안내 재개해 줘" to NaverVoiceCommand.Control.START_HUD,
            "안경 알림 안내 종료해 줘" to NaverVoiceCommand.Control.STOP_HUD,
            "길찾기 취소해 줘" to NaverVoiceCommand.Control.CANCEL_REQUEST,
            "길 안내 요청 취소" to NaverVoiceCommand.Control.CANCEL_REQUEST,
            "길 안내 상태 알려 줘?" to NaverVoiceCommand.Control.STATUS,
            "내비게이션 상태 알려줘" to NaverVoiceCommand.Control.STATUS,
            "네이버지도 길 안내 종료해 줘" to NaverVoiceCommand.Control.STOP_MAP_UNSUPPORTED,
            "길 안내 종료해줘" to NaverVoiceCommand.Control.STOP_MAP_UNSUPPORTED
        )
        expected.forEach { (question, control) ->
            assertEquals(question, control, c.control(question))
            assertNull(question, c.parse(question))
        }
        listOf("안경 길 안내 종료하지 마", "안경 밝기 올려줘", "길 안내 종료하는 법 알려줘",
            "네이버지도 길 안내 종료해 줘라고 말했어", "길 안내를 종료했다", "인천시청으로 안내해 줘",
            "안경 길 안내 종료해 줘 그리고 기록 삭제", "", "타이머 취소").forEach { assertNull(it, c.control(it)) }
        assertEquals(NaverVoiceCommand.Mode.WALK, c.parse("인천시청까지 걸어서 안내해 줘")?.mode)
        assertEquals("인천시청", c.parse("인천시청까지 걸어서 안내해 줘")?.destination)
        assertEquals(false, c.parse("인천시청까지 걸어서 안내해 줘")?.phoneApp)
        assertEquals(false, c.parse("네이버지도에서 인천시청까지 걸어서 안내해 줘")?.phoneApp)
        assertEquals(true, c.parse("네이버지도에서 인천시청까지 걸어서 안내해 줘")?.naverRequested)
        assertEquals(false, c.parse("집까지 길찾기 해 줘")?.naverRequested)
        assertEquals(true, c.parse("휴대폰에서 네이버지도 인천시청까지 걸어서 안내해 줘")?.phoneApp)
        assertEquals(true, c.parse("네이버지도에서 강남역 검색해 줘")?.phoneApp)
        assertEquals(false, c.parse("네이버지도에서 강남역 검색해 줘")?.routeRequested)
        assertNull(c.parse("서울역으로 안내하지 마"))
        assertEquals("집", c.parse("집까지 길찾기 해 줘")?.destination)
        assertEquals("회사", c.parse("회사로 차로 안내해 줘")?.destination)
        assertNull(c.parse("네이버지도에서 집 검색해 줘"))
        val frame = ProtocolCodec.StreamDecoder().append(ProtocolCodec.navigationPresetSync(
            0x6a01, 7, "preset-request", 2, "1234567890abcdef", "fedcba0987654321")).single()
        assertEquals(0x20, frame.protocol)
        val message = ProtocolCodec.message(frame.payload)
        assertEquals(1, message.version)
        assertEquals(0x10, message.type)
        assertEquals(7L, message.sequence)
        assertEquals(1L, message.mode)
        assertNull(message.audio)
        assertEquals("""{"request_id":"preset-request","revision":2,"home":{"available":true,"preset_id":"1234567890abcdef"},"company":{"available":true,"preset_id":"fedcba0987654321"}}""", message.json)
    }

    @Test fun liveSessionUsesPeerSidAndNeverRefreshesStaleGuidance() {
        val sid = "0123456789abcdef0123456789abcdef"
        val params = NativeNavigationStartup.Parameters(NativeNavigationStartup.Profile.RASTER_FRAME,
            1, "ko-KR", "KR", 1, 1, "overseas", "JVM fixture")
        val limits = NativeNavigationCapabilities.RasterLimits(4096,
            NativeNavigationCapabilities.ViewLimits(184, 90, 65536, 16),
            NativeNavigationCapabilities.ViewLimits(540, 220, 262144, 64))
        val session = NativeNavigationStartup.Session(params, sid)
        session.configureRaster(NativeNavigationCapabilities(true, false, true, true, limits))
        val reply = ProtocolCodec.message(ProtocolCodec.StreamDecoder()
            .append(session.acceptInitialPresetFrame(1)).single().payload)
        assertEquals(2, reply.type)
        assertEquals(sid, org.json.JSONObject(reply.json!!).getString("sid"))
        assertFalse(session.startAccepted)
        val snapshot = NativePhoneGuidance.Snapshot(1000L, 1, 0, 0, 0, -1,
            100, 300, -1, "", "", "fixture", false, false)
        var closed = false
        val route = object : NativeLiveNavigation.Route {
            override val parameters = params
            override val generation = 1L
            override val token = "fixture-route"
            override fun observation() = NativeLiveNavigation.Observation(snapshot, null, null)
            override fun close() { closed = true }
        }
        val live = NativeLiveNavigation(session, route)
        assertNull(live.next(2, 1100))
        session.receive(ProtocolCodec.Message(4, params.profileJson().put("sid", "other")
            .put("runtime_version", "fixture").toString(), null, 1, 0, 2))
        assertNull(live.next(3, 1200))
        session.receive(ProtocolCodec.Message(4, params.profileJson().put("sid", sid)
            .put("runtime_version", "fixture").toString(), null, 1, 0, 2))
        val write = checkNotNull(live.next(4, 1300))
        val message = ProtocolCodec.message(ProtocolCodec.StreamDecoder().append(write.packet).single().payload)
        assertEquals(0x34, message.type)
        assertEquals("fixture-route", org.json.JSONObject(message.json!!).getString("route_token"))
        // Peer UI evidence must be correlated to the current emitted route.
        val hud = params.profileJson().put("sid", sid).put("route_revision", 1)
            .put("route_generation", 1).put("route_token", "fixture-route")
            .put("reason", "HUD_FIRST_FRAME").put("last_guidance_seq", 1)
            .put("hud_first_frame_ms", 420)
        val report = ProtocolCodec.Message(0x32, hud.toString(), null, 1, 0, 1)
        session.receive(report.copy(mode = 2))
        session.receive(report.copy(json = org.json.JSONObject(report.json!!).put("route_token", "other").toString()))
        session.receive(report.copy(json = org.json.JSONObject(report.json!!).put("last_guidance_seq", 2).toString()))
        assertNull(session.hudFirstFrameMs)
        assertFalse(session.mapCommitObserved)
        session.receive(report)
        assertEquals(420L, session.hudFirstFrameMs ?: -1L)
        session.receive(report.copy(json = org.json.JSONObject(report.json!!).put("hud_first_frame_ms", 999).toString()))
        assertEquals(420L, session.hudFirstFrameMs ?: -1L)
        assertFalse(session.mapCommitObserved)
        assertNull(live.next(5, 1400))
        live.written(write, true, 1500)
        assertNull(live.next(6, 1600))
        // A real PNG in a JVM-only fixture, not a device transmission.
        val request = NativeRouteScene.Identity(sid, 1, 1, route.token).json()
            .put("view_generation", 1).put("view_mode", "MINI")
            .put("width", 184).put("height", 90).put("codec", "PNG")
        session.receive(ProtocolCodec.Message(0x39, request.toString(), null, 1, 0, 2))
        val png = fixturePng(false)
        assertTrue(png.size in 1..4096)
        assertTrue(session.submitRaster(checkNotNull(session.rasterView), png))
        val rasterWrite = checkNotNull(session.nextRasterWrite(100, 1700))
        val header = org.json.JSONObject(ProtocolCodec.message(
            ProtocolCodec.StreamDecoder().append(rasterWrite.packet).single().payload).json!!)
        session.rasterWritten(rasterWrite, true, 1710)
        assertFalse(session.mapCommitObserved)
        val ack = NativeRouteScene.Identity(sid, 1, 1, route.token).json()
            .put("view_generation", 1).put("frame_seq", header.getLong("frame_seq"))
            .put("crc32", header.getLong("crc32")).put("code", 0)
        val ackMsg = ProtocolCodec.Message(0x36, ack.toString(), null, 1, 0, 1)
        session.receive(ackMsg.copy(json = org.json.JSONObject(ackMsg.json!!).put("crc32", header.getLong("crc32") xor 1L).toString()))
        assertFalse(session.mapCommitObserved)
        session.receive(ackMsg)
        assertTrue(session.mapCommitObserved)
        assertNull(live.next(7, 5000))
        assertNull(live.next(8, 12000))
        assertNotNull(live.failure)
        live.close()
        assertTrue(closed)
    }

    @Test fun freshGuidanceCannotStarveRequestedRasterFragments() {
        verifyRasterInterleaveAndExpiry(false)
        verifyRasterInterleaveAndExpiry(true)
    }

    // Standard RGB PNG using only classes on Android's unit-test compile
    // classpath. A constant image fits one fragment; seeded noise spans many.
    private fun fixturePng(noise: Boolean): ByteArray {
        val width = 184
        val height = 90
        val result = java.io.ByteArrayOutputStream()
        val output = java.io.DataOutputStream(result)
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(name: String, data: ByteArray) {
            val type = name.toByteArray(Charsets.US_ASCII)
            val crc = java.util.zip.CRC32().apply { update(type); update(data) }
            output.writeInt(data.size)
            output.write(type)
            output.write(data)
            output.writeInt(crc.value.toInt())
        }
        val ihdr = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(ihdr).use {
            it.writeInt(width)
            it.writeInt(height)
            it.write(byteArrayOf(8, 2, 0, 0, 0))
        }
        chunk("IHDR", ihdr.toByteArray())
        val compressed = java.io.ByteArrayOutputStream()
        val random = java.util.Random(7)
        java.util.zip.DeflaterOutputStream(compressed).use { z ->
            repeat(height) {
                z.write(0) // PNG scanline filter None
                val row = ByteArray(width * 3)
                if (noise) random.nextBytes(row)
                z.write(row)
            }
        }
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", byteArrayOf())
        return result.toByteArray()
    }

    private fun verifyRasterInterleaveAndExpiry(arrive: Boolean) {
        // JVM-only synthetic fixture. The generated PNG is real, but the
        // coordinates/route and peer replies are not device observations.
        val sid = "0123456789abcdef0123456789abcdef"
        val params = NativeNavigationStartup.Parameters(NativeNavigationStartup.Profile.RASTER_FRAME,
            1, "ko-KR", "KR", 1, 1, "overseas", "JVM fixture")
        val limits = NativeNavigationCapabilities.RasterLimits(4096,
            NativeNavigationCapabilities.ViewLimits(184, 90, 65536, 16),
            NativeNavigationCapabilities.ViewLimits(540, 220, 262144, 64))
        val session = NativeNavigationStartup.Session(params, sid)
        session.configureRaster(NativeNavigationCapabilities(true, false, true, true, limits))
        session.acceptInitialPresetFrame(1)
        session.receive(ProtocolCodec.Message(4, params.profileJson().put("sid", sid)
            .put("runtime_version", "fixture").toString(), null, 1, 0, 2))
        val png = fixturePng(true)
        assertTrue(png.size in 8193..65536)
        var observedAt = 1000L
        var arrival = false
        val route = object : NativeLiveNavigation.Route {
            override val parameters = params
            override val generation = 1L
            override val token = "fixture-route"
            override fun observation() = NativeLiveNavigation.Observation(
                NativePhoneGuidance.Snapshot(observedAt, if (arrival) 4 else 1, 0, 0, 0, -1,
                    100, 300, -1, "", "", "fixture", false, arrival), png, null)
            override fun close() {}
        }
        val live = NativeLiveNavigation(session, route)
        fun message(write: NativeLiveNavigation.Write) =
            ProtocolCodec.message(ProtocolCodec.StreamDecoder().append(write.packet).single().payload)
        val first = checkNotNull(live.next(2, 1100))
        assertEquals(0x34, message(first).type)
        live.written(first, true, 1110)
        val request = NativeRouteScene.Identity(sid, 1, 1, route.token).json()
            .put("view_generation", 1).put("view_mode", "MINI")
            .put("width", 184).put("height", 90).put("codec", "PNG")
        session.receive(ProtocolCodec.Message(0x39, request.toString(), null, 1, 0, 2))
        observedAt = 1200
        val fragment0 = checkNotNull(live.next(3, 1250))
        assertEquals(0x35, message(fragment0).type)
        assertEquals(0, org.json.JSONObject(message(fragment0).json!!).getInt("fragment_index"))
        assertNull(live.next(4, 1260))
        live.written(fragment0, true, 1270)
        observedAt = 1300
        val guidance = checkNotNull(live.next(5, 1350))
        assertEquals(0x34, message(guidance).type)
        live.written(guidance, true, 1360)
        observedAt = 1400
        val fragment1 = checkNotNull(live.next(6, 1450))
        assertEquals(0x35, message(fragment1).type)
        assertEquals(1, org.json.JSONObject(message(fragment1).json!!).getInt("fragment_index"))
        live.written(fragment1, true, 1460)
        observedAt = 1500
        val resumedGuidance = checkNotNull(live.next(7, 1550))
        assertEquals(0x34, message(resumedGuidance).type)
        live.written(resumedGuidance, true, 1560)
        if (arrive) {
            // A fresh arrival may finish even though the partial image has expired.
            observedAt = 4900
            arrival = true
            val end = checkNotNull(live.next(8, 5000))
            assertEquals(0x34, message(end).type)
            assertTrue(org.json.JSONObject(message(end).json!!).getBoolean("arrival"))
            assertFalse(live.arrived)
            live.written(end, true, 5010)
            assertTrue(live.arrived)
        } else {
            // Never continue expired image fragments, even with fresh guidance.
            observedAt = 4900
            assertNull(live.next(8, 5000))
            assertNotNull(live.failure)
            assertFalse(live.arrived)
        }
        live.close()
    }

    @Test fun exactTurnOnlyNeverGuessFromEtaOrConflict() {
        val p = KoreanNavigationText
        assertEquals(KoreanNavigationText.Instruction("우회전", 300), p.parse(listOf("300m 앞에서 우회전")))
        assertEquals(KoreanNavigationText.Instruction("좌회전", 200), p.parse(listOf("전방 200m 좌회전")))
        assertEquals(KoreanNavigationText.Instruction("직진", 1500), p.parse(listOf("직진 1.5km")))
        assertEquals(KoreanNavigationText.Instruction("좌회전", 300), p.parse(listOf("In 300 m turn left")))
        assertNull(p.parse(listOf("300m 앞 우회전", "300m 앞 좌회전")))
        assertNull(p.parse(listOf("도착 예정 5분", "3km")))
        assertNull(p.parse(listOf("목적지에 도착했습니다")))
        assertNull(p.parse(listOf("경로를 다시 탐색합니다")))
        assertNull(p.parse(listOf("직진 20km")))
    }

    @Test fun onlyMatchingFreshSelectedDisplayMayResume() {
        val s = GlassesState(link = LinkState(LinkPhase.FULL), navigation = NavigationState(true, "naver-current", "frame"))
        val resume = GlassesEvent.ResumeNavigation("naver-current")
        val resumed = GlassesCoordinator.reduce(s, resume)
        assertEquals(DisplayIntent.Navigation, resumed.state.intent)
        assertEquals(PageMode.NAVIGATION, resumed.state.session?.mode)
        assertTrue(resumed.effects.any { it is GlassesEffect.StartPage })
        assertFalse(resumed.effects.any { it is GlassesEffect.FollowNavigation })
        val rejected = listOf(
            s.copy(navigation = s.navigation.copy(key = "different")),
            s.copy(navigation = s.navigation.copy(enabled = false)),
            s.copy(navigation = s.navigation.copy(frame = null)),
            s.copy(link = LinkState(LinkPhase.RETRY_WAIT)),
            s.copy(ownership = DisplayOwnership.YIELDED),
            s.copy(error = "page close failed"),
            s.copy(disconnecting = true),
            s.copy(intent = DisplayIntent.Translation),
            s.copy(session = PageSession(2, PageMode.NAVIGATION, PagePhase.CLOSING, PageContent("naver-current", "frame")))
        )
        for (other in rejected) {
            val result = GlassesCoordinator.reduce(other, resume)
            assertFalse("must not reopen after ${other.displayDescription}", result.effects.any { it is GlassesEffect.StartPage && it.session.mode == PageMode.NAVIGATION })
            assertNotEquals(DisplayIntent.Navigation, result.state.intent)
        }
        val stopped = GlassesCoordinator.reduce(s, GlassesEvent.StopNavigation)
        assertFalse(stopped.state.navigation.enabled)
        assertTrue(stopped.effects.any { it is GlassesEffect.StopFollowing })
        assertEquals(DisplayIntent.Idle, GlassesCoordinator.reduce(stopped.state, resume).state.intent)
    }

    @Test fun automaticAttachmentAndRestorationPolicy() {
        val p = NavigationVoicePolicy
        val D = NavigationVoicePolicy.Decision.FOLLOW
        assertEquals(D, p.decide(10, 20, true, 1, 1, true))
        assertEquals(NavigationVoicePolicy.Decision.WAIT, p.decide(10, 20, true, 1, 1, false))
        assertEquals(NavigationVoicePolicy.Decision.BUSY, p.decide(20, 20, true, 1, 1, false))
        assertEquals(NavigationVoicePolicy.Decision.EXPIRED, p.decide(20, 20, true, 0, 0, true))
        assertEquals(NavigationVoicePolicy.Decision.AMBIGUOUS, p.decide(10, 20, true, 2, 1, true))
        assertEquals(NavigationVoicePolicy.Decision.UNAVAILABLE, p.decide(10, 20, false, 1, 1, true))
        assertTrue(p.canRestore(true, true, true, true, true, true, true, false))
        for (i in 0..7) {
            val args = MutableList(8) { it != 7 }
            args[i] = !args[i]
            assertFalse(p.canRestore(args[0],args[1],args[2],args[3],args[4],args[5],args[6],args[7]))
        }
    }

    @Test fun verifiedUniqueCoordinatesOnly() {
        val p = NaverPlacePolicy
        assertNotNull(p.unique(1, "인천시청", "인천시청", "주소", "", "126.7052", "37.4563"))
        assertNotNull(p.unique(1, "인천시청", "인천시청", "주소", "", "1267052000", "374563000"))
        assertNull(p.unique(2, "인천시청", "인천시청", "주소", "", "126.7052", "37.4563"))
        assertNull(p.unique(1, "인천시청", "인천시청역", "주소", "", "126.7052", "37.4563"))
        assertNull(p.unique(1, "인천시청", "인천시청", "주소", "", "NaN", "37.4563"))
        assertNull(p.unique(1, "인천시청", "인천시청", "주소", "", "37.4563", "126.7052"))
        assertEquals("navigation", p.path(NaverVoiceCommand.Mode.CAR))
        assertEquals("route/walk", p.path(NaverVoiceCommand.Mode.WALK))
        assertEquals("route/public", p.path(NaverVoiceCommand.Mode.PUBLIC))
        assertNull(p.path(NaverVoiceCommand.Mode.UNSPECIFIED))
        // Synthetic JVM fixtures only; never sent to a device or used as guidance.
        val points = (0..1200).map { i -> NaverWalkingCourse.Point(37.0 + i * 0.0001, 126.0 + i * 0.0001) }
        val geometry = checkNotNull(NativeRouteScene.geometry(points))
        val local = checkNotNull(NativeRouteScene.geometry(points.take(20)))
        assertEquals(120000, geometry.bounds.north - geometry.bounds.south)
        assertEquals(0xcbf43926L, NativeRouteScene.crc32("123456789".toByteArray()))
        assertNull(NativeRouteScene.geometry(List(4097) { points[it % points.size] }))
        assertNull(NativeRouteScene.geometry(listOf(points[0], points[0])))
        val identity = NativeRouteScene.Identity("0123456789abcdef0123456789abcdef", 1, 2, "jvm-only")
        val scene = NativeRouteScene.encode(identity, "fixture", 1, geometry, local)
        assertTrue(scene.fragments.size > 1)
        val body = scene.fragments.flatMap { it.binary.toList() }.toByteArray()
        val decoded = org.json.JSONObject(body.toString(Charsets.UTF_8))
        assertEquals("ROUTE_SCENE_V1", decoded.getString("profile"))
        assertEquals(geometry.polyline6, decoded.getString("overview_polyline6"))
        assertEquals(local.polyline6, decoded.getString("local_polyline6"))
        assertEquals(geometry.bounds.north, decoded.getJSONObject("overview_bounds").getInt("north_e6"))
        var offset = 0
        scene.fragments.forEachIndexed { index, fragment ->
            val frame = ProtocolCodec.StreamDecoder().append(fragment.frame(index)).single()
            val message = ProtocolCodec.message(frame.payload)
            assertEquals(0x20, frame.protocol)
            assertEquals(0x3c, message.type)
            assertEquals(0L, message.mode)
            assertEquals(0L, message.sequence)
            assertArrayEquals(fragment.binary, message.audio)
            val header = org.json.JSONObject(message.json!!)
            assertEquals(index, header.getInt("fragment_index"))
            assertEquals(scene.fragments.size, header.getInt("fragment_count"))
            assertEquals(offset, header.getInt("fragment_offset"))
            assertEquals(fragment.binary.size, header.getInt("fragment_len"))
            assertEquals(body.size, header.getInt("total_len"))
            assertEquals(NativeRouteScene.crc32(body), header.getLong("crc32"))
            offset += fragment.binary.size
        }
        val first = org.json.JSONObject(scene.fragments.first().header)
        val response = identity.json().put("scene_id", "fixture")
            .put("route_hash", first.getString("route_hash")).put("total_len", body.size)
            .put("crc32", NativeRouteScene.crc32(body)).put("code", 0)
        val ack = ProtocolCodec.Message(0x3d, response.toString(), null, 1, 0, 1)
        assertTrue(scene.committed(ack))
        assertFalse(scene.committed(ack.copy(type = 0x3e)))
        assertFalse(scene.committed(ack.copy(json = response.put("route_generation", 3).toString())))
        val sid = "0123456789abcdef0123456789abcdef"
        val parameters = NativeNavigationStartup.Parameters(NativeNavigationStartup.Profile.VECTOR_TILE, 1,
            "ko-KR", "KR", 1, 1, "overseas", "JVM fixture only")
        val session = NativeNavigationStartup.Session(parameters, sid)
        val start = ProtocolCodec.StreamDecoder().append(session.startFrame(42)).single()
        val startMsg = ProtocolCodec.message(start.payload)
        assertEquals(0x20, start.protocol)
        assertEquals(1, startMsg.type)
        assertEquals(0L, startMsg.mode)
        assertEquals(0L, startMsg.sequence)
        val startJson = org.json.JSONObject(startMsg.json!!)
        assertEquals(sid, startJson.getString("sid"))
        assertEquals(1, startJson.getInt("trigger"))
        assertEquals(0, startJson.getInt("code"))
        val ready = ProtocolCodec.Message(4, parameters.profileJson().put("sid", sid)
            .put("runtime_version", "phone-hosted-v1").toString(), null, 1, 0, 2)
        val accepted = ProtocolCodec.Message(2, parameters.json().put("sid", sid).put("code", 0).toString(), null, 1, 0, 2)
        session.receive(ready.copy(json = parameters.profileJson().put("sid", "other").toString()))
        session.receive(ready)
        assertTrue(session.glassReady)
        assertFalse(session.startAccepted)
        assertEquals(NativeNavigationStartup.Session.Phase.STARTING, session.phase)
        session.receive(accepted.copy(json = parameters.json().put("sid", sid)
            .put("code", 0).put("locale", "en-US").toString()))
        assertTrue(session.incompatibleStartReply)
        assertFalse(session.startAccepted)
        session.receive(accepted)
        assertTrue(session.startAccepted)
        assertEquals(NativeNavigationStartup.Session.Phase.READY, session.phase)
        val parts = session.initialScene(1, "fixture-route", "fixture-scene", geometry, local)
        assertEquals(1L, org.json.JSONObject(parts.first().header).getLong("route_revision"))
        assertEquals(NativeNavigationStartup.Session.Phase.SCENE_PENDING, session.phase)
        val stop = ProtocolCodec.message(ProtocolCodec.StreamDecoder().append(session.closeFrame(43)!!).single().payload)
        assertEquals(3, stop.type)
        assertEquals(2, org.json.JSONObject(stop.json!!).getInt("reason_code"))
        assertNull(session.closeFrame(44))
        val peerEnd = ProtocolCodec.Message(3, org.json.JSONObject().put("sid", sid)
            .put("reason_code", 1).toString(), null, 1, 0, 2)
        assertEquals(1, session.peerEndReason(peerEnd))
        assertNull(session.peerEndReason(peerEnd.copy(mode = 0)))
        assertNull(session.peerEndReason(peerEnd.copy(json = org.json.JSONObject()
            .put("sid", "other").put("reason_code", 1).toString())))
        session.receive(ready)
        assertEquals(NativeNavigationStartup.Session.Phase.ENDED, session.phase)
        val rejected = NativeNavigationStartup.Session(parameters, sid)
        rejected.startFrame(45)
        rejected.receive(accepted.copy(json = parameters.json().put("sid", sid).put("code", 6).toString()))
        rejected.receive(ready)
        assertEquals(NativeNavigationStartup.Session.Phase.REJECTED, rejected.phase)
        assertEquals(6, rejected.rejectionCode)
        val presetConfig = NativeNavigationPresets.Config(1,
            NativeNavigationPresets.Slot("1234567890abcdef", "테스트 목적지"),
            NativeNavigationPresets.Slot("fedcba0987654321", "다른 목적지"))
        val presetBody = org.json.JSONObject().put("sid", sid).put("trigger", 2).put("code", 0)
            .put("preset", "HOME").put("preset_id", "1234567890abcdef")
            .put("engine_profile", "AUTO").put("map_profile", "AUTO").put("route_scene_profile", "AUTO")
        val request = ProtocolCodec.Message(1, presetBody.toString(), null, 1, 0, 2)
        assertEquals("집", NativeNavigationPresets.initialRequest(request, presetConfig)?.alias)
        assertEquals(sid, NativeNavigationPresets.initialRequest(request, presetConfig)?.sid)
        assertNull(NativeNavigationPresets.initialRequest(request.copy(mode = 0), presetConfig))
        assertNull(NativeNavigationPresets.initialRequest(request.copy(json = presetBody.put("preset_id", "other").toString()), presetConfig))
        val close = ProtocolCodec.StreamDecoder().append(NativeNavigationStartup.phoneCloseFrame(46, sid)).single()
        assertEquals(0x20, close.protocol)
        val closeMessage = ProtocolCodec.message(close.payload)
        assertEquals(3, closeMessage.type)
        assertEquals(0L, closeMessage.mode)
        assertEquals(sid, org.json.JSONObject(closeMessage.json!!).getString("sid"))
    }

    @Test fun hostFeatureAdvertisesConfiguredProviderBeforeRouteExists() {
        val ready = List(6) { true }
        fun available(v: List<Boolean>) = OfflineNativeRouteSettings.canAdvertise(v[0], v[1], v[2], v[3], v[4], v[5])
        assertTrue(available(ready))
        for (i in ready.indices) assertFalse("missing prerequisite $i", available(ready.mapIndexed { j, value -> j != i && value }))
        // Neither a route, fresh GPS fix, peer PNG profile nor session is an input.
        val idle = ProtocolCodec.StreamDecoder().append(ProtocolCodec.navigationHostCapabilities(1, true, available(ready))).single()
        val body = org.json.JSONObject(idle.payload.toString(Charsets.UTF_8))
        assertEquals("1.0.0.0", body.getJSONArray("capabilities").getJSONObject(0).getString("app.navigation.main"))
        val revoked = ProtocolCodec.StreamDecoder().append(ProtocolCodec.navigationHostCapabilities(2, false, available(ready.mapIndexed { i, b -> i != 0 && b }))).single()
        val update = org.json.JSONObject(revoked.payload.toString(Charsets.UTF_8))
        assertEquals(3, update.getInt("type"))
        assertEquals(0, update.getJSONArray("capabilities").length())
    }

    @Test fun offlineRouteProgressUsesFreshFixAndRejectsAmbiguousCrossing() {
        fun pt(lat: Double, lon: Double) = NaverWalkingCourse.Point(lat,lon)
        val points = listOf(pt(37.0,127.0),pt(37.001,127.0),pt(37.001,127.001))
        val route = OfflineWalkingRoute.Route(NaverWalkingCourse.Course(points),
            listOf(OfflineWalkingRoute.Turn(1,5,0,89.0,90)), listOf(0.0,100.0,180.0),180.0)
        val fix = OfflineRouteProgress.Fix(pt(37.0005,127.0),5.0,1000L)
        val progress = requireNotNull(OfflineRouteProgress.match(route,fix,1200L))
        assertTrue(progress.remainingMeters in 140..150)
        assertTrue(requireNotNull(progress.nextTurnMeters) in 54..57)
        assertEquals(5, progress.nextTurn?.sourceCode)
        assertEquals(130, progress.remainingEstimateSeconds)
        // Real production mapping, not fixture-only values.
        val wire=checkNotNull(OfflineNativeRoute.verifiedWire())
        assertEquals(2,wire.travelMode)
        assertEquals(1,wire.guidingState)
        assertEquals(1,wire.unitSystem)
        assertEquals(1,wire.drivingSide)
        assertEquals(0,wire.noManeuver)
        val profile=NativeNavigationStartup.Parameters(NativeNavigationStartup.Profile.RASTER_FRAME,
            wire.travelMode,"ko-KR","KR",wire.unitSystem,wire.drivingSide,"overseas","선택한 경로")
        val startJson=profile.json()
        assertEquals(2,startJson.getInt("travel_mode"))
        assertEquals(1,startJson.getInt("unit_system"))
        assertEquals(1,startJson.getInt("driving_side"))
        assertEquals(2,wire.maneuver(1))
        assertEquals(4,wire.maneuver(2))
        assertEquals(7,wire.maneuver(5))
        assertEquals(19,wire.maneuver(14))
        assertNull(wire.maneuver(15))
        assertNull(wire.maneuver(17))
        assertNull(wire.maneuver(18))
        val guidance=checkNotNull(OfflineNativeRoute.snapshot(progress,wire,123))
        assertEquals(7,guidance.maneuver)
        assertEquals(123,guidance.speedMpsX100)
        assertEquals("우회전",guidance.instruction)
        assertNull(OfflineNativeRoute.snapshot(progress.copy(nextTurn=progress.nextTurn?.copy(sourceCode=15)),wire))
        val circle=progress.copy(nextTurn=OfflineWalkingRoute.Turn(1,13,2,89.0,90))
        assertEquals(2,checkNotNull(OfflineNativeRoute.snapshot(circle,wire)).roundaboutExit)
        assertNull(OfflineNativeRoute.snapshot(circle.copy(nextTurn=circle.nextTurn?.copy(exit=0)),wire))
        val finalLeg=checkNotNull(OfflineNativeRoute.snapshot(progress.copy(nextTurn=null,nextTurnMeters=null),wire))
        assertEquals(0,finalLeg.maneuver)
        assertEquals(-1,finalLeg.nextActionDistanceM)
        assertFalse(finalLeg.arrival)
        assertNull(OfflineRouteProgress.match(route,fix,4000L))
        assertNull(OfflineRouteProgress.match(route,fix.copy(accuracyMeters=40.0),1200L))
        assertNull(OfflineRouteProgress.match(route,fix.copy(point=pt(37.0005,127.01)),1200L))
        // Out and back over the same geometry: proximity alone cannot
        // distinguish two different progress positions.
        val crossing = OfflineWalkingRoute.Route(
            NaverWalkingCourse.Course(listOf(points[0],points[1],points[0])),
            emptyList(),null,null)
        assertNull(OfflineRouteProgress.match(crossing,fix,1200L))
        val noEstimate = OfflineWalkingRoute.Route(route.course,emptyList(),null,null)
        assertNull(requireNotNull(OfflineRouteProgress.match(noEstimate,fix,1200L)).remainingEstimateSeconds)
    }

    @Test fun offlineArrivalAndDeviationRequireRepeatedFreshUnambiguousMeasurements() {
        fun pt(lat: Double, lon: Double) = NaverWalkingCourse.Point(lat, lon)
        val end = pt(37.001,127.0)
        val points = listOf(pt(37.0,127.0), end)
        val route = OfflineWalkingRoute.Route(NaverWalkingCourse.Course(points),
            emptyList(), null, null, requestedEnd = end)
        fun assess(point: NaverWalkingCourse.Point, stamp: Long, accuracy: Double = 5.0,
                   source: OfflineWalkingRoute.Route = route, now: Long = stamp + 100) =
            OfflineRouteProgress.assess(source, OfflineRouteProgress.Fix(point, accuracy, stamp), now)
        val tracker = OfflineRouteProgress.TerminalTracker()
        val near = assess(end,1000L)
        assertTrue(near.arrivalCandidate)
        assertNull(tracker.observe(near,1000L,1100L))
        assertNull(tracker.observe(near,1000L,1200L))
        assertNull(tracker.observe(assess(end,2000L),2000L,2100L))
        assertEquals(OfflineRouteProgress.Terminal.ARRIVED,
            tracker.observe(assess(end,3000L),3000L,3100L))
        val wire = checkNotNull(OfflineNativeRoute.verifiedWire())
        val arrived = OfflineNativeRoute.arrivalSnapshot(checkNotNull(near.progress),wire,null)
        assertTrue(arrived.arrival)
        assertEquals(4,arrived.navState)
        assertEquals(23,arrived.maneuver)
        assertEquals(1000L,arrived.observedAtMs)
        val snappedWrong = OfflineWalkingRoute.Route(route.course,emptyList(),null,null,requestedEnd = pt(37.0017,127.0))
        assertFalse(assess(end,4000L,source=snappedWrong).arrivalCandidate)
        assertFalse(assess(end,4000L,accuracy=20.0).arrivalCandidate)
        assertFalse(assess(end,4000L,now=7000L).arrivalCandidate)
        val loop = OfflineWalkingRoute.Route(NaverWalkingCourse.Course(listOf(end,points.first(),end)),emptyList(),null,null,requestedEnd = end)
        assertFalse(assess(end,4000L,source=loop).arrivalCandidate)
        val far=pt(37.0005,127.002)
        val off=assess(far,4000L)
        assertTrue(off.confidentlyOffRoute)
        assertFalse(assess(far,4000L,accuracy=20.0).confidentlyOffRoute)
        tracker.reset()
        assertNull(tracker.observe(off,4000L,4100L))
        assertNull(tracker.observe(assess(far,5000L,accuracy=25.0),5000L,5100L))
        assertNull(tracker.observe(assess(far,6000L),6000L,6100L))
        assertNull(tracker.observe(assess(far,7000L),7000L,7100L))
        assertEquals(OfflineRouteProgress.Terminal.OFF_ROUTE,
            tracker.observe(assess(far,8000L),8000L,8100L))
    }

    @Test fun nativeRerouteRequiresMobileCloseInSameConnectedEpoch() {
        assertTrue(OfflineNativeRoute.mayRestartAfterEnd(2,true,true))
        for (reason in 0..8) if (reason != 2)
            assertFalse(OfflineNativeRoute.mayRestartAfterEnd(reason,true,true))
        assertFalse(OfflineNativeRoute.mayRestartAfterEnd(2,false,true))
        assertFalse(OfflineNativeRoute.mayRestartAfterEnd(2,true,false))
        val sid = "0123456789abcdef0123456789abcdef"
        val params = NativeNavigationStartup.Parameters(NativeNavigationStartup.Profile.RASTER_FRAME,
            2,"ko-KR","KR",1,1,"overseas","fixture")
        val session = NativeNavigationStartup.Session(params,sid)
        fun end(id: String, reason: Int) = ProtocolCodec.Message(3,
            org.json.JSONObject().put("sid",id).put("reason_code",reason).toString(),null,1,0,2)
        assertEquals(2,session.peerEndReason(end(sid,2)))
        assertNull(session.peerEndReason(end("other",2)))
        assertNull(session.peerEndReason(end(sid,2).copy(mode=1)))
        assertNull(session.peerEndReason(end(sid,8)))
    }

    @Test fun observedModeTwoCapabilityReplyRequiresExactRequestId() {
        val id = "current-query"
        val json = org.json.JSONObject().put("request_id",id).put("code",0).put("protocol_version",1)
            .put("supported_engine_profiles",org.json.JSONArray().put("PHONE_GUIDANCE_V1"))
            .put("guidance_supported",true).put("map_supported",false)
        val reply = ProtocolCodec.Message(0x31,json.toString(),null,1,0,2)
        assertTrue(NativeNavigationCapabilities.matchesPending(reply,id,19))
        assertNotNull(NativeNavigationCapabilities.parse(reply,id))
        assertFalse(NativeNavigationCapabilities.matchesPending(reply,"old-query",19))
        assertFalse(NativeNavigationCapabilities.matchesPending(reply.copy(mode=1),id,19))
        assertNull(NativeNavigationCapabilities.parse(reply.copy(mode=0),id))
        assertNull(NativeNavigationCapabilities.parse(reply.copy(audio=byteArrayOf(1)),id))
        assertNull(NativeNavigationCapabilities.parse(reply.copy(json=json.put("code",17).toString()),id))
    }

    @Test fun observedExplicitStatusQuestionsStayLocalAndReadOnly() {
        assertEquals(NativeStatusQuestion.Kind.NAVIGATION, NativeStatusQuestion.parse("아니네비연동상태를파악해봐",ConversationSource.NATIVE,false))
        assertEquals(NativeStatusQuestion.Kind.FIRMWARE, NativeStatusQuestion.parse("지금우리펌웨어는어떤버전인데",ConversationSource.NATIVE,false))
        assertNull(NativeStatusQuestion.parse("지금우리펌웨어는어떤버전인데",ConversationSource.REALTIME,false))
        assertNull(NativeStatusQuestion.parse("지금우리펌웨어는어떤버전인데",ConversationSource.NATIVE,true))
        assertNull(NativeStatusQuestion.parse("펌웨어 업데이트해줘",ConversationSource.NATIVE,false))
    }
    @Test fun registrySupportIsNotTransientGpsReadiness() {
        assertTrue(OfflineNativeRouteSettings.supportsConfiguredProvider(true,true,true))
        assertFalse(OfflineNativeRouteSettings.supportsConfiguredProvider(false,true,true))
        assertFalse(OfflineNativeRouteSettings.supportsConfiguredProvider(true,false,true))
        assertFalse(OfflineNativeRouteSettings.supportsConfiguredProvider(true,true,false))
        // A missing location permission/service/GPS still blocks actual routing.
        assertFalse(OfflineNativeRouteSettings.canAdvertise(true,true,true,false,true,true))
        assertFalse(OfflineNativeRouteSettings.canAdvertise(true,true,true,true,false,true))
        assertFalse(OfflineNativeRouteSettings.canAdvertise(true,true,true,true,true,false))
    }
}
