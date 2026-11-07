@file:SuppressLint("InlinedApi", "NewApi", "MissingPermission")
// 화면 이름·상수는 문자열·정수라 낮은 API 에서도 안전하다 — 플랫폼 함수는 Build.VERSION.SDK_INT 로 가른 뒤 부른다.
// 상태 확인에 필요한 설치 시 권한은 declaredPermissions() 로 먼저 확인하고, 빠지면 SecurityException 을 상태로 바꾼다

package net.plumt.permission.internal

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import net.plumt.permission.EnableRequest
import net.plumt.permission.PermissionEnvironment
import net.plumt.permission.RequirementStatus
import net.plumt.permission.SystemRequirement
import net.plumt.permission.UnsupportedReason

private val NO_HARDWARE = RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE)
private val API_LEVEL = RequirementStatus.Unsupported(UnsupportedReason.API_LEVEL)

private fun satisfiedIf(condition: Boolean): RequirementStatus =
    if (condition) RequirementStatus.Satisfied else RequirementStatus.Disabled

private fun open(intent: Intent, onReady: (EnableRequest?) -> Unit) = onReady(EnableRequest.OfIntent(intent))

/** 해결 화면이 고정된 요구 조건의 뼈대 — 상태만 구현한다 */
internal abstract class SettingsRequirement(override val id: String, private val name: String) : SystemRequirement {
    abstract fun intent(context: Context): Intent

    override fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit) = open(intent(context), onReady)

    override fun toString(): String = name
}

/** 비행기 모드가 꺼져 있다. 켜져 있으면 비행기 모드 설정 화면을 연다 */
internal object AirplaneModeOffRequirement : SettingsRequirement("airplane_mode_off", "AirplaneModeOffRequirement") {
    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus =
        satisfiedIf(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 0)

    override fun intent(context: Context): Intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
}

/**
 * 기본 네트워크로 인터넷에 닿는다(`NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_VALIDATED`). 상태를 읽으려면 `ACCESS_NETWORK_STATE` 를 선언한다.
 * 끊겨 있으면 API 29 이상은 인터넷 연결 패널, 그 아래는 무선 설정 화면을 연다.
 */
internal object InternetRequirement : SettingsRequirement("internet_available", "InternetRequirement") {
    override fun declaredPermissions(environment: PermissionEnvironment): List<String> = listOf(NETWORK_STATE)

    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager? ?: return NO_HARDWARE
        return try {
            val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
            satisfiedIf(
                capabilities != null &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        } catch (e: SecurityException) {
            RequirementStatus.PermissionRequired(setOf(NETWORK_STATE))
        }
    }

    override fun intent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Api.Q) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIRELESS_SETTINGS)

    const val NETWORK_STATE = Manifest.permission.ACCESS_NETWORK_STATE
}

/**
 * 데이터 절약 모드가 이 앱의 백그라운드 데이터를 막지 않는다(`getRestrictBackgroundStatus`, API 24+). 막고 있으면 이 앱의
 * "무제한 데이터 사용" 설정 화면을 연다. API 24 미만은 데이터 절약 모드가 없어 Satisfied.
 */
internal object DataSaverRequirement : SettingsRequirement("data_saver_off", "DataSaverRequirement") {
    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        if (Build.VERSION.SDK_INT < Api.N) return RequirementStatus.Satisfied
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager? ?: return RequirementStatus.Satisfied
        return satisfiedIf(manager.restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
    }

    override fun intent(context: Context): Intent =
        Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, Uri.parse("package:${context.packageName}"))
}

/** 절전 모드가 꺼져 있다(`isPowerSaveMode`). 켜져 있으면 절전 모드 설정 화면을 연다 */
internal object PowerSaveOffRequirement : SettingsRequirement("power_save_off", "PowerSaveOffRequirement") {
    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager? ?: return RequirementStatus.Satisfied
        return satisfiedIf(!power.isPowerSaveMode)
    }

    override fun intent(context: Context): Intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
}

