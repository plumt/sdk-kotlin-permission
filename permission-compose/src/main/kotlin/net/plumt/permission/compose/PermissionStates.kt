package net.plumt.permission.compose

import androidx.compose.runtime.Stable
import net.plumt.permission.MultiplePermissionsStatus
import net.plumt.permission.PermissionSpec
import net.plumt.permission.PermissionStatus
import net.plumt.permission.RequirementStatus
import net.plumt.permission.SettingsPolicy
import net.plumt.permission.SystemRequirement

/** 전체 흐름([PermissionRequestState.launchFlow])에서 지금 보여야 할 안내. 넷으로 닫힌 집합이다. */
public sealed interface PermissionFlowStep {
    /** 보일 안내가 없다 */
    public data object None : PermissionFlowStep

    /** 요청 전에 근거 안내를 보인다. 확인하면 시스템 요청이 나간다 */
    public data object Rationale : PermissionFlowStep

    /** 영구 거부라 설정 화면으로 안내한다. 확인하면 앱 설정 화면이 열린다 */
    public data object Settings : PermissionFlowStep

    /** 요구한 시스템 기능(블루투스·위치 서비스 등)이 아직 꺼져 있다. 확인하면 켜기 화면을 다시 연다 */
    public data object Requirement : PermissionFlowStep
}

/**
 * 권한 요청 상태의 공통 부분. 화면이 다시 보일 때(ON_RESUME)마다 상태를 다시 판정한다 —
 * 설정 화면에서 돌아온 경우가 여기서 반영된다. 요청은 `permission` 의 같은 요청 흐름(요청기)을 쓰고, 이 상태는 그 위에
 * 안내 단계([flowStep])만 더한 얇은 어댑터다.
 *
 * 모든 함수는 메인 스레드(이벤트 콜백)에서 부른다.
 */
@Stable
public interface PermissionRequestState {

    /** 전체 흐름에서 지금 보여야 할 안내. [PermissionFlowDialogs] 가 이 값으로 대화상자를 띄운다. */
    public val flowStep: PermissionFlowStep

    /** 요청 흐름이 진행 중인지 */
    public val isRequesting: Boolean

    /** 함께 다루는 시스템 기능의 지금 상태(넘긴 순서). 없으면 빈 맵 */
    public val requirementStatuses: Map<SystemRequirement, RequirementStatus>

    /**
     * 근거 안내·설정 안내 없이 시스템 요청만 보낸다. 이미 진행 중이면 무시한다.
     *
     * @throws IllegalStateException 요청할 권한이 앱 매니페스트에 없을 때(디버그 빌드 기본값),
     *   또는 컴포지션에서 벗어난 뒤에 부를 때
     * @throws net.plumt.permission.PermissionException 특수 권한 설정 화면을 열 수 없을 때
     */
    public fun launchRequest()

    /**
     * [SettingsPolicy.Prompt] 로 [launchFlow] 한다 — 영구 거부면 설정 안내를 띄운다.
     *
     * @throws IllegalStateException [launchRequest] 와 같다
     */
    public fun launchFlow()

    /**
     * 확인 → 근거 안내 → 요청 → 결과 → 영구 거부면 [settingsPolicy] 대로 설정 이동 → 돌아오면 재판정까지의 흐름을 시작한다.
     * 요청 뒤에도 꺼진 시스템 기능이 남았으면 [PermissionFlowStep.Requirement] 안내로 켜기를 다시 청한다.
     * 안내는 [flowStep] 으로 드러나고 [confirmFlowStep]·[dismissFlowStep] 으로 진행한다.
     *
     * 시작할 때 영구 거부여도 먼저 시스템 요청을 보낸다 — 판정이 틀렸으면 대화상자가 뜨고 허용하거나 2초 이상 띄웠다 닫을 때
     * 바로잡히며(2초 안에 닫으면 판정이 남아 설정 안내가 틀릴 수 있다 — 사용자가 버튼을 눌렀을 때만 부른다), 맞으면 시스템이 곧바로 거부를 돌려줘 설정 안내가 뜬다. 흐름이 연 설정 화면에서 돌아오면 재판정한 상태를
     * 등록할 때 넘긴 `onResult` 로 한 번 준다(앱이 [openAppSettings] 로 직접 연 경우는 `status` 로만 반영된다).
     *
     * - [SettingsPolicy.Never]: 설정 안내 없이 끝낸다
     * - [SettingsPolicy.Prompt]: [PermissionFlowStep.Settings] 안내 → 확인하면 앱 설정 화면
     * - [SettingsPolicy.Direct]: 안내 없이 곧바로 앱 설정 화면 → 돌아오면 결과
     *
     * @throws IllegalStateException [launchRequest] 와 같다
     */
    public fun launchFlow(settingsPolicy: SettingsPolicy)

    /**
     * 지금 안내를 확인한다 — 근거 안내면 요청을 보내고, 설정 안내면 앱 설정 화면을 열고, 기능 켜기 안내면 켜기 화면을 다시 연다.
     * 안내가 없으면 아무것도 하지 않는다.
     */
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
