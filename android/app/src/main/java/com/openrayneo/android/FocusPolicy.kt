// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Controls only this companion's outputs, not Android or firmware-wide DND. */
internal object FocusPolicy {
    enum class Command { FOCUS_ON, FOCUS_OFF, FOCUS_READ, NOTIFICATIONS_ON, NOTIFICATIONS_OFF, NOTIFICATIONS_READ }
    enum class Output { PASSIVE_HINT, GENERAL_NOTIFICATION, TIMER_NOTIFICATION, DIRECT_ANSWER }
    private val commands = mapOf(
        "집중모드켜줘" to Command.FOCUS_ON, "집중모드시작해줘" to Command.FOCUS_ON,
        "방해금지켜줘" to Command.FOCUS_ON, "집중모드꺼줘" to Command.FOCUS_OFF,
        "집중모드종료해줘" to Command.FOCUS_OFF, "방해금지꺼줘" to Command.FOCUS_OFF,
        "집중모드상태알려줘" to Command.FOCUS_READ,
        "알림켜줘" to Command.NOTIFICATIONS_ON, "알림전송켜줘" to Command.NOTIFICATIONS_ON,
        "알림꺼줘" to Command.NOTIFICATIONS_OFF, "알림전송꺼줘" to Command.NOTIFICATIONS_OFF,
        "알림상태알려줘" to Command.NOTIFICATIONS_READ)
    fun parse(text: String, source: ConversationSource, backfill: Boolean): Command? {
        if (source != ConversationSource.NATIVE || backfill) return null
        val clean = KoreanLanguage.normalize(text).trim().trimEnd('?', '？', '.', '!', '。', '！').filterNot(Char::isWhitespace)
        val body = when { clean.startsWith("안경") -> clean.removePrefix("안경"); clean.startsWith("레이네오") -> clean.removePrefix("레이네오"); else -> return null }
        return commands[body]
    }
    class State {
        var focused = false; private set
        var notificationsEnabled = true; private set
        var generation = 0L; private set
        fun allows(output: Output): Boolean = when (output) {
            Output.PASSIVE_HINT, Output.GENERAL_NOTIFICATION -> notificationsEnabled && !focused
            Output.TIMER_NOTIFICATION -> notificationsEnabled
            Output.DIRECT_ANSWER -> true
        }
        fun acceptsHint(epoch: Long): Boolean = epoch == generation && allows(Output.PASSIVE_HINT)
        fun apply(command: Command): String {
            val wasFocused = focused
            val wasEnabled = notificationsEnabled
            when (command) {
                Command.FOCUS_ON -> focused = true
                Command.FOCUS_OFF -> focused = false
                Command.NOTIFICATIONS_ON -> notificationsEnabled = true
                Command.NOTIFICATIONS_OFF -> notificationsEnabled = false
                else -> Unit
            }
            if (focused != wasFocused || notificationsEnabled != wasEnabled) generation++
            return when (command) {
                Command.FOCUS_ON, Command.FOCUS_OFF, Command.FOCUS_READ -> "앱 집중 모드 ${if (focused) "켜짐" else "꺼짐"}."
                else -> "앱 안경 알림 ${if (notificationsEnabled) "켜짐" else "꺼짐"}."
            }
        }
        fun reset() { focused = false; notificationsEnabled = true; generation++ }
    }
}
