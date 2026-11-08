@file:SuppressLint("InlinedApi", "MissingPermission")
// 권한·화면 이름 상수는 문자열이라 낮은 API 에서도 안전하다.
// 어댑터 상태 확인에 필요한 권한은 관리자가 permissions()/declaredPermissions() 로 먼저 확인하고, 빠지면 SecurityException 을 상태로 바꾼다

package net.plumt.permission.internal

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.location.LocationManagerCompat
import net.plumt.permission.EnableRequest
import net.plumt.permission.PermissionEnvironment
import net.plumt.permission.RequirementStatus
import net.plumt.permission.SystemRequirement
import net.plumt.permission.UnsupportedReason

private val NO_HARDWARE = RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE)

private fun Context.has(feature: String): Boolean = packageManager.hasSystemFeature(feature)

private fun intentRequest(intent: Intent, onReady: (EnableRequest?) -> Unit) = onReady(EnableRequest.OfIntent(intent))

/**
 * 블루투스 어댑터 켜짐. 꺼져 있으면 `ACTION_REQUEST_ENABLE` 대화상자로 켠다.
 * API 31 이상(targetSdk 31 이상)은 그 대화상자에 `BLUETOOTH_CONNECT` 가 필요하고, 그 아래는 설치 시 부여되는 `BLUETOOTH` 를 선언한다.
 */
internal object BluetoothEnabledRequirement : SystemRequirement {
    override val id: String = "bluetooth_enabled"

    override fun permissions(environment: PermissionEnvironment): List<String> =
        if (environment.effectiveSdk >= Api.S) listOf(BluetoothSpec.CONNECT) else emptyList()

    override fun declaredPermissions(environment: PermissionEnvironment): List<String> =
        if (environment.effectiveSdk >= Api.S) listOf(BluetoothSpec.CONNECT) else listOf(LEGACY)

    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        if (!context.has(PackageManager.FEATURE_BLUETOOTH)) return NO_HARDWARE
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter ?: return NO_HARDWARE
        return try {
            if (adapter.isEnabled) RequirementStatus.Satisfied else RequirementStatus.Disabled
        } catch (e: SecurityException) {
            // API 30 이하에서 BLUETOOTH 를 선언하지 않았다
            RequirementStatus.PermissionRequired(setOf(LEGACY))
        }
    }

    override fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit) =
        intentRequest(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), onReady)

    override fun toString(): String = "BluetoothEnabledRequirement"

    const val LEGACY = Manifest.permission.BLUETOOTH
}

/**
 * 위치 서비스 켜짐(`LocationManager.isLocationEnabled`, API 28 미만은 위치 모드). 꺼져 있으면 위치 설정 화면을 연다 —
 * Play 서비스에 의존하지 않는다. 앱 안 대화상자(`SettingsClient`)가 필요하면 [SystemRequirement] 를 직접 구현한다.
 */
internal object LocationEnabledRequirement : SystemRequirement {
    override val id: String = "location_enabled"

    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        if (!context.has(PackageManager.FEATURE_LOCATION)) return NO_HARDWARE
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager? ?: return NO_HARDWARE
        return if (LocationManagerCompat.isLocationEnabled(manager)) RequirementStatus.Satisfied else RequirementStatus.Disabled
    }

    override fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit) =
        intentRequest(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), onReady)

    override fun toString(): String = "LocationEnabledRequirement"
}

/** NFC 켜짐. 꺼져 있으면 API 29 이상은 NFC 패널, 그 아래는 NFC 설정 화면을 연다 */
internal object NfcEnabledRequirement : SystemRequirement {
    override val id: String = "nfc_enabled"

    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return NO_HARDWARE
        return if (adapter.isEnabled) RequirementStatus.Satisfied else RequirementStatus.Disabled
    }

    override fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit) = intentRequest(
        if (Build.VERSION.SDK_INT >= Api.Q) Intent(Settings.Panel.ACTION_NFC) else Intent(Settings.ACTION_NFC_SETTINGS),
        onReady,
    )

    override fun toString(): String = "NfcEnabledRequirement"
}

/**
 * Wi-Fi 켜짐. 상태를 읽으려면 설치 시 부여되는 `ACCESS_WIFI_STATE` 를 선언한다. 앱이 Wi-Fi 를 직접 켜는 함수는 API 29 에서
 * 막혔으므로 API 29 이상은 Wi-Fi 패널, 그 아래는 Wi-Fi 설정 화면을 연다.
 */
internal object WifiEnabledRequirement : SystemRequirement {
    override val id: String = "wifi_enabled"

