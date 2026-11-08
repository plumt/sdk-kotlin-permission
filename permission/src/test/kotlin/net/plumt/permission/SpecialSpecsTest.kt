package net.plumt.permission

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSettings

/** 정확한 알람·다른 앱 위에 표시·알 수 없는 앱 설치 — API 레벨별 적용 여부, 실제 판정, 설정 복귀 재판정 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpecialSpecsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager: PermissionManager
        get() = PermissionManager.create(app, PermissionConfig(RequestHistoryStore.inMemory(), ManifestCheck.Off))

    private fun alarm() = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // ---- 정확한 알람

    @Test
    fun `정확한 알람은 API 31 부터 적용되고 선언 권한과 id 를 밝힌다`() {
        val spec = PermissionSpecs.exactAlarm
        for (sdk in 23..30) assertFalse(spec.isApplicable(PermissionEnvironment.of(sdk)))
        for (sdk in 31..36) assertTrue(spec.isApplicable(PermissionEnvironment.of(sdk)))
        assertEquals("android.permission.SCHEDULE_EXACT_ALARM", spec.manifestPermission)
        assertEquals("exact_alarm", spec.id)
        assertEquals("ExactAlarmSpec", spec.toString())
    }

    @Test
    @Config(sdk = [28, 30])
    fun `정확한 알람 API 31 미만은 NotApplicable 이고 직접 물으면 제약 없음`() {
        assertEquals(PermissionStatus.NotApplicable(android.os.Build.VERSION.SDK_INT), manager.check(PermissionSpecs.exactAlarm))
        assertTrue(PermissionSpecs.exactAlarm.isGranted(app))
    }

    @Test
    @Config(sdk = [31, 33, 35])
    fun `정확한 알람 API 31 이상은 AlarmManager 값을 따른다`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(PermissionStatus.Denied(false), manager.check(PermissionSpecs.exactAlarm))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertEquals(PermissionStatus.Granted, manager.check(PermissionSpecs.exactAlarm))
        assertTrue(alarm().canScheduleExactAlarms())
    }

    @Test
    @Config(sdk = [34])
    fun `정확한 알람 설정 인텐트는 이 패키지의 알람 화면`() {
        val intent = PermissionSpecs.exactAlarm.settingsIntent(app)
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    // ---- 다른 앱 위에 표시

    @Test
    fun `오버레이는 모든 API 레벨에 적용된다`() {
        val spec = PermissionSpecs.overlay
        for (sdk in 23..36) assertTrue(spec.isApplicable(PermissionEnvironment.of(sdk)))
        assertEquals("android.permission.SYSTEM_ALERT_WINDOW", spec.manifestPermission)
        assertEquals("overlay", spec.id)
        assertEquals("OverlaySpec", spec.toString())
    }

    @Test
    @Config(sdk = [28, 31, 35])
    fun `오버레이는 canDrawOverlays 값을 따른다`() {
        ShadowSettings.setCanDrawOverlays(false)
        assertEquals(PermissionStatus.Denied(false), manager.check(PermissionSpecs.overlay))
        ShadowSettings.setCanDrawOverlays(true)
        assertEquals(PermissionStatus.Granted, manager.check(PermissionSpecs.overlay))
        val intent = PermissionSpecs.overlay.settingsIntent(app)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    // ---- 알 수 없는 앱 설치

    @Test
    fun `알 수 없는 앱 설치는 API 26 부터 적용된다`() {
        val spec = PermissionSpecs.installUnknownApps
        for (sdk in 23..25) assertFalse(spec.isApplicable(PermissionEnvironment.of(sdk)))
        for (sdk in 26..36) assertTrue(spec.isApplicable(PermissionEnvironment.of(sdk)))
        assertEquals("android.permission.REQUEST_INSTALL_PACKAGES", spec.manifestPermission)
        assertEquals("install_unknown_apps", spec.id)
        assertEquals("InstallUnknownAppsSpec", spec.toString())
    }

    @Test
    @Config(sdk = [34])
    fun `알 수 없는 앱 설치 API 26 미만 환경은 NotApplicable`() {
        val m = fakeManager(app, FakeDevice(), sdkInt = 25)
        assertEquals(PermissionStatus.NotApplicable(25), m.check(PermissionSpecs.installUnknownApps))
    }

    @Test
    @Config(sdk = [28, 31, 35])
    fun `알 수 없는 앱 설치는 canRequestPackageInstalls 값을 따른다`() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        assertEquals(PermissionStatus.Denied(false), manager.check(PermissionSpecs.installUnknownApps))
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        assertEquals(PermissionStatus.Granted, manager.check(PermissionSpecs.installUnknownApps))
        val intent = PermissionSpecs.installUnknownApps.settingsIntent(app)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    // ---- 설정 화면 복귀 재판정 (요청기)

    @Test
    @Config(sdk = [34])
    fun `세 특수 권한을 차례로 열고 돌아올 때마다 다음으로 가며 마지막에 다시 판정한다`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        ShadowSettings.setCanDrawOverlays(false)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val registry = FakeRegistry()
        val results = mutableListOf<MultiplePermissionsStatus>()
        val specs = listOf(PermissionSpecs.exactAlarm, PermissionSpecs.overlay, PermissionSpecs.installUnknownApps)
        val r = fakeManager(app, FakeDevice(), sdkInt = 34).createRequester(registry, "s", { activity }, specs) { results += it }

        r.launch()
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, registry.lastIntent.action)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        registry.respondSettings()
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, registry.lastIntent.action)
        ShadowSettings.setCanDrawOverlays(true)
        registry.respondSettings()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, registry.lastIntent.action)
        assertTrue(results.isEmpty())
        // 사용자가 마지막 것은 켜지 않고 돌아왔다
        registry.respondSettings()

        val result = results.single()
        assertEquals(PermissionStatus.Granted, result[PermissionSpecs.exactAlarm])
        assertEquals(PermissionStatus.Granted, result[PermissionSpecs.overlay])
        assertEquals(PermissionStatus.Denied(false), result[PermissionSpecs.installUnknownApps])
        assertFalse(r.isRequesting)

        // 다시 요청하면 남은 하나만 연다
        r.launch()
        assertEquals(4, registry.launches.size)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, registry.lastIntent.action)
    }

    @Test
    @Config(sdk = [30])
    fun `API 30 에서 정확한 알람은 열지 않고 나머지만 연다`() {
        ShadowSettings.setCanDrawOverlays(false)
        val registry = FakeRegistry()
        val m = PermissionManager.create(app, PermissionConfig(RequestHistoryStore.inMemory(), ManifestCheck.Off))
        val results = mutableListOf<MultiplePermissionsStatus>()
        val r = m.createRequester(registry, "s", { null }, listOf(PermissionSpecs.exactAlarm, PermissionSpecs.overlay)) { results += it }
        r.launch()
        assertEquals(1, registry.launches.size)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, registry.lastIntent.action)
        registry.respondSettings()
        assertEquals(PermissionStatus.NotApplicable(30), results.single()[PermissionSpecs.exactAlarm])
    }

    @Test
    @Config(sdk = [34])
    fun `특수 권한의 매니페스트 선언이 없으면 디버그 빌드에서 IllegalStateException`() {
        val m = fakeManager(app, FakeDevice(), sdkInt = 34, declared = emptySet())
        val registry = FakeRegistry()
        for (spec in listOf(PermissionSpecs.exactAlarm, PermissionSpecs.overlay, PermissionSpecs.installUnknownApps)) {
            val r = m.createRequester(registry, spec.id, { null }, listOf(spec)) {}
            val e = org.junit.Assert.assertThrows(IllegalStateException::class.java) { r.launch() }
            assertTrue(e.message!!.contains(spec.manifestPermission!!))
        }
        assertTrue(registry.launches.isEmpty())
    }

    @Test
    fun `특수 명세의 id 는 서로와 다른 내장 명세와 다르다`() {
        val ids = listOf(
            PermissionSpecs.exactAlarm, PermissionSpecs.overlay, PermissionSpecs.installUnknownApps,
            PermissionSpecs.ignoreBatteryOptimizations,
        ).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertNotEquals(PermissionSpecs.overlay, PermissionSpecs.exactAlarm)
    }
}
