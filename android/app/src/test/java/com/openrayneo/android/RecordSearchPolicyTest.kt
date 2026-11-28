// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Assert.*
import org.junit.Test

class RecordSearchPolicyTest {
    @Test fun searchesOldRecordAndShowsMatchingPassage() {
        val record = ConversationRecord(mode = ConversationMode.DAILY, createdAt = 1L,
            segments = listOf(SpeechSegment("s", 0, 1000, "가".repeat(250) + "공사비 검토" + "나".repeat(250))))
        assertTrue(RecordSearchPolicy.matches(record, emptyMap(), "공사비"))
        val excerpt = RecordSearchPolicy.excerpt(record, emptyMap(), "공사비")
        assertTrue(excerpt.startsWith("원문 · …"))
        assertTrue(excerpt.contains("공사비"))
        assertTrue(excerpt.endsWith("…"))
        assertFalse(RecordSearchPolicy.matches(record, emptyMap(), "없는 내용"))
    }

    @Test fun findsAssignedPersonWithoutInventingASpeechMatch() {
        val person = VoicePerson(id = "p", name = "테스트 인물")
        val record = ConversationRecord(mode = ConversationMode.MEETING,
            segments = listOf(SpeechSegment("s", 0, 1000, "회의 내용", personId = "p")))
        val people = mapOf(person.id to person)
        assertTrue(RecordSearchPolicy.matches(record, people, "테스트"))
        assertTrue(RecordSearchPolicy.excerpt(record, people, "테스트").startsWith("인물 · 테스트 인물"))
        assertFalse(RecordSearchPolicy.matches(record, emptyMap(), "테스트"))
    }

    @Test fun labelsStaleSummaryAndMemorySeparately() {
        val record = ConversationRecord(mode = ConversationMode.DAILY, summary = "기존 계획", summaryStale = true,
            memories = listOf(AttributedMemory(sourceIds = emptyList(), speakerId = null, subjectId = null,
                subject = "프로젝트", text = "회의 일정")))
        assertTrue(RecordSearchPolicy.matches(record, emptyMap(), "기존"))
        assertEquals("이전 요약 · 기존 계획", RecordSearchPolicy.excerpt(record, emptyMap(), "기존"))
        assertTrue(RecordSearchPolicy.matches(record, emptyMap(), "일정"))
        assertTrue(RecordSearchPolicy.excerpt(record, emptyMap(), "일정").startsWith("기억 · "))
        assertTrue(RecordSearchPolicy.matches(record, emptyMap(), ""))
    }
}
