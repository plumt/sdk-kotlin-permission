package net.plumt.permission.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultRegistry
import androidx.savedstate.SavedStateRegistry
import net.plumt.permission.MultiplePermissionsStatus
import net.plumt.permission.PermissionEnvironment
import net.plumt.permission.PermissionManager
import net.plumt.permission.PermissionRequester
import net.plumt.permission.PermissionSpec
import net.plumt.permission.PermissionSpecs
import net.plumt.permission.PermissionStatus
import net.plumt.permission.RequestResult
import net.plumt.permission.RequirementStatus
import net.plumt.permission.SettingsPolicy
import net.plumt.permission.SystemRequirement
import androidx.activity.ComponentActivity
import androidx.compose.runtime.saveable.SaverScope
import net.plumt.permission.compose.internal.FlowMemory
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
private class FakeManager(
    var statuses: MutableMap<PermissionSpec, PermissionStatus>,
    var requirementStatuses: MutableMap<SystemRequirement, RequirementStatus> = mutableMapOf(),
) : PermissionManager {
    var checks = 0
    override val environment: PermissionEnvironment = PermissionEnvironment.of(34)
    override fun check(spec: PermissionSpec, activity: Activity?): PermissionStatus = statuses.getValue(spec)
    override fun check(requirement: SystemRequirement): RequirementStatus = requirementStatuses.getValue(requirement)
    override fun checkAll(specs: List<PermissionSpec>, activity: Activity?): MultiplePermissionsStatus {
        checks++
        return MultiplePermissionsStatus(specs.associateWith { statuses.getValue(it) })
    }
    override fun checkAll(
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement>,
        activity: Activity?,
    ): MultiplePermissionsStatus {
        checks++
        return MultiplePermissionsStatus(specs.associateWith { statuses.getValue(it) }, requirements.associateWith { check(it) })
    }
    override fun undeclaredPermissions(specs: List<PermissionSpec>, requirements: List<SystemRequirement>): Set<String> =
        emptySet()
    override fun appSettingsIntent(): Intent = throw UnsupportedOperationException()
    override fun openAppSettings(context: Context) = throw UnsupportedOperationException()
    override fun notificationSettingsIntent(channelId: String?): Intent = throw UnsupportedOperationException()
    override fun openNotificationSettings(context: Context, channelId: String?) = throw UnsupportedOperationException()
    override fun createRequester(
        caller: ActivityResultCaller,
        activity: () -> Activity?,
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement>,
        onResult: (MultiplePermissionsStatus) -> Unit,
    ): PermissionRequester = throw UnsupportedOperationException()
    override fun createRequester(
        registry: ActivityResultRegistry,
        key: String,
        activity: () -> Activity?,
        specs: List<PermissionSpec>,
        savedStateRegistry: SavedStateRegistry?,
        requirements: List<SystemRequirement>,
        onResult: (MultiplePermissionsStatus) -> Unit,
    ): PermissionRequester = throw UnsupportedOperationException()
    override suspend fun request(
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement>,
        settingsPolicy: SettingsPolicy,
    ): RequestResult = throw UnsupportedOperationException()
    override suspend fun request(
        activity: ComponentActivity,
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement>,
        settingsPolicy: SettingsPolicy,
    ): RequestResult = throw UnsupportedOperationException()
}

