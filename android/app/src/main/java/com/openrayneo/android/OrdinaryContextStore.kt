// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Only user-approved scope and field hashes are stored. Auto references are not stored in the outbox. */
internal class OrdinaryContextStore(context: Context) {
    private val app = context.applicationContext
    private val storage = AppSettings(app, "ordinary-context-v1")
    fun read(): OrdinaryContextPolicy.Grant {
        val row = storage.read()
        if (!row.has("version")) {
            check(!row.keys().hasNext())
            return OrdinaryContextPolicy.Grant()
        }
        require(row.opt("version") == 1)
        val revision = row.getString("revision")
        require(revision.isNotBlank())
        val hashes = row.getJSONObject("profileHashes")
        val profile = hashes.keys().asSequence().associateWith { hashes.getString(it) }
        require(profile.all { (key, value) -> key in OrdinaryContextPolicy.profileKeys && value.matches(Regex("[a-f0-9]{64}")) })
        val array = row.getJSONArray("topicIds")
        require(array.length() <= TopicPolicy.MAX_TOPICS)
        val ids = (0 until array.length()).map { array.getString(it) }.toSet()
        require(ids.size == array.length() && ids.all { it.isNotBlank() && it.length <= 128 })
        return OrdinaryContextPolicy.Grant(revision, profile, ids, row.opt("glasses") == true)
    }

    fun save(selectedProfile: Map<String, String>, topicIds: Set<String>, glasses: Boolean): OrdinaryContextPolicy.Grant {
        val current = AboutYouPolicy.shareableFields(AboutYouStore(app).read())
        require(selectedProfile.all { (key, value) -> current[key] == value }) { "개인화 정보가 변경되었습니다. 다시 확인하세요" }
        val topics = TopicStore(app).read().map { it.id }.toSet()
        val grant = OrdinaryContextPolicy.grant(java.util.UUID.randomUUID().toString(), current, selectedProfile.keys,
            topics, topicIds, glasses)
        storage.save(JSONObject().put("version", 1).put("revision", grant.revision)
            .put("profileHashes", JSONObject(grant.profileHashes)).put("topicIds", JSONArray(grant.topicIds.sorted()))
            .put("glasses", grant.glasses))
        return grant
    }

    /** Runs on the authenticated DSH polling thread, not on the UI thread or under the relay lock. */
    fun prepare(lease: OrdinaryContextPolicy.Grant, question: String, wearable: Boolean,
                repository: ConversationRepository): JSONObject? {
        if (!lease.allows(wearable) || question.isBlank() || question.length > 2000 || read() != lease) return null
        fun profile() = OrdinaryContextPolicy.profile(lease, AboutYouPolicy.shareableFields(AboutYouStore(app).read()))
        fun linkedRecords() = TopicStore(app).read().filter { it.id in lease.topicIds }.flatMap { it.recordIds }.toSet()
        val fields = if (lease.profileHashes.isNotEmpty()) profile() else emptyMap()
        val linked = if (lease.topicIds.isNotEmpty()) linkedRecords() else emptySet()
        val found = if (linked.isEmpty()) MemoryReferencePolicy.Result(emptyList(), false) else {
            val now = System.currentTimeMillis()
            val records = repository.referenceSnapshot().filter { it.id in linked &&
                it.endedAt != null && !it.processingPending && !it.audioPending && it.mode != ConversationMode.ENROLL }
            val result = MemoryReferenceSearch.automatic(question,
                AutomaticMemoryPolicy.Options(OrdinaryContextPolicy.DAYS, OrdinaryContextPolicy.LIMIT), now, records)
            result.copy(hits = result.hits.filter { it.score >= OrdinaryContextPolicy.MIN_SCORE })
        }
        // Scope may have changed while searching. Never widen or reuse a revoked grant.
        if (read() != lease) return null
        val checkedFields = if (fields.isNotEmpty()) profile().filter { (key, value) -> fields[key] == value } else emptyMap()
        val verified = if (found.hits.isEmpty()) found else {
            val current = linkedRecords()
            val records = repository.referenceSnapshot().filter { it.id in current &&
                it.endedAt != null && !it.processingPending && !it.audioPending && it.mode != ConversationMode.ENROLL }
            if (MemoryReferenceSearch.current(found, records)) found else MemoryReferencePolicy.Result(emptyList(), false)
        }
        if (read() != lease || (verified.hits.isEmpty() && checkedFields.isEmpty())) return null
        return MemoryReferenceSearch.json(verified).apply {
            put("selection", "user_preapproved_scope")
            if (checkedFields.isNotEmpty()) put("userProfile", JSONObject(checkedFields)).put("profileSource", "user_entered")
        }
    }
}
