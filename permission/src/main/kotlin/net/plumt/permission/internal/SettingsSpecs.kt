@file:SuppressLint("InlinedApi", "BatteryLife")
// 권한·화면 이름 상수는 문자열이라 낮은 API 에서도 안전하다 — 플랫폼 함수는 SDK_INT 로 가른 뒤 @RequiresApi 함수로 부른다.
// 배터리 최적화 예외 요청은 그 명세의 목적이다. Play 정책상 허용 용도는 README 에 적었다

package net.plumt.permission.internal

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.RequiresApi
import net.plumt.permission.PermissionEnvironment
import net.plumt.permission.SpecialPermissionSpec

// 특수 권한 가운데 "설정 토글" 의 구현 — 사용자가 설정의 특수 앱 접근에서 이 앱 하나의 스위치를 켜는 것은 모두 이 파일에 둔다.
// 판정은 플랫폼 함수나 앱 작업(AppOps) 모드 하나다. 앱 컴포넌트(서비스·수신기)를 고르거나 시스템 동의를 받는 것은 ComponentSpecs.kt.
// 적용 여부(isApplicable)는 기기 API 로 가른다 — 특수 권한은 앱 targetSdk 와 무관하게 기기에 있거나 없다.

/** 설정 토글 명세의 뼈대. [minSdk] 미만 기기에는 이 특수 권한이 없다 */
internal abstract class SettingsToggleSpec(
    override val id: String,
    override val manifestPermission: String?,
    private val minSdk: Int,
    private val name: String,
) : SpecialPermissionSpec {
    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= minSdk

    /** 이 기기에 특수 권한이 있으면 그 판정, 없으면 제약이 없는 것과 같아 참 */
    final override fun isGranted(context: Context): Boolean = Build.VERSION.SDK_INT < minSdk || isGrantedOnSupported(context)

    /** [minSdk] 이상 기기에서의 판정. [minSdk] 가 23 보다 크면 구현에 `@RequiresApi(minSdk)` 를 단다 — 이 뼈대가 그 아래에서는 부르지 않는다 */
    protected abstract fun isGrantedOnSupported(context: Context): Boolean

    override fun toString(): String = name
}

/**
 * 배터리 최적화 예외. 앱이 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 를 선언해야 요청 화면이 열린다.
 * 이 권한은 Google Play 정책상 허용 용도가 정해져 있다.
 */
internal object IgnoreBatteryOptimizationsSpec : SettingsToggleSpec(
    "battery_optimization.ignore",
    Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    Api.M,
    "IgnoreBatteryOptimizationsSpec",
) {
    override fun isGrantedOnSupported(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    override fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, context.packageUri())
}

/**
 * 정확한 알람 예약(API 31+). API 31 미만에는 제약이 없어 NotApplicable 이다.
 *
 * 앱이 `USE_EXACT_ALARM`(API 33+, 알람·캘린더 앱만 Play 정책상 허용)을 선언하면 설치 시 부여돼 늘 허용으로 판정된다.
 */
internal object ExactAlarmSpec : SettingsToggleSpec("exact_alarm", Manifest.permission.SCHEDULE_EXACT_ALARM, Api.S, "ExactAlarmSpec") {
    @RequiresApi(Api.S)
    override fun isGrantedOnSupported(context: Context): Boolean = canScheduleExactAlarms(context)

    override fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, context.packageUri())
}

@RequiresApi(Api.S)
private fun canScheduleExactAlarms(context: Context): Boolean =
    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

/**
 * 다른 앱 위에 표시(SYSTEM_ALERT_WINDOW). 모든 지원 API 레벨에서 설정 화면으로 켠다.
 *
 * API 26~27 은 설정에서 켜고 돌아온 직후 `canDrawOverlays` 가 한동안 거짓을 돌려주는 플랫폼 결함이 있어,
 * 그때는 앱 작업 모드가 허용인지도 본다. API 30 이상의 설정 화면은 `package:` 를 무시하고 전체 앱 목록을 연다.
 */
