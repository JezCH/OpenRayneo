// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** The published Korean model uses unigram SentencePiece. Restrict inputs to the app's Hangul wake
 * vocabulary; never guess a token for an unknown syllable. Parse only normal pieces and their scores.
 * No native SentencePiece dependency or broad arbitrary protobuf deserialization is required. */
internal class KoreanKeywordTokenizer(model: ByteArray, private val tokens: Set<String>) {
    private data class Piece(val text: String, val score: Double)
    private val pieces: Map<Char, List<Piece>>
    init {
        require(model.size in 1..1_000_000 && tokens.size in 1..10_000)
        val input = Proto(model)
        val all = mutableListOf<Piece>()
        while (input.more) {
            val key = input.varint()
            if (key == 10) {
                val row = Proto(input.bytes())
                var text = ""; var score = 0f; var type = 1
                while (row.more) {
                    val field = row.varint()
                    when (field) {
                        10 -> text = row.bytes().toString(Charsets.UTF_8)
                        21 -> score = Float.fromBits(row.fixed32())
                        24 -> type = row.varint()
                        else -> row.skip(field and 7)
                    }
                }
                if (type == 1 && score.isFinite() && text.isNotEmpty() &&
                    text.all { it in '가'..'힣' || it == '▁' } && text in tokens)
                    all.add(Piece(text, score.toDouble()))
            } else input.skip(key and 7)
        }
        require(all.size in 100..10_000) { "한국어 호출 모델의 토큰 형식이 일치하지 않습니다" }
        pieces = all.groupBy { it.text.first() }
    }

    fun encode(phrase: String): List<String> {
        val clean = java.text.Normalizer.normalize(phrase.trim(), java.text.Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")
        require(clean.matches(Regex("[가-힣]+(?: [가-힣]+)*")) && clean.count { it != ' ' } in 2..12)
        val normalized = "▁" + clean.replace(' ', '▁')
        val score = DoubleArray(normalized.length + 1) { Double.NEGATIVE_INFINITY }
        val previous = arrayOfNulls<Piece>(normalized.length + 1)
        score[0] = 0.0
        for (start in normalized.indices) {
            if (!score[start].isFinite()) continue
            for (piece in pieces[normalized[start]].orEmpty()) {
                if (!normalized.startsWith(piece.text, start)) continue
                val end = start + piece.text.length
                val next = score[start] + piece.score
                if (next > score[end]) { score[end] = next; previous[end] = piece }
            }
        }
        require(score.last().isFinite()) { "이 호출어를 경량 모델 토큰으로 변환할 수 없습니다. 다른 호출어를 선택하세요" }
        var end = normalized.length
        val result = mutableListOf<String>()
        while (end > 0) {
            val piece = checkNotNull(previous[end])
            result.add(piece.text); end -= piece.text.length
        }
        return result.asReversed()
    }

    private class Proto(private val data: ByteArray) {
        private var pos = 0
        val more get() = pos < data.size
        fun varint(): Int {
            var value = 0L
            for (shift in 0..28 step 7) {
                require(pos < data.size)
                val b = data[pos++].toInt() and 255
                value = value or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) { require(value <= Int.MAX_VALUE); return value.toInt() }
            }
            error("지원하지 않는 모델 토큰 형식")
        }
        fun bytes(): ByteArray {
            val n = varint()
            require(n <= data.size - pos)
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }
        fun fixed32(): Int {
            require(data.size - pos >= 4)
            var result = 0
            repeat(4) { i -> result = result or ((data[pos++].toInt() and 255) shl (8 * i)) }
            return result
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> { var count = 0; do { require(pos < data.size && count++ < 10); val b = data[pos++].toInt() and 255 } while (b and 128 != 0) }
                1 -> advance(8)
                2 -> advance(varint())
                5 -> advance(4)
                else -> error("지원하지 않는 모델 토큰 형식")
            }
        }
        private fun advance(n: Int) { require(n in 0..(data.size - pos)); pos += n }
    }
}
