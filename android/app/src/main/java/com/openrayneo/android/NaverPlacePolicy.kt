// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Naver's URL scheme requires verified destination coordinates, not an inferred POI. */
internal object NaverPlacePolicy {
    data class Place(val name: String, val address: String, val latitude: Double, val longitude: Double)
    private fun canonical(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
        .filterNot(Char::isWhitespace).lowercase(java.util.Locale.ROOT)
    fun unique(total: Int, query: String, name: String, road: String, parcel: String,
               mapx: String, mapy: String): Place? {
        if (total != 1 || name.isBlank() || (road.isBlank() && parcel.isBlank())) return null
        val q = canonical(query)
        if (q.isEmpty() || q !in listOf(name, road, parcel).filter { it.isNotBlank() }.map(::canonical)) return null
        val x = mapx.toDoubleOrNull() ?: return null
        val y = mapy.toDoubleOrNull() ?: return null
        if (!x.isFinite() || !y.isFinite()) return null
        val decimal = x in 122.37..132.0 && y in 31.43..44.35
        // The local-search API has also historically returned WGS84 multiplied by 10^7.
        val scaled = mapx.matches(Regex("[0-9]{10}")) && mapy.matches(Regex("[0-9]{9}")) &&
            x / 1e7 in 122.37..132.0 && y / 1e7 in 31.43..44.35
        if (!decimal && !scaled) return null
        return Place(name, road.ifBlank { parcel }, if (scaled) y / 1e7 else y, if (scaled) x / 1e7 else x)
    }
    fun path(mode: NaverVoiceCommand.Mode): String? = when (mode) {
        NaverVoiceCommand.Mode.CAR -> "navigation"
        NaverVoiceCommand.Mode.WALK -> "route/walk"
        NaverVoiceCommand.Mode.PUBLIC -> "route/public"
        NaverVoiceCommand.Mode.UNSPECIFIED -> null
    }
}
