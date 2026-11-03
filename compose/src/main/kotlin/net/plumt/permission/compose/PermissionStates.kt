package net.plumt.permission.compose

import androidx.compose.runtime.Stable
import net.plumt.permission.MultiplePermissionsStatus
import net.plumt.permission.PermissionSpec
import net.plumt.permission.PermissionStatus

/** 전체 흐름([PermissionRequestState.launchFlow])에서 지금 보여야 할 안내 */
public enum class PermissionFlowStep {
    /** 보일 안내가 없다 */
    NONE,

    /** 요청 전에 근거 안내를 보인다. 확인하면 시스템 요청이 나간다 */
    RATIONALE,

    /** 영구 거부라 설정 화면으로 안내한다. 확인하면 앱 설정 화면이 열린다 */
    SETTINGS,
}

/**
 * 권한 요청 상태의 공통 부분. 화면이 다시 보일 때(ON_RESUME)마다 상태를 다시 판정한다 —
 * 설정 화면에서 돌아온 경우가 여기서 반영된다.
 *
 * 모든 함수는 메인 스레드(이벤트 콜백)에서 부른다.
 */
@Stable
public interface PermissionRequestState {

    /** 전체 흐름에서 지금 보여야 할 안내. [PermissionFlowDialogs] 가 이 값으로 대화상자를 띄운다. */
    public val flowStep: PermissionFlowStep

    /** 요청 흐름이 진행 중인지 */
    public val isRequesting: Boolean

    /**
     * 근거 안내·설정 안내 없이 시스템 요청만 보낸다. 이미 진행 중이면 무시한다.
     *
     * @throws IllegalStateException 요청할 권한이 앱 매니페스트에 없을 때(디버그 빌드 기본값),
     *   또는 컴포지션에서 벗어난 뒤에 부를 때
     * @throws net.plumt.permission.PermissionException 특수 권한 설정 화면을 열 수 없을 때
     */
    public fun launchRequest()

    /**
     * 확인 → 근거 안내 → 요청 → 결과 → 영구 거부면 설정 안내 → 설정 화면 → 돌아오면 재판정까지의 흐름을 시작한다.
     * 안내는 [flowStep] 으로 드러나고 [confirmFlowStep]·[dismissFlowStep] 으로 진행한다.
     *
     * @throws IllegalStateException [launchRequest] 와 같다
     */
    public fun launchFlow()

    /** 지금 안내를 확인한다 — 근거 안내면 요청을 보내고, 설정 안내면 앱 설정 화면을 연다. 안내가 없으면 아무것도 하지 않는다. */
    public fun confirmFlowStep()

    /** 지금 안내를 닫고 흐름을 끝낸다. */
    public fun dismissFlowStep()

    /**
     * 앱 상세 설정 화면을 연다.
     *
     * @throws net.plumt.permission.PermissionException 설정 화면을 열 수 없을 때
     */
    public fun openAppSettings()

    /** 상태를 지금 다시 판정한다. */
    public fun refresh()
}

/** 권한 하나의 Compose 상태. [rememberPermissionState] 로 만든다. */
@Stable
public interface PermissionState : PermissionRequestState {
    /** 다루는 명세 */
    public val spec: PermissionSpec

    /** 지금 상태 */
    public val status: PermissionStatus
}

/** 여러 권한의 Compose 상태. [rememberMultiplePermissionsState] 로 만든다. 런타임 권한은 한 번에 묻는다. */
@Stable
public interface MultiplePermissionsState : PermissionRequestState {
    /** 다루는 명세(넘긴 순서) */
    public val specs: List<PermissionSpec>

    /** 지금 상태 묶음 */
    public val status: MultiplePermissionsStatus
}