/**
 * 사용자가 이 앱의 백그라운드 활동을 제한하지 않았다(`ActivityManager.isBackgroundRestricted`, API 28+).
 * 제한돼 있으면 앱 상세 설정 화면(배터리 항목)을 연다. API 28 미만은 이 제한이 없어 Satisfied.
 */
internal object BackgroundUnrestrictedRequirement : SettingsRequirement("background_unrestricted", "BackgroundUnrestrictedRequirement") {
    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        if (Build.VERSION.SDK_INT < Api.P) return RequirementStatus.Satisfied
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager? ?: return RequirementStatus.Satisfied
        return satisfiedIf(!am.isBackgroundRestricted)
    }

    override fun intent(context: Context): Intent = appDetailsIntent(context)
}

/**
 * 앱 대기 버킷이 [worstAllowed] 이하로 활발하다(`UsageStatsManager.getAppStandbyBucket`, API 28+). 조회만 한다 — 버킷은 사용 빈도로
 * 시스템이 정해 앱이 열 해결 화면이 없어 넘으면 Unmet 이다. API 28 미만은 버킷이 없어 Satisfied.
 */
internal data class StandbyBucketRequirement(private val worstAllowed: Int) : SystemRequirement {
    override val id: String = "standby_bucket.$worstAllowed"

    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        if (Build.VERSION.SDK_INT < Api.P) return RequirementStatus.Satisfied
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager? ?: return RequirementStatus.Satisfied
        return if (usage.appStandbyBucket <= worstAllowed) RequirementStatus.Satisfied else RequirementStatus.Unmet
    }

    override fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit) = onReady(null)

    companion object {
        /** `STANDBY_BUCKET_ACTIVE`·`WORKING_SET`·`FREQUENT`·`RARE`·`RESTRICTED` */
        val BUCKETS: Set<Int> = setOf(10, 20, 30, 40, 45)
    }
}

/**
 * 방해 금지가 꺼져 있다(`getCurrentInterruptionFilter` 가 `INTERRUPTION_FILTER_ALL`). 켜져 있으면 방해 금지 설정 화면을 연다.
 * 시스템이 값을 모르면(`INTERRUPTION_FILTER_UNKNOWN`) 막지 않는다.
 */
internal object DoNotDisturbOffRequirement : SettingsRequirement("do_not_disturb_off", "DoNotDisturbOffRequirement") {
    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager? ?: return RequirementStatus.Satisfied
        val filter = nm.currentInterruptionFilter
        return satisfiedIf(filter == NotificationManager.INTERRUPTION_FILTER_ALL || filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
    }

    override fun intent(context: Context): Intent = Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
}

/** 화면 잠금(PIN·패턴·비밀번호)이 설정돼 있다(`KeyguardManager.isDeviceSecure`). 없으면 화면 잠금 설정 화면을 연다 */
internal object DeviceSecureRequirement : SettingsRequirement("device_secure", "DeviceSecureRequirement") {
    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager? ?: return NO_HARDWARE
        return satisfiedIf(keyguard.isDeviceSecure)
    }

    override fun intent(context: Context): Intent = Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD)
}

/**
 * 생체 인증이 등록돼 있다(프레임워크 `android.hardware.biometrics.BiometricManager`, API 29+). 상태를 읽으려면 `USE_BIOMETRIC` 을 선언한다.
 * API 30 이상은 [strong] 이면 `BIOMETRIC_STRONG`, 아니면 `BIOMETRIC_WEAK` 로 묻고, API 29 는 강도를 고를 수 없어 `canAuthenticate()` 다.
 * 등록이 없으면 API 30 이상은 등록 화면, API 29 는 보안 설정 화면을 연다. API 29 미만은 Unsupported(API_LEVEL).
 */
internal data class BiometricRequirement(private val strong: Boolean) : SystemRequirement {
    override val id: String = if (strong) "biometric_enrolled.strong" else "biometric_enrolled.weak"

    private val authenticators: Int
        get() = if (strong) BiometricManager.Authenticators.BIOMETRIC_STRONG else BiometricManager.Authenticators.BIOMETRIC_WEAK

