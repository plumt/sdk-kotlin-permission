package net.plumt.permission

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import net.plumt.permission.internal.DefaultPermissionRequester
import net.plumt.permission.internal.RequestGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 설정 이동 정책, 요청 줄(직렬화), 알림 설정 화면, 선언 누락 조회 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlowControlTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val device = FakeDevice()
    private val camera = "android.permission.CAMERA"
    private val mic = "android.permission.RECORD_AUDIO"

    private fun permanentlyDeniedManager(history: RequestHistoryStore = RequestHistoryStore.inMemory()) =
        fakeManager(app, device, history = history).also {
            history.record(camera, permanentlyDenied = true)
            history.record(mic, true)
            // 진짜 영구 거부 — 시스템은 대화상자 없이 곧바로 거부를 돌려준다
            device.dialogShown = false
        }

    // ---- 설정 이동 정책

    @Test
    fun `DIRECT 는 결과가 영구 거부면 앱 설정 화면을 열고 돌아온 뒤 한 번 콜백한다`() {
        val activity: Activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val registry = FakeRegistry()
        val results = mutableListOf<MultiplePermissionsStatus>()
        val m = permanentlyDeniedManager()
        val r = m.createRequester(registry, "d", { activity }, listOf(PermissionSpecs.camera)) { results += it }
        assertTrue(r.launch(SettingsPolicy.DIRECT))
        registry.respondPermissions(mapOf(camera to false))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, registry.lastIntent.action)
        assertTrue(results.isEmpty())
        assertTrue(r.isRequesting)
        // 설정에서 허용하고 돌아왔다
        device.grant(camera)
        registry.respondSettings()
        assertEquals(PermissionStatus.Granted, results.single()[PermissionSpecs.camera])
        assertFalse(r.isRequesting)
    }

    @Test
    fun `DIRECT 는 허용됐거나 다시 물을 수 있으면 설정을 열지 않는다`() {
        val registry = FakeRegistry()
        val results = mutableListOf<MultiplePermissionsStatus>()
        val r = fakeManager(app, device).createRequester(registry, "d", { null }, listOf(PermissionSpecs.camera)) { results += it }
        r.launch(SettingsPolicy.DIRECT)
        registry.respondPermissions(mapOf(camera to false))
        assertEquals(1, registry.launches.size)
        assertEquals(PermissionStatus.Denied(false), results.single()[PermissionSpecs.camera])
    }

    @Test
    fun `NEVER 와 PROMPT 는 영구 거부여도 설정을 열지 않고 결과만 준다`() {
        val activity: Activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        for (policy in listOf(SettingsPolicy.NEVER, SettingsPolicy.PROMPT)) {
            val registry = FakeRegistry()
            val results = mutableListOf<MultiplePermissionsStatus>()
            val r = permanentlyDeniedManager().createRequester(registry, policy.name, { activity }, listOf(PermissionSpecs.camera)) {
                results += it
            }
            r.launch(policy)
            registry.respondPermissions(mapOf(camera to false))
            assertEquals(1, registry.launches.size)
            assertEquals(PermissionStatus.PermanentlyDenied, results.single()[PermissionSpecs.camera])
        }
        assertEquals(listOf("NEVER", "PROMPT", "DIRECT"), SettingsPolicy.entries.map { it.name })
    }

    @Test
    fun `DIRECT 에서 설정 화면을 열 수 없으면 PermissionException 이고 흐름이 끝난다`() {
        val activity: Activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val registry = FakeRegistry()
        val r = permanentlyDeniedManager().createRequester(registry, "d", { activity }, listOf(PermissionSpecs.camera)) {}
        r.launch(SettingsPolicy.DIRECT)
        registry.failIntentLaunches = 1
        val e = assertThrows(PermissionException::class.java) { registry.respondPermissions(mapOf(camera to false)) }
        assertTrue(e.cause is ActivityNotFoundException)
        assertFalse(r.isRequesting)
    }

    @Test
    fun `openAppSettings 는 설정 화면을 열고 돌아오면 재판정 결과를 준다`() {
        val registry = FakeRegistry()
        val results = mutableListOf<MultiplePermissionsStatus>()
        val r = fakeManager(app, device).createRequester(registry, "o", { null }, listOf(PermissionSpecs.camera)) { results += it }
        assertTrue(r.openAppSettings())
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, registry.lastIntent.action)
        assertFalse(r.openAppSettings())
        assertFalse(r.launch())
        device.grant(camera)
        registry.respondSettings()
        assertEquals(PermissionStatus.Granted, results.single()[PermissionSpecs.camera])

        registry.failIntentLaunches = 1
        assertThrows(PermissionException::class.java) { r.openAppSettings() }
        assertFalse(r.isRequesting)
        r.unregister()
        assertThrows(IllegalStateException::class.java) { r.openAppSettings() }
    }

    // ---- 요청 줄

    @Test
    fun `다른 요청기의 흐름이 진행 중이면 뒤 요청은 줄에서 기다렸다가 앞 흐름이 끝나면 뜬다`() {
        val m = fakeManager(app, device)
        val registry = FakeRegistry()
        val first = mutableListOf<MultiplePermissionsStatus>()
        val second = mutableListOf<MultiplePermissionsStatus>()
        val a = m.createRequester(registry, "a", { null }, listOf(PermissionSpecs.camera)) { first += it }
        val b = m.createRequester(registry, "b", { null }, listOf(PermissionSpecs.microphone)) { second += it }
        assertTrue(a.launch())
        assertTrue(b.launch())
        assertTrue(b.isRequesting)
        assertEquals(1, registry.launches.size)
        assertEquals(listOf(camera), registry.lastPermissions)
        assertFalse(b.launch())

        registry.respondPermissions(mapOf(camera to true))
        assertEquals(1, first.size)
        assertEquals(listOf(mic), registry.lastPermissions)
        registry.respondPermissions(mapOf(mic to false))
        assertEquals(1, second.size)
        assertNull(m.gate.current)
    }

    @Test
    fun `줄에서 기다리는 동안 허용됐으면 차례가 와도 시스템 요청 없이 콜백한다`() {
        val m = fakeManager(app, device)
        val registry = FakeRegistry()
        val second = mutableListOf<MultiplePermissionsStatus>()
        val a = m.createRequester(registry, "a", { null }, listOf(PermissionSpecs.camera)) {}
        val b = m.createRequester(registry, "b", { null }, listOf(PermissionSpecs.camera)) { second += it }
        a.launch()
        b.launch()
        device.grant(camera)
        registry.respondPermissions(mapOf(camera to true))
        assertEquals(1, registry.launches.size)
        assertEquals(PermissionStatus.Granted, second.single()[PermissionSpecs.camera])
    }

    @Test
    fun `기다리던 요청기의 등록을 풀면 줄에서 빠지고 앞 요청기를 풀면 다음이 뜬다`() {
        val m = fakeManager(app, device)
        val registry = FakeRegistry()
        val a = m.createRequester(registry, "a", { null }, listOf(PermissionSpecs.camera)) {}
        val b = m.createRequester(registry, "b", { null }, listOf(PermissionSpecs.microphone)) {}
        val c = m.createRequester(registry, "c", { null }, listOf(PermissionSpecs.calendar())) {}
        a.launch()
        b.launch()
        c.launch()
        assertEquals(2, m.gate.waitingCount)
        b.unregister()
        assertEquals(1, m.gate.waitingCount)
        a.unregister()
        assertEquals("c", m.gate.current)
        assertEquals(listOf("android.permission.READ_CALENDAR"), registry.lastPermissions)
    }

    @Test
    fun `화면 수명이 끝난 요청기는 줄의 자리를 내놓는다`() {
        val m = fakeManager(app, device)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = controller.get()
        val a = m.createRequester(activity, { activity }, listOf(PermissionSpecs.camera)) {}
        controller.start().resume()
        a.launch()
        assertTrue(m.gate.current === a)
        val registry = FakeRegistry()
        val b = m.createRequester(registry, "b", { null }, listOf(PermissionSpecs.microphone)) {}
        b.launch()
        assertEquals(0, registry.launches.size)
        controller.pause().stop().destroy()
        assertEquals("b", m.gate.current)
        assertEquals(listOf(mic), registry.lastPermissions)
        assertThrows(IllegalStateException::class.java) { a.launch() }
        (a as DefaultPermissionRequester).onOwnerDestroyed()
    }

    @Test
    fun `줄 단위 동작`() {
        val gate = RequestGate()
        val log = mutableListOf<String>()
        gate.acquire("a") { log += "a" }
        gate.acquire("a") { log += "a again" }
        gate.acquire("b") { log += "b" }
        gate.acquire("b") { log += "b replaced" }
        gate.claim("c")
        assertEquals("a", gate.current)
        gate.release("x")
        assertEquals(listOf("a", "a again"), log)
        gate.release("a")
        assertEquals(listOf("a", "a again", "b replaced"), log)
        gate.release("b")
        assertNull(gate.current)
        gate.claim("c")
        assertEquals("c", gate.current)
    }

    // ---- 알림 설정 화면

    @Test
    @Config(sdk = [25, 26, 34])
    fun `알림 설정 인텐트는 API 26 이상 앱·채널 화면 그 아래는 앱 상세 화면`() {
        val m = PermissionManager.create(app, PermissionConfig(RequestHistoryStore.inMemory(), ManifestCheck.OFF))
        val appIntent = m.notificationSettingsIntent()
        val channelIntent = m.notificationSettingsIntent("news")
        if (Build.VERSION.SDK_INT >= 26) {
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, appIntent.action)
            assertEquals(app.packageName, appIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channelIntent.action)
            assertEquals("news", channelIntent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
            assertEquals(app.packageName, channelIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        } else {
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appIntent.action)
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, channelIntent.action)
            assertEquals("package:${app.packageName}", appIntent.dataString)
        }
    }

    @Test
    fun `알림 설정을 열면 Activity 가 아닌 컨텍스트는 새 태스크로 연다`() {
        val m = PermissionManager.create(app, PermissionConfig(RequestHistoryStore.inMemory(), ManifestCheck.OFF))
        m.openNotificationSettings(app, "news")
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        m.openNotificationSettings(activity)
        val fromActivity = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, fromActivity.action)
        assertEquals(0, fromActivity.flags and Intent.FLAG_ACTIVITY_NEW_TASK)

        assertTrue(assertThrows(IllegalArgumentException::class.java) { m.openNotificationSettings(app, "") }.message!!.contains("channelId"))
    }

    @Test
    fun `알림 설정 화면이 없으면 앱 상세 화면으로 대신 열고 그것도 없으면 PermissionException`() {
        val m = PermissionManager.create(app, PermissionConfig(RequestHistoryStore.inMemory(), ManifestCheck.OFF))
        val failing = object : android.content.ContextWrapper(app) {
            var failures = 1
            val started = mutableListOf<Intent>()
            override fun startActivity(intent: Intent) {
                if (failures-- > 0) throw ActivityNotFoundException("no ${intent.action}")
                started += intent
            }
        }
        m.openNotificationSettings(failing)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, failing.started.single().action)

        failing.failures = 2
        val e = assertThrows(PermissionException::class.java) { m.openNotificationSettings(failing) }
        assertEquals(1, e.suppressed.size)
    }

    // ---- 선언 누락 조회

    @Test
    fun `undeclaredPermissions 는 이 기기에서 쓰는 권한 가운데 선언되지 않은 것만 준다`() {
        val m = fakeManager(app, device, sdkInt = 34, declared = setOf(camera))
        assertEquals(emptySet<String>(), m.undeclaredPermissions(listOf(PermissionSpecs.camera)))
        assertEquals(
            setOf(mic, "android.permission.SCHEDULE_EXACT_ALARM", "android.permission.BLUETOOTH_CONNECT"),
            m.undeclaredPermissions(
                listOf(PermissionSpecs.camera, PermissionSpecs.microphone, PermissionSpecs.exactAlarm),
                listOf(SystemRequirements.bluetoothEnabled),
            ),
        )
        // 이 API 에 없는 특수 권한·런타임 권한은 묻지 않는다
        val old = fakeManager(app, device, sdkInt = 30, declared = emptySet())
        assertEquals(emptySet<String>(), old.undeclaredPermissions(listOf(PermissionSpecs.exactAlarm, PermissionSpecs.notifications)))
    }
}
