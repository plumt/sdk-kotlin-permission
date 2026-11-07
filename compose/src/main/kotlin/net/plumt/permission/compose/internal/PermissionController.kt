package net.plumt.permission.compose.internal

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import net.plumt.permission.MultiplePermissionsStatus
import net.plumt.permission.PermissionManager
import net.plumt.permission.PermissionRequester
import net.plumt.permission.PermissionSpec
import net.plumt.permission.PermissionStatus
import net.plumt.permission.RequirementStatus
import net.plumt.permission.SettingsPolicy
import net.plumt.permission.SystemRequirement
import net.plumt.permission.needsSettings
import net.plumt.permission.compose.MultiplePermissionsState
import net.plumt.permission.compose.PermissionFlowStep
import net.plumt.permission.compose.PermissionRequestState
import net.plumt.permission.compose.PermissionState

/** 흐름 판정. 상태 묶음만 보고 다음 행동을 정한다 */
internal object PermissionFlow {
    enum class Action { DONE, RATIONALE, SETTINGS, REQUIREMENT, LAUNCH }

    /**
     * 시작할 때는 영구 거부여도 설정 안내로 가지 않고 먼저 요청한다. 영구 거부는 이력에서 온 추정이라
     * 틀릴 수 있고(설정에서 초기화·이력 유실), 진짜 영구 거부면 시스템이 화면 없이 곧바로 거부를 돌려주므로
     * 비용이 없다. 그 결과를 [afterResult] 가 설정 안내로 바꾼다. 권한이 다 있어도 꺼진 기능이 있으면 요청(켜기 화면)이다.
     */
    fun onStart(status: MultiplePermissionsStatus): Action = when {
        status.allGranted && status.pendingRequirements.isEmpty() -> Action.DONE
        status.shouldShowRationale -> Action.RATIONALE
        else -> Action.LAUNCH
    }

    /**
     * 요청 결과에 설정으로만 바꿀 수 있는 것(영구 거부, 나머지가 영구 거부인 일부 허용)이 있으면 정책에 따라 설정 안내,
     * 그다음 아직 꺼진 시스템 기능이 있으면 켜기 재요청 안내다. [SettingsPolicy.DIRECT] 는 요청기가 이미 설정 화면을 거쳤다.
     */
    fun afterResult(status: MultiplePermissionsStatus, policy: SettingsPolicy = SettingsPolicy.PROMPT): Action = when {
        status.isReady -> Action.DONE
        policy == SettingsPolicy.PROMPT && status.anyNeedsSettings -> Action.SETTINGS
        status.requirements.values.any { it == RequirementStatus.Disabled } -> Action.REQUIREMENT
        else -> Action.DONE
    }
}

/**
 * 화면 재생성·프로세스 종료 뒤에도 이어야 하는 흐름 상태. `rememberSaveable` 이 [Saver] 로 남긴다.
 *
 * @property active 흐름이 진행 중인지 — 참일 때만 요청 결과가 설정 안내로 이어진다
 * @property awaitingSettings 흐름이 설정 화면을 열었는지 — 돌아오면(ON_RESUME) 재판정 결과를 콜백한다
 * @property policy 진행 중인 흐름의 설정 이동 정책
 */
internal class FlowMemory(
    step: PermissionFlowStep = PermissionFlowStep.NONE,
    var active: Boolean = false,
    var awaitingSettings: Boolean = false,
    var policy: SettingsPolicy = SettingsPolicy.PROMPT,
) {
    var step: PermissionFlowStep by mutableStateOf(step)

    companion object {
        val Saver: Saver<FlowMemory, IntArray> = Saver(
            save = { intArrayOf(it.step.ordinal, it.active.toInt(), it.awaitingSettings.toInt(), it.policy.ordinal) },
            restore = {
                FlowMemory(
                    PermissionFlowStep.entries[it[0]],
                    it[1] == 1,
                    it[2] == 1,
                    SettingsPolicy.entries.getOrElse(it.getOrElse(3) { SettingsPolicy.PROMPT.ordinal }) { SettingsPolicy.PROMPT },
                )
            },
        )

        private fun Boolean.toInt(): Int = if (this) 1 else 0
    }
}

/**
 * Compose 상태의 실제 구현. 컴포저블에 묶이지 않아 단위 테스트한다.
 * [requester] 는 컴포지션에 들어갈 때 붙고 나올 때 떨어진다.
 */
