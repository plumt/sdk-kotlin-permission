package net.plumt.permission

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import net.plumt.permission.BuiltInSpecsTest.Companion.BG
import net.plumt.permission.BuiltInSpecsTest.Companion.CAMERA
import net.plumt.permission.BuiltInSpecsTest.Companion.COARSE
import net.plumt.permission.BuiltInSpecsTest.Companion.FINE
import net.plumt.permission.BuiltInSpecsTest.Companion.IMAGES
import net.plumt.permission.BuiltInSpecsTest.Companion.MIC
import net.plumt.permission.BuiltInSpecsTest.Companion.USER_SELECTED
import net.plumt.permission.internal.DefaultPermissionRequester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 요청 흐름 — 결과 해석, 영구 거부 판정, 특수 권한, 매니페스트 확인, 재진입 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionRequesterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = FakeDevice()
    private val history = RequestHistoryStore.inMemory()
    private val registry = FakeRegistry()
    private val activity: Activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private var currentActivity: Activity? = activity
    private val results = mutableListOf<MultiplePermissionsStatus>()

    private fun requester(
        vararg specs: PermissionSpec,
        sdkInt: Int = 34,
        manifestCheck: ManifestCheck = ManifestCheck.THROW_IF_DEBUGGABLE,
        debuggable: Boolean = true,
        declared: Set<String> = ALL_DECLARED,
        key: String = "k",
    ): PermissionRequester {
        val m = fakeManager(context, device, sdkInt, declared, history, manifestCheck, debuggable)
        return m.createRequester(registry, key, { currentActivity }, specs.toList()) { results += it }
    }

    @Test
    fun `이미 허용이면 시스템 요청 없이 곧바로 콜백한다`() {
        device.grant(CAMERA)
        val r = requester(PermissionSpecs.camera)
        assertTrue(r.launch())
        assertTrue(registry.launches.isEmpty())
        assertFalse(r.isRequesting)
        assertEquals(PermissionStatus.Granted, results.single()[PermissionSpecs.camera])
    }

    @Test
    fun `비해당만 있으면 요청 없이 NotApplicable 로 콜백한다`() {
        val r = requester(PermissionSpecs.notifications, sdkInt = 30)
        r.launch()
        assertTrue(registry.launches.isEmpty())
        assertEquals(PermissionStatus.NotApplicable(30), results.single()[PermissionSpecs.notifications])
    }

    @Test
    fun `허용 결과는 Granted 이고 이력의 영구 거부를 지운다`() {
        history.record(CAMERA, permanentlyDenied = true)
        val r = requester(PermissionSpecs.camera)
        r.launch()
        assertTrue(r.isRequesting)
        assertEquals(listOf(CAMERA), registry.lastPermissions)
        device.grant(CAMERA)
        registry.respondPermissions(mapOf(CAMERA to true))
        assertFalse(r.isRequesting)
        assertEquals(PermissionStatus.Granted, results.single()[PermissionSpecs.camera])
        assertFalse(history.isPermanentlyDenied(CAMERA))
        assertTrue(history.hasRequested(CAMERA))
    }

    @Test
    fun `첫 거부 후 근거 안내가 서면 Denied(true)`() {
        val r = requester(PermissionSpecs.camera)
        r.launch()
        device.rationale += CAMERA
        registry.respondPermissions(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.Denied(true), results.single()[PermissionSpecs.camera])
        assertFalse(history.isPermanentlyDenied(CAMERA))
    }

    @Test
    fun `근거 안내가 서 있다가 거부 후 사라지면 PermanentlyDenied`() {
        device.rationale += CAMERA
        val r = requester(PermissionSpecs.camera)
        r.launch()
        device.rationale -= CAMERA
        registry.respondPermissions(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.PermanentlyDenied, results.single()[PermissionSpecs.camera])
        assertTrue(history.isPermanentlyDenied(CAMERA))
    }

    @Test
    fun `처음 요청이 근거 안내 없이 거부되면 아직 Denied(false) 이고 두 번째에 PermanentlyDenied`() {
        val r = requester(PermissionSpecs.camera)
        r.launch()
        registry.respondPermissions(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.Denied(false), results.last()[PermissionSpecs.camera])
        r.launch()
        registry.respondPermissions(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.PermanentlyDenied, results.last()[PermissionSpecs.camera])
    }

    @Test
    fun `영구 거부여도 launch 는 시스템 요청을 보내고 허용되면 바로잡는다`() {
        history.record(CAMERA, permanentlyDenied = true)
        val r = requester(PermissionSpecs.camera)
        r.launch()
        assertEquals(listOf(CAMERA), registry.lastPermissions)
        device.grant(CAMERA)
        registry.respondPermissions(mapOf(CAMERA to true))
        assertEquals(PermissionStatus.Granted, results.single()[PermissionSpecs.camera])
    }

    @Test
    fun `설정에서 초기화된 뒤 다시 거부해 근거 안내가 서면 영구 거부를 지운다`() {
        history.record(CAMERA, permanentlyDenied = true)
        val r = requester(PermissionSpecs.camera)
        r.launch()
        device.rationale += CAMERA
        registry.respondPermissions(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.Denied(true), results.single()[PermissionSpecs.camera])
        assertFalse(history.isPermanentlyDenied(CAMERA))
    }

    @Test
    fun `빈 결과는 취소로 보고 이력을 건드리지 않는다`() {
        val r = requester(PermissionSpecs.camera)
        r.launch()
        registry.respondPermissions(emptyMap())
        assertFalse(history.hasRequested(CAMERA))
        assertEquals(PermissionStatus.Denied(false), results.single()[PermissionSpecs.camera])
        assertFalse(r.isRequesting)
    }

    @Test
    fun `결과 시점에 Activity 가 없으면 영구 거부 판정을 바꾸지 않는다`() {
        val r = requester(PermissionSpecs.camera, PermissionSpecs.microphone)
        history.record(MIC, permanentlyDenied = true)
        r.launch()
        currentActivity = null
        registry.respondPermissions(mapOf(CAMERA to false, MIC to false))
        assertTrue(history.hasRequested(CAMERA))
        assertFalse(history.isPermanentlyDenied(CAMERA))
        assertTrue(history.isPermanentlyDenied(MIC))
    }

    @Test
    fun `결과 맵이 거짓이어도 실제로 허용돼 있으면 허용으로 남긴다`() {
        history.record(CAMERA, permanentlyDenied = true)
        val r = requester(PermissionSpecs.camera)
        r.launch()
        device.grant(CAMERA)
        registry.respondPermissions(mapOf(CAMERA to false))
        assertFalse(history.isPermanentlyDenied(CAMERA))
    }

    @Test
    fun `Activity 가 없을 때 시작하면 근거 안내를 거짓으로 기록한다`() {
        currentActivity = null
        val r = requester(PermissionSpecs.camera)
        r.launch()
        currentActivity = activity
        registry.respondPermissions(mapOf(CAMERA to false))
        // 처음 요청 + 전후 근거 안내 없음 → 아직 영구 거부로 보지 않는다
        assertFalse(history.isPermanentlyDenied(CAMERA))
        assertTrue(history.hasRequested(CAMERA))
    }

    @Test
    fun `여러 런타임 권한은 한 번의 요청으로 묶고 중복을 지운다`() {
        val r = requester(PermissionSpecs.location(), PermissionSpecs.backgroundLocation(), PermissionSpecs.camera, sdkInt = 29)
        r.launch()
        assertEquals(listOf(FINE, COARSE, BG, CAMERA), registry.lastPermissions)
        assertEquals(1, registry.launches.size)
    }

    @Test
    fun `백그라운드 위치 API 30 은 두 번의 launch 로 두 단계를 밟는다`() {
        val spec = PermissionSpecs.backgroundLocation()
        val r = requester(spec, sdkInt = 30)
        r.launch()
        assertEquals(listOf(FINE, COARSE), registry.lastPermissions)
        device.grant(FINE, COARSE)
        registry.respondPermissions(mapOf(FINE to true, COARSE to true))
        val afterFirst = results.last()[spec]
        assertTrue(afterFirst is PermissionStatus.Partial)
        assertEquals(setOf(BG), (afterFirst as PermissionStatus.Partial).missing)
        r.launch()
        assertEquals(listOf(BG), registry.lastPermissions)
        device.grant(BG)
        registry.respondPermissions(mapOf(BG to true))
        assertEquals(PermissionStatus.Granted, results.last()[spec])
    }

    @Test
    fun `사진 일부 선택에서 다시 요청하면 USER_SELECTED 를 포함해 묻는다`() {
        device.grant(USER_SELECTED)
        val r = requester(PermissionSpecs.media())
        r.launch()
        assertEquals(listOf(IMAGES, USER_SELECTED), registry.lastPermissions)
        registry.respondPermissions(mapOf(IMAGES to false, USER_SELECTED to true))
        assertTrue(results.single()[PermissionSpecs.media()] is PermissionStatus.Partial)
    }

    @Test
    fun `진행 중에 다시 launch 하면 false 를 돌려주고 요청을 보내지 않는다`() {
        val r = requester(PermissionSpecs.camera)
        assertTrue(r.launch())
        assertFalse(r.launch())
        assertEquals(1, registry.launches.size)
    }

    @Test
    fun `등록을 푼 뒤 launch 는 IllegalStateException 이고 unregister 는 여러 번 불러도 된다`() {
        val r = requester(PermissionSpecs.camera)
        r.launch()
        r.unregister()
        r.unregister()
        assertFalse(r.isRequesting)
        val e = assertThrows(IllegalStateException::class.java) { r.launch() }
        assertEquals("등록이 풀린 요청기입니다. 새로 등록하세요", e.message)
    }

    @Test
    fun `등록을 푼 뒤 늦게 온 결과는 콜백하지 않는다`() {
        val r = requester(PermissionSpecs.camera) as DefaultPermissionRequester
        r.launch()
        r.unregister()
        r.onRuntimeResult(mapOf(CAMERA to true))
        assertTrue(results.isEmpty())
    }

    @Test
    fun `화면 재생성 후 같은 키의 새 요청기가 결과를 이어받는다`() {
        val first = requester(PermissionSpecs.camera, key = "same")
        first.launch()
        first.unregister()
        // 재생성된 화면의 레지스트리가 복원된 결과를 새 요청기에 넘기는 상황
        val second = requester(PermissionSpecs.camera, key = "same") as DefaultPermissionRequester
        device.rationale += CAMERA
        second.onRuntimeResult(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.Denied(true), results.single()[PermissionSpecs.camera])
        assertFalse(second.isRequesting)
    }

    @Test
    fun `프로세스 재시작 후에도 영구 거부 판정이 이어진다`() {
        val prefs = RequestHistoryStore.sharedPreferences(context, "restart-test")
        val m1 = fakeManager(context, device, history = prefs)
        val r = m1.createRequester(registry, "a", { activity }, listOf(PermissionSpecs.camera)) { results += it }
        device.rationale += CAMERA
        r.launch()
        device.rationale -= CAMERA
        registry.respondPermissions(mapOf(CAMERA to false))
        // 새 프로세스: 저장소와 관리자를 새로 만든다
        val m2 = fakeManager(context, device, history = RequestHistoryStore.sharedPreferences(context, "restart-test"))
        assertEquals(PermissionStatus.PermanentlyDenied, m2.check(PermissionSpecs.camera, activity))
    }

    // ---- 매니페스트 확인

    @Test
    fun `디버그 빌드에서 선언 없는 권한을 요청하면 IllegalStateException 이고 요청을 보내지 않는다`() {
        val r = requester(PermissionSpecs.camera, PermissionSpecs.microphone, declared = setOf(MIC))
        val e = assertThrows(IllegalStateException::class.java) { r.launch() }
        assertTrue(e.message!!.startsWith("앱 매니페스트에 선언되지 않은 권한을 요청하려 합니다: [$CAMERA]."))
        assertTrue(e.message!!.contains("<uses-permission android:name=\"$CAMERA\" />"))
        assertTrue(registry.launches.isEmpty())
        assertFalse(r.isRequesting)
    }

    @Test
    fun `릴리스 빌드 기본값은 확인하지 않는다`() {
        val r = requester(PermissionSpecs.camera, declared = emptySet(), debuggable = false)
        assertTrue(r.launch())
        assertEquals(listOf(CAMERA), registry.lastPermissions)
    }

    @Test
    fun `ALWAYS_THROW 는 릴리스에서도 확인하고 OFF 는 디버그에서도 확인하지 않는다`() {
        assertThrows(IllegalStateException::class.java) {
            requester(PermissionSpecs.camera, declared = emptySet(), debuggable = false, manifestCheck = ManifestCheck.ALWAYS_THROW).launch()
        }
        assertTrue(requester(PermissionSpecs.camera, declared = emptySet(), manifestCheck = ManifestCheck.OFF, key = "off").launch())
    }

    @Test
    fun `특수 권한의 매니페스트 권한도 확인한다`() {
        val spec = FakeSpecialSpec(manifestPermission = "x.DECLARE_ME")
        val e = assertThrows(IllegalStateException::class.java) { requester(spec).launch() }
        assertTrue(e.message!!.contains("x.DECLARE_ME"))
    }

    // ---- 특수 권한

    @Test
    fun `특수 권한은 설정 화면을 열고 돌아오면 다시 판정한다`() {
        val spec = FakeSpecialSpec()
        val r = requester(spec)
        r.launch()
        assertEquals("fake.SETTINGS", registry.lastIntent.action)
        assertTrue(r.isRequesting)
        spec.granted = true
        registry.respondSettings()
        assertEquals(PermissionStatus.Granted, results.single()[spec])
        assertFalse(r.isRequesting)
    }

    @Test
    fun `런타임 다음 특수 권한을 차례로 연다`() {
        val s1 = FakeSpecialSpec("s1")
        val s2 = FakeSpecialSpec("s2")
        val r = requester(PermissionSpecs.camera, s1, s2)
        r.launch()
        assertEquals(1, registry.launches.size)
        registry.respondPermissions(mapOf(CAMERA to false))
        assertEquals(2, registry.launches.size)
        registry.respondSettings()
        assertEquals(3, registry.launches.size)
        assertTrue(results.isEmpty())
        registry.respondSettings()
        assertEquals(3, results.single().statuses.size)
    }

    @Test
    fun `이미 허용된 특수 권한과 비해당 특수 권한은 열지 않는다`() {
        val r = requester(FakeSpecialSpec("g", granted = true), FakeSpecialSpec("n", applicable = false))
        r.launch()
        assertTrue(registry.launches.isEmpty())
        assertEquals(1, results.size)
    }

    @Test
    fun `명세의 설정 화면이 없으면 앱 상세 설정 화면으로 연다`() {
        registry.failIntentLaunches = 1
        val r = requester(FakeSpecialSpec())
        r.launch()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, registry.lastIntent.action)
    }

    @Test
    fun `설정 화면을 하나도 못 열면 PermissionException 이고 흐름을 끝낸다`() {
        registry.failIntentLaunches = 2
        val r = requester(FakeSpecialSpec())
        val e = assertThrows(PermissionException::class.java) { r.launch() }
        assertEquals("설정 화면을 열 수 없습니다: fake.special (fake.SETTINGS)", e.message)
        assertTrue(e.cause is ActivityNotFoundException)
        assertEquals(1, e.cause!!.suppressed.size)
        assertFalse(r.isRequesting)
        // 끝난 뒤 다시 시작할 수 있다
        assertTrue(r.launch())
    }

    @Test
    fun `런타임 결과 뒤 설정 화면을 못 열어도 PermissionException 이고 진행 상태를 푼다`() {
        val r = requester(PermissionSpecs.camera, FakeSpecialSpec()) as DefaultPermissionRequester
        r.launch()
        registry.failIntentLaunches = 2
        assertThrows(PermissionException::class.java) { r.onRuntimeResult(mapOf(CAMERA to false)) }
        assertFalse(r.isRequesting)
    }

    @Test
    fun `설정 인텐트를 만드는 명세가 던지면 PermissionException 으로 감싼다`() {
        val boom = IllegalStateException("intent")
        val r = requester(FakeSpecialSpec(failIntent = boom))
        val e = assertThrows(PermissionException::class.java) { r.launch() }
        assertSame(boom, e.cause)
        assertFalse(r.isRequesting)
    }

    @Test
    fun `Activity 가 없으면 설정 인텐트에 애플리케이션 컨텍스트를 넘긴다`() {
        var seen: Context? = null
        val spec = object : SpecialPermissionSpec by FakeSpecialSpec() {
            override fun settingsIntent(context: Context) = android.content.Intent("x").also { seen = context }
        }
        currentActivity = null
        requester(spec).launch()
        assertSame(context, seen)
    }

    // ---- 인자

    @Test
    fun `요청기 인자 검사`() {
        val m = fakeManager(context, device)
        val blank = assertThrows(IllegalArgumentException::class.java) { m.createRequester(registry, " ", { null }, listOf(PermissionSpecs.camera)) {} }
        assertEquals("key 가 비었습니다", blank.message)
        assertThrows(IllegalArgumentException::class.java) { m.createRequester(registry, "k", { null }, emptyList()) {} }
        assertThrows(IllegalArgumentException::class.java) {
            m.createRequester(registry, "k", { null }, listOf(PermissionSpecs.camera, PermissionSpecs.camera)) {}
        }
    }

    @Test
    fun `요청기는 명세 목록을 복사해 갖고 문자열에 id 를 담는다`() {
        val list = mutableListOf<PermissionSpec>(PermissionSpecs.camera)
        val r = fakeManager(context, device).createRequester(registry, "k", { null }, list) {}
        list.clear()
        assertEquals(listOf(PermissionSpecs.camera), r.specs)
        assertEquals("PermissionRequester(specs=[camera], requesting=false)", r.toString())
    }

    // ---- 실제 ComponentActivity 경로

    @Test
    fun `ComponentActivity 확장으로 등록하면 시스템 권한 요청이 나가고 결과가 돌아온다`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val act = controller.get()
        val m = fakeManager(context, device)
        val r = act.registerPermissionRequester(m, listOf(PermissionSpecs.camera)) { results += it }
        controller.start().resume()
        r.launch()
        val requested = shadowOf(act).lastRequestedPermission
        assertEquals(listOf(CAMERA), requested.requestedPermissions.toList())
        device.grant(CAMERA)
        act.onRequestPermissionsResult(requested.requestCode, arrayOf(CAMERA), intArrayOf(PackageManager.PERMISSION_GRANTED))
        assertEquals(PermissionStatus.Granted, results.single()[PermissionSpecs.camera])
    }

    @Test
    fun `Activity 수명이 끝나면 등록이 풀린다`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val m = fakeManager(context, device)
        val r = controller.get().registerPermissionRequester(m, listOf(PermissionSpecs.camera)) { results += it }
        controller.start().resume().pause().stop().destroy()
        // 등록이 풀린 런처로 요청하면 androidx 가 IllegalStateException 을 던진다
        assertThrows(IllegalStateException::class.java) { r.launch() }
        assertFalse(r.isRequesting)
    }
}
