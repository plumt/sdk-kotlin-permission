@file:SuppressLint("InlinedApi", "BatteryLife")
// 권한 이름 상수는 문자열이라 낮은 API 에서도 안전하다 — 실제 요청 여부는 각 명세가 API 레벨로 가른다
// 배터리 최적화 예외 요청은 이 명세의 목적이다. Play 정책상 허용 용도는 README 에 적었다

package net.plumt.permission.internal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.AlarmManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import net.plumt.permission.GrantLevel
import net.plumt.permission.PermissionStatus
import net.plumt.permission.PermissionEnvironment
import net.plumt.permission.RuntimePermissionSpec
import net.plumt.permission.SpecialPermissionSpec

/** API 레벨 상수 — 플랫폼 상수를 쓰면 낮은 compileSdk 소비자 쪽 린트가 시끄러워 숫자로 둔다 */
internal object Api {
    const val O = 26
    const val Q = 29
    const val R = 30
    const val S = 31
    const val TIRAMISU = 33
    const val UPSIDE_DOWN_CAKE = 34
}

/** 권한 하나만 다루는 단순 명세 */
internal data class SinglePermissionSpec(
    override val id: String,
    private val permission: String,
    private val minSdk: Int = 1,
) : RuntimePermissionSpec {
    override fun permissions(environment: PermissionEnvironment): List<String> =
        if (environment.sdkInt >= minSdk) listOf(permission) else emptyList()
}

/** 읽기·쓰기 짝 권한(연락처·캘린더). 둘 다 요청하면 둘 다 있어야 온전하다 */
internal data class ReadWriteSpec(
    override val id: String,
    private val read: String?,
    private val write: String?,
) : RuntimePermissionSpec {
    override fun permissions(environment: PermissionEnvironment): List<String> = listOfNotNull(read, write)
}

/**
 * 포그라운드 위치. 정밀 위치를 원할 때는 API 31 규칙대로 FINE·COARSE 를 함께 요청하고,
 * 사용자가 "대략적 위치" 만 고르면 [GrantLevel.PARTIAL] 이다.
 */
internal data class LocationSpec(private val precise: Boolean) : RuntimePermissionSpec {
    override val id: String = if (precise) "location.precise" else "location.approximate"

    override fun permissions(environment: PermissionEnvironment): List<String> =
        if (precise) listOf(FINE, COARSE) else listOf(COARSE)

    override fun evaluate(environment: PermissionEnvironment, isGranted: (String) -> Boolean): GrantLevel =
        when {
            isGranted(FINE) -> GrantLevel.FULL
            isGranted(COARSE) -> if (precise) GrantLevel.PARTIAL else GrantLevel.FULL
            else -> GrantLevel.NONE
        }

    // 대략 → 정밀 승격도 두 권한을 함께 다시 요청해야 시스템이 선택 대화상자를 띄운다
    override fun nextRequest(environment: PermissionEnvironment, isGranted: (String) -> Boolean): List<String> =
        if (evaluate(environment, isGranted) == GrantLevel.FULL) emptyList() else permissions(environment)

    companion object {
        const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
        const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
    }
}

/**
 * 백그라운드 위치.
 *
 * - API 29 미만: 백그라운드 권한이 따로 없다. 포그라운드 위치가 곧 백그라운드 위치다
 * - API 29: 포그라운드와 함께 한 번에 요청한다("항상 허용" 선택지가 대화상자에 있다)
 * - API 30 이상: 두 단계다. 포그라운드를 먼저 받고, 그다음 요청이 백그라운드 설정 화면을 연다.
 *   함께 요청하면 시스템이 요청 전체를 무시한다
 */
internal data class BackgroundLocationSpec(private val precise: Boolean) : RuntimePermissionSpec {
    private val foreground = LocationSpec(precise)

    override val id: String = if (precise) "location.background.precise" else "location.background.approximate"

    override fun permissions(environment: PermissionEnvironment): List<String> {
        val fg = foreground.permissions(environment)
        return if (environment.sdkInt >= Api.Q) fg + BACKGROUND else fg
    }

