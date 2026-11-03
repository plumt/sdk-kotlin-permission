package net.plumt.permission.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultRegistry
import net.plumt.permission.MultiplePermissionsStatus
import net.plumt.permission.PermissionEnvironment
import net.plumt.permission.PermissionManager
import net.plumt.permission.PermissionRequester
import net.plumt.permission.PermissionSpec
import net.plumt.permission.PermissionSpecs
import net.plumt.permission.PermissionStatus
import net.plumt.permission.compose.internal.MultiplePermissionsStateImpl
import net.plumt.permission.compose.internal.PermissionController
import net.plumt.permission.compose.internal.PermissionFlow
import net.plumt.permission.compose.internal.SinglePermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 상태를 손으로 정하는 가짜 관리자 — 요청기는 [FakeRequester] */
private class FakeManager(var statuses: MutableMap<PermissionSpec, PermissionStatus>) : PermissionManager {
    var checks = 0
    override val environment: PermissionEnvironment = PermissionEnvironment.of(34)
    override fun check(spec: PermissionSpec, activity: Activity?): PermissionStatus = statuses.getValue(spec)
    override fun checkAll(specs: List<PermissionSpec>, activity: Activity?): MultiplePermissionsStatus {
        checks++
        return MultiplePermissionsStatus(specs.associateWith { statuses.getValue(it) })
    }
    override fun appSettingsIntent(): Intent = throw UnsupportedOperationException()
    override fun openAppSettings(context: Context) = throw UnsupportedOperationException()
    override fun createRequester(
        caller: ActivityResultCaller,
        activity: () -> Activity?,
        specs: List<PermissionSpec>,
        onResult: (MultiplePermissionsStatus) -> Unit,
    ): PermissionRequester = throw UnsupportedOperationException()
    override fun createRequester(
        registry: ActivityResultRegistry,
        key: String,
        activity: () -> Activity?,
        specs: List<PermissionSpec>,
        onResult: (MultiplePermissionsStatus) -> Unit,
    ): PermissionRequester = throw UnsupportedOperationException()
}

