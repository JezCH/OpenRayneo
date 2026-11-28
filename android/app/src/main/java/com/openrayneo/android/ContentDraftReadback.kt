// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject

/** The UI only reconciles the exact requested item using a fresh device list.
 * A duplicate ledger hit alone is not evidence of a successful device write. */
internal object ContentDraftReadback {
    fun verifiedId(response: JSONObject, expectedId: Long?, title: String, start: Long?, end: Long?): Long? {
        if (!response.has("eventID")) return null
        val id = response.optLong("eventID", -1L)
        if (id < 0L || expectedId != null && expectedId != id) return null
        if (response.optBoolean("readBack")) return id
        if (!response.optBoolean("duplicate")) return null
        val items = response.optJSONArray("items") ?: return null
        for (index in 0 until items.length()) {
            val row = items.optJSONObject(index) ?: continue
            if (row.optLong("eventID", -1L) != id || row.optString("title") != title ||
                row.optString("syncState") == "outcome_unknown") continue
            if ((start == null) != (end == null)) return null
            if (start != null && end != null &&
                (row.optLong("startTime", Long.MIN_VALUE) != start || row.optLong("endTime", Long.MIN_VALUE) != end)) continue
            return id
        }
        return null
    }
}
