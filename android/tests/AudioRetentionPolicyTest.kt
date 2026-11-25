// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

// Standalone JVM regression: compile together with AudioRetentionPolicy.kt using kotlinc.
fun main() {
    var n = 0
    fun verify(value: Boolean) { check(value) { "assertion ${n + 1}" }; n++ }
    val settings = mapOf("" to false, "raw_audio" to false, " RAW_AUDIO " to false, "transcript_only" to true, "TRANSCRIPT_ONLY" to true, "unknown" to true, "true" to true, "false" to true)
    for ((setting, only) in settings) for (enroll in listOf(false,true)) {
        verify(AudioRetentionPolicy.transcriptOnly(setting, enroll) == (only && !enroll))
    }
    for (only in listOf(false,true)) {
        for (frames in listOf(0L, 1L, 16000L, 960000L, Long.MAX_VALUE)) verify(AudioRetentionPolicy.storedFrames(only,frames) == if(only) 0L else frames)
        for (incomplete in listOf(false,true)) verify(AudioRetentionPolicy.backfillPending(only,incomplete) == (!only && incomplete))
    }
    println("$n assertions passed")
}