private class FakeRequester(override val specs: List<PermissionSpec>) : PermissionRequester {
    var launches = 0
    var accept = true
    var failure: RuntimeException? = null
    override var isRequesting: Boolean = false
    override fun launch(): Boolean {
        failure?.let { throw it }
        if (!accept) return false
        launches++
        isRequesting = true
        return true
    }
    override fun unregister() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionControllerTest {

    private val cam = PermissionSpecs.camera
    private val mic = PermissionSpecs.microphone
    private val manager = FakeManager(mutableMapOf(cam to PermissionStatus.Denied(false), mic to PermissionStatus.Granted))
    private var settingsOpened = 0
    private val received = mutableListOf<MultiplePermissionsStatus>()

    private fun controller(vararg specs: PermissionSpec = arrayOf(cam)): Pair<PermissionController, FakeRequester> {
        val c = PermissionController(manager, specs.toList(), { null }, { settingsOpened++ })
        val r = FakeRequester(specs.toList())
        c.requester = r
        c.onResult = { received += it }
        return c to r
    }

    private fun status(vararg pairs: Pair<PermissionSpec, PermissionStatus>) = MultiplePermissionsStatus(mapOf(*pairs))

    // ---- 흐름 판정

    @Test
    fun `시작 판정 — 허용 근거 설정 요청`() {
        assertEquals(PermissionFlow.Action.DONE, PermissionFlow.onStart(status(cam to PermissionStatus.Granted)))
        assertEquals(PermissionFlow.Action.RATIONALE, PermissionFlow.onStart(status(cam to PermissionStatus.Denied(true))))
        assertEquals(PermissionFlow.Action.SETTINGS, PermissionFlow.onStart(status(cam to PermissionStatus.PermanentlyDenied)))
        assertEquals(PermissionFlow.Action.LAUNCH, PermissionFlow.onStart(status(cam to PermissionStatus.Denied(false))))
        val partial = PermissionStatus.Partial(setOf("a"), setOf("b"), false, false)
        assertEquals(PermissionFlow.Action.LAUNCH, PermissionFlow.onStart(status(cam to partial)))
        // 근거 안내가 하나라도 서면 영구 거부가 섞여도 근거 안내가 먼저다
        assertEquals(
            PermissionFlow.Action.RATIONALE,
            PermissionFlow.onStart(status(cam to PermissionStatus.PermanentlyDenied, mic to PermissionStatus.Denied(true))),
        )
    }

    @Test
    fun `결과 판정 — 영구 거부만 설정 안내`() {
        assertEquals(PermissionFlow.Action.DONE, PermissionFlow.afterResult(status(cam to PermissionStatus.Granted)))
        assertEquals(PermissionFlow.Action.SETTINGS, PermissionFlow.afterResult(status(cam to PermissionStatus.PermanentlyDenied)))
        assertEquals(PermissionFlow.Action.DONE, PermissionFlow.afterResult(status(cam to PermissionStatus.Denied(true))))
        val blocked = PermissionStatus.Partial(setOf("a"), setOf("b"), false, true)
        assertEquals(PermissionFlow.Action.DONE, PermissionFlow.afterResult(status(cam to blocked)))
    }

    // ---- 컨트롤러

    @Test
    fun `만들 때 상태를 판정하고 refresh 로 다시 판정한다`() {
        val (c, _) = controller()
        assertEquals(PermissionStatus.Denied(false), c.status[cam])
        manager.statuses[cam] = PermissionStatus.Granted
        c.refresh()
        assertEquals(PermissionStatus.Granted, c.status[cam])
    }

    @Test
    fun `launchRequest 는 요청기를 부르고 진행 상태를 반영한다`() {
        val (c, r) = controller()
        c.launchRequest()
        assertEquals(1, r.launches)
        assertTrue(c.isRequesting)
        c.onRequesterResult(status(cam to PermissionStatus.Granted))
        assertFalse(c.isRequesting)
        assertEquals(PermissionStatus.Granted, c.status[cam])
        assertEquals(1, received.size)
    }

    @Test
    fun `진행 중이라 무시된 launchRequest 는 상태를 바꾸지 않는다`() {
        val (c, r) = controller()
        r.accept = false
        c.launchRequest()
        assertFalse(c.isRequesting)
    }

    @Test
    fun `요청기가 없으면 IllegalStateException`() {
        val (c, _) = controller()
        c.requester = null
        val e = assertThrows(IllegalStateException::class.java) { c.launchRequest() }
        assertEquals("컴포지션 밖에서는 요청할 수 없습니다", e.message)
    }

    @Test
    fun `요청기 예외는 흐름을 끝내고 그대로 올린다`() {
        val (c, r) = controller()
        val boom = IllegalStateException("manifest")
        r.failure = boom
        assertSame(boom, assertThrows(IllegalStateException::class.java) { c.launchFlow() })
        // 흐름이 끝났으므로 뒤늦은 영구 거부 결과가 설정 안내를 띄우지 않는다
        c.onRequesterResult(status(cam to PermissionStatus.PermanentlyDenied))
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
    }

    @Test
    fun `흐름 — 이미 허용이면 아무것도 띄우지 않는다`() {
        manager.statuses[cam] = PermissionStatus.Granted
        val (c, r) = controller()
        c.launchFlow()
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
        assertEquals(0, r.launches)
    }

    @Test
    fun `흐름 — 근거 안내 확인 후 요청하고 영구 거부면 설정 안내 확인 시 설정을 연다`() {
        manager.statuses[cam] = PermissionStatus.Denied(true)
        val (c, r) = controller()
        c.launchFlow()
        assertEquals(PermissionFlowStep.RATIONALE, c.flowStep)
        assertEquals(0, r.launches)
        c.confirmFlowStep()
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
        assertEquals(1, r.launches)
        c.onRequesterResult(status(cam to PermissionStatus.PermanentlyDenied))
        assertEquals(PermissionFlowStep.SETTINGS, c.flowStep)
        c.confirmFlowStep()
        assertEquals(1, settingsOpened)
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
        // 흐름이 끝난 뒤의 결과는 안내를 띄우지 않는다
        c.onRequesterResult(status(cam to PermissionStatus.PermanentlyDenied))
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
    }

    @Test
    fun `흐름 — 근거 안내를 닫으면 요청하지 않고 끝난다`() {
        manager.statuses[cam] = PermissionStatus.Denied(true)
        val (c, r) = controller()
        c.launchFlow()
        c.dismissFlowStep()
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
        assertEquals(0, r.launches)
    }

    @Test
    fun `흐름 — 처음이면 곧바로 요청하고 거부면 끝난다`() {
        val (c, r) = controller()
        c.launchFlow()
        assertEquals(1, r.launches)
        c.onRequesterResult(status(cam to PermissionStatus.Denied(true)))
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
    }

    @Test
    fun `흐름 — 시작부터 영구 거부면 설정 안내 닫기로 끝난다`() {
        manager.statuses[cam] = PermissionStatus.PermanentlyDenied
        val (c, r) = controller()
        c.launchFlow()
        assertEquals(PermissionFlowStep.SETTINGS, c.flowStep)
        c.dismissFlowStep()
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
        assertEquals(0, r.launches)
        assertEquals(0, settingsOpened)
    }

    @Test
    fun `흐름을 다시 시작하면 이전 안내를 지우고 최신 상태로 판정한다`() {
        manager.statuses[cam] = PermissionStatus.PermanentlyDenied
        val (c, _) = controller()
        c.launchFlow()
        manager.statuses[cam] = PermissionStatus.Granted
        c.launchFlow()
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
    }

    @Test
    fun `안내가 없을 때 확인은 아무것도 하지 않는다`() {
        val (c, r) = controller()
        c.confirmFlowStep()
        assertEquals(0, r.launches)
        assertEquals(0, settingsOpened)
    }

    @Test
    fun `openAppSettings 는 설정 열기 함수를 부른다`() {
        val (c, _) = controller()
        c.openAppSettings()
        assertEquals(1, settingsOpened)
    }

    @Test
    fun `단일 상태는 그 명세의 상태를 보이고 공통 동작을 넘긴다`() {
        val (c, r) = controller()
        val single = SinglePermissionState(c)
        assertEquals(cam, single.spec)
        assertEquals(PermissionStatus.Denied(false), single.status)
        single.launchRequest()
        assertEquals(1, r.launches)
        assertTrue(single.isRequesting)
        c.onRequesterResult(status(cam to PermissionStatus.Granted))
        assertEquals(PermissionStatus.Granted, single.status)
        assertEquals(PermissionFlowStep.NONE, single.flowStep)
    }

    @Test
    fun `단일 상태는 명세가 하나여야 한다`() {
        val (c, _) = controller(cam, mic)
        assertThrows(IllegalArgumentException::class.java) { SinglePermissionState(c) }
    }

    @Test
    fun `여러 상태는 묶음을 그대로 보인다`() {
        val (c, _) = controller(cam, mic)
        val multi = MultiplePermissionsStateImpl(c)
        assertEquals(listOf(cam, mic), multi.specs)
        assertEquals(PermissionStatus.Granted, multi.status[mic])
        assertFalse(multi.status.allGranted)
        multi.refresh()
        assertEquals(2, manager.checks)
    }

    @Test
    fun `흐름 단계 값`() {
        assertEquals(listOf("NONE", "RATIONALE", "SETTINGS"), PermissionFlowStep.entries.map { it.name })
    }
}
