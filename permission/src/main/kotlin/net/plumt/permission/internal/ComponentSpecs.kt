@file:SuppressLint("InlinedApi")
// 권한·화면·서비스 인터페이스 이름 상수는 문자열이라 낮은 API 에서도 안전하다 — 플랫폼 함수는 SDK_INT 로 가른 뒤 @RequiresApi 함수로 부른다

package net.plumt.permission.internal

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.service.autofill.AutofillService
import android.service.notification.NotificationListenerService
import android.telecom.TelecomManager
import android.view.autofill.AutofillManager
import android.view.inputmethod.InputMethod
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import net.plumt.permission.AppRole
import net.plumt.permission.PermissionEnvironment
import net.plumt.permission.SpecialPermissionSpec

// 특수 권한 가운데 "앱 컴포넌트·시스템 동의" 의 구현 — 사용자가 앱이 선언한 컴포넌트(서비스·수신기)를 켜거나 기본 앱으로 고르는 것,
// 시스템 동의 대화상자(VPN·화면 캡처·역할)를 받는 것은 모두 이 파일에 둔다. 판정은 켜진 목록·보유 역할·동의 여부를 읽는다.
// 이 앱 하나의 스위치를 켜는 설정 토글은 SettingsSpecs.kt.

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
        if (component(context) == null) listOf(serviceDeclaration(BIND, NotificationListenerService.SERVICE_INTERFACE)) else emptyList()

    private fun component(context: Context): ComponentName? =
        declaredService(context, NotificationListenerService.SERVICE_INTERFACE, BIND)

    override fun toString(): String = "NotificationListenerSpec"

    private const val BIND = Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE
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
        if (declaredService(context, AccessibilityService.SERVICE_INTERFACE, BIND) == null) {
            listOf(serviceDeclaration(BIND, AccessibilityService.SERVICE_INTERFACE))
        } else {
            emptyList()
        }

    override fun toString(): String = "AccessibilityServiceSpec"

    private const val BIND = Manifest.permission.BIND_ACCESSIBILITY_SERVICE
}

/**
 * 자동완성 서비스(API 26+). 앱이 `BIND_AUTOFILL_SERVICE` 로 보호한 `AutofillService` 를 선언하고, 사용자가 그 서비스를
 * 기기의 자동완성 서비스로 고르면 허용이다(`AutofillManager.hasEnabledAutofillServices`). 요청하면 시스템의 자동완성 서비스 선택 화면
 * (`ACTION_REQUEST_SET_AUTOFILL_SERVICE`)을 연다. 자동완성이 없는 기기(`isAutofillSupported` 거짓)는 늘 거부다.
 */
internal object AutofillServiceSpec : SpecialPermissionSpec {
    override val id: String = "autofill_service"
    override val manifestPermission: String? = null

    override fun isApplicable(environment: PermissionEnvironment): Boolean = environment.sdkInt >= Api.O

    // 그 아래에는 자동완성 프레임워크가 없다
    override fun isGranted(context: Context): Boolean = Build.VERSION.SDK_INT >= Api.O && hasEnabledAutofill(context)

    override fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, context.packageUri())

    override fun missingDeclarations(context: Context): List<String> =
        if (declaredService(context, AutofillService.SERVICE_INTERFACE, BIND) == null) {
            listOf(serviceDeclaration(BIND, AutofillService.SERVICE_INTERFACE))
        } else {
            emptyList()
        }

    override fun toString(): String = "AutofillServiceSpec"

    private const val BIND = Manifest.permission.BIND_AUTOFILL_SERVICE
}

@RequiresApi(Api.O)
private fun hasEnabledAutofill(context: Context): Boolean {
    val manager = context.getSystemService(AutofillManager::class.java) ?: return false
    return manager.isAutofillSupported && manager.hasEnabledAutofillServices()
}