internal object OverlaySpec : SettingsToggleSpec("overlay", Manifest.permission.SYSTEM_ALERT_WINDOW, Api.M, "OverlaySpec") {
    override fun isGrantedOnSupported(context: Context): Boolean =
        Settings.canDrawOverlays(context) ||
            (Build.VERSION.SDK_INT in Api.O..Api.O_MR1 && appOpMode(context, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW) == AppOpsManager.MODE_ALLOWED)

    override fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, context.packageUri())
}

/**
 * 출처를 알 수 없는 앱 설치(API 26+, 앱별 허용). API 26 미만은 기기 전체 설정이라 앱별 요청 화면이 없어
 * NotApplicable 이다. 그 기기에서 [isGranted] 를 직접 부르면 기기 전체 설정 값을 돌려준다.
 */
internal object InstallUnknownAppsSpec : SpecialPermissionSpec {
    override val id: String = "install_unknown_apps"

    override val manifestPermission: String = Manifest.permission.REQUEST_INSTALL_PACKAGES

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= Api.O

    override fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Api.O) {
            canRequestPackageInstalls(context)
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
        }

    override fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, context.packageUri())

    override fun toString(): String = "InstallUnknownAppsSpec"
}

@RequiresApi(Api.O)
private fun canRequestPackageInstalls(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

/**
 * 전체 파일 접근(MANAGE_EXTERNAL_STORAGE, API 30+). API 30 미만은 이 특수 권한이 없어 NotApplicable 이다 —
 * 그 기기에서 공유 저장소 전체는 저장소 런타임 권한(`storage(write = true)`)이 맡는다.
 */
internal object AllFilesAccessSpec : SpecialPermissionSpec {
    override val id: String = "all_files_access"
    override val manifestPermission: String = Manifest.permission.MANAGE_EXTERNAL_STORAGE

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= Api.R

    // Environment.isExternalStorageManager 와 같은 판정(앱 작업 모드, 기본값이면 권한 보유)을 직접 한다 — 그 함수는
    // 프로세스 전역 Application 을 찾아 쓰고, 이 판정은 넘긴 컨텍스트를 쓴다. API 30 미만에서 직접 물으면 옛 쓰기 권한을 본다
    override fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Api.R) {
            appOpAllowed(context, OP, Manifest.permission.MANAGE_EXTERNAL_STORAGE)
        } else {
            isPermissionGranted(context, StorageSpec.WRITE)
        }

    override fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, context.packageUri())

    override fun toString(): String = "AllFilesAccessSpec"

    /** `AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE` — 공개되지 않은 상수라 값으로 둔다 */
    const val OP = "android:manage_external_storage"
}

/** 사용 정보 접근(PACKAGE_USAGE_STATS). API 29 이상은 설정 화면이 이 앱 항목을 바로 연다 */
internal object UsageStatsSpec : SettingsToggleSpec("usage_stats", Manifest.permission.PACKAGE_USAGE_STATS, Api.M, "UsageStatsSpec") {
    override fun isGrantedOnSupported(context: Context): Boolean =
        appOpAllowed(context, AppOpsManager.OPSTR_GET_USAGE_STATS, Manifest.permission.PACKAGE_USAGE_STATS)

    // 옛 설정 앱은 package: 데이터를 받는 필터가 없어 화면을 못 찾는다
    override fun settingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Api.Q) {
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, context.packageUri())
        } else {
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }
}

/** 방해 금지 정책 접근(ACCESS_NOTIFICATION_POLICY) */
internal object NotificationPolicySpec : SettingsToggleSpec(
    "notification_policy",
    Manifest.permission.ACCESS_NOTIFICATION_POLICY,
    Api.M,
    "NotificationPolicySpec",
) {
    override fun isGrantedOnSupported(context: Context): Boolean =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted

    override fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
}

/** 시스템 설정 쓰기(WRITE_SETTINGS) */
internal object WriteSettingsSpec : SettingsToggleSpec("write_settings", Manifest.permission.WRITE_SETTINGS, Api.M, "WriteSettingsSpec") {
    override fun isGrantedOnSupported(context: Context): Boolean = Settings.System.canWrite(context)

    override fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, context.packageUri())
}

