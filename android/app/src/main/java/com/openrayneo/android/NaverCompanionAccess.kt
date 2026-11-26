// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.bluetooth.BluetoothAdapter
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.Context

/** Optional, user-approved association with the actual authenticated RayNeo device.
 * No overlay, wake-lock, lock-screen dismissal, accessibility, or Bluetooth rebond. */
internal object NaverCompanionAccess {
    private fun prefs(context: Context) = context.getSharedPreferences("naver_companion_launch", Context.MODE_PRIVATE)
    fun matching(context: Context, address: String?): List<AssociationInfo> {
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address.uppercase(java.util.Locale.ROOT))) return emptyList()
        return runCatching {
            context.getSystemService(CompanionDeviceManager::class.java)?.myAssociations.orEmpty()
                .filter { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
        }.getOrDefault(emptyList())
    }
    fun allowed(context: Context, address: String?): Boolean {
        val id = prefs(context).getInt("id", -1)
        return id >= 0 && matching(context, address).any { it.id == id }
    }
    fun enable(context: Context, address: String, info: AssociationInfo, createdHere: Boolean) {
        require(matching(context, address).any { it.id == info.id }) { "현재 안경의 부속 기기 등록을 확인하지 못했습니다." }
        val prefs = prefs(context)
        val previousId = prefs.getInt("id", -1)
        val previousCreated = prefs.getBoolean("createdHere", false)
        if (previousCreated && previousId >= 0 && previousId != info.id) {
            context.getSystemService(CompanionDeviceManager::class.java)?.let { manager ->
                if (manager.myAssociations.any { it.id == previousId }) manager.disassociate(previousId)
            }
        }
        check(prefs.edit().putInt("id", info.id)
            .putBoolean("createdHere", createdHere || (previousId == info.id && previousCreated)).commit())
    }
    fun disable(context: Context) {
        val prefs = prefs(context)
        val id = prefs.getInt("id", -1)
        val created = prefs.getBoolean("createdHere", false)
        check(prefs.edit().clear().commit())
        if (created && id >= 0) {
            context.getSystemService(CompanionDeviceManager::class.java)?.let { manager ->
                if (manager.myAssociations.any { it.id == id }) manager.disassociate(id)
            }
        }
    }
}