/**
 * 입력기(키보드) 사용 설정. 앱이 `BIND_INPUT_METHOD` 로 보호한 입력기 서비스를 선언하고, 사용자가 설정의 키보드 목록에서
 * 그 입력기를 켜면 허용이다(`InputMethodManager.getEnabledInputMethodList`). 요청하면 입력기 설정 화면을 연다.
 * 켠 입력기를 **기본 키보드로 고르는 것**은 결과를 돌려주는 화면이 없어(`showInputMethodPicker` 는 선택 창만 띄운다) 이 명세가 다루지 않는다.
 */
internal object InputMethodSpec : SpecialPermissionSpec {
    override val id: String = "input_method"
    override val manifestPermission: String? = null

    override fun isApplicable(environment: PermissionEnvironment): Boolean = true

    override fun isGranted(context: Context): Boolean {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager? ?: return false
        return manager.enabledInputMethodList.any { it.packageName == context.packageName }
    }

    override fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)

    override fun missingDeclarations(context: Context): List<String> =
        if (declaredService(context, InputMethod.SERVICE_INTERFACE, BIND) == null) {
            listOf(serviceDeclaration(BIND, InputMethod.SERVICE_INTERFACE))
        } else {
            emptyList()
        }

    override fun toString(): String = "InputMethodSpec"

    private const val BIND = Manifest.permission.BIND_INPUT_METHOD
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
                    "<meta-data android:name=\"$META\" android:resource=\"@xml/…\" /> + " +
                    "<intent-filter><action android:name=\"$ENABLED\" /></intent-filter>",
            )
        }
    }

    private companion object {
        const val META = "android.app.device_admin"
        const val ENABLED = "android.app.action.DEVICE_ADMIN_ENABLED"
    }
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
        if (declaredService(context, VpnService.SERVICE_INTERFACE, BIND) == null) {
            listOf(serviceDeclaration(BIND, VpnService.SERVICE_INTERFACE))
        } else {
            emptyList()
        }

    override fun toString(): String = "VpnSpec"

    private const val BIND = Manifest.permission.BIND_VPN_SERVICE
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
 * 기본 앱 역할(RoleManager, API 29+). 역할마다 생긴 API([AppRole.minSdk]) 미만은 NotApplicable 이고, SMS·전화만 API 23~28 에
 * 옛 기본 앱 API 로 다룬다. 앱이 요청할 수 없는 역할과 이 기기에 없는 역할은 기본 앱 설정 화면을 연다.
 */
internal data class RoleSpec(private val role: AppRole) : SpecialPermissionSpec {
    override val id: String = "role." + role.roleName.substringAfterLast('.').lowercase()
    override val manifestPermission: String? = null

    override fun isApplicable(environment: PermissionEnvironment): Boolean =
        environment.sdkInt >= role.minSdk || (role.legacy != null && environment.sdkInt < Api.Q)

    override fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Api.Q) return isRoleHeld(context, role.roleName)
        return when (role.legacy) {
            AppRole.LegacyRole.SMS -> Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
            AppRole.LegacyRole.DIALER -> telecom(context)?.defaultDialerPackage == context.packageName
            null -> false
        }
    }

    override fun settingsIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Api.Q) return requestRoleIntent(context, role) ?: Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        return when (role.legacy) {
            AppRole.LegacyRole.SMS -> @Suppress("DEPRECATION") Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
            AppRole.LegacyRole.DIALER -> Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
            null -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        }
    }

    private fun telecom(context: Context): TelecomManager? = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager?
}

@RequiresApi(Api.Q)
private fun roleManager(context: Context): RoleManager? = context.getSystemService(Context.ROLE_SERVICE) as RoleManager?

@RequiresApi(Api.Q)
private fun isRoleHeld(context: Context, roleName: String): Boolean = roleManager(context)?.isRoleHeld(roleName) == true

/** 요청할 수 있고 이 기기에 있는 역할이면 시스템 요청 대화상자, 아니면 null */
@RequiresApi(Api.Q)
private fun requestRoleIntent(context: Context, role: AppRole): Intent? {
    val manager = roleManager(context) ?: return null
    if (!role.requestable || Build.VERSION.SDK_INT < role.minSdk || !manager.isRoleAvailable(role.roleName)) return null
    return manager.createRequestRoleIntent(role.roleName)
}
