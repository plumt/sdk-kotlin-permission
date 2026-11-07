package net.plumt.permission.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import net.plumt.permission.PermissionManager
import net.plumt.permission.PermissionRequester
import net.plumt.permission.PermissionSpecs
import net.plumt.permission.SettingsPolicy
import net.plumt.permission.SystemRequirements
import net.plumt.permission.registerPermissionRequester

/** 확인용 화면 — 카메라·오래 걸리는 작업·자동 회수 예외와 블루투스·인터넷·생체 인증 상태를 한 흐름으로 요청한다 */
class SampleActivity : ComponentActivity() {
    private lateinit var requester: PermissionRequester

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = PermissionManager.create(this)
        requester = registerPermissionRequester(
            permissions,
            listOf(PermissionSpecs.camera, PermissionSpecs.userInitiatedJobs, PermissionSpecs.autoRevokeExempt),
            listOf(SystemRequirements.bluetoothEnabled, SystemRequirements.internetAvailable, SystemRequirements.biometricEnrolled()),
        ) { status -> title = if (status.isReady) "ready" else "not ready" }
        requester.launch(SettingsPolicy.DIRECT)
    }
}
