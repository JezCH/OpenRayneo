// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject

internal data class DeviceSettingsState(val status: JSONObject = JSONObject(), val settings: JSONObject = JSONObject(),
    val loaded: Boolean = false, val busy: Boolean = false, val notice: String = "안경 연결 후 설정 읽기",
    val readback: DeviceReadback = DeviceReadback.NONE)
internal fun JSONObject.deviceInt(key: String): Int? = (opt(key) as? Number)?.toInt()
internal fun JSONObject.deviceFlag(key: String): Boolean? = when (val value = opt(key)) {
    is Boolean -> value; is Number -> value.toInt() == 1; else -> null
}
internal class GlassesDeviceSettings(private val requests: GlassesRequests, private val changed: () -> Unit) {
    @Volatile var state = DeviceSettingsState(); private set
    private val reported = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var revision = 0L
    /** Two independent, correlated business replies from this connection.
     * A rejected or timed-out read does not make the transport disconnect,
     * and a successful socket write does not become device-readback success. */
    fun refresh() {
        check(!state.busy) { "현재 기기 작업을 기다리세요" }
        val token = ++revision
        state = state.copy(busy = true, readback = DeviceReadback.PENDING, notice = "안경 상태와 설정을 각각 읽는 중…"); changed()
        requests.perform({
            val status = runCatching {
                requests.request(15, 1, envelope("request_general_status")) { type, body ->
                    type == 1 && body.optJSONObject("generalStatus")?.length()?.let { it > 0 } == true
                }?.optJSONObject("generalStatus")
            }.getOrNull()
            val settings = runCatching {
                readSettingsForVoice()
            }.getOrNull()
            val battery = (status?.opt("battery") as? Number)?.toDouble()
            val brightness = (status?.opt("brightness") as? Number)?.toDouble()
            val meaningfulStatus = battery?.let { it in 0.0..100.0 && it == it.toInt().toDouble() } == true ||
                brightness?.let { it in 1.0..17.0 && it == it.toInt().toDouble() } == true
            DeviceReadback.from(meaningfulStatus, settings?.length()?.let { it > 0 } == true)
        }) { result ->
            if (token == revision) {
                val readback = result.getOrDefault(DeviceReadback.FAILED)
                state = state.copy(busy = false, readback = readback, notice = readback.label)
                changed()
            }
        }
    }
    fun disconnected() { revision++; state = DeviceSettingsState(); reported.clear(); changed() }
    private fun readAll() {
        requests.request(15, 1, envelope("request_general_status")) { type, body -> type == 1 && body.has("generalStatus") }
        requests.request(15, 4, envelope("request_general_settings")) { type, body -> type == 4 && body.has("generalSettings") }
    }
    fun brightness(value: Int) { require(value in 1..17); apply("brightness_change", 2, 3, payload(value), "brightness") { it.status.deviceInt("brightness") == value } }
    fun display(height: Int, distance: Int) {
        require(height in 1..6 && distance in 1..3)
        apply("display_config", 5, 6, payload(data = JSONObject().put("height", height).put("distance", distance).toString()), "displayConfig") {
            it.settings.optJSONObject("displayConfig")?.let { v -> v.deviceInt("height") == height && v.deviceInt("distance") == distance } == true
        }
    }
    fun autoLock(seconds: Int) { require(seconds in listOf(5,10,15,25,40,60,120)); apply("auto_lock_time", 5, 6, payload(seconds), "autoLockTime") { it.settings.deviceInt("autoLockTime") == seconds } }
    fun headup(enabled: Boolean) {
        val wake = checkNotNull(state.settings.optJSONObject("wakeupConfig"))
        val data = JSONObject().put("headup_switch", if (enabled) 1 else 0)
            .put("headup_degree", wake.getInt("headupDegree")).put("crown_switch", wake.getInt("crownSwitch"))
        apply("wakeup_config", 5, 6, payload(data = data.toString()), "wakeupConfig") { it.settings.optJSONObject("wakeupConfig")?.deviceFlag("headupSwitch") == enabled }
    }
    fun wakeup(enabled: Boolean, angle: Int, crown: Int) {
        require(angle in 0..90 && crown in 0..3)
        val data = JSONObject().put("headup_switch", if (enabled) 1 else 0).put("headup_degree", angle).put("crown_switch", crown)
        apply("wakeup_config", 5, 6, payload(data = data.toString()), "wakeupConfig") { s -> s.settings.optJSONObject("wakeupConfig")?.let {
            it.deviceFlag("headupSwitch") == enabled && it.deviceInt("headupDegree") == angle && it.deviceInt("crownSwitch") == crown } == true }
    }
    fun headGestures(enabled: Boolean, mode: Int) {
        require(mode in 0..1)
        apply("head_gestures", 5, 6, payload(if (enabled) 1 else 0, mode), "headGestures") { s -> s.settings.optJSONObject("headGestures")?.let {
            it.deviceFlag("enabled") == enabled && it.deviceInt("mode") == mode } == true }
    }
    fun crown(direction: Int, double: Int, longPress: Int) {
        require(direction in 0..1 && double in 0..9 && longPress in 0..9 && longPress != 6)
        apply("crown_config", 5, 6, payload(value = direction, data = JSONObject().put("double", double).put("longPress", longPress).toString()), "crownConfig") { s ->
            s.settings.optJSONObject("crownConfig")?.let { it.deviceInt("direction") == direction && it.deviceInt("double") == double && it.deviceInt("longPress") == longPress } == true }
    }
    fun language(value: String) {
        require(state.settings.optJSONArray("supportedLanguages")?.let { a -> (0 until a.length()).any { a.getString(it) == value } } == true)
        apply("language_change", 5, 6, payload(data = JSONObject().put("currentLang", value).toString()), "currentLang") { it.settings.optString("currentLang") == value }
    }
    fun privacy(values: Map<String, Boolean>) {
        val data = JSONObject(checkNotNull(state.settings.optJSONObject("privacyConfig")).toString())
        values.forEach { (key, value) -> require(key in setOf("mic_switch", "led_light", "led_auto_light", "log_switch")); data.put(key, if (value) 1 else 0) }
        apply("privacy_config", 5, 6, payload(data = data.toString()), "privacyConfig") { s -> values.all { (key, value) -> s.settings.optJSONObject("privacyConfig")?.deviceFlag(key) == value } }
    }
    /** Explicit device-side AI switch, independent of the phone's response setting.
     * Require a fresh reported value; never treat an ACK or cached merged value as success. */
    fun assistantEnabled(enabled: Boolean) {
        check(state.loaded)
        perform {
            val before = readSettingsForVoice()?.optJSONObject("voiceAssistantConfig")?.deviceFlag("enabled")
                ?: error("안경이 AI 활성화 상태를 보고하지 않았습니다. 변경하지 않았습니다.")
            if (before == enabled) return@perform "안경 AI가 이미 " + if (enabled) "켜져 있습니다." else "꺼져 있습니다."
            try {
                requests.request(15, 16, JSONObject().put("cmd", "set_ai_state")
                    .put("payload", payload(mode = if (enabled) 1 else 0))) { type, body ->
                    type == 17 && body.optString("cmd") in setOf("set_ai_state", "sync_ai_settings")
                }
            } catch (_: IllegalStateException) { /* A fresh full read is the confirmation. */ }
            val actual = readSettingsForVoice()?.optJSONObject("voiceAssistantConfig")?.deviceFlag("enabled")
            when (actual) {
                enabled -> "안경 AI 활성화 설정 재조회 일치 · 실제 호출은 별도 확인하세요."
                null -> "안경 AI 변경 후 새 상태를 확인하지 못했습니다."
                else -> "안경 AI 재조회 값이 요청과 다릅니다."
            }
        }
    }
    fun voiceWake(enabled: Boolean) = apply("set_ai_voice_wakeup", 16, 17, payload(mode = if (enabled) 1 else 0), "voiceAssistantConfig") {
        it.settings.optJSONObject("voiceAssistantConfig")?.deviceFlag("voiceWakeup") == enabled
    }
    /** Firmware barge-in ("对话打断"): while the assistant answers, speaking cuts it off and starts a new turn. */
    fun interrupt(enabled: Boolean) = apply("set_ai_work_mode", 16, 17, payload(mode = if (enabled) 1 else 0), "voiceAssistantConfig") {
        it.settings.optJSONObject("voiceAssistantConfig")?.deviceFlag("workMode") == enabled
    }
    /** Firmware wake word: 1 = 小雷小雷 (Chinese), 2 = English wake word. */
    fun wakeWord(word: Int) {
        require(word in 1..2)
        apply("set_ai_wakeup_word", 16, 17, payload(mode = word), "voiceAssistantConfig") {
            it.settings.optJSONObject("voiceAssistantConfig")?.deviceInt("wakeupWord") == word
        }
    }
    fun units(time: Int, temperature: Int, measurement: Int) {
        require(listOf(time, temperature, measurement).all { it in 1..2 })
        val data = JSONObject(checkNotNull(state.settings.optJSONObject("systemUnitsConfig")).toString())
            .put("time_format", time).put("temp_unit", temperature).put("measurement", measurement)
        apply("system_unints_config", 5, 6, payload(data = data.toString()), "systemUnitsConfig") { s -> s.settings.optJSONObject("systemUnitsConfig")?.let {
            it.deviceInt("time_format") == time && it.deviceInt("temp_unit") == temperature && it.deviceInt("measurement") == measurement } == true }
    }
    private fun apply(command: String, type: Int, reply: Int, payload: JSONObject, field: String, matches: (DeviceSettingsState) -> Boolean) {
        check(state.loaded)
        perform { applyBlocking(command, type, reply, payload, field, matches) }
    }
    private fun applyBlocking(command: String, type: Int, reply: Int, payload: JSONObject, field: String,
                              matches: (DeviceSettingsState) -> Boolean): String {
        val before = System.nanoTime()
        var acknowledgementMissing = false
        try { requests.request(15, type, JSONObject().put("cmd", command).put("payload", payload)) { t, body -> t == reply &&
            (body.optString("cmd") == command || type == 16 && body.optString("cmd") == "sync_ai_settings") } }
        catch (error: IllegalStateException) { acknowledgementMissing = true }
        readAll()
        return if ((reported[field] ?: 0) < before) "전송됨, 기기가 아직 새 값을 보고하지 않음"
            else if (matches(state)) "안경 재조회로 확인됨" else if (acknowledgementMissing) "변경 응답을 받지 못했고 현재 재조회 값이 요청과 다름" else "기기 재조회 값이 요청과 다릅니다. 현재 값을 확인하세요"
    }
    /** Read from the current request's reply, not cached state or an acknowledgement echo. */
    private fun readStatusIntForVoice(key: String, range: IntRange): Int? {
        val reply = requests.request(15, 1, envelope("request_general_status")) { type, body ->
            type == 1 && body.has("generalStatus")
        }
        val value = reply?.optJSONObject("generalStatus")?.opt(key) as? Number ?: return null
        val number = value.toDouble()
        return number.toInt().takeIf { number == it.toDouble() && it in range }
    }
    fun readBatteryForVoice(): Int? = readStatusIntForVoice("battery", 0..100)

