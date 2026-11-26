// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Manual, reversible isolation of the competing vendor app. No ADB helper,
 * permission revocation, account removal or fabricated process-running status. */
@Composable
internal fun OfficialAppConnectionControls() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val entries = remember(context, refresh) {
        listOf("com.rayneo.venus.pub", "com.rayneo.venus").mapNotNull { pkg ->
            runCatching {
                context.packageManager.getApplicationInfo(pkg, 0)
                pkg to (context.packageManager.checkPermission(Manifest.permission.BLUETOOTH_CONNECT, pkg) == PackageManager.PERMISSION_GRANTED)
            }.getOrNull()
        }
    }
    if (entries.isEmpty()) {
        Note("공식 RayNeo 앱: 설치 확인되지 않음")
    } else {
        Note("공식 앱 연결 경쟁 확인: 근처 기기 권한이 남아 있으면 앱이 다시 연결할 수 있습니다. 권한 허용만으로 현재 연결 중임을 뜻하지는 않습니다.")
        entries.forEach { (pkg, permitted) ->
            Note((if (pkg.endsWith(".pub")) "공식 글로벌 앱" else "공식 중국 앱") + " · 블루투스 연결 권한: " + if (permitted) "허용" else "미허용")
            ToolOutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            }) { Text("공식 앱 설정 열기") }
        }
        Note("독립 시험 시 공식 앱 설정에서 근처 기기 권한을 허용하지 않음으로 바꾸고 강제 종료한 뒤 OpenRayneo에서 다시 연결하세요. 계정 연결 해제·데이터 삭제는 하지 마세요. 공식 앱을 다시 쓸 때 권한을 복원할 수 있습니다.")
        ToolOutlinedButton(onClick = { refresh++ }) { Text("공식 앱 권한 다시 확인") }
    }
}
