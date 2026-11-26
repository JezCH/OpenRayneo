// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

internal enum class MediaCaptionScope { YOUTUBE, ALL_MEDIA }
internal data class MediaCaptionOptions(val scope: MediaCaptionScope = MediaCaptionScope.YOUTUBE, val language: String = "en") {
    init { require(language in setOf("en", "ja", "zh", "ko")) }
}
internal enum class MediaCapturePhase { IDLE, STARTING, CAPTURING, STOPPING, ERROR }
internal data class MediaCaptionState(
    val phase: MediaCapturePhase = MediaCapturePhase.IDLE,
    val id: String? = null,
    val options: MediaCaptionOptions = MediaCaptionOptions(),
    val detail: String = "미디어 자막 꺼짐",
    val samples: Long = 0,
    val dropped: Long = 0,
    val rms: Double = 0.0,
    val firstPcmMs: Long? = null,
    val heardNonzero: Boolean = false,
) {
    val busy get() = phase in setOf(MediaCapturePhase.STARTING, MediaCapturePhase.CAPTURING, MediaCapturePhase.STOPPING)
}
