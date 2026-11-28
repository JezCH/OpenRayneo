// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Assert.*
import org.junit.Test

class NaverNavigationRegressionTest {
    @Test fun explicitCommandsAndNonCommands() {
        val c = NaverVoiceCommand
        val expected = mapOf(
            "안경 길 안내 종료해 줘" to NaverVoiceCommand.Control.STOP_HUD,
            "안경 내비게이션 종료!" to NaverVoiceCommand.Control.STOP_HUD,
            "안경 안내 꺼 줘" to NaverVoiceCommand.Control.STOP_HUD,
            "안경 길 안내 시작해 줘" to NaverVoiceCommand.Control.START_HUD,
            "안경 길 안내 재개해 줘" to NaverVoiceCommand.Control.START_HUD,
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
        assertEquals(NativeNavigationStartup.Session.Phase.STARTING, session.phase)
        session.receive(accepted)
        assertEquals(NativeNavigationStartup.Session.Phase.READY, session.phase)
        val parts = session.initialScene(1, "fixture-route", "fixture-scene", geometry, local)
        assertEquals(1L, org.json.JSONObject(parts.first().header).getLong("route_revision"))
        assertEquals(NativeNavigationStartup.Session.Phase.SCENE_PENDING, session.phase)
        val stop = ProtocolCodec.message(ProtocolCodec.StreamDecoder().append(session.closeFrame(43)!!).single().payload)
        assertEquals(3, stop.type)
        assertEquals(2, org.json.JSONObject(stop.json!!).getInt("reason_code"))
        assertNull(session.closeFrame(44))
        session.receive(ready)
        assertEquals(NativeNavigationStartup.Session.Phase.ENDED, session.phase)
        val rejected = NativeNavigationStartup.Session(parameters, sid)
        rejected.startFrame(45)
        rejected.receive(accepted.copy(json = parameters.json().put("sid", sid).put("code", 6).toString()))
        rejected.receive(ready)
        assertEquals(NativeNavigationStartup.Session.Phase.REJECTED, rejected.phase)
        assertEquals(6, rejected.rejectionCode)
    }
}