/**
 * 전체 화면 인텐트(USE_FULL_SCREEN_INTENT). API 34 이상에서만 사용자가 끌 수 있는 특수 권한이고, 그 아래는 설치 시 부여돼
 * NotApplicable 이다(선언은 여전히 필요하다).
 */
internal object FullScreenIntentSpec : SpecialPermissionSpec {
    override val id: String = "full_screen_intent"
    override val manifestPermission: String = Manifest.permission.USE_FULL_SCREEN_INTENT

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= Api.UPSIDE_DOWN_CAKE

    // 그 아래는 설치 시 부여라 선언했는지가 곧 허용이다
    override fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Api.UPSIDE_DOWN_CAKE) canUseFullScreenIntent(context) else isPermissionGranted(context, manifestPermission)

    override fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, context.packageUri())

    override fun toString(): String = "FullScreenIntentSpec"
}

@RequiresApi(Api.UPSIDE_DOWN_CAKE)
private fun canUseFullScreenIntent(context: Context): Boolean =
    (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()

/** 미디어 관리(MANAGE_MEDIA, API 31+) — 확인 대화상자 없이 사용자 매체를 고치고 지운다 */
internal object ManageMediaSpec : SpecialPermissionSpec {
    override val id: String = "manage_media"
    override val manifestPermission: String = Manifest.permission.MANAGE_MEDIA

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= Api.S

    // 그 아래에는 이 접근이 없어 가질 수도 없다
    override fun isGranted(context: Context): Boolean = Build.VERSION.SDK_INT >= Api.S && canManageMedia(context)

    override fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, context.packageUri())

    override fun toString(): String = "ManageMediaSpec"
}

@RequiresApi(Api.S)
private fun canManageMedia(context: Context): Boolean = MediaStore.canManageMedia(context)

/**
 * 미사용 앱 권한 자동 회수 예외(API 30+). 켜져 있으면(예외면) 오래 쓰지 않아도 권한이 회수되지 않고 앱이 휴면에 들지 않는다.
 * API 30 미만은 플랫폼에 자동 회수가 없어 NotApplicable 이다(Play 서비스가 API 23~29 에 넣는 회수는 Play 서비스 API 의 몫이다).
 */
internal object AutoRevokeExemptSpec : SettingsToggleSpec("auto_revoke_exempt", null, Api.R, "AutoRevokeExemptSpec") {
    @RequiresApi(Api.R)
    override fun isGrantedOnSupported(context: Context): Boolean = isAutoRevokeWhitelisted(context)

    override fun settingsIntent(context: Context): Intent = Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, context.packageUri())
}

@RequiresApi(Api.R)
private fun isAutoRevokeWhitelisted(context: Context): Boolean = context.packageManager.isAutoRevokeWhitelisted

/**
 * Picture-in-Picture 허용(API 26+, 앱 작업 `android:picture_in_picture`). 요청 API 는 없고 설정 화면으로 보낸다.
 * 기본값은 허용이고 사용자가 끄면 거부다.
 */
internal object PictureInPictureSpec : SpecialPermissionSpec {
    override val id: String = "picture_in_picture"
    override val manifestPermission: String? = null

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= Api.O

    // 그 아래에는 PiP 가 없어 쓸 수도 없다
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
 * 화면 켜기(`TURN_SCREEN_ON`, 앱 작업). 깨우기 잠금의 `ACQUIRE_CAUSES_WAKEUP` 등으로 화면을 켠다.
 * 권한 이름은 API 34 에 공개됐고 사용자 토글(앱 작업)은 API 36 기기에서 다룬다 — 그 아래 기기는 NotApplicable 이다.
 * 다른 특수 권한처럼 기기 API 로만 가른다(판정과 적용 여부가 같은 기준이다). 전용 설정 화면 상수가 공개되지 않아 앱 상세 설정 화면을 연다.
 */
internal object TurnScreenOnSpec : SettingsToggleSpec("turn_screen_on", Manifest.permission.TURN_SCREEN_ON, Api.BAKLAVA, "TurnScreenOnSpec") {
    @RequiresApi(Api.BAKLAVA)
    override fun isGrantedOnSupported(context: Context): Boolean = appOpAllowed(context, OP, Manifest.permission.TURN_SCREEN_ON)

