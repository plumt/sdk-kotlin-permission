@file:SuppressLint("InlinedApi")
// 권한·화면 이름 상수는 문자열이라 낮은 API 에서도 안전하다 — 플랫폼 함수는 Build.VERSION.SDK_INT 로 가른 뒤 부른다

package net.plumt.permission.internal

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import net.plumt.permission.PermissionEnvironment
import net.plumt.permission.SpecialPermissionSpec

private fun Context.packageUri() = "package:$packageName".toUri()

/**
 * 앱 작업(AppOps) 모드로 판정하는 특수 권한. 모드가 기본값이면 플랫폼처럼 권한 보유로 판정한다.
 */
internal fun appOpAllowed(context: Context, op: String, permission: String): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION") // API 36 에서 폐기 표시됐지만 대체 함수는 같은 판정을 한다 — 모든 지원 API 에 있는 쪽을 쓴다
    val mode = if (Build.VERSION.SDK_INT >= Api.Q) {
        appOps.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(op, Process.myUid(), context.packageName)
    }
    return when (mode) {
        AppOpsManager.MODE_ALLOWED -> true
        AppOpsManager.MODE_DEFAULT -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        else -> false
    }
}

/** 이 앱이 [action] 으로 [bindPermission] 을 요구하는 서비스를 선언했으면 그 컴포넌트. 자기 패키지만 묻어 패키지 가시성 선언이 필요 없다 */
@SuppressLint("QueryPermissionsNeeded")
internal fun declaredService(context: Context, action: String, bindPermission: String): ComponentName? {
    val intent = Intent(action).setPackage(context.packageName)
    @Suppress("DEPRECATION")
    val services = context.packageManager.queryIntentServices(intent, 0)
    val info = services.firstOrNull { it.serviceInfo?.permission == bindPermission }?.serviceInfo ?: return null
    return ComponentName(info.packageName, info.name)
}

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
            ContextCompat.checkSelfPermission(context, StorageSpec.WRITE) == PackageManager.PERMISSION_GRANTED
        }

    override fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, context.packageUri())

    override fun toString(): String = "AllFilesAccessSpec"

    /** `AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE` — 공개되지 않은 상수라 값으로 둔다 */
    const val OP = "android:manage_external_storage"
}

/** 사용 정보 접근(PACKAGE_USAGE_STATS). API 29 이상은 설정 화면이 이 앱 항목을 바로 연다 */
internal object UsageStatsSpec : SpecialPermissionSpec {
    override val id: String = "usage_stats"
    override val manifestPermission: String = Manifest.permission.PACKAGE_USAGE_STATS

    override fun isApplicable(environment: PermissionEnvironment): Boolean = true

    override fun isGranted(context: Context): Boolean =
        appOpAllowed(context, AppOpsManager.OPSTR_GET_USAGE_STATS, Manifest.permission.PACKAGE_USAGE_STATS)

    // 옛 설정 앱은 package: 데이터를 받는 필터가 없어 화면을 못 찾는다
    override fun settingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Api.Q) {
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, context.packageUri())
        } else {
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }

    override fun toString(): String = "UsageStatsSpec"
}

/** 방해 금지 정책 접근(ACCESS_NOTIFICATION_POLICY) */
internal object NotificationPolicySpec : SpecialPermissionSpec {
    override val id: String = "notification_policy"
    override val manifestPermission: String = Manifest.permission.ACCESS_NOTIFICATION_POLICY

    override fun isApplicable(environment: PermissionEnvironment): Boolean = true

    override fun isGranted(context: Context): Boolean =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted

    override fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    override fun toString(): String = "NotificationPolicySpec"
}

/** 시스템 설정 쓰기(WRITE_SETTINGS) */
internal object WriteSettingsSpec : SpecialPermissionSpec {
    override val id: String = "write_settings"
    override val manifestPermission: String = Manifest.permission.WRITE_SETTINGS

    override fun isApplicable(environment: PermissionEnvironment): Boolean = true

