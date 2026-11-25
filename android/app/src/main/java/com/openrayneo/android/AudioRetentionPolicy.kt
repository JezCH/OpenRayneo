// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Session-scoped retention; unknown nonempty settings fail closed. */
internal object AudioRetentionPolicy {
    fun transcriptOnly(setting: String, enrollment: Boolean = false): Boolean =
        !enrollment && setting.trim().lowercase() !in setOf("", "raw_audio")

    fun storedFrames(transcriptOnly: Boolean, frames: Long): Long =
        if (transcriptOnly) 0L else frames

    fun backfillPending(transcriptOnly: Boolean, incomplete: Boolean): Boolean =
        !transcriptOnly && incomplete
}
