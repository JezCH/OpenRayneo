// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContentDraftReadbackTest {
    private fun response(id: Long = 10L, duplicate: Boolean = true, row: JSONObject? = null) =
        JSONObject().put("eventID", id).put("duplicate", duplicate).put("readBack", false)
            .put("items", JSONArray().apply { row?.let { put(it) } })
    private fun row(id: Long = 10L, title: String = "회의") = JSONObject().put("eventID", id).put("title", title)

    @Test fun verifiedReplyNeedsAnIdentity() {
        assertEquals(10L, ContentDraftReadback.verifiedId(response().put("readBack", true), null, "회의", null, null))
        assertNull(ContentDraftReadback.verifiedId(response().put("readBack", true), 11L, "회의", null, null))
        assertNull(ContentDraftReadback.verifiedId(JSONObject().put("readBack", true), null, "회의", null, null))
    }
    @Test fun duplicateNeedsExactItemFromReadback() {
        assertEquals(10L, ContentDraftReadback.verifiedId(response(row = row()), null, "회의", null, null))
        assertNull(ContentDraftReadback.verifiedId(response(row = row(11L)), null, "회의", null, null))
        assertNull(ContentDraftReadback.verifiedId(response(row = row(title = "다른 회의")), null, "회의", null, null))
        assertNull(ContentDraftReadback.verifiedId(response(row = row().put("syncState", "outcome_unknown")), null, "회의", null, null))
        assertNull(ContentDraftReadback.verifiedId(response(duplicate = false, row = row()), null, "회의", null, null))
        assertNull(ContentDraftReadback.verifiedId(response(), null, "회의", null, null))
    }
    @Test fun duplicateScheduleNeedsSameEpochTimes() {
        val item = row().put("startTime", 100L).put("endTime", 200L)
        assertEquals(10L, ContentDraftReadback.verifiedId(response(row = item), null, "회의", 100L, 200L))
        assertNull(ContentDraftReadback.verifiedId(response(row = item), null, "회의", 101L, 200L))
        assertNull(ContentDraftReadback.verifiedId(response(row = row()), null, "회의", 100L, 200L))
    }
}