    private fun readSettingsForVoice(): JSONObject? =
        requests.request(15, 4, envelope("request_general_settings")) { type, body ->
            type == 4 && body.optJSONObject("generalSettings")?.length()?.let { it > 0 } == true
        }?.optJSONObject("generalSettings")
    private fun exactInt(value: Any?): Int? {
        val number = (value as? Number)?.toDouble() ?: return null
        return number.toInt().takeIf { number == it.toDouble() }
    }
    private fun executeSettingVoice(command: LocalDeviceQuery.Command): String {
        val kind = command.kind
        val height = kind in setOf(LocalDeviceQuery.Kind.DISPLAY_HEIGHT_READ, LocalDeviceQuery.Kind.DISPLAY_HEIGHT_SET)
        val distance = kind in setOf(LocalDeviceQuery.Kind.DISPLAY_DISTANCE_READ, LocalDeviceQuery.Kind.DISPLAY_DISTANCE_SET)
        val writing = kind in setOf(LocalDeviceQuery.Kind.DISPLAY_HEIGHT_SET, LocalDeviceQuery.Kind.DISPLAY_DISTANCE_SET, LocalDeviceQuery.Kind.AUTO_LOCK_SET)
        val label = if (height) "화면 높이" else if (distance) "화면 거리" else "자동 절전 시간"
        val unit = if (height || distance) "단계" else "초"
        if (writing && !LocalDeviceQuery.validSetting(kind, command.level)) return when {
            height -> "안경 화면 높이는 1~6단계로 지정해 주세요."
            distance -> "안경 화면 거리는 1~3단계로 지정해 주세요."
            else -> "자동 절전 시간은 5, 10, 15, 25, 40, 60, 120초 중에서 지정해 주세요."
        }
        val before = readSettingsForVoice() ?: return "$label 설정을 확인하지 못했습니다."
        val display = before.optJSONObject("displayConfig")
        val oldHeight = exactInt(display?.opt("height"))
        val oldDistance = exactInt(display?.opt("distance"))
        val current = if (height) oldHeight else if (distance) oldDistance else exactInt(before.opt("autoLockTime"))
        if (!LocalDeviceQuery.validSetting(kind, current)) return "$label 설정을 확인하지 못했습니다."
        if (!writing) return "현재 $label: $current$unit"
        val target = checkNotNull(command.level)
        if (target == current) return "이미 $label $current${unit}입니다."
        val newHeight = if (height) target else oldHeight
        val newDistance = if (distance) target else oldDistance
        if ((height || distance) && (newHeight !in 1..6 || newDistance !in 1..3)) return "화면의 다른 설정을 확인하지 못해 변경하지 않았습니다."
        val wire = if (height || distance) "display_config" else "auto_lock_time"
        val data = if (height || distance) payload(data = JSONObject().put("height", newHeight).put("distance", newDistance).toString()) else payload(target)
        try {
            requests.request(15, 5, JSONObject().put("cmd", wire).put("payload", data)) { type, body ->
                type == 6 && body.optString("cmd") == wire
            }
        } catch (_: IllegalStateException) { /* Confirm only by fresh readback, never an ACK. */ }
        val after = readSettingsForVoice() ?: return "$label 변경 결과를 확인하지 못했습니다."
        val actualDisplay = after.optJSONObject("displayConfig")
        val actualHeight = exactInt(actualDisplay?.opt("height"))
        val actualDistance = exactInt(actualDisplay?.opt("distance"))
        val actual = if (height) actualHeight else if (distance) actualDistance else exactInt(after.opt("autoLockTime"))
        if (!LocalDeviceQuery.validSetting(kind, actual)) return "$label 변경 결과를 확인하지 못했습니다."
        val matches = if (height || distance) actualHeight == newHeight && actualDistance == newDistance else actual == target
        return if (matches) "$label ${target}${unit}로 설정했습니다." else "요청한 설정과 재조회 값이 다릅니다. 현재 $label: $actual$unit"
    }

