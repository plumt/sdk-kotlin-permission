package net.plumt.permission

import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.test.core.app.ApplicationProvider
import net.plumt.permission.BuiltInSpecsTest.Companion.CAMERA
import net.plumt.permission.BuiltInSpecsTest.Companion.COARSE
import net.plumt.permission.BuiltInSpecsTest.Companion.FINE
import net.plumt.permission.BuiltInSpecsTest.Companion.IMAGES
import net.plumt.permission.BuiltInSpecsTest.Companion.USER_SELECTED
import net.plumt.permission.internal.DefaultPermissionRequester
import net.plumt.permission.internal.DialogEvidence
import net.plumt.permission.internal.PauseDialogWatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 영구 거부는 거부 횟수가 아니라 요청 전·후 근거 안내와 "대화상자가 실제로 떴는지" 로 판정한다.
 *
 * Android 11 이상은 대화상자를 바깥 탭·뒤로가기로 닫으면 거부 횟수에 세지 않는다(근거 안내도 거짓 그대로).
 * 실기기(API 36): 뒤로가기로 두 번 닫은 뒤에도 세 번째 요청에 대화상자가 다시 떴다.
 * 대화상자 없이 끝나는 요청도 창 포커스를 잠깐 뺏으므로 포커스는 근거가 못 된다(API 29·36).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 33, 35])
class DialogDismissJudgementTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = FakeDevice()
    private val history = RequestHistoryStore.inMemory()
    private val registry = FakeRegistry()
    private val activity: Activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val results = mutableListOf<MultiplePermissionsStatus>()

    private fun manager(sdkInt: Int = 34) = fakeManager(context, device, sdkInt, history = history)

    private fun requester(spec: PermissionSpec = PermissionSpecs.camera, sdkInt: Int = 34): PermissionRequester =
        manager(sdkInt).createRequester(registry, "k", { activity }, listOf(spec)) { results += it }

    private val camera get() = results.last()[PermissionSpecs.camera]

    @Test
    fun `대화상자를 뒤로가기로 두 번 닫아도 영구 거부가 아니고 세 번째도 요청한다`() {
        for (sdk in listOf(android.os.Build.VERSION.SDK_INT, 36)) {
            history.clear()
            results.clear()
            registry.launches.clear()
            val r = requester(sdkInt = sdk)
            repeat(2) {
                assertTrue(r.launch())
                registry.respondPermissions(mapOf(CAMERA to false))
                assertEquals("sdk=$sdk", PermissionStatus.Denied(false), camera)
            }
            assertFalse(history.isPermanentlyDenied(CAMERA))
            assertEquals(PermissionStatus.Denied(false), manager(sdk).check(PermissionSpecs.camera, activity))
            assertTrue(r.launch())
            assertEquals(3, registry.launches.size)
            assertEquals(listOf(CAMERA), registry.lastPermissions)
            r.unregister()
        }
    }

    @Test
    fun `대화상자 없이 곧바로 끝난 거부는 첫 요청이어도 영구 거부다`() {
        device.dialogShown = false
        val r = requester()
        r.launch()
        registry.respondPermissions(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.PermanentlyDenied, camera)
        assertTrue(history.isPermanentlyDenied(CAMERA))
    }

    @Test
    fun `닫은 뒤 다음 요청이 화면 없이 끝나면 그때 영구 거부다`() {
        val r = requester()
        r.launch()
        registry.respondPermissions(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.Denied(false), camera)
        device.dialogShown = false
        r.launch()
        registry.respondPermissions(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.PermanentlyDenied, camera)
    }

    @Test
    fun `요청 전 근거 안내가 있었고 뒤에 사라졌으면 대화상자 관측과 관계없이 영구 거부다`() {
        for (shown in listOf(true, false)) {
            history.clear()
            device.rationale += CAMERA
            device.dialogShown = shown
            val r = requester()
            r.launch()
            device.rationale -= CAMERA
            registry.respondPermissions(mapOf(CAMERA to false))
            assertEquals("shown=$shown", PermissionStatus.PermanentlyDenied, camera)
            r.unregister()
        }
    }

    @Test
    fun `요청 뒤 근거 안내가 서 있으면 대화상자를 못 봤어도 다시 물을 수 있다`() {
        device.dialogShown = false
        val r = requester()
        r.launch()
        device.rationale += CAMERA
        registry.respondPermissions(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.Denied(true), camera)
        assertFalse(history.isPermanentlyDenied(CAMERA))
    }

    @Test
    fun `근거 안내가 선 상태에서 닫으면 근거 안내가 남아 다시 물을 수 있다`() {
        device.rationale += CAMERA
        val r = requester()
        r.launch()
        registry.respondPermissions(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.Denied(true), camera)
    }

    @Test
    fun `영구 거부 이력이 있어도 대화상자가 떠서 닫혔으면 바로잡힌다`() {
        history.record(CAMERA, permanentlyDenied = true)
        assertEquals(PermissionStatus.PermanentlyDenied, manager().check(PermissionSpecs.camera, activity))
        val r = requester()
        r.launch()
        registry.respondPermissions(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.Denied(false), camera)
        assertFalse(history.isPermanentlyDenied(CAMERA))
    }

    @Test
    fun `진짜 영구 거부는 다음 요청에서도 유지된다`() {
        history.record(CAMERA, permanentlyDenied = true)
        device.dialogShown = false
        val r = requester()
        repeat(2) {
            r.launch()
            registry.respondPermissions(mapOf(CAMERA to false))
            assertEquals(PermissionStatus.PermanentlyDenied, camera)
        }
    }

    @Test
    fun `대화상자 관측은 요청마다 한 번 시작한다`() {
        val r = requester()
        r.launch()
        registry.respondPermissions(mapOf(CAMERA to false))
        r.launch()
        registry.respondPermissions(mapOf(CAMERA to false))
        assertEquals(2, device.dialogWatches)
    }

    @Test
    fun `프로세스 재시작 — 저장소를 다시 열어도 닫힘 판정은 영구 거부가 아니다`() {
        val store = RequestHistoryStore.sharedPreferences(context, "perm05")
        store.clear()
        val r = fakeManager(context, device, history = store)
            .createRequester(registry, "p", { activity }, listOf(PermissionSpecs.camera)) { results += it }
        repeat(2) {
            r.launch()
            registry.respondPermissions(mapOf(CAMERA to false))
        }
        val reopened = RequestHistoryStore.sharedPreferences(context, "perm05")
        assertTrue(reopened.hasRequested(CAMERA))
        assertFalse(reopened.isPermanentlyDenied(CAMERA))
        assertEquals(
            PermissionStatus.Denied(false),
            fakeManager(context, device, history = reopened).check(PermissionSpecs.camera, activity),
        )
    }

    @Test
    fun `프로세스 재시작으로 이어받은 요청은 관측이 없어 떴던 것으로 본다`() {
        device.dialogShown = false
        val first = Owner(null)
        val r1 = fakeManager(context, device, history = history)
            .createRequester(FakeRegistry(), "s", { activity }, listOf(PermissionSpecs.camera), first.savedStateRegistry) {}
        r1.launch()
        val saved = first.save()
        r1.unregister()
        val second = Owner(saved)
        val r2 = fakeManager(context, device, history = history)
            .createRequester(registry, "s", { activity }, listOf(PermissionSpecs.camera), second.savedStateRegistry) {
                results += it
            } as DefaultPermissionRequester
        assertTrue(r2.isRequesting)
        r2.onRuntimeResult(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.Denied(false), camera)
    }

    // ---- 지난 수정 회귀 고정: 사진 일부 선택·대략적 위치

    @Test
    fun `사진 일부 선택을 고른 대화상자는 대화상자 관측과 관계없이 영구 거부가 아니다`() {
        for (shown in listOf(true, false)) {
            history.clear()
            device.revoke(USER_SELECTED)
            device.dialogShown = shown
            val spec = PermissionSpecs.media()
            val r = requester(spec)
            r.launch()
            device.grant(USER_SELECTED)
            registry.respondPermissions(mapOf(IMAGES to false, USER_SELECTED to true))
            val status = results.last()[spec] as PermissionStatus.Partial
            assertEquals(PartialAccess.SELECTED_MEDIA, status.access)
            assertFalse("shown=$shown", status.isMissingPermanentlyDenied)
            // 다시 요청해 "추가 선택 안함" 이어도 일부 선택이 남아 있으면 다시 물을 수 있다
            r.launch()
            registry.respondPermissions(mapOf(IMAGES to false, USER_SELECTED to true))
            assertFalse("shown=$shown", (results.last()[spec] as PermissionStatus.Partial).isMissingPermanentlyDenied)
            r.unregister()
        }
    }

    @Test
    fun `첫 대화상자에서 대략적 위치를 고르면 영구 거부가 아니다`() {
        val spec = PermissionSpecs.location()
        val r = requester(spec)
        r.launch()
        device.grant(COARSE)
        registry.respondPermissions(mapOf(FINE to false, COARSE to true))
        val status = results.last()[spec] as PermissionStatus.Partial
        assertEquals(PartialAccess.APPROXIMATE_LOCATION, status.access)
        assertFalse(status.isMissingPermanentlyDenied)
        assertTrue(status.canAskAgain)
    }

    @Test
    fun `정밀 승격 대화상자 거절은 대화상자가 떴어도 영구 거부다`() {
        val spec = PermissionSpecs.location()
        device.grant(COARSE)
        val r = requester(spec)
        r.launch()
        assertEquals(listOf(FINE, COARSE), registry.lastPermissions)
        registry.respondPermissions(mapOf(FINE to false, COARSE to true))
        val status = results.last()[spec] as PermissionStatus.Partial
        assertTrue(status.isMissingPermanentlyDenied)
        assertTrue(status.needsSettings)
    }

    @Test
    fun `대략적 위치 대화상자를 닫으면 아무것도 고르지 않은 것이라 다시 물을 수 있다`() {
        val spec = PermissionSpecs.location()
        val r = requester(spec)
        r.launch()
        registry.respondPermissions(mapOf(FINE to false, COARSE to false))
        assertEquals(PermissionStatus.Denied(false), results.last()[spec])
        assertFalse(history.isPermanentlyDenied(FINE))
        assertFalse(history.isPermanentlyDenied(COARSE))
    }

    // ---- 실기기 회귀: 이미 확인한 영구 거부는 불확실한 근거로 풀리지 않는다

    @Test
    fun `이미 영구 거부인 권한은 대화상자가 떴다는 증거가 애매하면 이력이 그대로다`() {
        history.record(CAMERA, permanentlyDenied = true)
        device.dialogEvidence = DialogEvidence.LIKELY
        val r = requester()
        repeat(3) {
            r.launch()
            registry.respondPermissions(mapOf(CAMERA to false))
            assertEquals(PermissionStatus.PermanentlyDenied, camera)
            assertTrue(history.isPermanentlyDenied(CAMERA))
        }
    }

    @Test
    fun `이력이 없는 권한은 증거가 애매하면 다시 물을 수 있다`() {
        device.dialogEvidence = DialogEvidence.LIKELY
        val r = requester()
        r.launch()
        registry.respondPermissions(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.Denied(false), camera)
        assertFalse(history.isPermanentlyDenied(CAMERA))
    }

    @Test
    fun `프로세스 재시작으로 이어받은 요청은 관측이 없으니 영구 거부 이력을 지우지 않는다`() {
        history.record(CAMERA, permanentlyDenied = true)
        val first = Owner(null)
        val r1 = fakeManager(context, device, history = history)
            .createRequester(FakeRegistry(), "s", { activity }, listOf(PermissionSpecs.camera), first.savedStateRegistry) {}
        r1.launch()
        val saved = first.save()
        r1.unregister()
        val second = Owner(saved)
        val r2 = fakeManager(context, device, history = history)
            .createRequester(registry, "s", { activity }, listOf(PermissionSpecs.camera), second.savedStateRegistry) {
                results += it
            } as DefaultPermissionRequester
        r2.onRuntimeResult(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.PermanentlyDenied, camera)
        assertTrue(history.isPermanentlyDenied(CAMERA))
    }

    @Test
    fun `복귀 경로 — 설정에서 허용하면 이력이 지워지고, 철회 뒤 대화상자에서 거부하면 다시 물을 수 있다`() {
        history.record(CAMERA, permanentlyDenied = true)
        device.grant(CAMERA)
        assertEquals(PermissionStatus.Granted, manager().check(PermissionSpecs.camera, activity))
        assertFalse(history.hasRequested(CAMERA))
        device.revoke(CAMERA)
        assertEquals(PermissionStatus.Denied(false), manager().check(PermissionSpecs.camera, activity))
        val r = requester()
        r.launch()
        device.rationale += CAMERA
        registry.respondPermissions(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.Denied(true), camera)
    }

    @Test
    fun `복귀 경로 — 앱이 허용을 못 본 채 철회돼도 근거 안내가 서면 이력이 지워진다`() {
        history.record(CAMERA, permanentlyDenied = true)
        device.rationale += CAMERA
        assertEquals(PermissionStatus.Denied(true), manager().check(PermissionSpecs.camera, activity))
        assertFalse(history.isPermanentlyDenied(CAMERA))
    }

    private class Owner(state: android.os.Bundle?) : SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry.createUnsafe(this as LifecycleOwner)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        init {
            controller.performAttach()
            controller.performRestore(state)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }

        fun save(): android.os.Bundle = android.os.Bundle().also { controller.performSave(it) }
    }
}

