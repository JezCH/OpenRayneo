// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Independent encrypted namespace, not included in general settings import/export or record sync. */
internal object ChatContinuityStore {
    fun open(context: Context): ChatContinuity {
        val storage = AppSettings(context, "ordinary-chat-context")
        return ChatContinuity({
            val row = storage.read()
            if (!row.keys().hasNext()) ChatContinuity.State()
            else {
                check(row.opt("version") == 1 && row.opt("enabled") is Boolean)
                val items = row.getJSONArray("turns")
                require(items.length() <= ChatContinuity.MAX_TURNS)
                ChatContinuity.State(row.getBoolean("enabled"), List(items.length()) { i ->
                    val item = items.getJSONObject(i)
                    ChatContinuity.Turn(item.getString("source"), item.getString("question"), item.getString("answer"))
                })
            }
        }, { state ->
            storage.save(JSONObject().put("version", 1).put("enabled", state.enabled).put("turns", json(state.turns)))
        })
    }
    fun json(turns: List<ChatContinuity.Turn>): JSONArray = JSONArray(turns.map {
        JSONObject().put("source", it.source).put("question", it.question).put("answer", it.answer)
    })
}
