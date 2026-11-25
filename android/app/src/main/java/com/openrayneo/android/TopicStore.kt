// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Separate encrypted namespace. Only topic IDs, names and record links are stored. */
internal class TopicStore(context: Context) {
    private val storage = AppSettings(context, "record-topics")
    fun read(): List<TopicPolicy.Topic> {
        val row = storage.read()
        if (!row.has("version")) {
            check(!row.keys().hasNext()) { "주제 저장 형식을 읽을 수 없습니다" }
            return emptyList()
        }
        check(row.opt("version") == 1) { "지원하지 않는 주제 저장 형식입니다" }
        val topics = row.optJSONArray("topics") ?: error("주제 목록이 없습니다")
        require(topics.length() <= TopicPolicy.MAX_TOPICS)
        return TopicPolicy.validate((0 until topics.length()).map { index ->
            val item = topics.getJSONObject(index)
            val ids = item.optJSONArray("recordIds") ?: error("기록 연결 목록이 없습니다")
            require(ids.length() <= TopicPolicy.MAX_RECORDS)
            TopicPolicy.Topic(item.opt("id") as? String ?: error("주제 ID가 없습니다"),
                item.opt("title") as? String ?: error("주제 이름이 없습니다"),
                (0 until ids.length()).map { ids.opt(it) as? String ?: error("기록 ID가 잘못되었습니다") }.toSet())
        })
    }
    fun update(change: (List<TopicPolicy.Topic>) -> List<TopicPolicy.Topic>): List<TopicPolicy.Topic> {
        val next = TopicPolicy.validate(change(read()))
        storage.save(JSONObject().put("version", 1).put("topics", JSONArray(next.map { topic ->
            JSONObject().put("id", topic.id).put("title", topic.title).put("recordIds", JSONArray(topic.recordIds.sorted()))
        })))
        return next
    }
}