    override fun declaredPermissions(environment: PermissionEnvironment): List<String> = listOf(ACCESS_WIFI_STATE)

    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        if (!context.has(PackageManager.FEATURE_WIFI)) return NO_HARDWARE
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager? ?: return NO_HARDWARE
        return try {
            if (manager.isWifiEnabled) RequirementStatus.Satisfied else RequirementStatus.Disabled
        } catch (e: SecurityException) {
            RequirementStatus.PermissionRequired(setOf(ACCESS_WIFI_STATE))
        }
    }

    override fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit) = intentRequest(
        if (Build.VERSION.SDK_INT >= Api.Q) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS),
        onReady,
    )

    override fun toString(): String = "WifiEnabledRequirement"

    const val ACCESS_WIFI_STATE = Manifest.permission.ACCESS_WIFI_STATE
}

/**
 * 이 앱의 알림 켜짐(`areNotificationsEnabled`). API 33 미만에서도 사용자가 설정에서 끌 수 있다.
 * API 33 이상(targetSdk 33 이상)은 `POST_NOTIFICATIONS` 가 먼저 필요하다. 꺼져 있으면 앱 알림 설정 화면을 연다.
 */
internal object NotificationsEnabledRequirement : SystemRequirement {
    override val id: String = "notifications_enabled"

    override fun permissions(environment: PermissionEnvironment): List<String> =
        if (environment.effectiveSdk >= Api.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus =
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            RequirementStatus.Satisfied
        } else {
            RequirementStatus.Disabled
        }

    override fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit) =
        intentRequest(notificationSettingsIntent(context, channelId = null), onReady)

    override fun toString(): String = "NotificationsEnabledRequirement"
}

/**
 * 이 앱의 알림 채널 [channelId] 가 켜져 있다 — 앱 알림이 켜져 있고(`areNotificationsEnabled`), 채널 중요도가 `IMPORTANCE_NONE` 이 아니고,
 * 채널이 든 그룹이 막히지 않았다(API 28+). API 26 미만은 채널이 없어 앱 알림 켜짐과 같다.
 * 앱이 아직 채널을 만들지 않았으면 해결 화면이 없어 Unmet 이다(채널은 앱 코드가 만든다).
 * 꺼져 있으면 앱 알림이 꺼졌거나 그룹이 막혔을 때 앱 알림 설정 화면을, 채널만 꺼졌을 때 그 채널 설정 화면을 연다.
 * API 33 이상(targetSdk 33 이상)은 `POST_NOTIFICATIONS` 가 먼저 필요하다.
 */
internal data class NotificationChannelRequirement(private val channelId: String) : SystemRequirement {
    override val id: String = "notification_channel_enabled:$channelId"

    override fun permissions(environment: PermissionEnvironment): List<String> =
        NotificationsEnabledRequirement.permissions(environment)

    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return RequirementStatus.Disabled
        if (Build.VERSION.SDK_INT < Api.O) return RequirementStatus.Satisfied
        val channel = manager.getNotificationChannelCompat(channelId) ?: return RequirementStatus.Unmet
        if (channel.importance == NotificationManagerCompat.IMPORTANCE_NONE) return RequirementStatus.Disabled
        val group = channel.group ?: return RequirementStatus.Satisfied
        val blocked = Build.VERSION.SDK_INT >= Api.P && manager.getNotificationChannelGroupCompat(group)?.isBlocked == true
        return if (blocked) RequirementStatus.Disabled else RequirementStatus.Satisfied
    }

    override fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit) {
        val manager = NotificationManagerCompat.from(context)
        val channelOnly = manager.areNotificationsEnabled() && !isGroupBlocked(manager)
        intentRequest(notificationSettingsIntent(context, channelId.takeIf { channelOnly }), onReady)
    }

    private fun isGroupBlocked(manager: NotificationManagerCompat): Boolean {
        if (Build.VERSION.SDK_INT < Api.P) return false
        val group = manager.getNotificationChannelCompat(channelId)?.group ?: return false
        return manager.getNotificationChannelGroupCompat(group)?.isBlocked == true
    }
}

/** 하드웨어 기능이 있는지(`hasSystemFeature`). 켜기 요청은 없다 */
internal data class HardwareRequirement(private val feature: String, private val minSdk: Int) : SystemRequirement {
    override val id: String = "hardware:$feature"

    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus = when {
        environment.sdkInt < minSdk -> RequirementStatus.Unsupported(UnsupportedReason.API_LEVEL)
        context.has(feature) -> RequirementStatus.Satisfied
        else -> NO_HARDWARE
    }

    override fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit) = onReady(null)
}

/**
 * 앱(또는 채널) 알림 설정 화면. API 26 이상은 `ACTION_APP_NOTIFICATION_SETTINGS`·`ACTION_CHANNEL_NOTIFICATION_SETTINGS`,
 * 그 아래는 알림 설정 화면이 공개 API 가 아니라 앱 상세 설정 화면이다(채널도 없다).
 */
internal fun notificationSettingsIntent(context: Context, channelId: String?): Intent = when {
    Build.VERSION.SDK_INT < Api.O ->
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    channelId != null -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
    else -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
