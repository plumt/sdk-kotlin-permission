@file:SuppressLint("InlinedApi", "NewApi")
// 권한·화면 이름 상수는 문자열이라 낮은 API 에서도 안전하다 — 플랫폼 함수는 Build.VERSION.SDK_INT 로 가른 뒤 부른다

package net.plumt.permission.internal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.job.JobScheduler
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import net.plumt.permission.AppRole
import net.plumt.permission.PermissionEnvironment
import net.plumt.permission.RuntimePermissionSpec
import net.plumt.permission.SpecialPermissionSpec

private fun Context.packageUri(): Uri = Uri.parse("package:$packageName")

internal fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

/** 앱 작업 모드를 읽는다. API 29 이상은 폐기되지 않은 쪽을 쓴다 */
internal fun appOpMode(context: Context, op: String): Int {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return if (Build.VERSION.SDK_INT >= Api.Q) {
        @Suppress("DEPRECATION")
        appOps.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(op, Process.myUid(), context.packageName)
    }
}

/**
 * 미사용 앱 권한 자동 회수 예외(API 30+). 켜져 있으면(예외면) 오래 쓰지 않아도 권한이 회수되지 않고 앱이 휴면에 들지 않는다.
 * API 30 미만은 플랫폼에 자동 회수가 없어 NotApplicable 이다(Play 서비스가 API 23~29 에 넣는 회수는 Play 서비스 API 의 몫이다).
 */
internal object AutoRevokeExemptSpec : SpecialPermissionSpec {
    override val id: String = "auto_revoke_exempt"
    override val manifestPermission: String? = null

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= Api.R

    // API 30 미만은 회수가 없으니 늘 예외와 같다
    override fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Api.R || context.packageManager.isAutoRevokeWhitelisted

    override fun settingsIntent(context: Context): Intent = Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, context.packageUri())

    override fun toString(): String = "AutoRevokeExemptSpec"
}

/**
 * 기기 관리자. 앱이 `BIND_DEVICE_ADMIN` 으로 보호한 `DeviceAdminReceiver` 를 [admin] 으로 선언해야 한다.
 * 요청하면 시스템의 기기 관리자 활성화 화면이 열린다.
 */
internal data class DeviceAdminSpec(private val admin: ComponentName, private val explanation: String?) : SpecialPermissionSpec {
    override val id: String = "device_admin:" + admin.flattenToString()
    override val manifestPermission: String? = null

    override fun isApplicable(environment: PermissionEnvironment): Boolean = true

    override fun isGranted(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager? ?: return false
        return dpm.isAdminActive(admin)
    }

    override fun settingsIntent(context: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
        .apply { if (explanation != null) putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation) }

    override fun missingDeclarations(context: Context): List<String> {
        val declared = try {
            @Suppress("DEPRECATION")
            context.packageManager.getReceiverInfo(admin, PackageManager.GET_META_DATA).permission == Manifest.permission.BIND_DEVICE_ADMIN
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        return if (declared) {
            emptyList()
        } else {
            listOf(
                "<receiver android:name=\"${admin.className}\" android:permission=\"${Manifest.permission.BIND_DEVICE_ADMIN}\"> + " +
                    "<meta-data android:name=\"${DeviceAdminMeta.META}\" android:resource=\"@xml/…\" /> + " +
                    "<intent-filter><action android:name=\"${DeviceAdminMeta.ENABLED}\" /></intent-filter>",
            )
        }
    }
}

private object DeviceAdminMeta {
    const val META = "android.app.device_admin"
    const val ENABLED = "android.app.action.DEVICE_ADMIN_ENABLED"
}

/**
 * VPN 준비(`VpnService.prepare`). 이 앱이 사용자 동의를 받은 VPN 앱이면 허용이다. 앱이 `BIND_VPN_SERVICE` 로 보호한
 * `VpnService` 를 선언해야 한다. 요청하면 시스템 VPN 연결 동의 대화상자가 뜬다.
 */
internal object VpnSpec : SpecialPermissionSpec {
    override val id: String = "vpn"
    override val manifestPermission: String? = null

    override fun isApplicable(environment: PermissionEnvironment): Boolean = true

    override fun isGranted(context: Context): Boolean = VpnService.prepare(context) == null

    // 그사이 동의됐으면 prepare 가 null 이다 — VPN 설정 화면(API 24+)으로 대신 간다
    override fun settingsIntent(context: Context): Intent = VpnService.prepare(context)
        ?: if (Build.VERSION.SDK_INT >= Api.N) Intent(Settings.ACTION_VPN_SETTINGS) else Intent(Settings.ACTION_WIRELESS_SETTINGS)

    override fun missingDeclarations(context: Context): List<String> =
        if (declaredService(context, VpnService.SERVICE_INTERFACE, Manifest.permission.BIND_VPN_SERVICE) == null) {
            listOf(
                "<service android:permission=\"${Manifest.permission.BIND_VPN_SERVICE}\"> + " +
                    "<intent-filter><action android:name=\"${VpnService.SERVICE_INTERFACE}\" /></intent-filter>",
            )
        } else {
            emptyList()
        }

