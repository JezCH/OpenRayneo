// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Conservative local retrieval. No semantic inference or automatic external model calls. */
internal object AutomaticMemoryPolicy {
    val days = listOf(7, 30, 90)
    val limits = listOf(1, 3, 5)
    data class Options(val days: Int = 30, val limit: Int = 3) {
        init { require(days in AutomaticMemoryPolicy.days && limit in limits) }
    }
    private val stop = setOf("내가", "우리", "저희", "예전에", "이전에", "지난번", "지난", "오늘", "어제", "최근", "기억", "기록",
        "대화", "내용", "관련", "대해서", "대해", "무엇", "뭐야", "어떻게", "알려줘", "알려주세요", "정리해줘", "했어", "했지", "했던", "있어", "있었어", "확인해줘", "말해줘")
    fun keywords(question: String): List<String> = Regex("[가-힣A-Za-z0-9]{2,32}")
        .findAll(KoreanLanguage.normalize(question).lowercase(java.util.Locale.ROOT))
        .map { it.value }.filterNot { it in stop || it.all(Char::isDigit) }
        .distinct().take(8).toList()
    fun search(question: String, options: Options, now: Long, records: Sequence<MemoryReferencePolicy.Source>): MemoryReferencePolicy.Result {
        val terms = keywords(question)
        if (terms.isEmpty()) return MemoryReferencePolicy.Result(emptyList(), false)
        val from = (now - options.days * 86_400_000L).coerceAtLeast(0)
        val candidates = records.asSequence().filter { it.start >= from && it.end <= now && it.end > it.start &&
            it.stable && !it.mixed && !it.incomplete && it.text.isNotBlank() }.mapNotNull { source ->
            val text = KoreanLanguage.normalize(source.text).lowercase(java.util.Locale.ROOT)
            val matches = terms.filter { text.contains(it) }
            // Single terms must be specific enough; multiple matching terms are preferred.
            if (matches.isEmpty() || (matches.size == 1 && matches[0].length < 3)) null
            else source to matches
        }.sortedWith(compareByDescending<Pair<MemoryReferencePolicy.Source, List<String>>> { it.second.size }
            .thenByDescending { it.second.maxOf { term -> term.length } }.thenByDescending { it.first.start }
            .thenBy { it.first.recordId }.thenBy { it.first.segmentId })
            .take(options.limit + 1).toList()
        val hits = candidates.take(options.limit).mapNotNull { (source, matches) ->
            MemoryReferencePolicy.search(MemoryReferencePolicy.Scope(query = matches.maxBy { it.length }, limit = 1),
                sequenceOf(source)).hits.firstOrNull()
        }
        return MemoryReferencePolicy.Result(hits, candidates.size > options.limit)
    }
}
