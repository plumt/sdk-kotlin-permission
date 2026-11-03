package net.plumt.permission.compose.internal

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.plumt.permission.MultiplePermissionsStatus
import net.plumt.permission.PermissionManager
import net.plumt.permission.PermissionRequester
import net.plumt.permission.PermissionSpec
import net.plumt.permission.PermissionStatus
import net.plumt.permission.compose.MultiplePermissionsState
import net.plumt.permission.compose.PermissionFlowStep
import net.plumt.permission.compose.PermissionRequestState
import net.plumt.permission.compose.PermissionState

/** 흐름 판정. 상태 묶음만 보고 다음 행동을 정한다 */
internal object PermissionFlow {
    enum class Action { DONE, RATIONALE, SETTINGS, LAUNCH }

    fun onStart(status: MultiplePermissionsStatus): Action = when {
        status.allGranted -> Action.DONE
        status.shouldShowRationale -> Action.RATIONALE
        status.onlySettingsCanHelp -> Action.SETTINGS
        else -> Action.LAUNCH
    }

    fun afterResult(status: MultiplePermissionsStatus): Action = when {
        status.allGranted -> Action.DONE
        status.anyPermanentlyDenied -> Action.SETTINGS
        else -> Action.DONE
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
) : PermissionRequestState {

    var requester: PermissionRequester? = null
    var onResult: (MultiplePermissionsStatus) -> Unit = {}

    var status: MultiplePermissionsStatus by mutableStateOf(manager.checkAll(specs, activity()))
        private set

    override var flowStep: PermissionFlowStep by mutableStateOf(PermissionFlowStep.NONE)
        private set

    private var requesting by mutableStateOf(false)
    private var flowActive = false

    override val isRequesting: Boolean get() = requesting

    override fun refresh() {
        status = manager.checkAll(specs, activity())
    }

    override fun launchRequest() {
        val current = checkNotNull(requester) { "컴포지션 밖에서는 요청할 수 없습니다" }
        try {
            if (current.launch()) requesting = current.isRequesting
        } catch (e: RuntimeException) {
            flowActive = false
            throw e
        }
    }

    override fun launchFlow() {
        refresh()
        flowStep = PermissionFlowStep.NONE
        when (PermissionFlow.onStart(status)) {
            PermissionFlow.Action.DONE -> flowActive = false
            PermissionFlow.Action.RATIONALE -> {
                flowActive = true
                flowStep = PermissionFlowStep.RATIONALE
            }
            PermissionFlow.Action.SETTINGS -> {
                flowActive = true
                flowStep = PermissionFlowStep.SETTINGS
            }
            PermissionFlow.Action.LAUNCH -> {
                flowActive = true
                launchRequest()
            }
        }
    }

    override fun confirmFlowStep() {
        when (flowStep) {
            PermissionFlowStep.NONE -> Unit
            PermissionFlowStep.RATIONALE -> {
                flowStep = PermissionFlowStep.NONE
                launchRequest()
            }
            PermissionFlowStep.SETTINGS -> {
                flowStep = PermissionFlowStep.NONE
                flowActive = false
                openSettings()
            }
        }
    }

    override fun dismissFlowStep() {
        flowStep = PermissionFlowStep.NONE
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
            if (PermissionFlow.afterResult(result) == PermissionFlow.Action.SETTINGS) {
                flowActive = true
                flowStep = PermissionFlowStep.SETTINGS
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
