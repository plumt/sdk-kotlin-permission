package net.plumt.permission.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import net.plumt.permission.PermissionManager
import net.plumt.permission.PermissionSpecs
import net.plumt.permission.RequestHandle
import net.plumt.permission.SettingsPolicy
import net.plumt.permission.SystemRequirements

/** 확인용 화면 — 카메라·오래 걸리는 작업·자동 회수 예외와 블루투스·인터넷·생체 인증 상태를 콜백 요청 한 번으로 요청한다 */
class SampleActivity : ComponentActivity() {
    private var request: RequestHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = PermissionManager.create(this)
        request = permissions.request(
            activity = this,
            specs = listOf(PermissionSpecs.camera, PermissionSpecs.userInitiatedJobs, PermissionSpecs.autoRevokeExempt),
            requirements = listOf(SystemRequirements.bluetoothEnabled, SystemRequirements.internetAvailable, SystemRequirements.biometricEnrolled()),
            settingsPolicy = SettingsPolicy.Direct,
        ) { result -> title = if (result.status.isReady) "ready" else "not ready" }
    }

    override fun onDestroy() {
        if (isFinishing) request?.cancel()
        super.onDestroy()
    }
}