    override fun toString(): String = "VpnSpec"
}

/**
 * 화면 캡처 동의(MediaProjection). 동의는 캡처 세션마다 받고 저장되지 않아 상태는 늘 Denied 다 — 동의 결과는
 * [onConsent] 로 받는다(`MediaProjectionManager.getMediaProjection(resultCode, data)` 에 넘긴다).
 */
internal data class ScreenCaptureSpec(private val onConsent: (resultCode: Int, data: Intent) -> Unit) : SpecialPermissionSpec {
    override val id: String = "screen_capture"
    override val manifestPermission: String? = null

    override fun isApplicable(environment: PermissionEnvironment): Boolean = true

    override fun isGranted(context: Context): Boolean = false

    override fun settingsIntent(context: Context): Intent =
        (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()

    override fun onSettingsResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) onConsent(resultCode, data)
    }

    override fun toString(): String = "ScreenCaptureSpec"
}

/**
 * Picture-in-Picture 허용(API 26+, 앱 작업 `android:picture_in_picture`). 요청 API 는 없고 설정 화면으로 보낸다.
 * 기본값은 허용이고 사용자가 끄면 거부다.
 */
internal object PictureInPictureSpec : SpecialPermissionSpec {
    override val id: String = "picture_in_picture"
    override val manifestPermission: String? = null

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= Api.O

    override fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Api.O) return false
        val mode = appOpMode(context, AppOpsManager.OPSTR_PICTURE_IN_PICTURE)
        return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
    }

    // 공개 상수가 아닌 설정 앱 화면이다 — 없는 기기는 요청기가 앱 상세 설정 화면으로 대신 연다
    override fun settingsIntent(context: Context): Intent = Intent(ACTION, context.packageUri())

    override fun toString(): String = "PictureInPictureSpec"

    const val ACTION = "android.settings.PICTURE_IN_PICTURE_SETTINGS"
}

/**
 * 기본 앱 역할(RoleManager, API 29+). API 29 미만은 SMS·전화만 옛 기본 앱 API(23~28)로 다루고, 나머지 역할은 NotApplicable 이다.
 * 사용자가 요청할 수 없는 역할(보조)과 이 기기에 없는 역할은 기본 앱 설정 화면을 연다.
 */
internal data class RoleSpec(private val role: AppRole) : SpecialPermissionSpec {
    override val id: String = "role." + role.name.lowercase()
    override val manifestPermission: String? = null

    override fun isApplicable(environment: PermissionEnvironment): Boolean =
        environment.sdkInt >= Api.Q || (role.hasLegacyApi && environment.sdkInt >= Api.M)

    override fun isGranted(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Api.Q -> roleManager(context)?.isRoleHeld(role.roleName) == true
        role == AppRole.SMS -> Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        role == AppRole.DIALER -> telecom(context)?.defaultDialerPackage == context.packageName
        else -> false
    }

    override fun settingsIntent(context: Context): Intent = when {
        Build.VERSION.SDK_INT >= Api.Q -> {
            val manager = roleManager(context)
            if (manager != null && role.requestable && manager.isRoleAvailable(role.roleName)) {
                manager.createRequestRoleIntent(role.roleName)
            } else {
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            }
        }
        role == AppRole.SMS -> @Suppress("DEPRECATION") Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        role == AppRole.DIALER -> Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
        else -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    }

    private fun roleManager(context: Context): RoleManager? = context.getSystemService(Context.ROLE_SERVICE) as RoleManager?

    private fun telecom(context: Context): TelecomManager? = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager?
}

/**
 * Health Connect 권한(API 34+ 플랫폼). 고른 데이터 종류의 `android.permission.health.READ_*`·`WRITE_*` 를 시스템 권한 대화상자로 요청한다.
 * API 34 미만은 Health Connect 가 별도 앱이라 이 명세의 범위 밖이고 NotApplicable 이다.
 * 백그라운드 읽기(`READ_HEALTH_DATA_IN_BACKGROUND`)는 다른 읽기 권한을 하나라도 받은 뒤 따로 요청한다.
 */
internal data class HealthConnectSpec(private val read: List<String>, private val write: List<String>) : RuntimePermissionSpec {
    override val id: String = "health_connect." + (read.map { "read_$it" } + write.map { "write_$it" }).joinToString("+").lowercase()

    private val all: List<String> = (read.map { PREFIX + "READ_" + it } + write.map { PREFIX + "WRITE_" + it }).distinct()

    override fun permissions(environment: PermissionEnvironment): List<String> =
        if (environment.sdkInt >= Api.UPSIDE_DOWN_CAKE) all else emptyList()

