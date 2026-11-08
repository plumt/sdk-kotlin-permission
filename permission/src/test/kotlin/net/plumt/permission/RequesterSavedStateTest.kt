package net.plumt.permission

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.test.core.app.ApplicationProvider
import net.plumt.permission.BuiltInSpecsTest.Companion.BG
import net.plumt.permission.BuiltInSpecsTest.Companion.CAMERA
import net.plumt.permission.BuiltInSpecsTest.Companion.COARSE
import net.plumt.permission.BuiltInSpecsTest.Companion.FINE
import net.plumt.permission.internal.DefaultPermissionRequester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 저장 상태를 손으로 저장·복원하는 화면 흉내 */
private class TestOwner : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry.createUnsafe(this as LifecycleOwner)
    private val controller = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun restore(state: Bundle?) {
        controller.performAttach()
        controller.performRestore(state)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun save(): Bundle = Bundle().also { controller.performSave(it) }
}

/** 리뷰 M1 — 화면 재생성·프로세스 종료 뒤 요청 흐름을 잇는다 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RequesterSavedStateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = FakeDevice()
    private val history = RequestHistoryStore.inMemory()
    private val activity: Activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val results = mutableListOf<MultiplePermissionsStatus>()

    private fun requester(
        registry: FakeRegistry,
        owner: TestOwner?,
        vararg specs: PermissionSpec,
        sdkInt: Int = 34,
    ): DefaultPermissionRequester =
        fakeManager(context, device, sdkInt, history = history)
            .createRequester(registry, "k", { activity }, specs.toList(), owner?.savedStateRegistry) { results += it }
            as DefaultPermissionRequester

    @Test
    fun `재생성 뒤 런타임 결과가 오면 남은 특수 권한 설정 화면을 이어 연다`() {
        val special = FakeSpecialSpec("s1")
        val first = TestOwner().apply { restore(null) }
        val r1 = requester(FakeRegistry(), first, PermissionSpecs.camera, special)
        r1.launch()
        val saved = first.save()
        r1.unregister()

        val second = TestOwner().apply { restore(saved) }
        val registry = FakeRegistry()
        val r2 = requester(registry, second, PermissionSpecs.camera, special)
        assertTrue(r2.isRequesting)
        // 재생성 전에 보낸 요청의 결과가 새 요청기로 온다
        r2.onRuntimeResult(mapOf(CAMERA to false))
        assertEquals("fake.SETTINGS", registry.lastIntent.action)
        assertTrue(results.isEmpty())
        special.granted = true
        registry.respondSettings()
        assertEquals(PermissionStatus.Granted, results.single()[special])
        assertFalse(r2.isRequesting)
    }

    @Test
    fun `재생성 뒤에도 요청 전 근거 안내 값으로 첫 요청의 영구 거부를 판정한다`() {
        device.rationale += CAMERA
        val first = TestOwner().apply { restore(null) }
        val r1 = requester(FakeRegistry(), first, PermissionSpecs.camera)
        r1.launch()
        val saved = first.save()
        r1.unregister()

        device.rationale -= CAMERA
        val r2 = requester(FakeRegistry(), TestOwner().apply { restore(saved) }, PermissionSpecs.camera)
        r2.onRuntimeResult(mapOf(CAMERA to false))
        assertEquals(PermissionStatus.PermanentlyDenied, results.single()[PermissionSpecs.camera])
    }

    @Test
    fun `재생성 뒤 남은 백그라운드 위치 요청도 잇는다`() {
        device.grant(FINE, COARSE)
        val first = TestOwner().apply { restore(null) }
        val r1 = requester(FakeRegistry(), first, PermissionSpecs.camera, PermissionSpecs.backgroundLocation(), sdkInt = 30)
        r1.launch()
        val saved = first.save()
        r1.unregister()

        val registry = FakeRegistry()
        val r2 = requester(registry, TestOwner().apply { restore(saved) }, PermissionSpecs.camera, PermissionSpecs.backgroundLocation(), sdkInt = 30)
        r2.onRuntimeResult(mapOf(CAMERA to true))
        assertEquals(listOf(BG), registry.lastPermissions)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `재생성 뒤에도 요청 전 허용 상태로 정밀 승격 거절을 영구 거부로 판정한다`() {
        // 실기기: 대략적 위치를 가진 채 승격 대화상자에서 "대략적인 위치 계속 사용" → 이후 묻지 않는다
        device.grant(COARSE)
        history.record(FINE, permanentlyDenied = false)
        val first = TestOwner().apply { restore(null) }
        val r1 = requester(FakeRegistry(), first, PermissionSpecs.location())
        r1.launch()
        val saved = first.save()
        r1.unregister()

        val r2 = requester(FakeRegistry(), TestOwner().apply { restore(saved) }, PermissionSpecs.location())
        r2.onRuntimeResult(mapOf(FINE to false, COARSE to true))
        assertTrue((results.single()[PermissionSpecs.location()] as PermissionStatus.Partial).isMissingPermanentlyDenied)
    }

    @Test
    fun `진행 중이 아니면 복원해도 진행 상태가 아니다`() {
        val first = TestOwner().apply { restore(null) }
        val r1 = requester(FakeRegistry(), first, PermissionSpecs.camera)
        val saved = first.save()
        r1.unregister()
        val r2 = requester(FakeRegistry(), TestOwner().apply { restore(saved) }, PermissionSpecs.camera)
        assertFalse(r2.isRequesting)
    }

    @Test
    fun `복원 상태가 없으면 새로 시작한다`() {
        val r = requester(FakeRegistry(), TestOwner().apply { restore(Bundle()) }, PermissionSpecs.camera)
        assertFalse(r.isRequesting)
        assertTrue(r.launch())
    }

    @Test
    fun `복원 전에 등록하면 복원된 뒤 처음 쓰일 때 읽는다`() {
        val first = TestOwner().apply { restore(null) }
        val r1 = requester(FakeRegistry(), first, PermissionSpecs.camera, FakeSpecialSpec("s1"))
        r1.launch()
        val saved = first.save()
        r1.unregister()

        // Activity 필드 초기화처럼 onCreate 의 복원보다 먼저 등록된다
        val owner = TestOwner()
        val registry = FakeRegistry()
        val r2 = requester(registry, owner, PermissionSpecs.camera, FakeSpecialSpec("s1"))
        assertFalse(r2.isRequesting)
        owner.restore(saved)
        r2.onRuntimeResult(mapOf(CAMERA to false))
        assertEquals("fake.SETTINGS", registry.lastIntent.action)
    }

    @Test
    fun `복원된 진행 중 흐름에서는 launch 가 false 다`() {
        val first = TestOwner().apply { restore(null) }
        val r1 = requester(FakeRegistry(), first, PermissionSpecs.camera)
        r1.launch()
        val saved = first.save()
        r1.unregister()
        val owner = TestOwner()
        val r2 = requester(FakeRegistry(), owner, PermissionSpecs.camera)
        owner.restore(saved)
        assertFalse(r2.launch())
    }

    @Test
    fun `복원 상태의 모르는 특수 명세 id 는 건너뛴다`() {
        val first = TestOwner().apply { restore(null) }
        val r1 = requester(FakeRegistry(), first, PermissionSpecs.camera, FakeSpecialSpec("gone"))
        r1.launch()
        val saved = first.save()
        r1.unregister()
        val r2 = requester(FakeRegistry(), TestOwner().apply { restore(saved) }, PermissionSpecs.camera)
        r2.onRuntimeResult(mapOf(CAMERA to false))
        assertEquals(1, results.size)
    }

    @Test
    fun `같은 키를 같은 저장소에 두 번 등록하면 IllegalArgumentException`() {
        val owner = TestOwner().apply { restore(null) }
        requester(FakeRegistry(), owner, PermissionSpecs.camera)
        val e = assertThrows(IllegalArgumentException::class.java) { requester(FakeRegistry(), owner, PermissionSpecs.camera) }
        assertEquals("같은 key 의 요청기가 이미 등록돼 있습니다: k", e.message)
    }

    @Test
    fun `등록을 풀면 저장 상태 제공자도 풀린다`() {
        val owner = TestOwner().apply { restore(null) }
        val r = requester(FakeRegistry(), owner, PermissionSpecs.camera)
        assertNotNull(owner.savedStateRegistry.getSavedStateProvider("k#state"))
        r.unregister()
        assertNull(owner.savedStateRegistry.getSavedStateProvider("k#state"))
        // 다시 등록할 수 있다
        requester(FakeRegistry(), owner, PermissionSpecs.camera)
    }

    @Test
    fun `ComponentActivity 에 등록하면 등록 순서대로 저장 상태 키를 받는다`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val act = controller.get()
        val m = fakeManager(context, device, history = history)
        act.registerPermissionRequester(m, listOf(PermissionSpecs.camera)) {}
        act.registerPermissionRequester(m, listOf(PermissionSpecs.microphone)) {}
        assertNotNull(act.savedStateRegistry.getSavedStateProvider("net.plumt.permission.requester#0"))
        assertNotNull(act.savedStateRegistry.getSavedStateProvider("net.plumt.permission.requester#1"))
    }

    @Test
    fun `ComponentActivity 재생성 뒤 같은 순서로 등록하면 진행 중이던 흐름을 잇는다`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val m = fakeManager(context, device, history = history)
        val special = FakeSpecialSpec("s1")
        val r1 = controller.get().registerPermissionRequester(m, listOf(PermissionSpecs.camera, special)) { results += it }
        controller.start().resume()
        r1.launch()
        val bundle = Bundle()
        controller.saveInstanceState(bundle)
        val again = Robolectric.buildActivity(ComponentActivity::class.java).create(bundle)
        val r2 = again.get().registerPermissionRequester(m, listOf(PermissionSpecs.camera, special)) { results += it }
        // 복원은 처음 쓰일 때 읽는다
        assertFalse(r2.launch())
        assertTrue(r2.isRequesting)
    }
}
