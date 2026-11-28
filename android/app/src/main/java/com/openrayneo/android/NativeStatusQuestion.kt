// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Narrow read-only routing for explicit questions. Never turn passive
 * transcripts, general discussion or mutation requests into device commands. */
internal object NativeStatusQuestion {
    enum class Kind { FIRMWARE, NAVIGATION }
    private val firmware = Regex("^(?:(?:지금|현재)?(?:우리|내|이)?(?:안경|레이네오)?(?:의)?(?:펌웨어|페메어|허메어|페메|펌웨어버전)(?:는|이|가|을|를)?(?:버전)?(?:은|는|이|가)?(?:어떤버전인데|몇버전이야|몇이야|뭐야|무엇이야|알려줘|확인해봐|확인해줘|확인|어떻게돼))$")
    private val navigation = Regex("^(?:(?:아니|그러면|지금|현재|우리|안경|레이네오|순정)*(?:내비|네비|내비게이션|네비게이션)(?:의)?(?:연동|연결|지원|작동)(?:상태|여부)?(?:를|을|로|는|이|가)?(?:파악해봐|파악해줘|확인해봐|확인해줘|확인|알려줘|어때|돼|되니|됐어))$")
    fun parse(text: String, source: ConversationSource, backfill: Boolean): Kind? {
        if (source != ConversationSource.NATIVE || backfill) return null
        val normalized = KoreanLanguage.normalize(text).filterNot { it.isWhitespace() }.trimEnd('?', '？', '.', '!', '。', '！')
        return when {
            firmware.matches(normalized) -> Kind.FIRMWARE
            navigation.matches(normalized) -> Kind.NAVIGATION
            else -> null
        }
    }
}
