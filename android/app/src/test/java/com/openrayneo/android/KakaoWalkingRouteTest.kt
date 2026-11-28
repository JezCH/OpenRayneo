// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Test
import org.junit.Assert.*

class KakaoWalkingRouteTest {
    private val start = NaverWalkingCourse.Point(37.4,127.1)
    private val middle = NaverWalkingCourse.Point(37.4005,127.1)
    private val end = NaverWalkingCourse.Point(37.4005,127.1005)
    private val sample = """{"status":"OK","route":{"properties":{"totalDistance":100,"totalTime":90},"legs":[{"steps":[{"properties":{"distance":55,"time":50,"guidance":"55m 직진"},"path":{"points":[[127.1,37.4],[127.1,37.4005]]}},{"properties":{"distance":45,"time":40,"guidance":"우회전"},"path":{"points":[[127.1,37.4005],[127.1005,37.4005]]}}]}]}}"""
    @Test fun ambiguousKeywordRetainsActualCandidatesWithoutChoosing() {
        val raw = """{"meta":{"is_end":true},"documents":[
          {"id":"1","place_name":"버거킹 A점","road_address_name":"주소 A","x":"127.1","y":"37.4","distance":"100"},
          {"id":"2","place_name":"버거킹 B점","road_address_name":"주소 B","x":"127.1005","y":"37.4005","distance":"230"}]}"""
        val error = assertThrows(KakaoWalkingRoute.ChoiceRequired::class.java) {
            KakaoWalkingRoute.selectKeywordDestination(raw, "버거킹")
        }
        assertEquals(listOf("1", "2"), error.candidates.map { it.id })
        assertEquals(end, error.candidates[1].point)
        assertEquals(230, error.candidates[1].distanceMeters)
        assertEquals("2", KakaoWalkingRoute.selectKeywordDestination(raw, "버거킹 B점").id)
        val partial = raw.replace("\"is_end\":true", "\"is_end\":false")
        assertThrows(KakaoWalkingRoute.ChoiceRequired::class.java) {
            KakaoWalkingRoute.selectKeywordDestination(partial, "버거킹 B점")
        }
        assertEquals(2, KakaoCandidateVoicePolicy.parse("2번 목적지 선택"))
        assertEquals(2, KakaoCandidateVoicePolicy.parse("두 번째 목적지 선택"))
        assertNull(KakaoCandidateVoicePolicy.parse("이번목적지선택"))
        assertEquals(0, KakaoCandidateVoicePolicy.parse("목적지 후보 알려줘"))
        assertNull(KakaoCandidateVoicePolicy.parse("2번"))
        assertNull(KakaoCandidateVoicePolicy.parse("2번 목적지 선택하지 마"))
    }
    @Test fun realProviderShapeFeedsExistingNativeProgress() {
        val route = KakaoWalkingRoute.parse(sample,start,end)
        assertEquals("kakao",route.provider)
        assertEquals(listOf(start,middle,end),route.course.points)
        assertEquals(listOf(0.0,50.0,90.0),route.pointTimesSeconds)
        assertEquals(listOf(2,7),route.turns.map { it.nativeManeuver })
        val progress = OfflineRouteProgress.match(route, OfflineRouteProgress.Fix(start,5.0,100L),100L)!!
        val guidance = OfflineNativeRoute.snapshot(progress, OfflineNativeRoute.verifiedWire()!!)!!
        assertEquals(2,guidance.maneuver)
        assertEquals("55m 직진",guidance.instruction)
    }
    @Test fun officialExampleMayOmitSuccessStatusButExplicitErrorNeverPasses() {
        val noStatus = sample.replace("\"status\":\"OK\",", "")
        assertEquals("kakao", KakaoWalkingRoute.parse(noStatus,start,end).provider)
        assertThrows(KakaoWalkingRoute.Failure::class.java) { KakaoWalkingRoute.parse(sample.replace("\"OK\"", "\"SAME_POINT\""),start,end) }
    }
    @Test fun unknownTextDoesNotInventDirection() {
        assertEquals(0,KakaoWalkingRoute.maneuver("판교역 1번 출구까지 역사 내 이동"))
        assertEquals(0,KakaoWalkingRoute.maneuver("좌회전 또는 우회전"))
    }
    @Test fun rejectsProviderFailureAndDisconnectedPath() {
        assertThrows(KakaoWalkingRoute.Failure::class.java) { KakaoWalkingRoute.parse("""{"status":"ROUTE_RESULT_NOT_FOUND"}""",start,end) }
        assertThrows(KakaoWalkingRoute.Failure::class.java) { KakaoWalkingRoute.parse(sample.replace("[[127.1,37.4005],[127.1005,37.4005]]","[[127.11,37.4005],[127.1005,37.4005]]"),start,end) }
    }
    @Test fun kakaoRouteRetainsRequestedEndpointAndUsesSameTerminalTracker() {
        val route = KakaoWalkingRoute.parse(sample,start,end)
        assertEquals(end, route.requestedEnd)
        val tracker = OfflineRouteProgress.TerminalTracker()
        for (at in listOf(1000L, 2000L, 3000L)) {
            val assessment = OfflineRouteProgress.assess(route, OfflineRouteProgress.Fix(end, 3.0, at), at)
            assertTrue(assessment.arrivalCandidate)
            val result = tracker.observe(assessment, at, at)
            if (at == 3000L) assertEquals(OfflineRouteProgress.Terminal.ARRIVED, result)
            else assertNull(result)
        }
    }
}
