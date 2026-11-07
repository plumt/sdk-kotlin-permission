package net.plumt.permission

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import androidx.activity.result.IntentSenderRequest
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNfcAdapter

/** 시스템 기능 요구 조건 — 내장 판정(API 여러 개), 권한 선행, 요청 흐름(켜기·재요청·비동기·IntentSender), 상태 묶음 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RequirementsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val device = FakeDevice()

    private fun manager(sdkInt: Int = Build.VERSION.SDK_INT, declared: Set<String> = ALL_DECLARED) =
        fakeManager(app, device, sdkInt = sdkInt, declared = declared)

    private fun feature(name: String, has: Boolean) = shadowOf(app.packageManager).setSystemFeature(name, has)

    private fun bluetooth() = shadowOf((app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter)

    // ---- 블루투스

    @Test
    @Config(sdk = [23, 30, 31, 34, 35])
    fun `블루투스 켜짐은 어댑터 상태를 따르고 API 31 이상은 켜기 전에 BLUETOOTH_CONNECT 가 필요하다`() {
        feature(PackageManager.FEATURE_BLUETOOTH, true)
        val m = manager()
        val r = SystemRequirements.bluetoothEnabled
        bluetooth().setEnabled(true)
        assertEquals(RequirementStatus.Satisfied, m.check(r))
        bluetooth().setEnabled(false)
        if (Build.VERSION.SDK_INT >= 31) {
            assertEquals(RequirementStatus.PermissionRequired(setOf("android.permission.BLUETOOTH_CONNECT")), m.check(r))
            device.grant("android.permission.BLUETOOTH_CONNECT")
        }
        assertEquals(RequirementStatus.Disabled, m.check(r))
        val env = m.environment
        val expectedDeclared = if (Build.VERSION.SDK_INT >= 31) "android.permission.BLUETOOTH_CONNECT" else "android.permission.BLUETOOTH"
        assertEquals(listOf(expectedDeclared), r.declaredPermissions(env))
        var request: EnableRequest? = null
        r.enableRequest(app) { request = it }
        assertEquals(BluetoothAdapter.ACTION_REQUEST_ENABLE, (request as EnableRequest.OfIntent).intent.action)
        assertEquals("bluetooth_enabled", r.id)
        assertEquals("BluetoothEnabledRequirement", r.toString())
    }

    @Test
    fun `블루투스 targetSdk 30 앱은 API 34 기기에서도 옛 권한 모델`() {
        val env = PermissionEnvironment.of(34, targetSdkInt = 30)
        assertEquals(emptyList<String>(), SystemRequirements.bluetoothEnabled.permissions(env))
        assertEquals(listOf("android.permission.BLUETOOTH"), SystemRequirements.bluetoothEnabled.declaredPermissions(env))
    }

    @Test
    fun `블루투스 하드웨어가 없으면 Unsupported(NO_HARDWARE)`() {
        feature(PackageManager.FEATURE_BLUETOOTH, false)
        assertEquals(RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE), manager().check(SystemRequirements.bluetoothEnabled))
    }

    // ---- 위치 서비스

    @Test
    @Config(sdk = [23, 27, 28, 34, 35])
    fun `위치 서비스 켜짐은 LocationManager 를 따르고 위치 설정 화면을 연다`() {
        feature(PackageManager.FEATURE_LOCATION, true)
        val lm = shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        lm.setLocationEnabled(false)
        assertEquals(RequirementStatus.Disabled, manager().check(SystemRequirements.locationEnabled))
        lm.setLocationEnabled(true)
        assertEquals(RequirementStatus.Satisfied, manager().check(SystemRequirements.locationEnabled))
        var request: EnableRequest? = null
        SystemRequirements.locationEnabled.enableRequest(app) { request = it }
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, (request as EnableRequest.OfIntent).intent.action)
        assertEquals(emptyList<String>(), SystemRequirements.locationEnabled.declaredPermissions(PermissionEnvironment.of(34)))
        feature(PackageManager.FEATURE_LOCATION, false)
        assertEquals(RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE), manager().check(SystemRequirements.locationEnabled))
    }

    // ---- NFC

    @Test
    @Config(sdk = [28, 29, 34])
    fun `NFC 켜짐은 어댑터를 따르고 API 29 이상은 NFC 패널을 연다`() {
        ShadowNfcAdapter.setNfcHardwareExists(true)
        feature(PackageManager.FEATURE_NFC, true)
        val adapter = NfcAdapter.getDefaultAdapter(app)
        shadowOf(adapter).setEnabled(false)
        assertEquals(RequirementStatus.Disabled, manager().check(SystemRequirements.nfcEnabled))
        shadowOf(adapter).setEnabled(true)
        assertEquals(RequirementStatus.Satisfied, manager().check(SystemRequirements.nfcEnabled))
        var request: EnableRequest? = null
        SystemRequirements.nfcEnabled.enableRequest(app) { request = it }
        val expected = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
        assertEquals(expected, (request as EnableRequest.OfIntent).intent.action)
    }

    @Test
    fun `NFC 하드웨어가 없으면 Unsupported`() {
        ShadowNfcAdapter.setNfcHardwareExists(false)
        assertEquals(RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE), manager().check(SystemRequirements.nfcEnabled))
    }

    // ---- Wi-Fi

    @Test
    @Config(sdk = [28, 29, 34])
    fun `Wi-Fi 켜짐은 WifiManager 를 따르고 ACCESS_WIFI_STATE 선언을 요구한다`() {
        feature(PackageManager.FEATURE_WIFI, true)
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        shadowOf(wm).setWifiState(WifiManager.WIFI_STATE_DISABLED)
        assertEquals(RequirementStatus.Disabled, manager().check(SystemRequirements.wifiEnabled))
        shadowOf(wm).setWifiState(WifiManager.WIFI_STATE_ENABLED)
        assertEquals(RequirementStatus.Satisfied, manager().check(SystemRequirements.wifiEnabled))
        shadowOf(wm).setAccessWifiStatePermission(false)
        assertEquals(
            RequirementStatus.PermissionRequired(setOf("android.permission.ACCESS_WIFI_STATE")),
            manager().check(SystemRequirements.wifiEnabled),
        )
        assertEquals(
            listOf("android.permission.ACCESS_WIFI_STATE"),
            SystemRequirements.wifiEnabled.declaredPermissions(PermissionEnvironment.of(34)),
        )
        var request: EnableRequest? = null
        SystemRequirements.wifiEnabled.enableRequest(app) { request = it }
        val expected = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
        assertEquals(expected, (request as EnableRequest.OfIntent).intent.action)
        feature(PackageManager.FEATURE_WIFI, false)
        assertEquals(RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE), manager().check(SystemRequirements.wifiEnabled))
    }

    // ---- 앱 알림

    @Test
    @Config(sdk = [25, 26, 32, 33, 34])
    fun `앱 알림 켜짐은 areNotificationsEnabled 를 따르고 API 33 이상은 POST_NOTIFICATIONS 가 먼저 필요하다`() {
        val nm = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        nm.setNotificationsEnabled(true)
        assertEquals(RequirementStatus.Satisfied, manager().check(SystemRequirements.notificationsEnabled))
        nm.setNotificationsEnabled(false)
        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals(
                RequirementStatus.PermissionRequired(setOf("android.permission.POST_NOTIFICATIONS")),
                manager().check(SystemRequirements.notificationsEnabled),
            )
            device.grant("android.permission.POST_NOTIFICATIONS")
        }
        assertEquals(RequirementStatus.Disabled, manager().check(SystemRequirements.notificationsEnabled))
        var request: EnableRequest? = null
        SystemRequirements.notificationsEnabled.enableRequest(app) { request = it }
        val intent = (request as EnableRequest.OfIntent).intent
        if (Build.VERSION.SDK_INT >= 26) {
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
            assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        } else {
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        }
    }

    // ---- 하드웨어

    @Test
    @Config(sdk = [30, 31])
    fun `하드웨어 요구 조건은 기능 유무와 최소 API 를 본다`() {
        val uwb = SystemRequirements.hardware("android.hardware.uwb", minSdk = 31)
        feature("android.hardware.uwb", true)
        val expected = if (Build.VERSION.SDK_INT >= 31) {
            RequirementStatus.Satisfied
        } else {
            RequirementStatus.Unsupported(UnsupportedReason.API_LEVEL)
        }
        assertEquals(expected, manager().check(uwb))
        val camera = SystemRequirements.hardware(PackageManager.FEATURE_CAMERA_ANY)
        feature(PackageManager.FEATURE_CAMERA_ANY, false)
        assertEquals(RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE), manager().check(camera))
        feature(PackageManager.FEATURE_CAMERA_ANY, true)
        assertEquals(RequirementStatus.Satisfied, manager().check(camera))
        var called = false
        camera.enableRequest(app) { called = true; assertEquals(null, it) }
        assertTrue(called)
        assertEquals("hardware:android.hardware.camera.any", camera.id)
        assertEquals(camera, SystemRequirements.hardware(PackageManager.FEATURE_CAMERA_ANY))
        assertNotEquals(camera, uwb)
    }

    @Test
    fun `하드웨어 요구 조건 인자 검사`() {
        assertTrue(assertThrows(IllegalArgumentException::class.java) { SystemRequirements.hardware(" ") }.message!!.contains("feature"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { SystemRequirements.hardware("x", 0) }.message!!.contains("0"))
    }

    @Test
    fun `상태 확장 속성`() {
        assertTrue(RequirementStatus.Satisfied.isSatisfied)
        assertFalse(RequirementStatus.Disabled.isSatisfied)
        assertTrue(RequirementStatus.Disabled.canRequestEnable)
        assertTrue(RequirementStatus.PermissionRequired(setOf("a")).canRequestEnable)
        assertFalse(RequirementStatus.Satisfied.canRequestEnable)
        assertFalse(RequirementStatus.Unsupported(UnsupportedReason.API_LEVEL).canRequestEnable)
        assertEquals(listOf("NO_HARDWARE", "API_LEVEL"), UnsupportedReason.entries.map { it.name })
        assertEquals("EnableRequest.OfIntent(x)", EnableRequest.OfIntent(Intent("x")).toString())
    }

    // ---- 관리자 판정

    @Test
    fun `꺼짐이 아니면 권한이 빠졌어도 상태를 그대로 둔다`() {
        val r = FakeRequirement(status = RequirementStatus.Satisfied, runtime = listOf("x.A"))
        assertEquals(RequirementStatus.Satisfied, manager().check(r))
        r.status = RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE)
        assertEquals(r.status, manager().check(r))
        r.status = RequirementStatus.Disabled
        assertEquals(RequirementStatus.PermissionRequired(setOf("x.A")), manager().check(r))
    }

    @Test
    fun `소비자 요구 조건이 예외를 던지면 PermissionException 이고 원인을 보존한다`() {
        val boom = object : SystemRequirement {
            override val id = "boom"
            override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus = error("status boom")
            override fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit) = Unit
        }
        val e = assertThrows(PermissionException::class.java) { manager().check(boom) }
        assertEquals("status boom", e.cause!!.message)
        assertTrue(e.message!!.contains("boom"))
        // 기본 구현: 권한 없음, 선언 = 권한
        assertEquals(emptyList<String>(), boom.permissions(PermissionEnvironment.of(34)))
        assertEquals(emptyList<String>(), boom.declaredPermissions(PermissionEnvironment.of(34)))
    }

    @Test
    fun `checkAll 은 권한과 요구 조건을 따로 담는다`() {
        val r = FakeRequirement()
        val m = manager()
        val all = m.checkAll(listOf(PermissionSpecs.camera), listOf(r))
        assertEquals(PermissionStatus.Denied(false), all[PermissionSpecs.camera])
        assertEquals(RequirementStatus.Disabled, all[r])
        assertEquals(listOf(r), all.pendingRequirements)
        assertFalse(all.isReady)
        // 요구 조건만
        assertEquals(RequirementStatus.Disabled, m.checkAll(emptyList(), listOf(r))[r])
        // 겹침·비었음
        assertThrows(IllegalArgumentException::class.java) { m.checkAll(emptyList(), emptyList()) }
        val e = assertThrows(IllegalArgumentException::class.java) { m.checkAll(emptyList(), listOf(r, FakeRequirement())) }
        assertTrue(e.message!!.contains("fake.requirement"))
        assertThrows(IllegalArgumentException::class.java) {
            m.checkAll(listOf(PermissionSpecs.camera, PermissionSpecs.camera), listOf(r))
        }
    }

    // ---- 요청 흐름

    @Test
    fun `권한을 받은 뒤 꺼진 기능의 켜기 화면을 열고 돌아오면 다시 판정해 콜백한다`() {
        val r = FakeRequirement()
        val registry = FakeRegistry()
        val results = mutableListOf<MultiplePermissionsStatus>()
        val q = manager().createRequester(registry, "q", { null }, listOf(PermissionSpecs.camera), requirements = listOf(r)) {
            results += it
        }
        assertEquals(listOf(r), q.requirements)
        assertTrue(q.toString().contains("requirements=[fake.requirement]"))
        assertTrue(q.launch())
        assertEquals(listOf("android.permission.CAMERA"), registry.lastPermissions)
        device.grant("android.permission.CAMERA")
        registry.respondPermissions(mapOf("android.permission.CAMERA" to true))
        assertEquals("fake.ENABLE", registry.lastIntent.action)
        assertTrue(results.isEmpty())
        // 사용자가 켜지 않고 돌아왔다
        registry.respondSettings()
        assertEquals(RequirementStatus.Disabled, results.single()[r])
        assertTrue(results.single().allGranted)
        assertFalse(results.single().isReady)

        // 다시 요청하면 켜기 화면만 연다
        assertTrue(q.launch())
        assertEquals(3, registry.launches.size)
        assertEquals("fake.ENABLE", registry.lastIntent.action)
        r.status = RequirementStatus.Satisfied
        registry.respondSettings()
        assertTrue(results.last().isReady)

        // 다 켜졌으면 화면 없이 곧바로 콜백
        assertTrue(q.launch())
        assertEquals(3, registry.launches.size)
        assertEquals(3, results.size)
    }

    @Test
    fun `켜기에 필요한 권한은 런타임 요청에 묶고 거부되면 켜기 화면을 건너뛴다`() {
        val r = FakeRequirement(runtime = listOf("android.permission.BLUETOOTH_CONNECT"))
        val registry = FakeRegistry()
        val results = mutableListOf<MultiplePermissionsStatus>()
        val q = manager().createRequester(registry, "q", { null }, emptyList(), requirements = listOf(r)) { results += it }
        q.launch()
        assertEquals(listOf("android.permission.BLUETOOTH_CONNECT"), registry.lastPermissions)
        registry.respondPermissions(mapOf("android.permission.BLUETOOTH_CONNECT" to false))
        assertEquals(1, registry.launches.size)
        assertEquals(RequirementStatus.PermissionRequired(setOf("android.permission.BLUETOOTH_CONNECT")), results.single()[r])
        assertEquals(0, r.enableCalls)

        // 허용하면 켜기 화면까지 간다
        q.launch()
        device.grant("android.permission.BLUETOOTH_CONNECT")
        registry.respondPermissions(mapOf("android.permission.BLUETOOTH_CONNECT" to true))
        assertEquals("fake.ENABLE", registry.lastIntent.action)
    }

    @Test
    fun `Unsupported 나 Satisfied 요구 조건은 요청하지 않는다`() {
        val unsupported = FakeRequirement("u", RequirementStatus.Unsupported(UnsupportedReason.NO_HARDWARE))
        val satisfied = FakeRequirement("s", RequirementStatus.Satisfied)
        val registry = FakeRegistry()
        val results = mutableListOf<MultiplePermissionsStatus>()
        manager().createRequester(registry, "q", { null }, emptyList(), requirements = listOf(unsupported, satisfied)) { results += it }
            .launch()
        assertTrue(registry.launches.isEmpty())
        assertEquals(1, results.size)
        assertEquals(0, unsupported.enableCalls + satisfied.enableCalls)
    }

    @Test
    fun `요청 시점에 켜졌으면 켜기 화면을 건너뛴다`() {
        val first = FakeRequirement("a")
        val second = FakeRequirement("b")
        val registry = FakeRegistry()
        val results = mutableListOf<MultiplePermissionsStatus>()
        manager().createRequester(registry, "q", { null }, emptyList(), requirements = listOf(first, second)) { results += it }.launch()
        assertEquals(1, registry.launches.size)
        // 첫 화면에서 돌아오는 사이 둘째도 켜졌다
        first.status = RequirementStatus.Satisfied
        second.status = RequirementStatus.Satisfied
        registry.respondSettings()
        assertEquals(1, registry.launches.size)
        assertTrue(results.single().isReady)
        assertEquals(1, second.enableCalls + first.enableCalls)
    }

    @Test
    fun `켜기 요청을 만들 수 없으면(null) 다음으로 넘어간다`() {
        val r = FakeRequirement(request = { null })
        val registry = FakeRegistry()
        val results = mutableListOf<MultiplePermissionsStatus>()
        manager().createRequester(registry, "q", { null }, emptyList(), requirements = listOf(r)) { results += it }.launch()
        assertTrue(registry.launches.isEmpty())
        assertEquals(RequirementStatus.Disabled, results.single()[r])
    }

    @Test
    fun `비동기 켜기 요청은 준비되면 띄우고 흐름이 끝난 뒤 온 준비는 무시한다`() {
        val r = FakeRequirement(async = true)
        val registry = FakeRegistry()
        val results = mutableListOf<MultiplePermissionsStatus>()
        val q = manager().createRequester(registry, "q", { null }, emptyList(), requirements = listOf(r)) { results += it }
        q.launch()
        assertTrue(registry.launches.isEmpty())
        assertTrue(q.isRequesting)
        val ready = r.pending!!
        ready(EnableRequest.OfIntent(Intent("late.ENABLE")))
        assertEquals("late.ENABLE", registry.lastIntent.action)
        // 같은 준비가 두 번 와도 한 번만 띄운다
        ready(EnableRequest.OfIntent(Intent("again")))
        assertEquals(1, registry.launches.size)
        registry.respondSettings()
        assertEquals(1, results.size)

        // 등록을 푼 뒤 온 준비는 무시한다
        q.launch()
        val stale = r.pending!!
        q.unregister()
        stale(EnableRequest.OfIntent(Intent("stale")))
        assertEquals(1, registry.launches.size)
    }

    @Test
    fun `IntentSender 켜기 요청은 IntentSender 런처로 띄운다`() {
        val sender = PendingIntent.getActivity(app, 0, Intent("x"), PendingIntent.FLAG_IMMUTABLE).intentSender
        val request = IntentSenderRequest.Builder(sender).build()
        val r = FakeRequirement(request = { EnableRequest.OfIntentSender(request) })
        val registry = FakeRegistry()
        val results = mutableListOf<MultiplePermissionsStatus>()
        manager().createRequester(registry, "q", { null }, emptyList(), requirements = listOf(r)) { results += it }.launch()
        assertSame(request, registry.launches.single().input)
        r.status = RequirementStatus.Satisfied
        registry.respondSender()
        assertTrue(results.single().isReady)
        assertEquals("EnableRequest.OfIntentSender", EnableRequest.OfIntentSender(request).toString())
    }

    @Test
    fun `켜기 화면을 열 수 없으면 PermissionException 이고 흐름을 끝낸다`() {
        val r = FakeRequirement()
        val registry = FakeRegistry().apply { failIntentLaunches = 1 }
        val q = manager().createRequester(registry, "q", { null }, emptyList(), requirements = listOf(r)) {}
        val e = assertThrows(PermissionException::class.java) { q.launch() }
        assertTrue(e.message!!.contains("fake.requirement"))
        assertTrue(e.cause is android.content.ActivityNotFoundException)
        assertFalse(q.isRequesting)
    }

    @Test
    fun `켜기 요청을 만드는 중 예외면 PermissionException 이고 흐름을 끝낸다`() {
        val r = FakeRequirement(request = null)
        val q = manager().createRequester(FakeRegistry(), "q", { null }, emptyList(), requirements = listOf(r)) {}
        val e = assertThrows(PermissionException::class.java) { q.launch() }
        assertEquals("enable boom", e.cause!!.message)
        assertFalse(q.isRequesting)
    }

    @Test
    fun `요구 조건의 선언 권한이 없으면 디버그 빌드에서 IllegalStateException`() {
        val r = FakeRequirement(declared = listOf("android.permission.BLUETOOTH"))
        val registry = FakeRegistry()
        val q = manager(declared = emptySet()).createRequester(registry, "q", { null }, emptyList(), requirements = listOf(r)) {}
        val e = assertThrows(IllegalStateException::class.java) { q.launch() }
        assertTrue(e.message!!.contains("android.permission.BLUETOOTH"))
        assertTrue(registry.launches.isEmpty())
    }

    @Test
    fun `남은 켜기 화면은 저장 상태로 재생성 뒤에도 이어진다`() {
        val a = FakeRequirement("a")
        val b = FakeRequirement("b")
        val owner = SavedStateOwner()
        val registry = FakeRegistry()
        val results = mutableListOf<MultiplePermissionsStatus>()
        val m = manager()
        m.createRequester(registry, "q", { null }, emptyList(), owner.registry, listOf(a, b)) { results += it }.launch()
        assertEquals(1, registry.launches.size)
        val saved = owner.save()

        val owner2 = SavedStateOwner(saved)
        val registry2 = FakeRegistry()
        val q2 = m.createRequester(registry2, "q", { null }, emptyList(), owner2.registry, listOf(a, b)) { results += it }
        // 첫 화면 결과가 새 화면으로 왔다 — 둘째 켜기 화면이 이어서 열린다
        (q2 as net.plumt.permission.internal.DefaultPermissionRequester).onSettingsResult()
        assertEquals(1, registry2.launches.size)
        b.status = RequirementStatus.Satisfied
        a.status = RequirementStatus.Satisfied
        (q2 as net.plumt.permission.internal.DefaultPermissionRequester).onSettingsResult()
        assertTrue(results.single().isReady)
    }

    // ---- 상태 묶음

    @Test
    fun `상태 묶음은 요구 조건을 함께 비교하고 문자열에 담는다`() {
        val r = FakeRequirement()
        val a = MultiplePermissionsStatus(mapOf(PermissionSpecs.camera to PermissionStatus.Granted), mapOf(r to RequirementStatus.Satisfied))
        val b = MultiplePermissionsStatus(mapOf(PermissionSpecs.camera to PermissionStatus.Granted), mapOf(r to RequirementStatus.Disabled))
        assertNotEquals(a, b)
        assertEquals(a, MultiplePermissionsStatus(mapOf(PermissionSpecs.camera to PermissionStatus.Granted), mapOf(r to RequirementStatus.Satisfied)))
        assertEquals(a.hashCode(), MultiplePermissionsStatus(a.statuses, a.requirements).hashCode())
        assertTrue(a.isReady)
        assertTrue(a.allRequirementsSatisfied)
        assertTrue(a.toString().contains("requirements: fake.requirement=Satisfied"))
        assertEquals("MultiplePermissionsStatus(camera=Granted)", MultiplePermissionsStatus(a.statuses).toString())
        assertEquals(RequirementStatus.Satisfied, a[FakeRequirement()])
        val e = assertThrows(IllegalArgumentException::class.java) { a[FakeRequirement("other")] }
        assertTrue(e.message!!.contains("other"))
        assertThrows(IllegalArgumentException::class.java) { MultiplePermissionsStatus(emptyMap(), emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) {
            MultiplePermissionsStatus(emptyMap(), mapOf(r to RequirementStatus.Satisfied, FakeRequirement() to RequirementStatus.Disabled))
        }
        val onlyRequirement = MultiplePermissionsStatus(emptyMap(), mapOf(r to RequirementStatus.Disabled))
        assertTrue(onlyRequirement.allGranted)
        assertFalse(onlyRequirement.isReady)
        assertFalse(onlyRequirement.anyNeedsSettings)
    }
}