private class FakeRequester(
    override val specs: List<PermissionSpec>,
    override val requirements: List<SystemRequirement> = emptyList(),
) : PermissionRequester {
    var launches = 0
    var accept = true
    var failure: RuntimeException? = null
    val policies = mutableListOf<SettingsPolicy>()
    var settingsOpens = 0
    override var isRequesting: Boolean = false
    override fun launch(): Boolean = launch(SettingsPolicy.NEVER)
    override fun launch(settingsPolicy: SettingsPolicy): Boolean {
        failure?.let { throw it }
        if (!accept) return false
        launches++
        policies += settingsPolicy
        isRequesting = true
        return true
    }
    override fun openAppSettings(): Boolean {
        settingsOpens++
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

    private var settingsFailure: RuntimeException? = null

    private fun controller(
        vararg specs: PermissionSpec = arrayOf(cam),
        memory: FlowMemory = FlowMemory(),
        requirements: List<SystemRequirement> = emptyList(),
    ): Pair<PermissionController, FakeRequester> {
        val c = PermissionController(manager, specs.toList(), { null }, {
            settingsFailure?.let { throw it }
            settingsOpened++
        }, memory, requirements)
        val r = FakeRequester(specs.toList(), requirements)
        c.attach(r)
        c.onResult = { received += it }
        return c to r
    }

    private fun status(vararg pairs: Pair<PermissionSpec, PermissionStatus>) = MultiplePermissionsStatus(mapOf(*pairs))

    // ---- 흐름 판정

    @Test
    fun `시작 판정 — 허용 근거 설정 요청`() {
        assertEquals(PermissionFlow.Action.DONE, PermissionFlow.onStart(status(cam to PermissionStatus.Granted)))
        assertEquals(PermissionFlow.Action.RATIONALE, PermissionFlow.onStart(status(cam to PermissionStatus.Denied(true))))
        // 영구 거부 판정은 이력에서 온 추정이라 먼저 시스템 요청으로 확인한다(H1) — 진짜면 결과 판정이 설정 안내를 띄운다
        assertEquals(PermissionFlow.Action.LAUNCH, PermissionFlow.onStart(status(cam to PermissionStatus.PermanentlyDenied)))
        val blockedStart = PermissionStatus.Partial(setOf("a"), setOf("b"), false, true)
        assertEquals(PermissionFlow.Action.LAUNCH, PermissionFlow.onStart(status(cam to blockedStart)))
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
    fun `결과 판정 — 설정으로만 바꿀 수 있으면 설정 안내`() {
        assertEquals(PermissionFlow.Action.DONE, PermissionFlow.afterResult(status(cam to PermissionStatus.Granted)))
        assertEquals(PermissionFlow.Action.SETTINGS, PermissionFlow.afterResult(status(cam to PermissionStatus.PermanentlyDenied)))
        assertEquals(PermissionFlow.Action.DONE, PermissionFlow.afterResult(status(cam to PermissionStatus.Denied(true))))
        // 나머지가 영구 거부인 일부 허용도 설정으로만 넓힐 수 있다(L2)
        val blocked = PermissionStatus.Partial(setOf("a"), setOf("b"), false, true)
        assertEquals(PermissionFlow.Action.SETTINGS, PermissionFlow.afterResult(status(cam to blocked)))
        val open = PermissionStatus.Partial(setOf("a"), setOf("b"), false, false)
        assertEquals(PermissionFlow.Action.DONE, PermissionFlow.afterResult(status(cam to open)))
        assertEquals(
            PermissionFlow.Action.SETTINGS,
            PermissionFlow.afterResult(status(cam to PermissionStatus.Granted, mic to PermissionStatus.PermanentlyDenied)),
        )
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
        c.attach(null)
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
    fun `H1 흐름 — 시작부터 영구 거부여도 먼저 요청하고 진짜 영구 거부면 설정 안내 닫기로 끝난다`() {
        manager.statuses[cam] = PermissionStatus.PermanentlyDenied
        val (c, r) = controller()
        c.launchFlow()
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
        assertEquals(1, r.launches)
        // 시스템이 대화상자 없이 곧바로 거부를 돌려줬다 — 진짜 영구 거부
        c.onRequesterResult(status(cam to PermissionStatus.PermanentlyDenied))
        assertEquals(PermissionFlowStep.SETTINGS, c.flowStep)
        c.dismissFlowStep()
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
        assertEquals(0, settingsOpened)
    }

    @Test
    fun `H1 흐름 — 영구 거부가 오판이면 대화상자 결과로 바로잡히고 설정 안내를 띄우지 않는다`() {
        manager.statuses[cam] = PermissionStatus.PermanentlyDenied
        val (c, r) = controller()
        c.launchFlow()
        assertEquals(1, r.launches)
        c.onRequesterResult(status(cam to PermissionStatus.Granted))
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
        assertEquals(PermissionStatus.Granted, c.status[cam])
    }

    @Test
    fun `닫기 흐름 — 대화상자를 닫아 Denied(false) 면 설정 안내 없이 끝나고 다음 흐름도 대화상자를 먼저 띄운다`() {
        val (c, r) = controller()
        repeat(2) { round ->
            c.launchFlow()
            assertEquals(round + 1, r.launches)
            // 바깥 탭·뒤로가기로 닫음 — 핵심 판정은 Denied(false) 를 유지한다
            c.onRequesterResult(status(cam to PermissionStatus.Denied(false)))
            assertEquals(PermissionFlowStep.NONE, c.flowStep)
        }
        manager.statuses[cam] = PermissionStatus.Denied(false)
        c.launchFlow()
        assertEquals(3, r.launches)
        assertEquals(0, settingsOpened)
        // 다음 요청이 화면 없이 끝나 영구 거부로 판정되면 그제야 설정 안내
        c.onRequesterResult(status(cam to PermissionStatus.PermanentlyDenied))
        assertEquals(PermissionFlowStep.SETTINGS, c.flowStep)
    }

    @Test
    fun `H1 흐름 — 나머지가 영구 거부인 일부 허용도 먼저 요청한다`() {
        val blocked = PermissionStatus.Partial(setOf("a"), setOf("b"), false, true)
        manager.statuses[cam] = blocked
        val (c, r) = controller()
        c.launchFlow()
        assertEquals(1, r.launches)
        c.onRequesterResult(status(cam to blocked))
        assertEquals(PermissionFlowStep.SETTINGS, c.flowStep)
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
        assertEquals(listOf("NONE", "RATIONALE", "SETTINGS", "REQUIREMENT"), PermissionFlowStep.entries.map { it.name })
    }

    // ---- M3: 흐름이 연 설정 화면에서 돌아오면 onResult

    @Test
    fun `M3 흐름이 연 설정 화면에서 돌아오면 재판정 결과를 한 번 콜백한다`() {
        manager.statuses[cam] = PermissionStatus.PermanentlyDenied
        val (c, _) = controller()
        c.launchFlow()
        c.onRequesterResult(status(cam to PermissionStatus.PermanentlyDenied))
        received.clear()
        c.confirmFlowStep()
        assertEquals(1, settingsOpened)
        // 사용자가 설정에서 허용하고 돌아왔다
        manager.statuses[cam] = PermissionStatus.Granted
        c.onResume()
        assertEquals(PermissionStatus.Granted, received.single()[cam])
        // 다음 ON_RESUME 은 상태만 갱신한다
        c.onResume()
        assertEquals(1, received.size)
    }

    @Test
    fun `M3 흐름 밖의 ON_RESUME 은 상태만 갱신한다`() {
        val (c, _) = controller()
        manager.statuses[cam] = PermissionStatus.Granted
        c.onResume()
        assertEquals(PermissionStatus.Granted, c.status[cam])
        assertTrue(received.isEmpty())
        // 직접 연 설정 화면도 콜백하지 않는다
        c.openAppSettings()
        c.onResume()
        assertTrue(received.isEmpty())
    }

    @Test
    fun `M3 설정 화면을 열지 못하면 돌아올 때 콜백하지 않고 예외를 올린다`() {
        manager.statuses[cam] = PermissionStatus.PermanentlyDenied
        val (c, _) = controller()
        c.launchFlow()
        c.onRequesterResult(status(cam to PermissionStatus.PermanentlyDenied))
        received.clear()
        val boom = IllegalStateException("settings")
        settingsFailure = boom
        assertSame(boom, assertThrows(IllegalStateException::class.java) { c.confirmFlowStep() })
        c.onResume()
        assertTrue(received.isEmpty())
    }

    // ---- M1: 재생성 뒤 흐름 잇기

    @Test
    fun `M1 흐름 상태는 저장했다가 복원된다`() {
        val memory = FlowMemory(PermissionFlowStep.SETTINGS, active = true, awaitingSettings = true)
        val saved = with(FlowMemory.Saver) { SaverScope { true }.save(memory) }!!
        val restored = FlowMemory.Saver.restore(saved)!!
        assertEquals(PermissionFlowStep.SETTINGS, restored.step)
        assertTrue(restored.active)
        assertTrue(restored.awaitingSettings)
        val empty = FlowMemory.Saver.restore(with(FlowMemory.Saver) { SaverScope { true }.save(FlowMemory()) }!!)!!
        assertEquals(PermissionFlowStep.NONE, empty.step)
        assertFalse(empty.active)
        assertFalse(empty.awaitingSettings)
    }

    @Test
    fun `M1 재생성 뒤 진행 중이던 흐름의 영구 거부 결과는 설정 안내를 띄운다`() {
        val memory = FlowMemory()
        val (before, _) = controller(memory = memory)
        before.launchFlow()
        // 화면이 다시 만들어져 새 컨트롤러가 같은 흐름 상태를 받는다
        val (after, _) = controller(memory = FlowMemory.Saver.restore(with(FlowMemory.Saver) { SaverScope { true }.save(memory) }!!)!!)
        after.onRequesterResult(status(cam to PermissionStatus.PermanentlyDenied))
        assertEquals(PermissionFlowStep.SETTINGS, after.flowStep)
    }

    @Test
    fun `M1 재생성 뒤 보이던 안내가 다시 보인다`() {
        val (c, _) = controller(memory = FlowMemory(PermissionFlowStep.RATIONALE, active = true))
        assertEquals(PermissionFlowStep.RATIONALE, c.flowStep)
        c.confirmFlowStep()
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
    }

    @Test
    fun `M1 이어받은 요청기가 진행 중이면 진행 상태를 따른다`() {
        val (c, _) = controller()
        val r = FakeRequester(listOf(cam)).apply { isRequesting = true }
        c.attach(r)
        assertTrue(c.isRequesting)
        assertSame(r, c.requester)
        c.attach(null)
        assertFalse(c.isRequesting)
    }

    // ---- 설정 이동 정책

    @Test
    fun `정책별 결과 판정`() {
        val denied = status(cam to PermissionStatus.PermanentlyDenied)
        assertEquals(PermissionFlow.Action.SETTINGS, PermissionFlow.afterResult(denied, SettingsPolicy.PROMPT))
        assertEquals(PermissionFlow.Action.DONE, PermissionFlow.afterResult(denied, SettingsPolicy.NEVER))
        assertEquals(PermissionFlow.Action.DONE, PermissionFlow.afterResult(denied, SettingsPolicy.DIRECT))
    }

    @Test
    fun `launchFlow 기본은 PROMPT 이고 요청기에는 NEVER 로 보낸다`() {
        val (c, r) = controller()
        c.launchFlow()
        assertEquals(listOf(SettingsPolicy.NEVER), r.policies)
        c.onRequesterResult(status(cam to PermissionStatus.PermanentlyDenied))
        assertEquals(PermissionFlowStep.SETTINGS, c.flowStep)
    }

    @Test
    fun `NEVER 는 영구 거부여도 설정 안내 없이 끝난다`() {
        val (c, r) = controller()
        c.launchFlow(SettingsPolicy.NEVER)
        assertEquals(listOf(SettingsPolicy.NEVER), r.policies)
        c.onRequesterResult(status(cam to PermissionStatus.PermanentlyDenied))
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
        assertEquals(0, settingsOpened)
        assertEquals(1, received.size)
    }

    @Test
    fun `DIRECT 는 요청기에 바로 이동을 맡기고 안내를 띄우지 않는다`() {
        val (c, r) = controller()
        c.launchFlow(SettingsPolicy.DIRECT)
        assertEquals(listOf(SettingsPolicy.DIRECT), r.policies)
        c.onRequesterResult(status(cam to PermissionStatus.PermanentlyDenied))
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
        // 근거 안내 확인 뒤 요청도 같은 정책으로 간다
        manager.statuses[cam] = PermissionStatus.Denied(true)
        c.launchFlow(SettingsPolicy.DIRECT)
        assertEquals(PermissionFlowStep.RATIONALE, c.flowStep)
        c.confirmFlowStep()
        assertEquals(listOf(SettingsPolicy.DIRECT, SettingsPolicy.DIRECT), r.policies)
        // launchRequest 는 흐름 밖이라 언제나 NEVER
        c.launchRequest()
        assertEquals(SettingsPolicy.NEVER, r.policies.last())
    }

    // ---- 시스템 기능

    private val bt = object : SystemRequirement {
        override val id = "bt"
        override fun status(context: android.content.Context, environment: PermissionEnvironment) = RequirementStatus.Satisfied
        override fun enableRequest(context: android.content.Context, onReady: (net.plumt.permission.EnableRequest?) -> Unit) = Unit
    }

    @Test
    fun `요구 조건이 있으면 함께 판정하고 상태를 따로 보인다`() {
        manager.requirementStatuses[bt] = RequirementStatus.Disabled
        val (c, _) = controller(requirements = listOf(bt))
        assertEquals(mapOf<SystemRequirement, RequirementStatus>(bt to RequirementStatus.Disabled), c.requirementStatuses)
        assertEquals(listOf(bt), c.requirements)
        manager.requirementStatuses[bt] = RequirementStatus.Satisfied
        c.refresh()
        assertEquals(RequirementStatus.Satisfied, c.requirementStatuses[bt])
        val single = SinglePermissionState(c)
        assertEquals(RequirementStatus.Satisfied, single.requirementStatuses[bt])
    }

    @Test
    fun `권한이 다 있어도 꺼진 기능이 있으면 흐름은 요청하고 결과가 꺼짐이면 켜기 재요청 안내`() {
        manager.statuses[cam] = PermissionStatus.Granted
        manager.requirementStatuses[bt] = RequirementStatus.Disabled
        val (c, r) = controller(requirements = listOf(bt))
        c.launchFlow()
        assertEquals(1, r.launches)
        c.onRequesterResult(MultiplePermissionsStatus(mapOf(cam to PermissionStatus.Granted), mapOf(bt to RequirementStatus.Disabled)))
        assertEquals(PermissionFlowStep.REQUIREMENT, c.flowStep)
        // 확인하면 켜기 화면을 다시 연다(요청기를 다시 부른다)
        c.confirmFlowStep()
        assertEquals(2, r.launches)
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
        c.onRequesterResult(MultiplePermissionsStatus(mapOf(cam to PermissionStatus.Granted), mapOf(bt to RequirementStatus.Satisfied)))
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
        // 닫으면 끝난다
        c.launchFlow()
        c.onRequesterResult(MultiplePermissionsStatus(mapOf(cam to PermissionStatus.Granted), mapOf(bt to RequirementStatus.Disabled)))
        c.dismissFlowStep()
        assertEquals(PermissionFlowStep.NONE, c.flowStep)
    }

    @Test
    fun `요구 조건 시작·결과 판정`() {
        fun st(p: PermissionStatus, r: RequirementStatus) = MultiplePermissionsStatus(mapOf(cam to p), mapOf(bt to r))
        assertEquals(PermissionFlow.Action.DONE, PermissionFlow.onStart(st(PermissionStatus.Granted, RequirementStatus.Satisfied)))
        // 쓸 수 없는 기능은 요청해도 소용없다
        assertEquals(
            PermissionFlow.Action.DONE,
            PermissionFlow.onStart(st(PermissionStatus.Granted, RequirementStatus.Unsupported(net.plumt.permission.UnsupportedReason.NO_HARDWARE))),
        )
        assertEquals(PermissionFlow.Action.LAUNCH, PermissionFlow.onStart(st(PermissionStatus.Granted, RequirementStatus.Disabled)))
        assertEquals(
            PermissionFlow.Action.LAUNCH,
            PermissionFlow.onStart(st(PermissionStatus.Granted, RequirementStatus.PermissionRequired(setOf("x")))),
        )
        // 설정 안내가 켜기 안내보다 먼저다
        assertEquals(PermissionFlow.Action.SETTINGS, PermissionFlow.afterResult(st(PermissionStatus.PermanentlyDenied, RequirementStatus.Disabled)))
        assertEquals(
            PermissionFlow.Action.REQUIREMENT,
            PermissionFlow.afterResult(st(PermissionStatus.PermanentlyDenied, RequirementStatus.Disabled), SettingsPolicy.NEVER),
        )
        // 권한을 받지 못해 켤 수 없는 상태는 켜기 재요청 안내를 띄우지 않는다
        assertEquals(PermissionFlow.Action.DONE, PermissionFlow.afterResult(st(PermissionStatus.Denied(false), RequirementStatus.PermissionRequired(setOf("x")))))
    }

    @Test
    fun `흐름 상태 저장은 정책을 담고 옛 형식도 읽는다`() {
        val memory = FlowMemory(PermissionFlowStep.REQUIREMENT, active = true, awaitingSettings = false, policy = SettingsPolicy.DIRECT)
        val restored = FlowMemory.Saver.restore(with(FlowMemory.Saver) { SaverScope { true }.save(memory) }!!)!!
        assertEquals(SettingsPolicy.DIRECT, restored.policy)
        assertEquals(PermissionFlowStep.REQUIREMENT, restored.step)
        val old = FlowMemory.Saver.restore(intArrayOf(0, 0, 0))!!
        assertEquals(SettingsPolicy.PROMPT, old.policy)
        val broken = FlowMemory.Saver.restore(intArrayOf(0, 0, 0, 99))!!
        assertEquals(SettingsPolicy.PROMPT, broken.policy)
    }
}
