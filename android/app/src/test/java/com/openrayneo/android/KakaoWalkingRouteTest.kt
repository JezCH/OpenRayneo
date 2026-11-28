// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Test
import org.junit.Assert.*

class KakaoWalkingRouteTest {
    private val start = NaverWalkingCourse.Point(37.4,127.1)
    private val middle = NaverWalkingCourse.Point(37.4005,127.1)
    private val end = NaverWalkingCourse.Point(37.4005,127.1005)
    private val sample = """{"status":"OK","route":{"properties":{"totalDistance":100,"totalTime":90},"legs":[{"steps":[{"properties":{"distance":55,"time":50,"guidance":"55m 직진"},"path":{"points":[[127.1,37.4],[127.1,37.4005]]}},{"properties":{"distance":45,"time":40,"guidance":"우회전"},"path":{"points":[[127.1,37.4005],[127.1005,37.4005]]}}]}]}}"""
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