    override fun declaredPermissions(environment: PermissionEnvironment): List<String> =
        if (environment.sdkInt >= Api.Q) listOf(USE_BIOMETRIC) else emptyList()

    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        if (Build.VERSION.SDK_INT < Api.Q) return API_LEVEL
        val manager = context.getSystemService(Context.BIOMETRIC_SERVICE) as BiometricManager? ?: return NO_HARDWARE
        val code = try {
            if (Build.VERSION.SDK_INT >= Api.R) manager.canAuthenticate(authenticators) else @Suppress("DEPRECATION") manager.canAuthenticate()
        } catch (e: SecurityException) {
            return RequirementStatus.PermissionRequired(setOf(USE_BIOMETRIC))
        }
        return statusOf(code)
    }

    override fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit) = open(
        if (Build.VERSION.SDK_INT >= Api.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, authenticators)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        },
        onReady,
    )

    companion object {
        const val USE_BIOMETRIC = Manifest.permission.USE_BIOMETRIC

        /** `BiometricManager.canAuthenticate` 결과 코드를 상태로 */
        fun statusOf(code: Int): RequirementStatus = when (code) {
            BiometricManager.BIOMETRIC_SUCCESS -> RequirementStatus.Satisfied
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> RequirementStatus.Disabled
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> NO_HARDWARE
            // 하드웨어 일시 불가·보안 업데이트 필요(API 31+)·그 밖의 값 — 앱이 열 해결 화면이 없다
            else -> RequirementStatus.Unmet
        }
    }
}

/**
 * GPS 공급자가 켜져 있다(`isProviderEnabled(GPS_PROVIDER)`) — 위치는 켜져 있지만 GPS 만 꺼진 경우를 가른다.
 * GPS 하드웨어(`FEATURE_LOCATION_GPS`)가 없으면 Unsupported(NO_HARDWARE). 꺼져 있으면 위치 설정 화면을 연다.
 */
internal object GpsRequirement : SettingsRequirement("gps_enabled", "GpsRequirement") {
    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)) return NO_HARDWARE
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager? ?: return NO_HARDWARE
        return satisfiedIf(manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
    }

    override fun intent(context: Context): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
}

/** 자동 시간(`Settings.Global.AUTO_TIME`, [includeTimeZone] 이면 `AUTO_TIME_ZONE` 도)이 켜져 있다. 꺼져 있으면 날짜·시간 설정 화면을 연다 */
internal data class AutoTimeRequirement(private val includeTimeZone: Boolean) : SystemRequirement {
    override val id: String = if (includeTimeZone) "auto_time+zone" else "auto_time"

    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        val resolver = context.contentResolver
        val time = Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME, 1) == 1
        val zone = !includeTimeZone || Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME_ZONE, 1) == 1
        return satisfiedIf(time && zone)
    }

    override fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit) = open(Intent(Settings.ACTION_DATE_SETTINGS), onReady)
}

/**
 * 이 앱에 컴패니언 기기 연결(`CompanionDeviceManager`, API 26+)이 하나 이상 있다. 연결은 앱이 `associate()` 로 만들고 시스템에
 * 이 화면이 없어 상태만 알린다(없으면 Unmet). 기능(`FEATURE_COMPANION_DEVICE_SETUP`)이 없으면 Unsupported(NO_HARDWARE),
 * API 26 미만은 Unsupported(API_LEVEL).
 */
internal object CompanionDeviceRequirement : SystemRequirement {
    override val id: String = "companion_device_associated"

    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus {
        if (Build.VERSION.SDK_INT < Api.O) return API_LEVEL
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) return NO_HARDWARE
        val manager = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager? ?: return NO_HARDWARE
        val associated = if (Build.VERSION.SDK_INT >= Api.TIRAMISU) {
            manager.myAssociations.isNotEmpty()
        } else {
            @Suppress("DEPRECATION")
            manager.associations.isNotEmpty()
        }
        return if (associated) RequirementStatus.Satisfied else RequirementStatus.Unmet
    }

    override fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit) = onReady(null)

    override fun toString(): String = "CompanionDeviceRequirement"
}