    override fun isGranted(context: Context): Boolean = Settings.System.canWrite(context)

    override fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, context.packageUri())

    override fun toString(): String = "WriteSettingsSpec"
}

/**
 * 전체 화면 인텐트(USE_FULL_SCREEN_INTENT). API 34 이상에서만 사용자가 끌 수 있는 특수 권한이고, 그 아래는 설치 시 부여돼
 * NotApplicable 이다(선언은 여전히 필요하다).
 */
internal object FullScreenIntentSpec : SpecialPermissionSpec {
    override val id: String = "full_screen_intent"
    override val manifestPermission: String = Manifest.permission.USE_FULL_SCREEN_INTENT

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= Api.UPSIDE_DOWN_CAKE

    override fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Api.UPSIDE_DOWN_CAKE) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()
        } else {
            ContextCompat.checkSelfPermission(context, manifestPermission) == PackageManager.PERMISSION_GRANTED
        }

    override fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, context.packageUri())

    override fun toString(): String = "FullScreenIntentSpec"
}

/** 미디어 관리(MANAGE_MEDIA, API 31+) — 확인 대화상자 없이 사용자 매체를 고치고 지운다 */
internal object ManageMediaSpec : SpecialPermissionSpec {
    override val id: String = "manage_media"
    override val manifestPermission: String = Manifest.permission.MANAGE_MEDIA

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= Api.S

    override fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Api.S && MediaStore.canManageMedia(context)

    override fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, context.packageUri())

    override fun toString(): String = "ManageMediaSpec"
}

/**
 * 알림 접근(알림 리스너). 앱이 `BIND_NOTIFICATION_LISTENER_SERVICE` 로 보호한 `NotificationListenerService` 를 선언해야
 * 설정 목록에 나온다. API 30 이상은 그 서비스의 상세 화면을 바로 연다.
 */
internal object NotificationListenerSpec : SpecialPermissionSpec {
    override val id: String = "notification_listener"
    override val manifestPermission: String? = null

    override fun isApplicable(environment: PermissionEnvironment): Boolean = true

    override fun isGranted(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    override fun settingsIntent(context: Context): Intent {
        val component = component(context)
        return if (Build.VERSION.SDK_INT >= Api.R && component != null) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
    }

    override fun missingDeclarations(context: Context): List<String> =
        if (component(context) == null) {
            listOf(
                "<service android:permission=\"${Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE}\"> + " +
                    "<intent-filter><action android:name=\"${NotificationListenerService.SERVICE_INTERFACE}\" /></intent-filter>",
            )
        } else {
            emptyList()
        }

    private fun component(context: Context): ComponentName? = declaredService(
        context,
        NotificationListenerService.SERVICE_INTERFACE,
        Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE,
    )

    override fun toString(): String = "NotificationListenerSpec"
}

/**
 * 접근성 서비스. 앱이 `BIND_ACCESSIBILITY_SERVICE` 로 보호한 `AccessibilityService` 를 선언해야 설정 목록에 나온다.
 * 켜진 서비스 목록(`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`)에 이 앱 서비스가 있으면 허용이다.
 */
internal object AccessibilityServiceSpec : SpecialPermissionSpec {
    override val id: String = "accessibility_service"
    override val manifestPermission: String? = null

    override fun isApplicable(environment: PermissionEnvironment): Boolean = true

    override fun isGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it)?.packageName == context.packageName }
    }

    override fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    override fun missingDeclarations(context: Context): List<String> =
        if (declaredService(context, AccessibilityService.SERVICE_INTERFACE, Manifest.permission.BIND_ACCESSIBILITY_SERVICE) == null) {
            listOf(
                "<service android:permission=\"${Manifest.permission.BIND_ACCESSIBILITY_SERVICE}\"> + " +
                    "<intent-filter><action android:name=\"${AccessibilityService.SERVICE_INTERFACE}\" /></intent-filter>",
            )
        } else {
            emptyList()
        }

    override fun toString(): String = "AccessibilityServiceSpec"
}
