package net.plumt.permission

import android.app.AppOpsManager
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
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

/** 새 특수 권한 — API 레벨별 적용 여부, 실제 판정, 설정 인텐트, 서비스 선언 확인 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoreSpecialSpecsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager: PermissionManager
        get() = PermissionManager.create(app, PermissionConfig(RequestHistoryStore.inMemory(), ManifestCheck.Off))

    private fun appOps() = shadowOf(app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)

    private fun setOp(op: String, mode: Int) = appOps().setMode(op, Process.myUid(), app.packageName, mode)

    private fun applicableFrom(spec: SpecialPermissionSpec, min: Int) {
        for (sdk in 23..36) assertEquals("${spec.id}@$sdk", sdk >= min, spec.isApplicable(PermissionEnvironment.of(sdk)))
    }

    @Test
    fun `적용 API 와 선언 권한과 id`() {
        applicableFrom(PermissionSpecs.allFilesAccess, 30)
        applicableFrom(PermissionSpecs.usageStats, 1)
        applicableFrom(PermissionSpecs.notificationPolicy, 1)
        applicableFrom(PermissionSpecs.writeSettings, 1)
        applicableFrom(PermissionSpecs.fullScreenIntent, 34)
        applicableFrom(PermissionSpecs.manageMedia, 31)
        applicableFrom(PermissionSpecs.notificationListener, 1)
        applicableFrom(PermissionSpecs.accessibilityService, 1)
        val expected = mapOf(
            PermissionSpecs.allFilesAccess to "android.permission.MANAGE_EXTERNAL_STORAGE",
            PermissionSpecs.usageStats to "android.permission.PACKAGE_USAGE_STATS",
            PermissionSpecs.notificationPolicy to "android.permission.ACCESS_NOTIFICATION_POLICY",
            PermissionSpecs.writeSettings to "android.permission.WRITE_SETTINGS",
            PermissionSpecs.fullScreenIntent to "android.permission.USE_FULL_SCREEN_INTENT",
            PermissionSpecs.manageMedia to "android.permission.MANAGE_MEDIA",
        )
        for ((spec, permission) in expected) assertEquals(permission, spec.manifestPermission)
        assertNull(PermissionSpecs.notificationListener.manifestPermission)
        assertNull(PermissionSpecs.accessibilityService.manifestPermission)
        val all = expected.keys + PermissionSpecs.notificationListener + PermissionSpecs.accessibilityService
        for (spec in all) assertTrue(spec.toString().endsWith("Spec"))
    }

    // ---- 전체 파일 접근

    @Test
    @Config(sdk = [30, 34, 35])
    fun `전체 파일 접근은 AppOps 모드를 따르고 이 앱 화면을 연다`() {
        setOp("android:manage_external_storage", AppOpsManager.MODE_ERRORED)
        assertEquals(PermissionStatus.Denied(false), manager.check(PermissionSpecs.allFilesAccess))
        setOp("android:manage_external_storage", AppOpsManager.MODE_ALLOWED)
        assertEquals(PermissionStatus.Granted, manager.check(PermissionSpecs.allFilesAccess))
        val intent = PermissionSpecs.allFilesAccess.settingsIntent(app)
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    @Test
    @Config(sdk = [28, 29])
    fun `전체 파일 접근 API 30 미만은 NotApplicable 이고 직접 물으면 옛 쓰기 권한`() {
        assertEquals(PermissionStatus.NotApplicable(Build.VERSION.SDK_INT), manager.check(PermissionSpecs.allFilesAccess))
        assertFalse(PermissionSpecs.allFilesAccess.isGranted(app))
        shadowOf(app).grantPermissions("android.permission.WRITE_EXTERNAL_STORAGE")
        assertTrue(PermissionSpecs.allFilesAccess.isGranted(app))
    }

    // ---- 사용 정보 접근

    @Test
    @Config(sdk = [23, 28, 29, 34])
    fun `사용 정보 접근은 AppOps 모드와 기본 모드의 권한 보유를 따른다`() {
        setOp(AppOpsManager.OPSTR_GET_USAGE_STATS, AppOpsManager.MODE_IGNORED)
        assertEquals(PermissionStatus.Denied(false), manager.check(PermissionSpecs.usageStats))
        setOp(AppOpsManager.OPSTR_GET_USAGE_STATS, AppOpsManager.MODE_ALLOWED)
        assertEquals(PermissionStatus.Granted, manager.check(PermissionSpecs.usageStats))
        setOp(AppOpsManager.OPSTR_GET_USAGE_STATS, AppOpsManager.MODE_DEFAULT)
        shadowOf(app).denyPermissions("android.permission.PACKAGE_USAGE_STATS")
        assertFalse(PermissionSpecs.usageStats.isGranted(app))
        shadowOf(app).grantPermissions("android.permission.PACKAGE_USAGE_STATS")
        assertTrue(PermissionSpecs.usageStats.isGranted(app))
        val intent = PermissionSpecs.usageStats.settingsIntent(app)
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, intent.action)
        // 옛 설정 앱은 package: 를 받지 못한다
        assertEquals(if (Build.VERSION.SDK_INT >= 29) "package:${app.packageName}" else null, intent.dataString)
    }

    // ---- 방해 금지

    @Test
    @Config(sdk = [23, 30, 35])
    fun `방해 금지 정책 접근은 NotificationManager 값을 따른다`() {
        val nm = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        nm.setNotificationPolicyAccessGranted(false)
        assertEquals(PermissionStatus.Denied(false), manager.check(PermissionSpecs.notificationPolicy))
        nm.setNotificationPolicyAccessGranted(true)
        assertEquals(PermissionStatus.Granted, manager.check(PermissionSpecs.notificationPolicy))
        assertEquals(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, PermissionSpecs.notificationPolicy.settingsIntent(app).action)
    }

    // ---- 시스템 설정 쓰기

    @Test
    @Config(sdk = [23, 30, 35])
    fun `시스템 설정 쓰기는 Settings_System_canWrite 를 따르고 이 앱 화면을 연다`() {
        setOp(AppOpsManager.OPSTR_WRITE_SETTINGS, AppOpsManager.MODE_ERRORED)
        assertEquals(PermissionSpecs.writeSettings.isGranted(app), Settings.System.canWrite(app))
        val intent = PermissionSpecs.writeSettings.settingsIntent(app)
        assertEquals(Settings.ACTION_MANAGE_WRITE_SETTINGS, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    // ---- 전체 화면 인텐트

    @Test
    @Config(sdk = [26, 33])
    fun `전체 화면 인텐트 API 34 미만은 NotApplicable 이고 직접 물으면 설치 시 권한 보유`() {
        assertEquals(PermissionStatus.NotApplicable(Build.VERSION.SDK_INT), manager.check(PermissionSpecs.fullScreenIntent))
        shadowOf(app).denyPermissions("android.permission.USE_FULL_SCREEN_INTENT")
        assertFalse(PermissionSpecs.fullScreenIntent.isGranted(app))
        shadowOf(app).grantPermissions("android.permission.USE_FULL_SCREEN_INTENT")
        assertTrue(PermissionSpecs.fullScreenIntent.isGranted(app))
    }

    @Test
    @Config(sdk = [34, 35])
    fun `전체 화면 인텐트 API 34 이상은 canUseFullScreenIntent 를 따르고 이 앱 화면을 연다`() {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(nm.canUseFullScreenIntent(), PermissionSpecs.fullScreenIntent.isGranted(app))
        val intent = PermissionSpecs.fullScreenIntent.settingsIntent(app)
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    // ---- 미디어 관리

    @Test
    @Config(sdk = [31, 34])
    fun `미디어 관리 API 31 이상은 MediaStore_canManageMedia 를 따르고 이 앱 화면을 연다`() {
        assertEquals(android.provider.MediaStore.canManageMedia(app), PermissionSpecs.manageMedia.isGranted(app))
        val intent = PermissionSpecs.manageMedia.settingsIntent(app)
        assertEquals(Settings.ACTION_REQUEST_MANAGE_MEDIA, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    @Test
    @Config(sdk = [30])
    fun `미디어 관리 API 31 미만은 NotApplicable 이고 직접 물으면 거짓`() {
        assertEquals(PermissionStatus.NotApplicable(30), manager.check(PermissionSpecs.manageMedia))
        assertFalse(PermissionSpecs.manageMedia.isGranted(app))
    }

    // ---- 알림 접근

    private fun declareService(action: String, permission: String, name: String) {
        val component = ComponentName(app.packageName, name)
        val pm = shadowOf(app.packageManager)
        val info = ServiceInfo().apply {
            packageName = app.packageName
            this.name = name
            this.permission = permission
            applicationInfo = app.applicationInfo
        }
        pm.addOrUpdateService(info)
        pm.addIntentFilterForService(component, android.content.IntentFilter(action))
    }

    @Test
    @Config(sdk = [29, 30, 34])
    fun `알림 접근은 켜진 리스너 패키지를 따르고 서비스가 없으면 선언 누락을 알린다`() {
        val spec = PermissionSpecs.notificationListener
        assertEquals(1, spec.missingDeclarations(app).size)
        assertTrue(spec.missingDeclarations(app).single().contains("android.service.notification.NotificationListenerService"))
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, spec.settingsIntent(app).action)

        declareService(
            "android.service.notification.NotificationListenerService",
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            "x.Listener",
        )
        assertEquals(emptyList<String>(), spec.missingDeclarations(app))
        val intent = spec.settingsIntent(app)
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, intent.action)
            assertEquals("${app.packageName}/x.Listener", intent.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME))
        } else {
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, intent.action)
        }

        assertEquals(PermissionStatus.Denied(false), manager.check(spec))
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "${app.packageName}/x.Listener")
        assertEquals(PermissionStatus.Granted, manager.check(spec))
    }

    @Test
    fun `보호 권한이 다른 서비스는 선언으로 치지 않는다`() {
        declareService("android.service.notification.NotificationListenerService", "x.OTHER", "x.Listener")
        assertEquals(1, PermissionSpecs.notificationListener.missingDeclarations(app).size)
    }

    // ---- 접근성

    @Test
    @Config(sdk = [23, 34])
    fun `접근성은 켜진 서비스 목록에 이 앱이 있는지 보고 서비스가 없으면 선언 누락을 알린다`() {
        val spec = PermissionSpecs.accessibilityService
        assertTrue(spec.missingDeclarations(app).single().contains("android.accessibilityservice.AccessibilityService"))
        declareService("android.accessibilityservice.AccessibilityService", "android.permission.BIND_ACCESSIBILITY_SERVICE", "x.A11y")
        assertEquals(emptyList<String>(), spec.missingDeclarations(app))

        assertFalse(spec.isGranted(app))
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "other.pkg/x.S:not a component")
        assertFalse(spec.isGranted(app))
        Settings.Secure.putString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "other.pkg/x.S:${app.packageName}/x.A11y",
        )
        assertTrue(spec.isGranted(app))
        assertEquals(PermissionStatus.Granted, manager.check(spec))
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, spec.settingsIntent(app).action)
    }

    // ---- 요청기의 선언 확인

    @Test
    fun `서비스 선언이 없는 특수 권한을 요청하면 디버그 빌드에서 IllegalStateException`() {
        // NotificationManagerCompat 은 켜진 리스너 목록을 프로세스 정적 값으로 캐시한다 — 앞 테스트 값을 지운다
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
        val m = fakeManager(app, FakeDevice(), sdkInt = 34)
        val registry = FakeRegistry()
        val r = m.createRequester(registry, "n", { null }, listOf(PermissionSpecs.notificationListener)) {}
        val e = assertThrows(IllegalStateException::class.java) { r.launch() }
        assertTrue(e.message!!.contains("BIND_NOTIFICATION_LISTENER_SERVICE"))
        assertTrue(registry.launches.isEmpty())
    }

    @Test
    fun `소비자 특수 명세의 선언 확인이 실패하면 PermissionException`() {
        val failing = object : SpecialPermissionSpec {
            override val id = "failing"
            override val manifestPermission: String? = null
            override fun isApplicable(environment: PermissionEnvironment) = true
            override fun isGranted(context: Context) = false
            override fun settingsIntent(context: Context) = android.content.Intent("x")
            override fun missingDeclarations(context: Context): List<String> = throw IllegalStateException("boom")
        }
        val r = fakeManager(app, FakeDevice()).createRequester(FakeRegistry(), "f", { null }, listOf(failing)) {}
        val e = assertThrows(PermissionException::class.java) { r.launch() }
        assertEquals("boom", e.cause!!.message)
        // 기본 구현은 빈 목록
        assertEquals(emptyList<String>(), FakeSpecialSpec().missingDeclarations(app))
    }
}
