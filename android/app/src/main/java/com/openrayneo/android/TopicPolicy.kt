// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import java.text.Normalizer
import java.util.Locale

/** User-managed links to original records; no record content is copied or changed. */
internal object TopicPolicy {
    const val MAX_TOPICS = 32
    const val MAX_RECORDS = 1000
    const val MAX_TITLE = 80
    data class Topic(val id: String, val title: String, val recordIds: Set<String> = emptySet())
    private fun key(title: String) = Normalizer.normalize(title.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
    private fun validId(id: String) = id.isNotBlank() && id.length <= 128
    private fun title(value: String): String = value.trim().also { require(it.length in 1..MAX_TITLE && it.none(Char::isISOControl)) { "주제 이름을 1~80자로 입력하세요" } }
    fun validate(topics: List<Topic>): List<Topic> {
        require(topics.size <= MAX_TOPICS && topics.map { it.id }.distinct().size == topics.size)
        require(topics.map { key(it.title) }.distinct().size == topics.size) { "같은 이름의 주제가 이미 있습니다" }
        return topics.map { topic ->
            require(validId(topic.id) && topic.recordIds.size <= MAX_RECORDS && topic.recordIds.all(::validId))
            topic.copy(title = title(topic.title), recordIds = topic.recordIds.toSet())
        }
    }
    fun create(topics: List<Topic>, id: String, name: String): List<Topic> = validate(topics + Topic(id, name))
    fun rename(topics: List<Topic>, id: String, name: String): List<Topic> {
        require(topics.any { it.id == id }) { "주제가 없습니다" }
        return validate(topics.map { if (it.id == id) it.copy(title = name) else it })
    }
    fun link(topics: List<Topic>, id: String, recordId: String, linked: Boolean): List<Topic> {
        require(validId(recordId) && topics.any { it.id == id }) { "기록 또는 주제가 없습니다" }
        return validate(topics.map { if (it.id == id) it.copy(recordIds = if (linked) it.recordIds + recordId else it.recordIds - recordId) else it })
    }
    fun remove(topics: List<Topic>, id: String): List<Topic> = validate(topics.filterNot { it.id == id })
}
