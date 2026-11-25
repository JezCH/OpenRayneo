// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Process-local, explicitly requested diagnostic; never includes conversation or profile data. */
internal object AgentProbePolicy {
    enum class Phase { IDLE, WAITING, PASSED, MISMATCH, FAILED, CANCELLED }
    enum class Hud { NOT_SENT, REQUESTED, CONFIRMED }
    data class State(val phase: Phase = Phase.IDLE, val code: String = "", val elapsedMs: Long? = null,
                     val hud: Hud = Hud.NOT_SENT)
    const val CONTEXT = "This is a connection diagnostic, not a conversation or task. Return only the exact code field from the JSON input. No explanation or other text."
    class Probe {
        var state = State(); private set
        private var started = 0L
        fun begin(code: String, now: Long) {
            check(state.phase != Phase.WAITING)
            require(code.matches(Regex("OR-[0-9A-F]{8}")))
            started = now; state = State(Phase.WAITING, code)
        }
        fun finish(code: String, answer: String?, now: Long): Boolean {
            if (state.phase != Phase.WAITING || code != state.code) return false
            val phase = when {
                answer == null -> Phase.FAILED
                answer.trim() == code -> Phase.PASSED
                else -> Phase.MISMATCH
            }
            state = state.copy(phase = phase, elapsedMs = (now - started).coerceAtLeast(0))
            return true
        }
        fun cancel(): Boolean {
            if (state.phase != Phase.WAITING) return false
            state = state.copy(phase = Phase.CANCELLED); return true
        }
        fun reset() { state = State() }
        fun hudRequested() {
            check(state.phase == Phase.PASSED && state.hud == Hud.NOT_SENT)
            state = state.copy(hud = Hud.REQUESTED)
        }
        fun hudConfirmed() {
            check(state.phase == Phase.PASSED && state.hud == Hud.REQUESTED)
            state = state.copy(hud = Hud.CONFIRMED)
        }
    }
}
