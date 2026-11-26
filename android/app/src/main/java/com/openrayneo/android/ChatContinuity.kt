// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Ordinary explicit chat only. Owned by the service main thread; no provider or Android dependency. */
internal class ChatContinuity(private val load: () -> State, private val save: (State) -> Unit) {
    companion object {
        const val MAX_TURNS = 30
        const val CONTEXT_TURNS = 4
        private fun clip(value: String, max: Int): String {
            val end = minOf(value.length, max)
            return value.substring(0, if (end > 0 && value[end - 1].isHighSurrogate()) end - 1 else end)
        }
        fun turn(source: String, question: String, answer: String): Turn? {
            if (source !in setOf("phone", "glasses") || question.isBlank() || answer.isBlank() || answer.trim() == "[PASS]") return null
            return Turn(source, clip(question.trim(), 1000), clip(answer.trim(), 1800))
        }
    }
    data class Turn(val source: String, val question: String, val answer: String)
    data class State(val enabled: Boolean = false, val turns: List<Turn> = emptyList()) {
        init {
            require(turns.size <= MAX_TURNS && (enabled || turns.isEmpty()))
            require(turns.all { turn(it.source, it.question, it.answer) == it })
        }
    }
    data class Ticket(val epoch: Long, val turns: List<Turn>)
    private var epoch = 0L
    var error = ""; private set
    var state: State = try { load() } catch (_: Exception) {
        error = "저장된 대화 맥락을 읽지 못했습니다. 자동 공유하지 않습니다."
        State()
    }; private set
    // Consent changes are committed before they affect any subsequent request.
    fun configure(enabled: Boolean) {
        if (state.enabled == enabled && error.isEmpty()) return
        replace(State(enabled))
    }
    fun clear() = replace(State(state.enabled))
    private fun replace(next: State) {
        save(next)
        state = next
        epoch++
        error = ""
    }
    fun ticket(question: String): Ticket? {
        if (!state.enabled) return null
        val turns = state.turns
        val recent = turns.takeLast(CONTEXT_TURNS - 1)
        val older = turns.dropLast(recent.size)
        // At most one older lexical match replaces the oldest of the four recent turns.
        val keys = AutomaticMemoryPolicy.keywords(question).filter { it.length >= 3 }
        val relevant = older.asReversed().map { item ->
            item to keys.count { key -> item.question.contains(key, ignoreCase = true) || item.answer.contains(key, ignoreCase = true) }
        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first ?: older.lastOrNull()
        return Ticket(epoch, listOfNotNull(relevant) + recent)
    }
    fun remember(ticket: Ticket?, source: String, question: String, answer: String) {
        if (ticket == null || ticket.epoch != epoch || !state.enabled) return
        val turn = turn(source, question, answer) ?: return
        val next = State(true, (state.turns + turn).takeLast(MAX_TURNS))
        try {
            save(next)
            state = next
            error = ""
        } catch (_: Exception) {
            error = "대화 저장 실패 · 이전 저장 맥락만 유지됩니다."
        }
    }
}