    override fun nextRequest(environment: PermissionEnvironment, isGranted: (String) -> Boolean): List<String> {
        val missing = permissions(environment).filterNot(isGranted)
        if (BACKGROUND !in missing) return missing
        val foreground = missing - BACKGROUND
        val readGranted = all.any { it != BACKGROUND && it.startsWith(PREFIX + "READ_") && isGranted(it) }
        return if (readGranted) foreground + BACKGROUND else foreground.ifEmpty { listOf(BACKGROUND) }
    }

    /** 근거 화면(`VIEW_PERMISSION_USAGE` + `HEALTH_PERMISSIONS`)이 없으면 시스템이 대화상자 없이 거부한다 */
    @SuppressLint("QueryPermissionsNeeded")
    override fun missingDeclarations(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Api.UPSIDE_DOWN_CAKE) return emptyList()
        val intent = Intent(Intent.ACTION_VIEW_PERMISSION_USAGE).addCategory(CATEGORY).setPackage(context.packageName)
        @Suppress("DEPRECATION")
        val found = context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        return if (found) {
            emptyList()
        } else {
            listOf(
                "<activity-alias android:permission=\"android.permission.START_VIEW_PERMISSION_USAGE\"> + " +
                    "<intent-filter><action android:name=\"${Intent.ACTION_VIEW_PERMISSION_USAGE}\" />" +
                    "<category android:name=\"$CATEGORY\" /></intent-filter>",
            )
        }
    }

    companion object {
        const val PREFIX = "android.permission.health."
        const val CATEGORY = "android.intent.category.HEALTH_PERMISSIONS"
        const val BACKGROUND = BodySensorsSpec.HEALTH_BACKGROUND
        val TYPE = Regex("[A-Z][A-Z0-9_]*")
    }
}

/**
 * 화면 켜기(`TURN_SCREEN_ON`, API 36+ 앱 작업). 깨우기 잠금의 `ACQUIRE_CAUSES_WAKEUP` 등으로 화면을 켠다.
 * API 36 미만(앱 targetSdk 와 기기 API 가운데 낮은 쪽)은 제약이 없어 NotApplicable 이다. 전용 설정 화면 상수가 공개되지 않아 앱 상세 설정 화면을 연다.
 */
internal object TurnScreenOnSpec : SpecialPermissionSpec {
    override val id: String = "turn_screen_on"
    override val manifestPermission: String = PERMISSION

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.effectiveSdk >= Api.BAKLAVA

    override fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Api.BAKLAVA || appOpAllowed(context, OP, PERMISSION)

    override fun settingsIntent(context: Context): Intent = appDetailsIntent(context)

    override fun toString(): String = "TurnScreenOnSpec"

    const val PERMISSION = "android.permission.TURN_SCREEN_ON"
    const val OP = "android:turn_screen_on"
}

/**
 * 오래 걸리는 사용자 시작 작업(`RUN_USER_INITIATED_JOBS`, API 34+). 사용자가 "오래 걸리는 백그라운드 작업" 에서 끌 수 있다.
 * API 34 미만은 이 작업 종류가 없어 NotApplicable 이다.
 */
internal object UserInitiatedJobsSpec : SpecialPermissionSpec {
    override val id: String = "user_initiated_jobs"
    override val manifestPermission: String = Manifest.permission.RUN_USER_INITIATED_JOBS

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= Api.UPSIDE_DOWN_CAKE

    override fun isGranted(context: Context): Boolean = Build.VERSION.SDK_INT >= Api.UPSIDE_DOWN_CAKE &&
        (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).canRunUserInitiatedJobs()

    // 공개 상수가 아닌 설정 앱 화면이다 — 없는 기기는 요청기가 앱 상세 설정 화면으로 대신 연다
    override fun settingsIntent(context: Context): Intent = Intent(ACTION, context.packageUri())

    override fun toString(): String = "UserInitiatedJobsSpec"

    const val ACTION = "android.settings.MANAGE_APP_LONG_RUNNING_JOBS"
}

/**
 * 업무·개인 프로필 연결(`INTERACT_ACROSS_PROFILES`, API 30+). 관리자가 허용 목록에 넣고 두 프로필에 앱이 있어야 요청할 수 있다 —
 * 요청할 수 없으면 앱 상세 설정 화면을 연다. API 30 미만은 NotApplicable.
 */
internal object CrossProfileSpec : SpecialPermissionSpec {
    override val id: String = "cross_profile"
    override val manifestPermission: String = Manifest.permission.INTERACT_ACROSS_PROFILES

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= Api.R

    override fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Api.R && apps(context)?.canInteractAcrossProfiles() == true

    override fun settingsIntent(context: Context): Intent {
        val apps = if (Build.VERSION.SDK_INT >= Api.R) apps(context) else null
        return if (apps != null && apps.canRequestInteractAcrossProfiles()) {
            apps.createRequestInteractAcrossProfilesIntent()
        } else {
            appDetailsIntent(context)
        }
    }

    private fun apps(context: Context): CrossProfileApps? =
        context.getSystemService(Context.CROSS_PROFILE_APPS_SERVICE) as CrossProfileApps?

    override fun toString(): String = "CrossProfileSpec"
}