    override fun evaluate(environment: PermissionEnvironment, isGranted: (String) -> Boolean): GrantLevel {
        val fg = foreground.evaluate(environment, isGranted)
        if (environment.sdkInt < Api.Q) return fg
        return when {
            fg == GrantLevel.NONE -> GrantLevel.NONE
            fg == GrantLevel.FULL && isGranted(BACKGROUND) -> GrantLevel.FULL
            else -> GrantLevel.PARTIAL
        }
    }

    override fun nextRequest(environment: PermissionEnvironment, isGranted: (String) -> Boolean): List<String> {
        if (evaluate(environment, isGranted) == GrantLevel.FULL) return emptyList()
        val fgNext = foreground.nextRequest(environment, isGranted)
        return when {
            environment.sdkInt < Api.Q -> fgNext
            environment.sdkInt == Api.Q -> if (isGranted(BACKGROUND)) fgNext else fgNext + BACKGROUND
            // 대략적 위치만 있어도 백그라운드는 요청할 수 있다 — 정밀 승격보다 백그라운드를 먼저 묻지 않는다
            foreground.evaluate(environment, isGranted) == GrantLevel.NONE -> fgNext
            !isGranted(BACKGROUND) -> listOf(BACKGROUND)
            else -> fgNext
        }
    }

    companion object {
        const val BACKGROUND = Manifest.permission.ACCESS_BACKGROUND_LOCATION
    }
}

/**
 * 사진·동영상.
 *
 * - API 34 이상: READ_MEDIA_IMAGES/VIDEO, 그리고 앱이 READ_MEDIA_VISUAL_USER_SELECTED 를 선언했으면 그것도 함께
 *   요청한다. 사용자가 "일부 사진 선택" 을 고르면 그것만 허용돼 [GrantLevel.PARTIAL] 이다
 * - API 33: READ_MEDIA_IMAGES/VIDEO
 * - API 32 이하: READ_EXTERNAL_STORAGE
 */
internal data class MediaSpec(private val images: Boolean, private val video: Boolean) : RuntimePermissionSpec {
    override val id: String = "media." + listOfNotNull("images".takeIf { images }, "video".takeIf { video }).joinToString("+")

    private fun mediaPermissions(): List<String> =
        listOfNotNull(IMAGES.takeIf { images }, VIDEO.takeIf { video })

    override fun permissions(environment: PermissionEnvironment): List<String> = when {
        environment.sdkInt >= Api.UPSIDE_DOWN_CAKE ->
            mediaPermissions() + listOfNotNull(USER_SELECTED.takeIf { environment.isDeclared(it) })
        environment.sdkInt >= Api.TIRAMISU -> mediaPermissions()
        else -> listOf(LEGACY)
    }

    override fun evaluate(environment: PermissionEnvironment, isGranted: (String) -> Boolean): GrantLevel {
        if (environment.sdkInt < Api.TIRAMISU) {
            return if (isGranted(LEGACY)) GrantLevel.FULL else GrantLevel.NONE
        }
        val media = mediaPermissions()
        val grantedMedia = media.count(isGranted)
        val userSelected = environment.sdkInt >= Api.UPSIDE_DOWN_CAKE && isGranted(USER_SELECTED)
        return when {
            grantedMedia == media.size -> GrantLevel.FULL
            grantedMedia > 0 || userSelected -> GrantLevel.PARTIAL
            else -> GrantLevel.NONE
        }
    }

    // 일부 선택 상태에서 선택을 넓히려면 USER_SELECTED 를 포함한 전체를 다시 요청해야 선택 대화상자가 뜬다
    override fun nextRequest(environment: PermissionEnvironment, isGranted: (String) -> Boolean): List<String> =
        if (evaluate(environment, isGranted) == GrantLevel.FULL) emptyList() else permissions(environment)

    companion object {
        const val IMAGES = Manifest.permission.READ_MEDIA_IMAGES
        const val VIDEO = Manifest.permission.READ_MEDIA_VIDEO
        const val USER_SELECTED = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        const val LEGACY = Manifest.permission.READ_EXTERNAL_STORAGE
    }
}

