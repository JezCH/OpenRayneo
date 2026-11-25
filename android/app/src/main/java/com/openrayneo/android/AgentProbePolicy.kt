// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Process-local, explicitly requested diagnostic; never includes conversation or profile data. */
internal object AgentProbePolicy {
    enum class Phase { IDLE, WAITING, PASSED, MISMATCH, FAILED, CANCELLED }
    enum class Hud { NOT_SENT, REQUESTED, CONFIRMED }
    enum class Target { DEFAULT, AMBIENT }
    data class ModelRoute(val provider: String, val model: String, val selection: String) {
        companion object {
            fun from(provider: String, model: String, selection: String): ModelRoute? {
                fun valid(value: String) = value.isNotBlank() && value.length <= 256 && value.none { it.isISOControl() }
                return if (valid(provider) && valid(model) && selection in setOf("default", "ambient_default", "ambient_override"))
                    ModelRoute(provider, model, selection) else null
            }
        }
        fun matches(target: Target) = if (target == Target.DEFAULT) selection == "default" else selection.startsWith("ambient_")
    }
    data class State(val phase: Phase = Phase.IDLE, val code: String = "", val elapsedMs: Long? = null,
                     val hud: Hud = Hud.NOT_SENT, val target: Target = Target.DEFAULT, val modelRoute: ModelRoute? = null)
    const val CONTEXT = "This is a connection diagnostic, not a conversation or task. Return only the exact code field from the JSON input. No explanation or other text."
    class Probe {
        var state = State(); private set
        private var started = 0L
        fun begin(code: String, now: Long, target: Target = Target.DEFAULT) {
            check(state.phase != Phase.WAITING)
            require(code.matches(Regex("OR-[0-9A-F]{8}")))
            started = now; state = State(Phase.WAITING, code, target = target)
        }
        fun finish(code: String, answer: String?, now: Long, route: ModelRoute? = null): Boolean {
            if (state.phase != Phase.WAITING || code != state.code) return false
            val phase = when {
                answer == null -> Phase.FAILED
                answer.trim() == code -> Phase.PASSED
                else -> Phase.MISMATCH
            }
            state = state.copy(phase = phase, elapsedMs = (now - started).coerceAtLeast(0), modelRoute = route)
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
