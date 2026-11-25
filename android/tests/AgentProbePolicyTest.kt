// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

fun main() {
    var n = 0
    fun verify(ok: Boolean) { check(ok) { "assertion ${n + 1}" }; n++ }
    val p = AgentProbePolicy.Probe()
    val code = "OR-0123ABCD"
    val next = "OR-1234EFAB"
    verify(p.state.phase == AgentProbePolicy.Phase.IDLE)
    verify(!p.cancel())
    verify(!p.finish(code, code, 100))
    verify(runCatching { p.begin("invalid", 100) }.isFailure)
    p.begin(code, 100)
    verify(p.state.phase == AgentProbePolicy.Phase.WAITING)
    verify(runCatching { p.begin(next, 200) }.isFailure)
    verify(!p.finish(next, next, 200))
    verify(runCatching { p.hudRequested() }.isFailure)
    verify(p.finish(code, " $code\n", 500))
    verify(p.state.phase == AgentProbePolicy.Phase.PASSED && p.state.elapsedMs == 400L)
    verify(!p.finish(code, null, 600))
    verify(runCatching { p.hudConfirmed() }.isFailure)
    p.hudRequested(); verify(p.state.hud == AgentProbePolicy.Hud.REQUESTED)
    verify(runCatching { p.hudRequested() }.isFailure)
    p.hudConfirmed(); verify(p.state.hud == AgentProbePolicy.Hud.CONFIRMED)
    p.begin(next, 700); verify(p.state.hud == AgentProbePolicy.Hud.NOT_SENT)
    verify(!p.finish(code, code, 800)); verify(p.finish(next, "Code: $next", 800))
    verify(p.state.phase == AgentProbePolicy.Phase.MISMATCH)
    p.begin(code, 900); verify(p.finish(code, null, 1000))
    verify(p.state.phase == AgentProbePolicy.Phase.FAILED)
    p.begin(next, 1100); verify(p.cancel()); verify(!p.finish(next, next, 1200))
    verify(p.state.phase == AgentProbePolicy.Phase.CANCELLED)
    p.begin(code, 1300); p.reset(); verify(!p.finish(code, code, 1400))
    verify(p.state == AgentProbePolicy.State())
    println("$n assertions passed")
}