/**
 * 블루투스.
 *
 * - API 31 이상: BLUETOOTH_SCAN·CONNECT·ADVERTISE 가 런타임 권한이다
 * - API 30 이하: 연결·광고용 BLUETOOTH/BLUETOOTH_ADMIN 은 설치 시 부여되는 일반 권한이고,
 *   기기 검색에는 위치 권한이 필요하다(API 29~30 은 FINE, 그 아래는 COARSE)
 */
internal data class BluetoothSpec(
    private val scan: Boolean,
    private val connect: Boolean,
    private val advertise: Boolean,
) : RuntimePermissionSpec {
    override val id: String = "bluetooth." +
        listOfNotNull("scan".takeIf { scan }, "connect".takeIf { connect }, "advertise".takeIf { advertise }).joinToString("+")

    override fun permissions(environment: PermissionEnvironment): List<String> = when {
        environment.sdkInt >= Api.S -> listOfNotNull(
            SCAN.takeIf { scan },
            CONNECT.takeIf { connect },
            ADVERTISE.takeIf { advertise },
        )
        !scan -> emptyList()
        environment.sdkInt >= Api.Q -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    companion object {
        const val SCAN = Manifest.permission.BLUETOOTH_SCAN
        const val CONNECT = Manifest.permission.BLUETOOTH_CONNECT
        const val ADVERTISE = Manifest.permission.BLUETOOTH_ADVERTISE
    }
}

/**
 * 배터리 최적화 예외. 앱이 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 를 선언해야 요청 화면이 열린다.
 * 이 권한은 Google Play 정책상 허용 용도가 정해져 있다.
 */
internal object IgnoreBatteryOptimizationsSpec : SpecialPermissionSpec {
    override val id: String = "battery_optimization.ignore"

    override val manifestPermission: String = Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS

    override fun isApplicable(environment: PermissionEnvironment): Boolean = true

    override fun isGranted(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    override fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    override fun toString(): String = "IgnoreBatteryOptimizationsSpec"
}

/**
 * 정확한 알람 예약(API 31+). API 31 미만에는 제약이 없어 [PermissionStatus.NotApplicable] 이다.
 *
 * 앱이 `USE_EXACT_ALARM`(API 33+, 알람·캘린더 앱만 Play 정책상 허용)을 선언하면 설치 시 부여돼 늘 허용으로 판정된다.
 */
internal object ExactAlarmSpec : SpecialPermissionSpec {
    override val id: String = "exact_alarm"

    override val manifestPermission: String = Manifest.permission.SCHEDULE_EXACT_ALARM

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= Api.S

    // API 31 미만은 정확한 알람에 제약이 없다
    override fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Api.S ||
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    override fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))

    override fun toString(): String = "ExactAlarmSpec"
}

/** 다른 앱 위에 표시(SYSTEM_ALERT_WINDOW). 모든 지원 API 레벨에서 설정 화면으로 켠다 */
internal object OverlaySpec : SpecialPermissionSpec {
    override val id: String = "overlay"

    override val manifestPermission: String = Manifest.permission.SYSTEM_ALERT_WINDOW

    override fun isApplicable(environment: PermissionEnvironment): Boolean = true

    override fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    override fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    override fun toString(): String = "OverlaySpec"
}

/**
 * 출처를 알 수 없는 앱 설치(API 26+, 앱별 허용). API 26 미만은 기기 전체 설정이라 앱별 요청 화면이 없어
 * [PermissionStatus.NotApplicable] 이다. 그 기기에서 [isGranted] 를 직접 부르면 기기 전체 설정 값을 돌려준다.
 */
internal object InstallUnknownAppsSpec : SpecialPermissionSpec {
    override val id: String = "install_unknown_apps"

    override val manifestPermission: String = Manifest.permission.REQUEST_INSTALL_PACKAGES

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= Api.O

    override fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Api.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
        }

    override fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    override fun toString(): String = "InstallUnknownAppsSpec"
}