internal class PermissionController(
    private val manager: PermissionManager,
    val specs: List<PermissionSpec>,
    private val activity: () -> Activity?,
    private val openSettings: () -> Unit,
    private val memory: FlowMemory = FlowMemory(),
    val requirements: List<SystemRequirement> = emptyList(),
) : PermissionRequestState {

    var requester: PermissionRequester? = null
        private set
    var onResult: (MultiplePermissionsStatus) -> Unit = {}

    var status: MultiplePermissionsStatus by mutableStateOf(checkNow())
        private set

    override val flowStep: PermissionFlowStep get() = memory.step

    override val requirementStatuses: Map<SystemRequirement, RequirementStatus> get() = status.requirements

    private var requesting by mutableStateOf(false)
    private var flowActive: Boolean
        get() = memory.active
        set(value) {
            memory.active = value
        }

    override val isRequesting: Boolean get() = requesting

    private fun checkNow(): MultiplePermissionsStatus =
        if (requirements.isEmpty()) manager.checkAll(specs, activity()) else manager.checkAll(specs, requirements, activity())

    /** 요청기를 붙인다. 재생성 뒤 요청기가 진행 중이던 흐름을 이어받았으면 그 상태를 따른다 */
    fun attach(requester: PermissionRequester?) {
        this.requester = requester
        requesting = requester?.isRequesting == true
    }

    override fun refresh() {
        status = checkNow()
    }

    /** 화면이 다시 보일 때. 흐름이 연 설정 화면에서 돌아왔으면 재판정 결과를 [onResult] 로 준다 */
    fun onResume() {
        refresh()
        if (memory.awaitingSettings) {
            memory.awaitingSettings = false
            onResult(status)
        }
    }

    override fun launchRequest() = launchWith(SettingsPolicy.NEVER)

    /** 흐름 안의 요청 — 바로 이동 정책이면 요청기가 설정 화면까지 거친다 */
    private fun launchInFlow() = launchWith(if (memory.policy == SettingsPolicy.DIRECT) SettingsPolicy.DIRECT else SettingsPolicy.NEVER)

    private fun launchWith(policy: SettingsPolicy) {
        val current = checkNotNull(requester) { "컴포지션 밖에서는 요청할 수 없습니다" }
        try {
            if (current.launch(policy)) requesting = current.isRequesting
        } catch (e: RuntimeException) {
            flowActive = false
            throw e
        }
    }

    override fun launchFlow() = launchFlow(SettingsPolicy.PROMPT)

    override fun launchFlow(settingsPolicy: SettingsPolicy) {
        refresh()
        memory.step = PermissionFlowStep.NONE
        memory.policy = settingsPolicy
        when (PermissionFlow.onStart(status)) {
            PermissionFlow.Action.DONE -> flowActive = false
            PermissionFlow.Action.RATIONALE -> {
                flowActive = true
                memory.step = PermissionFlowStep.RATIONALE
            }
            // 시작 판정은 설정 안내를 내지 않는다 — 나머지는 요청이다
            else -> {
                flowActive = true
                launchInFlow()
            }
        }
    }

    override fun confirmFlowStep() {
        when (flowStep) {
            PermissionFlowStep.NONE -> Unit
            PermissionFlowStep.RATIONALE, PermissionFlowStep.REQUIREMENT -> {
                memory.step = PermissionFlowStep.NONE
                launchInFlow()
            }
            PermissionFlowStep.SETTINGS -> {
                memory.step = PermissionFlowStep.NONE
                flowActive = false
                memory.awaitingSettings = true
                try {
                    openSettings()
                } catch (e: RuntimeException) {
                    memory.awaitingSettings = false
                    throw e
                }
            }
        }
    }

    override fun dismissFlowStep() {
        memory.step = PermissionFlowStep.NONE
        flowActive = false
    }

    override fun openAppSettings() {
        openSettings()
    }

    fun onRequesterResult(result: MultiplePermissionsStatus) {
        status = result
        requesting = false
        if (flowActive) {
            flowActive = false
            when (PermissionFlow.afterResult(result, memory.policy)) {
                PermissionFlow.Action.SETTINGS -> {
                    flowActive = true
                    memory.step = PermissionFlowStep.SETTINGS
                }
                PermissionFlow.Action.REQUIREMENT -> {
                    flowActive = true
                    memory.step = PermissionFlowStep.REQUIREMENT
                }
                else -> Unit
            }
        }
        onResult(result)
    }
}

internal class SinglePermissionState(private val controller: PermissionController) :
    PermissionState, PermissionRequestState by controller {
    override val spec: PermissionSpec = controller.specs.single()
    override val status: PermissionStatus get() = controller.status[spec]
}

internal class MultiplePermissionsStateImpl(private val controller: PermissionController) :
    MultiplePermissionsState, PermissionRequestState by controller {
    override val specs: List<PermissionSpec> get() = controller.specs
    override val status: MultiplePermissionsStatus get() = controller.status
}
