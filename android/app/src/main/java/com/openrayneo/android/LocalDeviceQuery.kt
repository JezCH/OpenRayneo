// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Exact commands addressed to the glasses during a native wake; no ambient commands. */
internal object LocalDeviceQuery {
    enum class Kind { BATTERY, BRIGHTNESS_READ, BRIGHTNESS_UP, BRIGHTNESS_DOWN, BRIGHTNESS_SET, AUTO_BRIGHTNESS_READ, DISPLAY_HEIGHT_READ, DISPLAY_HEIGHT_SET, DISPLAY_DISTANCE_READ, DISPLAY_DISTANCE_SET, AUTO_LOCK_READ, AUTO_LOCK_SET }
    data class Command(val kind: Kind, val level: Int? = null)
    private val subjects = listOf("안경배터리", "안경배터리잔량", "안경의배터리", "레이네오배터리", "레이네오배터리잔량")
    private val endings = listOf("확인", "확인해줘", "알려줘", "얼마야", "얼마남았어", "몇퍼센트야", "몇퍼센트남았어", "몇프로야", "몇프로남았어", "몇%야", "몇%남았어")
    private val batteryQueries = subjects.flatMap { subject -> endings.map { subject + it } }.toSet()
    private val brightness = "(?:안경(?:의|화면)?|레이네오(?:의|화면)?)밝기(?:를|가|는)?"
    private val read = Regex("^$brightness(?:확인|확인해줘|알려줘|얼마야|몇단계야)$")
    private val up = Regex("^$brightness(?:올려|올려줘|높여|높여줘|한단계올려줘|한단계높여줘)$")
    private val down = Regex("^$brightness(?:내려|내려줘|낮춰|낮춰줘|한단계내려줘|한단계낮춰줘)$")
    private val set = Regex("^$brightness([0-9]+)단계(?:로)?(?:설정해|설정해줘|맞춰|맞춰줘|바꿔|바꿔줘)$")

    private val device = "(?:안경|레이네오)(?:의)?"
    private val autoBrightness = Regex("^${device}(?:화면)?자동밝기(?:상태|설정)?(?:확인|확인해줘|알려줘|켜져있어|켜져있니)$")
    private val heightRead = Regex("^${device}화면높이(?:가|는)?(?:확인|확인해줘|알려줘|몇단계야)$")
    private val distanceRead = Regex("^${device}화면거리(?:가|는)?(?:확인|확인해줘|알려줘|몇단계야)$")
    private val lockRead = Regex("^${device}(?:자동절전|자동잠금)(?:시간)?(?:이|은)?(?:확인|확인해줘|알려줘|몇초야)$")
    private val heightSet = Regex("^${device}화면높이(?:를)?([0-9]+)단계(?:로)?(?:설정해|설정해줘|맞춰|맞춰줘|바꿔|바꿔줘)$")
    private val distanceSet = Regex("^${device}화면거리(?:를)?([0-9]+)단계(?:로)?(?:설정해|설정해줘|맞춰|맞춰줘|바꿔|바꿔줘)$")
    private val lockSet = Regex("^${device}(?:자동절전|자동잠금)(?:시간)?(?:을|를)?([0-9]+)초(?:로)?(?:설정해|설정해줘|맞춰|맞춰줘|바꿔|바꿔줘)$")
    val lockSeconds = setOf(5, 10, 15, 25, 40, 60, 120)
    fun validSetting(kind: Kind, value: Int?): Boolean = value != null && when (kind) {
        Kind.DISPLAY_HEIGHT_SET, Kind.DISPLAY_HEIGHT_READ -> value in 1..6
        Kind.DISPLAY_DISTANCE_SET, Kind.DISPLAY_DISTANCE_READ -> value in 1..3
        Kind.AUTO_LOCK_SET, Kind.AUTO_LOCK_READ -> value in lockSeconds
        else -> false
    }

    fun parse(text: String, source: ConversationSource, backfill: Boolean): Command? {
        if (source != ConversationSource.NATIVE || backfill) return null
        val normalized = text.trim().trimEnd('?', '？', '.', '!', '。', '！').filterNot { it.isWhitespace() }
        if (normalized in batteryQueries) return Command(Kind.BATTERY)
        if (read.matches(normalized)) return Command(Kind.BRIGHTNESS_READ)
        if (up.matches(normalized)) return Command(Kind.BRIGHTNESS_UP)
        if (down.matches(normalized)) return Command(Kind.BRIGHTNESS_DOWN)
        if (autoBrightness.matches(normalized)) return Command(Kind.AUTO_BRIGHTNESS_READ)
        if (heightRead.matches(normalized)) return Command(Kind.DISPLAY_HEIGHT_READ)
        if (distanceRead.matches(normalized)) return Command(Kind.DISPLAY_DISTANCE_READ)
        if (lockRead.matches(normalized)) return Command(Kind.AUTO_LOCK_READ)
        for ((pattern, kind) in listOf(heightSet to Kind.DISPLAY_HEIGHT_SET, distanceSet to Kind.DISPLAY_DISTANCE_SET, lockSet to Kind.AUTO_LOCK_SET)) {
            pattern.matchEntire(normalized)?.let { return Command(kind, it.groupValues[1].toIntOrNull()) }
        }
        return set.matchEntire(normalized)?.let { Command(Kind.BRIGHTNESS_SET, it.groupValues[1].toIntOrNull()) }
    }

    fun isBatteryQuery(text: String, source: ConversationSource, backfill: Boolean): Boolean =
        parse(text, source, backfill)?.kind == Kind.BATTERY

    fun validLevel(value: Int?) = value != null && value in 1..17
    fun target(command: Command, current: Int?): Int? = when (command.kind) {
        Kind.BRIGHTNESS_UP -> current?.takeIf(::validLevel)?.let { (it + 1).coerceAtMost(17) }
        Kind.BRIGHTNESS_DOWN -> current?.takeIf(::validLevel)?.let { (it - 1).coerceAtLeast(1) }
        Kind.BRIGHTNESS_SET -> command.level?.takeIf(::validLevel)
        else -> null
    }
    fun batteryAnswer(percent: Int?): String = if (percent != null && percent in 0..100)
        "안경 배터리는 ${percent}%입니다." else "안경 배터리 잔량을 확인하지 못했습니다."
    fun brightnessAnswer(level: Int?): String = if (validLevel(level))
        "안경 밝기는 ${level}단계입니다." else "안경 밝기를 확인하지 못했습니다."
}