/**
 * 기본 관측 [PauseDialogWatcher] — 경계값은 실기기 측정 분포에서 왔다(ms, 화면이 멈춘 뒤 결과까지).
 * 대화상자 없는 요청 최댓값: API 29 214, API 36 161(내 프로세스 CPU 부하 포함). 요청 300ms 뒤 닫은 대화상자 최솟값: API 36 310.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PauseDialogWatcherTest {

    private var now = 1_000L
    private val watcher = PauseDialogWatcher(clock = { now })
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()

    private fun afterPause(millis: Long): DialogEvidence {
        val watch = watcher.watch(controller.get())
        controller.pause()
        now += millis
        val evidence = watch.finish()
        controller.resume()
        return evidence
    }

    @Test
    fun `측정한 대화상자 없는 요청의 분포는 모두 증거 없음이다`() {
        for (millis in listOf(38L, 52L, 72L, 104L, 136L, 214L, 279L)) {
            assertEquals("$millis", DialogEvidence.NONE, afterPause(millis))
        }
    }

    @Test
    fun `측정한 300ms 뒤 닫힘의 최솟값부터는 떴다고 볼 만하다`() {
        for (millis in listOf(280L, 310L, 327L, 500L, 1_999L)) {
            assertEquals("$millis", DialogEvidence.LIKELY, afterPause(millis))
        }
    }

    @Test
    fun `사람이 대화상자를 본 시간이면 확실하다`() {
        for (millis in listOf(2_000L, 5_000L)) {
            assertEquals("$millis", DialogEvidence.CERTAIN, afterPause(millis))
        }
    }

    @Test
    fun `대화상자 없는 요청이 잠깐 포커스를 뺏어도 증거가 아니다 — 회귀`() {
        controller.windowFocusChanged(true)
        val watch = watcher.watch(controller.get())
        controller.pause()
        controller.windowFocusChanged(false)
        shadowOf(Looper.getMainLooper()).idle()
        now += 50
        controller.windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(DialogEvidence.NONE, watch.finish())
    }

    @Test
    fun `요청 직후 앱 스레드가 밀린 시간은 세지 않는다`() {
        val watch = watcher.watch(controller.get())
        now += 1_000
        controller.pause()
        now += 60
        assertEquals(DialogEvidence.NONE, watch.finish())
    }

    @Test
    fun `화면이 멈추지 않은 포커스 소실은 시간이 길어도 멈춤 기준을 쓰지 않는다`() {
        val watch = watcher.watch(controller.get())
        controller.windowFocusChanged(false)
        shadowOf(Looper.getMainLooper()).idle()
        now += 399
        assertEquals(DialogEvidence.NONE, watch.finish())
    }

    @Test
    fun `화면이 없으면 요청부터 잰다 — 측정한 콜드 스타트 최댓값 318ms 는 증거 없음이다`() {
        val fast = watcher.watch(null)
        now += 318
        assertEquals(DialogEvidence.NONE, fast.finish())
        val edge = watcher.watch(null)
        now += 399
        assertEquals(DialogEvidence.NONE, edge.finish())
        val slow = watcher.watch(null)
        now += 400
        assertEquals(DialogEvidence.LIKELY, slow.finish())
        val long = watcher.watch(null)
        now += 2_000
        assertEquals(DialogEvidence.CERTAIN, long.finish())
    }

    @Test
    fun `수명주기가 없는 화면도 요청부터 잰다`() {
        val plain = Robolectric.buildActivity(Activity::class.java).setup().get()
        val watch = watcher.watch(plain)
        now += 500
        assertEquals(DialogEvidence.LIKELY, watch.finish())
    }

    @Test
    fun `첫 멈춤부터 잰다`() {
        val watch = watcher.watch(controller.get())
        controller.pause()
        now += 100
        controller.resume()
        controller.pause()
        now += 100
        assertEquals(DialogEvidence.NONE, watch.finish())
        val second = watcher.watch(controller.get())
        controller.resume()
        controller.pause()
        now += 300
        assertEquals(DialogEvidence.LIKELY, second.finish())
    }

    @Test
    fun `관측을 끝낸 뒤의 멈춤은 세지 않는다`() {
        val first = watcher.watch(controller.get())
        assertEquals(DialogEvidence.NONE, first.finish())
        val second = watcher.watch(controller.get())
        assertEquals(DialogEvidence.NONE, second.finish())
        controller.pause()
        now += 5_000
        assertEquals(DialogEvidence.CERTAIN, first.finish())
    }

    @Test
    fun `기본값`() {
        assertEquals(280L, PauseDialogWatcher.LIKELY_AFTER_MILLIS)
        assertEquals(2_000L, PauseDialogWatcher.CERTAIN_AFTER_MILLIS)
        assertEquals(400L, PauseDialogWatcher.LIKELY_AFTER_UNPAUSED_MILLIS)
        assertEquals(DialogEvidence.NONE, PauseDialogWatcher().watch(null).finish())
    }

    // ---- 요청기까지 묶은 회귀: 실제 관측기와 실제 수명주기

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = FakeDevice()
    private val history = RequestHistoryStore.inMemory()
    private val registry = FakeRegistry()
    private val results = mutableListOf<MultiplePermissionsStatus>()

    private fun requester(): PermissionRequester =
        fakeManager(context, device, history = history, dialogWatcher = watcher)
            .createRequester(registry, "k", { controller.get() }, listOf(PermissionSpecs.camera)) { results += it }

    private fun roundTrip(r: PermissionRequester, millis: Long) {
        assertTrue(r.launch())
        controller.pause()
        now += millis
        registry.respondPermissions(mapOf(CAMERA to false))
        controller.resume()
    }

    @Test
    fun `A — 고정된 권한을 몇 번을 다시 요청해도 영구 거부이고 이력이 남는다`() {
        history.record(CAMERA, permanentlyDenied = true)
        val r = requester()
        for (millis in listOf(38L, 52L, 72L, 136L, 214L, 279L, 310L, 900L, 1_999L)) {
            roundTrip(r, millis)
            assertEquals("$millis", PermissionStatus.PermanentlyDenied, results.last()[PermissionSpecs.camera])
            assertTrue("$millis", history.isPermanentlyDenied(CAMERA))
        }
    }

    @Test
    fun `A — 이력이 없어도 대화상자 없는 시간이면 영구 거부다`() {
        val r = requester()
        roundTrip(r, 72)
        assertEquals(PermissionStatus.PermanentlyDenied, results.last()[PermissionSpecs.camera])
    }

    @Test
    fun `B — 대화상자를 닫으면 Denied(false) 이고 다음 요청을 보낸다`() {
        val r = requester()
        for (millis in listOf(310L, 500L, 800L, 1_200L, 2_000L)) {
            roundTrip(r, millis)
            assertEquals("$millis", PermissionStatus.Denied(false), results.last()[PermissionSpecs.camera])
            assertFalse("$millis", history.isPermanentlyDenied(CAMERA))
        }
        assertEquals(5, registry.launches.size)
    }

    @Test
    fun `B — 영구 거부 이력이 있어도 오래 떠 있던 대화상자를 닫았으면 풀린다`() {
        history.record(CAMERA, permanentlyDenied = true)
        val r = requester()
        roundTrip(r, 2_000)
        assertEquals(PermissionStatus.Denied(false), results.last()[PermissionSpecs.camera])
        assertFalse(history.isPermanentlyDenied(CAMERA))
    }
}