    /** Run on the existing serialized device queue with a current-wake permission. */
    fun executeVoiceCommand(command: LocalDeviceQuery.Command): String {
        if (command.kind == LocalDeviceQuery.Kind.BATTERY)
            return LocalDeviceQuery.batteryAnswer(readBatteryForVoice())
        if (command.kind == LocalDeviceQuery.Kind.AUTO_BRIGHTNESS_READ) {
            val reply = requests.request(15, 1, envelope("request_general_status")) { type, body -> type == 1 && body.has("generalStatus") }
            val raw = reply?.optJSONObject("generalStatus")?.opt("automaticBrightness")
            val enabled = if (raw is Boolean) raw else exactInt(raw)?.let { if (it in 0..1) it == 1 else null }
            return when (enabled) {
                true -> "안경 화면 자동 밝기가 켜져 있습니다."
                false -> "안경 화면 자동 밝기가 꺼져 있습니다."
                null -> "안경 화면 자동 밝기 상태를 확인하지 못했습니다."
            }
        }
        if (command.kind in setOf(LocalDeviceQuery.Kind.DISPLAY_HEIGHT_READ, LocalDeviceQuery.Kind.DISPLAY_HEIGHT_SET,
                LocalDeviceQuery.Kind.DISPLAY_DISTANCE_READ, LocalDeviceQuery.Kind.DISPLAY_DISTANCE_SET,
                LocalDeviceQuery.Kind.AUTO_LOCK_READ, LocalDeviceQuery.Kind.AUTO_LOCK_SET)) return executeSettingVoice(command)
        if (command.kind == LocalDeviceQuery.Kind.BRIGHTNESS_SET && !LocalDeviceQuery.validLevel(command.level))
            return "안경 밝기는 1~17단계로 지정해 주세요."
        val current = readStatusIntForVoice("brightness", 1..17)
            ?: return LocalDeviceQuery.brightnessAnswer(null)
        if (command.kind == LocalDeviceQuery.Kind.BRIGHTNESS_READ)
            return LocalDeviceQuery.brightnessAnswer(current)
        val target = LocalDeviceQuery.target(command, current)
            ?: return LocalDeviceQuery.brightnessAnswer(null)
        if (current == target) return "이미 밝기 ${current}단계입니다."
        try {
            requests.request(15, 2, JSONObject().put("cmd", "brightness_change").put("payload", payload(target))) { type, body ->
                type == 3 && body.optString("cmd") == "brightness_change"
            }
        } catch (_: IllegalStateException) {
            // A lost ACK does not establish failure or success. Read the actual device value.
            // The request queue rechecks the wake permission before any additional wire command.
        }
        val actual = readStatusIntForVoice("brightness", 1..17)
        return when {
            actual == target -> "안경 밝기를 ${target}단계로 설정했습니다."
            actual == null -> "밝기 변경 결과를 확인하지 못했습니다."
            else -> "요청한 밝기와 다릅니다. 현재 ${actual}단계입니다."
        }
    }
    fun readForTool(): JSONObject { readAll(); return JSONObject().put("status", state.status).put("settings", state.settings) }
    fun tool(name: String, args: JSONObject, completion: (Result<JSONObject>) -> Unit) {
        requests.perform({
            readAll()
            val message = when (name) {
                "device_brightness" -> {
                    val value = args.getInt("value"); require(value in 1..17)
                    applyBlocking("brightness_change", 2, 3, payload(value), "brightness") { it.status.deviceInt("brightness") == value }
                }
                "device_display" -> {
                    val height = args.getInt("height"); val distance = args.getInt("distance"); require(height in 1..6 && distance in 1..3)
                    applyBlocking("display_config", 5, 6, payload(data = JSONObject().put("height", height).put("distance", distance).toString()), "displayConfig") {
                        it.settings.optJSONObject("displayConfig")?.let { v -> v.deviceInt("height") == height && v.deviceInt("distance") == distance } == true }
                }
                else -> error("알 수 없는 기기 도구: $name")
            }
            JSONObject().put("message", message).put("status", state.status).put("settings", state.settings)
        }, completion)
    }
    private fun perform(work: () -> String) {
        check(!state.busy) { "현재 기기 작업을 기다리세요" }
        val token = ++revision
        state = state.copy(busy = true, notice = "안경과 동기화 중…"); changed()
        requests.perform(work) { result ->
            if (token == revision) {
                state = state.copy(busy = false, notice = result.getOrElse { it.message ?: "기기 작업 실패" }); changed()
            }
        }
    }
    fun observe(type: Int, body: JSONObject) {
        val now = System.nanoTime()
        body.optJSONObject("generalStatus")?.let { data -> data.keys().forEach { reported[it] = now }; state = state.copy(status = merge(state.status, data)) }
        body.optJSONObject("generalSettings")?.let { data -> data.keys().forEach { reported[it] = now }; state = state.copy(settings = merge(state.settings, data), loaded = true) }
        val command = body.optString("cmd"); val payload = body.optJSONObject("payload")
        if (payload != null && command in setOf("sync_ai_settings", "system_unints_config", "system_units_config") && type in setOf(6,17)) {
            val raw = payload.opt("data")
            val data = if (raw is JSONObject) raw else (raw as? String)?.takeIf { it.isNotBlank() }?.let(::JSONObject)
            if (data != null) {
                if (command == "sync_ai_settings") {
                    val normalized = JSONObject()
                    listOf("ai_state" to "enabled", "ai_voice_wakeup" to "voiceWakeup", "ai_work_mode" to "workMode", "ai_wakeup_word" to "wakeupWord").forEach { (wire, key) -> if (data.has(wire)) normalized.put(key, data.opt(wire)) }
                    state = state.copy(settings = merge(state.settings, JSONObject().put("voiceAssistantConfig", normalized)))
                    reported["voiceAssistantConfig"] = now
                } else {
                    state = state.copy(settings = merge(state.settings, JSONObject().put("systemUnitsConfig", data)))
                    // A same-command payload may be an ACK echo; only full settings reads confirm unit writes.
                }
            }
        }
        changed()
    }
    private fun merge(previous: JSONObject, next: JSONObject): JSONObject = JSONObject(previous.toString()).apply {
        next.keys().forEach { key -> val old = optJSONObject(key); val value = next.opt(key)
            put(key, if (old != null && value is JSONObject) merge(old, value) else value) }
    }
    private fun payload(value: Int = 0, mode: Int = 0, data: String = "") = JSONObject().put("value", value).put("mode", mode).put("data", data)
    private fun envelope(command: String) = JSONObject().put("cmd", command).put("payload", payload())
}
