package net.plumt.permission

import android.app.ActivityManager
import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import net.plumt.permission.internal.BiometricRequirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/** 기기 상태 요구 조건 — API 레벨별 판정, 해결 인텐트, 상태만 알리는 항목, 요청 흐름에서의 동작 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StateRequirementsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val device = FakeDevice()

    private fun manager(declared: Set<String> = ALL_DECLARED + NETWORK + BIOMETRIC) =
        fakeManager(app, device, sdkInt = Build.VERSION.SDK_INT, declared = declared)

    private fun enableIntent(requirement: SystemRequirement): Intent? {
        var request: EnableRequest? = null
        var calls = 0
        requirement.enableRequest(app) {
            request = it
            calls++
        }
        assertEquals(1, calls)
        return (request as EnableRequest.OfIntent?)?.intent
    }

    /** 서비스가 없는 기기 흉내 */
    private val noServices = object : ContextWrapper(app) {
        override fun getSystemService(name: String): Any? = null
    }

    private fun global(name: String, value: Int) = Settings.Global.putInt(app.contentResolver, name, value)

    @Test
    fun `id 와 문자열`() {
        val ids = listOf(
            SystemRequirements.airplaneModeOff, SystemRequirements.internetAvailable, SystemRequirements.dataSaverOff,
            SystemRequirements.powerSaveOff, SystemRequirements.backgroundUnrestricted, SystemRequirements.standbyBucket(),
            SystemRequirements.doNotDisturbOff, SystemRequirements.deviceSecure, SystemRequirements.biometricEnrolled(),
            SystemRequirements.biometricEnrolled(strong = true), SystemRequirements.gpsEnabled, SystemRequirements.autoTimeEnabled(),
            SystemRequirements.autoTimeEnabled(includeTimeZone = true), SystemRequirements.companionDeviceAssociated,
        ).map { it.id }
        assertEquals(
            listOf(
                "airplane_mode_off", "internet_available", "data_saver_off", "power_save_off", "background_unrestricted",
                "standby_bucket.40", "do_not_disturb_off", "device_secure", "biometric_enrolled.weak", "biometric_enrolled.strong",
                "gps_enabled", "auto_time", "auto_time+zone", "companion_device_associated",
            ),
            ids,
        )
        for (r in listOf(
            SystemRequirements.airplaneModeOff, SystemRequirements.internetAvailable, SystemRequirements.dataSaverOff,
            SystemRequirements.powerSaveOff, SystemRequirements.backgroundUnrestricted, SystemRequirements.doNotDisturbOff,
            SystemRequirements.deviceSecure, SystemRequirements.gpsEnabled, SystemRequirements.companionDeviceAssociated,
        )) {
            assertTrue(r.toString(), r.toString().endsWith("Requirement"))
        }
        assertEquals(SystemRequirements.standbyBucket(30), SystemRequirements.standbyBucket(30))
        assertEquals(SystemRequirements.biometricEnrolled(true), SystemRequirements.biometricEnrolled(true))
    }

    @Test
    fun `선언 권한`() {
        val env = PermissionEnvironment.of(34)
        assertEquals(listOf(NETWORK), SystemRequirements.internetAvailable.declaredPermissions(env))
        assertEquals(emptyList<String>(), SystemRequirements.internetAvailable.permissions(env))
        assertEquals(listOf(BIOMETRIC), SystemRequirements.biometricEnrolled().declaredPermissions(env))
        assertEquals(emptyList<String>(), SystemRequirements.biometricEnrolled().declaredPermissions(PermissionEnvironment.of(28)))
        for (r in listOf(SystemRequirements.airplaneModeOff, SystemRequirements.dataSaverOff, SystemRequirements.gpsEnabled)) {
            assertEquals(emptyList<String>(), r.declaredPermissions(env))
        }
        val m = fakeManager(app, device, declared = emptySet())
        assertEquals(
            setOf(NETWORK, BIOMETRIC),
            m.undeclaredPermissions(emptyList(), listOf(SystemRequirements.internetAvailable, SystemRequirements.biometricEnrolled())),
        )
    }

    // ---- 비행기 모드

    @Test
    @Config(sdk = [23, 29, 35])
    fun `비행기 모드`() {
        val r = SystemRequirements.airplaneModeOff
        global(Settings.Global.AIRPLANE_MODE_ON, 1)
        assertEquals(RequirementStatus.Disabled, manager().check(r))
        global(Settings.Global.AIRPLANE_MODE_ON, 0)
        assertEquals(RequirementStatus.Satisfied, manager().check(r))
        assertEquals(Settings.ACTION_AIRPLANE_MODE_SETTINGS, enableIntent(r)!!.action)
    }

    // ---- 인터넷

    @Test
    @Config(sdk = [23, 28, 29, 34, 35])
    fun `인터넷은 기본 네트워크의 INTERNET 과 VALIDATED 를 함께 본다`() {
        val r = SystemRequirements.internetAvailable
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork!!
        fun caps(vararg capabilities: Int): NetworkCapabilities {
            val caps = ShadowNetworkCapabilities.newInstance()
            for (c in capabilities) shadowOf(caps).addCapability(c)
            return caps
        }
        shadowOf(cm).setNetworkCapabilities(network, caps(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertEquals(RequirementStatus.Satisfied, manager().check(r))
        // 공용 Wi-Fi 인증 전 — 연결은 있지만 검증되지 않았다
        shadowOf(cm).setNetworkCapabilities(network, caps(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertEquals(RequirementStatus.Disabled, manager().check(r))
        shadowOf(cm).setNetworkCapabilities(network, caps(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertEquals(RequirementStatus.Disabled, manager().check(r))
        shadowOf(cm).setActiveNetworkInfo(null)
        assertEquals(RequirementStatus.Disabled, manager().check(r))
        val expected = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
        assertEquals(expected, enableIntent(r)!!.action)
        assertEquals(RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE), r.status(noServices, PermissionEnvironment.of(34)))
    }

    // ---- 데이터 절약

    @Test
    @Config(sdk = [24, 30, 35])
    fun `데이터 절약 모드가 이 앱을 막을 때만 Disabled 이고 이 앱의 무제한 데이터 화면을 연다`() {
        val r = SystemRequirements.dataSaverOff
        val cm = shadowOf(app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        cm.setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
        assertEquals(RequirementStatus.Disabled, manager().check(r))
        cm.setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED)
        assertEquals(RequirementStatus.Satisfied, manager().check(r))
        cm.setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED)
        assertEquals(RequirementStatus.Satisfied, manager().check(r))
        val intent = enableIntent(r)!!
        assertEquals(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
        assertEquals(RequirementStatus.Satisfied, r.status(noServices, PermissionEnvironment.of(34)))
    }

    @Test
    @Config(sdk = [23])
    fun `데이터 절약 모드 API 24 미만은 Satisfied`() {
        assertEquals(RequirementStatus.Satisfied, manager().check(SystemRequirements.dataSaverOff))
    }

    // ---- 절전·백그라운드 제한·대기 버킷

    @Test
    @Config(sdk = [23, 30, 35])
    fun `절전 모드`() {
        val r = SystemRequirements.powerSaveOff
        val power = shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager)
        power.setIsPowerSaveMode(true)
        assertEquals(RequirementStatus.Disabled, manager().check(r))
        power.setIsPowerSaveMode(false)
        assertEquals(RequirementStatus.Satisfied, manager().check(r))
        assertEquals(Settings.ACTION_BATTERY_SAVER_SETTINGS, enableIntent(r)!!.action)
        assertEquals(RequirementStatus.Satisfied, r.status(noServices, PermissionEnvironment.of(34)))
    }

    @Test
    @Config(sdk = [28, 31, 35])
    fun `백그라운드 제한은 앱 상세 설정 화면을 연다`() {
        val r = SystemRequirements.backgroundUnrestricted
        val am = shadowOf(app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
        am.setBackgroundRestricted(true)
        assertEquals(RequirementStatus.Disabled, manager().check(r))
        am.setBackgroundRestricted(false)
        assertEquals(RequirementStatus.Satisfied, manager().check(r))
        val intent = enableIntent(r)!!
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
        assertEquals(RequirementStatus.Satisfied, r.status(noServices, PermissionEnvironment.of(34)))
    }

    @Test
    @Config(sdk = [23, 27])
    fun `API 28 미만은 백그라운드 제한과 대기 버킷이 없어 Satisfied`() {
        assertEquals(RequirementStatus.Satisfied, manager().check(SystemRequirements.backgroundUnrestricted))
        assertEquals(RequirementStatus.Satisfied, manager().check(SystemRequirements.standbyBucket(10)))
    }

    @Test
    fun `대기 버킷 인자 검사`() {
        for (bad in listOf(Int.MIN_VALUE, -1, 0, 5, 15, 41, 50, Int.MAX_VALUE)) {
            val e = assertThrows(IllegalArgumentException::class.java) { SystemRequirements.standbyBucket(bad) }
            assertTrue(e.message!!.contains(": $bad"))
            assertTrue(e.message!!.contains("[10, 20, 30, 40, 45]"))
        }
        for (ok in listOf(10, 20, 30, 40, 45)) assertEquals("standby_bucket.$ok", SystemRequirements.standbyBucket(ok).id)
    }

    @Test
    @Config(sdk = [28, 31, 35])
    fun `대기 버킷은 조회만 하고 넘으면 Unmet 이며 요청기는 건너뛴다`() {
        val usage = shadowOf(app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
        usage.setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RESTRICTED)
        assertEquals(RequirementStatus.Unmet, manager().check(SystemRequirements.standbyBucket()))
        assertEquals(RequirementStatus.Satisfied, manager().check(SystemRequirements.standbyBucket(45)))
        usage.setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_WORKING_SET)
        assertEquals(RequirementStatus.Satisfied, manager().check(SystemRequirements.standbyBucket()))
        assertEquals(RequirementStatus.Unmet, manager().check(SystemRequirements.standbyBucket(10)))
        assertNull(enableIntent(SystemRequirements.standbyBucket()))
        assertEquals(RequirementStatus.Satisfied, SystemRequirements.standbyBucket(10).status(noServices, PermissionEnvironment.of(34)))

        // 요청기 — 해결 화면이 없으니 아무것도 열지 않고 끝난다
        val registry = FakeRegistry()
        var result: MultiplePermissionsStatus? = null
        val r = manager().createRequester(registry, "b", { null }, emptyList(), requirements = listOf(SystemRequirements.standbyBucket(10))) {
            result = it
        }
        assertTrue(r.launch())
        assertTrue(registry.launches.isEmpty())
        assertEquals(RequirementStatus.Unmet, result!![SystemRequirements.standbyBucket(10)])
        assertFalse(result!!.isReady)
        assertTrue(result!!.pendingRequirements.isEmpty())
    }

    // ---- 방해 금지·화면 잠금

    @Test
    @Config(sdk = [23, 30, 35])
    fun `방해 금지`() {
        val r = SystemRequirements.doNotDisturbOff
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        for (filter in listOf(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
        )) {
            nm.setInterruptionFilter(filter)
            assertEquals("$filter", RequirementStatus.Disabled, manager().check(r))
        }
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        assertEquals(RequirementStatus.Satisfied, manager().check(r))
        assertEquals(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, enableIntent(r)!!.action)
        assertEquals(RequirementStatus.Satisfied, r.status(noServices, PermissionEnvironment.of(34)))
    }

    @Test
    @Config(sdk = [23, 30, 35])
    fun `화면 잠금`() {
        val r = SystemRequirements.deviceSecure
        val keyguard = shadowOf(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
        keyguard.setIsDeviceSecure(false)
        assertEquals(RequirementStatus.Disabled, manager().check(r))
        keyguard.setIsDeviceSecure(true)
        assertEquals(RequirementStatus.Satisfied, manager().check(r))
        assertEquals(DevicePolicyManager.ACTION_SET_NEW_PASSWORD, enableIntent(r)!!.action)
        assertEquals(RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE), r.status(noServices, PermissionEnvironment.of(34)))
    }

    // ---- 생체 인증

    @Test
    fun `생체 인증 결과 코드를 상태로 바꾼다`() {
        assertEquals(RequirementStatus.Satisfied, BiometricRequirement.statusOf(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(RequirementStatus.Disabled, BiometricRequirement.statusOf(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        val noHardware = RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE)
        assertEquals(noHardware, BiometricRequirement.statusOf(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertEquals(RequirementStatus.Unmet, BiometricRequirement.statusOf(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
        assertEquals(RequirementStatus.Unmet, BiometricRequirement.statusOf(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED))
        assertEquals(RequirementStatus.Unmet, BiometricRequirement.statusOf(-1))
        assertEquals(RequirementStatus.Unmet, BiometricRequirement.statusOf(Int.MAX_VALUE))
    }

    @Test
    @Config(sdk = [29, 30, 34, 35])
    fun `생체 인증은 BiometricManager 를 따르고 등록 화면을 연다`() {
        for (strong in listOf(false, true)) {
            val r = SystemRequirements.biometricEnrolled(strong)
            val bm = app.getSystemService(Context.BIOMETRIC_SERVICE) as BiometricManager
            org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowBiometricManager>(bm).setCanAuthenticate(true)
            val authenticators = if (strong) BiometricManager.Authenticators.BIOMETRIC_STRONG else BiometricManager.Authenticators.BIOMETRIC_WEAK
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 30) bm.canAuthenticate(authenticators) else bm.canAuthenticate()
            assertEquals(BiometricRequirement.statusOf(code), manager().check(r))
            org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowBiometricManager>(bm).setCanAuthenticate(false)
            @Suppress("DEPRECATION")
            val off = if (Build.VERSION.SDK_INT >= 30) bm.canAuthenticate(authenticators) else bm.canAuthenticate()
            assertEquals(BiometricRequirement.statusOf(off), manager().check(r))
            val intent = enableIntent(r)!!
            if (Build.VERSION.SDK_INT >= 30) {
                assertEquals(Settings.ACTION_BIOMETRIC_ENROLL, intent.action)
                assertEquals(authenticators, intent.getIntExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, 0))
            } else {
                assertEquals(Settings.ACTION_SECURITY_SETTINGS, intent.action)
            }
            assertEquals(RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE), r.status(noServices, PermissionEnvironment.of(34)))
        }
    }

    @Test
    @Config(sdk = [23, 28])
    fun `생체 인증 API 29 미만은 Unsupported API_LEVEL`() {
        assertEquals(RequirementStatus.Unsupported(UnsupportedReason.API_LEVEL), manager().check(SystemRequirements.biometricEnrolled()))
        assertEquals(Settings.ACTION_SECURITY_SETTINGS, enableIntent(SystemRequirements.biometricEnrolled())!!.action)
    }

    // ---- GPS·자동 시간·컴패니언

    @Test
    @Config(sdk = [23, 29, 35])
    fun `GPS 공급자는 위치와 따로 본다`() {
        val r = SystemRequirements.gpsEnabled
        val pm = shadowOf(app.packageManager)
        pm.setSystemFeature(PackageManager.FEATURE_LOCATION_GPS, false)
        assertEquals(RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE), manager().check(r))
        pm.setSystemFeature(PackageManager.FEATURE_LOCATION_GPS, true)
        val lm = shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        lm.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        assertEquals(RequirementStatus.Disabled, manager().check(r))
        lm.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        assertEquals(RequirementStatus.Satisfied, manager().check(r))
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, enableIntent(r)!!.action)
        assertEquals(RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE), r.status(noServices, PermissionEnvironment.of(34)))
    }

    @Test
    @Config(sdk = [23, 35])
    fun `자동 시간과 자동 시간대`() {
        val time = SystemRequirements.autoTimeEnabled()
        val zone = SystemRequirements.autoTimeEnabled(includeTimeZone = true)
        global(Settings.Global.AUTO_TIME, 1)
        global(Settings.Global.AUTO_TIME_ZONE, 0)
        assertEquals(RequirementStatus.Satisfied, manager().check(time))
        assertEquals(RequirementStatus.Disabled, manager().check(zone))
        global(Settings.Global.AUTO_TIME_ZONE, 1)
        assertEquals(RequirementStatus.Satisfied, manager().check(zone))
        global(Settings.Global.AUTO_TIME, 0)
        assertEquals(RequirementStatus.Disabled, manager().check(time))
        assertEquals(RequirementStatus.Disabled, manager().check(zone))
        assertEquals(Settings.ACTION_DATE_SETTINGS, enableIntent(zone)!!.action)
    }

    @Test
    @Config(sdk = [26, 32, 33, 35])
    fun `컴패니언 연결은 상태만 알린다`() {
        val r = SystemRequirements.companionDeviceAssociated
        val pm = shadowOf(app.packageManager)
        pm.setSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP, false)
        assertEquals(RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE), manager().check(r))
        pm.setSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP, true)
        assertEquals(RequirementStatus.Unmet, manager().check(r))
        shadowOf(app.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager).addAssociation("00:11:22:33:44:55")
        assertEquals(RequirementStatus.Satisfied, manager().check(r))
        assertNull(enableIntent(r))
        assertEquals(RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE), r.status(noServices, PermissionEnvironment.of(34)))
    }

    @Test
    @Config(sdk = [23, 25])
    fun `컴패니언 연결 API 26 미만은 Unsupported API_LEVEL`() {
        assertEquals(RequirementStatus.Unsupported(UnsupportedReason.API_LEVEL), manager().check(SystemRequirements.companionDeviceAssociated))
    }

    // ---- Unmet 의 성질

    @Test
    fun `Unmet 은 켜짐도 켜기 요청 대상도 아니고 관리자가 권한 선행으로 바꾸지 않는다`() {
        assertFalse(RequirementStatus.Unmet.isSatisfied)
        assertFalse(RequirementStatus.Unmet.canRequestEnable)
        assertEquals("Unmet", RequirementStatus.Unmet.toString())
        val fake = FakeRequirement(status = RequirementStatus.Unmet, runtime = listOf("x.permission.NEED"))
        assertEquals(RequirementStatus.Unmet, manager().check(fake))
        val registry = FakeRegistry()
        var result: MultiplePermissionsStatus? = null
        manager(ALL_DECLARED + "x.permission.NEED").createRequester(registry, "u", { null }, emptyList(), requirements = listOf(fake)) {
            result = it
        }.launch()
        assertTrue(registry.launches.isEmpty())
        assertEquals(0, fake.enableCalls)
        assertEquals(RequirementStatus.Unmet, result!![fake])
    }

    private companion object {
        const val NETWORK = "android.permission.ACCESS_NETWORK_STATE"
        const val BIOMETRIC = "android.permission.USE_BIOMETRIC"
    }
}