    override fun settingsIntent(context: Context): Intent = appDetailsIntent(context)

    /** `AppOpsManager.OPSTR_TURN_SCREEN_ON` — 공개되지 않은 상수라 값으로 둔다 */
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

    // 그 아래에는 이 작업 종류가 없어 쓸 수도 없다
    override fun isGranted(context: Context): Boolean = Build.VERSION.SDK_INT >= Api.UPSIDE_DOWN_CAKE && canRunUserInitiatedJobs(context)

    // 공개 상수가 아닌 설정 앱 화면이다 — 없는 기기는 요청기가 앱 상세 설정 화면으로 대신 연다
    override fun settingsIntent(context: Context): Intent = Intent(ACTION, context.packageUri())

    override fun toString(): String = "UserInitiatedJobsSpec"

    const val ACTION = "android.settings.MANAGE_APP_LONG_RUNNING_JOBS"
}

@RequiresApi(Api.UPSIDE_DOWN_CAKE)
private fun canRunUserInitiatedJobs(context: Context): Boolean =
    (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).canRunUserInitiatedJobs()

/**
 * 업무·개인 프로필 연결(`INTERACT_ACROSS_PROFILES`, API 30+). 관리자가 허용 목록에 넣고 두 프로필에 앱이 있어야 요청할 수 있다 —
 * 요청할 수 없으면 앱 상세 설정 화면을 연다. API 30 미만은 NotApplicable.
 */
internal object CrossProfileSpec : SpecialPermissionSpec {
    override val id: String = "cross_profile"
    override val manifestPermission: String = Manifest.permission.INTERACT_ACROSS_PROFILES

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= Api.R

    // 그 아래에는 프로필 연결이 없다
    override fun isGranted(context: Context): Boolean = Build.VERSION.SDK_INT >= Api.R && canInteractAcrossProfiles(context)

    override fun settingsIntent(context: Context): Intent =
        (if (Build.VERSION.SDK_INT >= Api.R) requestAcrossProfilesIntent(context) else null) ?: appDetailsIntent(context)

    override fun toString(): String = "CrossProfileSpec"
}

@RequiresApi(Api.R)
private fun crossProfileApps(context: Context): CrossProfileApps? =
    context.getSystemService(Context.CROSS_PROFILE_APPS_SERVICE) as CrossProfileApps?

@RequiresApi(Api.R)
private fun canInteractAcrossProfiles(context: Context): Boolean = crossProfileApps(context)?.canInteractAcrossProfiles() == true

@RequiresApi(Api.R)
private fun requestAcrossProfilesIntent(context: Context): Intent? {
    val apps = crossProfileApps(context) ?: return null
    return if (apps.canRequestInteractAcrossProfiles()) apps.createRequestInteractAcrossProfilesIntent() else null
}

/**
 * 알림 말풍선(버블) 허용(API 30+). 사용자가 앱 알림 설정의 "대화 말풍선" 에서 고른다 — API 31 이상은 `getBubblePreference` 가
 * 모든 대화(`ALL`)나 선택한 대화(`SELECTED`)면 허용, API 30 은 `areBubblesAllowed`. 기기 전체 말풍선 스위치(API 31+ `areBubblesEnabled`)와
 * 대화별 허용(채널·바로가기)은 보지 않는다. 요청하면 이 앱의 말풍선 설정 화면을 연다.
 */
internal object BubblesSpec : SettingsToggleSpec("bubbles", null, Api.R, "BubblesSpec") {
    @RequiresApi(Api.R)
    override fun isGrantedOnSupported(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager? ?: return false
        return if (Build.VERSION.SDK_INT >= Api.S) bubblePreferenceAllows(manager) else bubblesAllowedLegacy(manager)
    }

    override fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}

@RequiresApi(Api.S)
private fun bubblePreferenceAllows(manager: NotificationManager): Boolean =
    manager.bubblePreference != NotificationManager.BUBBLE_PREFERENCE_NONE

@RequiresApi(Api.Q)
private fun bubblesAllowedLegacy(manager: NotificationManager): Boolean {
    @Suppress("DEPRECATION")
    return manager.areBubblesAllowed()
}
