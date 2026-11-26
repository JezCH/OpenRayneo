// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import java.util.Locale
import java.util.PriorityQueue

/** Local ranked lexical retrieval; no embeddings, network or automatic transmission. */
internal object AutomaticMemoryPolicy {
    val days = listOf(7, 30, 90)
    val limits = listOf(1, 3, 5)
    data class Options(val days: Int = 30, val limit: Int = 3) {
        init { require(days in AutomaticMemoryPolicy.days && limit in limits) }
    }
    private val stop = setOf("내가", "우리", "저희", "예전에", "이전에", "지난번", "지난", "오늘", "어제", "최근", "기억", "기록",
        "대화", "내용", "관련", "대해서", "대해", "무엇", "뭐야", "뭐였지", "어떻게", "알려줘", "알려주세요", "정리해줘", "했어", "했지", "했던", "있어", "있었어", "확인해줘", "말해줘",
        "언제", "누구", "그때", "그거", "그것", "어떤", "무슨", "다시", "좀", "그전에", "했더라", "기억나", "기억해")
    private val particles = listOf("에서는", "에게서", "으로는", "에서", "에게", "으로", "까지", "부터", "은", "는", "이", "가", "을", "를", "의", "에", "도", "와", "과", "로")
    private val tokenPattern = Regex("[가-힣A-Za-z0-9]{2,32}")
    private val whitespace = Regex("\\s+")
    private fun normalize(text: String) = KoreanLanguage.normalize(text).trim().lowercase(Locale.ROOT)
    private fun stem(term: String): String {
        if (!term.all { it in '가'..'힣' }) return term
        val suffix = particles.firstOrNull { term.endsWith(it) && term.length - it.length >= 2 }
        return if (suffix == null) term else term.dropLast(suffix.length)
    }
    fun keywords(question: String): List<String> = tokenPattern.findAll(normalize(question))
        .map { it.value }.filterNot { it in stop || stem(it) in stop || it.all(Char::isDigit) }
        .distinctBy(::stem).take(8).toList()
    private data class Term(val literal: String, val root: String, val flexible: Regex?)
    private fun terms(query: String) = keywords(query).map { word ->
        val root = stem(word)
        // Only for a long Hangul term; never bridge punctuation, words in another script or line breaks.
        val flexible = if (root.length >= 4 && root.all { it in '가'..'힣' })
            Regex(root.map { Regex.escape(it.toString()) }.joinToString("[ \\t]*")) else null
        Term(word, root, flexible)
    }
    private data class Match(val index: Int, val strength: Int, val length: Int)
    private fun match(term: Term, text: String, words: List<MatchResult>): Match? {
        // Short terms must be an entire token or the stem of a token, not an arbitrary substring.
        val whole = words.firstOrNull { it.value == term.literal || it.value == term.root || stem(it.value) == term.root }
        if (whole != null) return Match(whole.range.first, if (whole.value == term.literal) 4 else 3, term.root.length)
        if (term.root.length < 3) return null
        val literal = text.indexOf(term.literal)
        if (literal >= 0) return Match(literal, 3, term.literal.length)
        val root = text.indexOf(term.root)
        if (root >= 0) return Match(root, 2, term.root.length)
        val split = term.flexible?.find(text) ?: return null
        return Match(split.range.first, 1, term.root.length)
    }
    private data class Candidate(val hit: MemoryReferencePolicy.Hit, val key: String)
    private val ranking = compareByDescending<Candidate> { it.hit.score }
        .thenByDescending { it.hit.source.start }.thenBy { it.hit.source.recordId }.thenBy { it.hit.source.segmentId }

    /** User-facing manual search permits partial term matches; the exact external tool contract is unchanged. */
    fun related(query: String, limit: Int, records: Sequence<MemoryReferencePolicy.Source>,
                automatic: Boolean = false): MemoryReferencePolicy.Result {
        require(limit in 1..MemoryReferencePolicy.MAX_RESULTS)
        val phrase = normalize(query).replace(whitespace, " ")
        val terms = terms(query)
        val compactPhrase = phrase.replace(" ", "")
        val flexiblePhrase = if (compactPhrase.length in 4..32 && phrase.all { it in '가'..'힣' || it == ' ' })
            Regex(compactPhrase.map { Regex.escape(it.toString()) }.joinToString("[ \\t]*")) else null
        if (phrase.isBlank() || (automatic && terms.isEmpty())) return MemoryReferencePolicy.Result(emptyList(), false)
        // Bound working storage to the returned limit + one, including duplicate filtering.
        val best = PriorityQueue<Candidate>(limit + 1, ranking.reversed())
        for (source in records) {
            if (source.recordId.isBlank() || source.segmentId.isBlank() || source.text.isBlank()) continue
            val text = normalize(source.text)
            val exact = text.replace(whitespace, " ").contains(phrase)
            val spaced = !exact && flexiblePhrase?.containsMatchIn(text) == true
            val words = tokenPattern.findAll(text).toList()
            val matches = terms.mapNotNull { match(it, text, words) }
            if (!exact && !spaced && matches.isEmpty()) continue
            // One short term alone is too weak for unattended suggestions; manual search is explicit.
            if (automatic && !spaced && (matches.isEmpty() || (matches.size == 1 && matches[0].length < 3))) continue
            val strongest = matches.maxWithOrNull(compareBy<Match> { it.strength }.thenBy { it.length })
            val score = (if (exact) 1000 else if (spaced) 800 else 0) + matches.size * 100 + matches.sumOf { it.strength * 10 + it.length.coerceAtMost(12) }
            // Resolve the location against the original raw string (NFC can change offsets).
            val raw = source.text
            val rawIndex = raw.indexOf(query.trim(), ignoreCase = true).takeIf { it >= 0 }
                ?: flexiblePhrase?.find(raw)?.range?.first
                ?: terms.mapNotNull { t -> match(t, text, words)?.let { m -> t to m } }
                    .sortedWith(compareByDescending<Pair<Term, Match>> { it.second.strength }.thenByDescending { it.second.length })
                    .firstNotNullOfOrNull { (t, _) ->
                        raw.indexOf(t.literal, ignoreCase = true).takeIf { it >= 0 }
                            ?: raw.indexOf(t.root, ignoreCase = true).takeIf { it >= 0 }
                            ?: t.flexible?.find(raw)?.range?.first
                    } ?: strongest?.index?.takeIf { text == raw.lowercase(Locale.ROOT) } ?: 0
            val candidate = Candidate(MemoryReferencePolicy.excerpt(source, rawIndex, score), text.replace(whitespace, " "))
            if (automatic) {
                val duplicate = best.firstOrNull { it.key == candidate.key }
                if (duplicate != null) {
                    if (ranking.compare(candidate, duplicate) >= 0) continue
                    best.remove(duplicate)
                }
            }
            if (best.size < limit + 1) best.add(candidate)
            else if (ranking.compare(candidate, best.peek()) < 0) { best.poll(); best.add(candidate) }
        }
        return MemoryReferencePolicy.Result(best.toList().sortedWith(ranking).take(limit).map { it.hit }, best.size > limit)
    }
    fun search(question: String, options: Options, now: Long, records: Sequence<MemoryReferencePolicy.Source>): MemoryReferencePolicy.Result {
        val from = (now - options.days * 86_400_000L).coerceAtLeast(0)
        return related(question, options.limit, records.filter { it.start >= from && it.end <= now && it.end > it.start &&
            it.stable && !it.mixed && !it.incomplete }, automatic = true)
    }
}
