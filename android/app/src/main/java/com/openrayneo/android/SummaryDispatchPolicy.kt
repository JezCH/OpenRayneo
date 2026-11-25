// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** The existing automatic-summary preference covers every unattended summary trigger.
 * A deliberately requested manual summary is independent of that preference.
 */
internal object SummaryDispatchPolicy {
    fun autoEnabled(setting: String): Boolean = when (setting.trim().lowercase()) {
        "", "true" -> true // Preserve the existing default for unset settings.
        else -> false
    }

    fun allows(kind: String, setting: String): Boolean = when (kind) {
        "manual" -> true
        "rolling", "record_end", "day_end" -> autoEnabled(setting)
        else -> false
    }
}
