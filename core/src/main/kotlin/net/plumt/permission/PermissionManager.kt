package net.plumt.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import net.plumt.permission.internal.DefaultPermissionManager

/**
 * 권한 확인·요청의 진입점. [create] 로 만들고 앱에 하나 두고 공유한다.
 *
 * 모든 함수는 메인 스레드에서 부른다. 확인([check])은 바인더 호출 몇 번이라 메인 스레드에서 불러도 된다.
 */
public interface PermissionManager {

    /** 판정에 쓰는 기기 정보(API 레벨·매니페스트 선언) */
    public val environment: PermissionEnvironment

    /**
     * 지금 상태를 판정한다. 요청하지 않는다.
     *
     * @param activity 근거 안내 판정(`shouldShowRequestPermissionRationale`)에 쓴다. null 이면 근거 안내는 거짓으로 본다
     * @throws PermissionException 소비자가 구현한 명세가 예외를 던졌을 때(원래 예외가 cause)
     */
    public fun check(spec: PermissionSpec, activity: Activity? = null): PermissionStatus

    /**
     * 시스템 기능의 지금 상태를 판정한다. 꺼져 있고 켜기에 필요한 권한([SystemRequirement.permissions])이 빠졌으면
     * [RequirementStatus.PermissionRequired] 다.
     *
     * @throws PermissionException 소비자가 구현한 요구 조건이 예외를 던졌을 때
     */
    public fun check(requirement: SystemRequirement): RequirementStatus

    /**
     * 여러 명세의 상태를 판정한다.
     *
     * @throws IllegalArgumentException [specs] 가 비었거나 id 가 겹칠 때
     * @throws PermissionException 소비자가 구현한 명세가 예외를 던졌을 때
     */
    public fun checkAll(specs: List<PermissionSpec>, activity: Activity? = null): MultiplePermissionsStatus

    /**
     * 명세와 시스템 기능의 상태를 함께 판정한다.
     *
     * @throws IllegalArgumentException 둘 다 비었거나 id 가 겹칠 때
     * @throws PermissionException 소비자가 구현한 명세·요구 조건이 예외를 던졌을 때
     */
    public fun checkAll(
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement>,
        activity: Activity? = null,
    ): MultiplePermissionsStatus

    /**
     * 이 기기에서 [specs]·[requirements] 가 쓰는 권한 가운데 앱 매니페스트에 선언되지 않은 것.
     * 빈 집합이면 다 선언돼 있다. 개발 중 시작 시점이나 계측 테스트에서 단언하는 데 쓴다 —
     * 선언하지 않은 권한은 대화상자 없이 거부돼 영구 거부와 구별되지 않는다.
     */
    public fun undeclaredPermissions(
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement> = emptyList(),
    ): Set<String>

    /** 이 앱의 상세 설정 화면 인텐트(`ACTION_APPLICATION_DETAILS_SETTINGS`) */
    public fun appSettingsIntent(): Intent

    /**
     * 이 앱의 상세 설정 화면을 연다. 돌아오면 [check] 로 다시 판정한다.
     * [context] 가 Activity 가 아니면 새 태스크로 연다.
     *
     * @throws PermissionException 설정 화면을 열 수 없을 때
     */
    public fun openAppSettings(context: Context)

    /**
     * 이 앱의 알림 설정 화면 인텐트. [channelId] 를 주면 그 채널의 설정 화면이다(API 26+).
     * API 26 미만은 알림 설정 화면이 공개되지 않아 앱 상세 설정 화면이다.
     */
    public fun notificationSettingsIntent(channelId: String? = null): Intent

    /**
     * 이 앱(또는 채널)의 알림 설정 화면을 연다. 화면이 없으면 앱 상세 설정 화면으로 대신 연다.
     * [context] 가 Activity 가 아니면 새 태스크로 연다.
     *
     * @throws IllegalArgumentException [channelId] 가 빈 문자열일 때
     * @throws PermissionException 두 화면을 모두 열 수 없을 때
     */
    public fun openNotificationSettings(context: Context, channelId: String? = null)

    /**
     * 요청기를 등록한다. Activity·Fragment 의 `onCreate` 처럼 STARTED 전에 불러야 한다
     * (`registerForActivityResult` 규칙). 등록은 [caller] 의 수명이 끝나면 함께 풀린다.
     *
     * [caller] 가 [SavedStateRegistryOwner](ComponentActivity·Fragment)이면 진행 중인 흐름(남은 특수 권한 설정 화면,
     * 요청 전 근거 안내 값)을 그 저장 상태에 남겨, 화면 재생성·프로세스 종료 뒤에도 흐름을 잇는다.
     * 같은 화면에서 등록하는 순서가 매번 같아야 한다(`registerForActivityResult` 와 같은 조건).
     *
     * @param activity 근거 안내 판정과 설정 화면 열기에 쓸 현재 Activity
     * @param requirements 권한을 받은 뒤 켜기 화면을 차례로 열 시스템 기능
     * @param onResult 요청 흐름이 끝날 때마다 받는 최종 상태
     * @throws IllegalArgumentException [specs]·[requirements] 가 모두 비었거나 id 가 겹칠 때
     */
    public fun createRequester(
        caller: ActivityResultCaller,
        activity: () -> Activity?,
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement> = emptyList(),
        onResult: (MultiplePermissionsStatus) -> Unit,
    ): PermissionRequester

    /**
     * 수명주기 없이 [registry] 에 직접 등록한다. 다 쓰면 [PermissionRequester.unregister] 를 불러야 한다.
     * 화면이 다시 만들어진 뒤 같은 [key] 로 다시 등록하면 진행 중이던 결과를 이어받는다.
     *
     * @param savedStateRegistry 넘기면 진행 중인 흐름을 `"$key#state"` 로 남겨 재생성·프로세스 종료 뒤에도 잇는다.
     *   null 이면 결과는 이어받지만 남은 특수 권한 설정 화면·켜기 화면은 건너뛴다
     * @throws IllegalArgumentException [specs]·[requirements] 가 모두 비었거나 id 가 겹치거나 [key] 가 비었을 때,
     *   또는 같은 [key] 의 요청기가 [savedStateRegistry] 에 이미 등록돼 있을 때
     */
    public fun createRequester(
        registry: ActivityResultRegistry,
        key: String,
        activity: () -> Activity?,
        specs: List<PermissionSpec>,
        savedStateRegistry: SavedStateRegistry? = null,
        requirements: List<SystemRequirement> = emptyList(),
        onResult: (MultiplePermissionsStatus) -> Unit,
    ): PermissionRequester

    /**
     * 화면 없이(ViewModel·UseCase 에서) 요청하고 결과를 기다린다. 이미 다 허용·켜짐이면 시스템 화면 없이 곧바로 돌아온다.
     *
     * 요청은 지금 시작된 `ComponentActivity` 에 띄운다 — 관리자는 만들어질 때부터 `Application` 의 Activity 수명주기를
     * 따라가므로 `Application.onCreate` 에서 만들어 두는 것이 안전하다. 띄울 화면이 없으면 [RequestResult.NoActivity] 다.
     * 화면 회전(구성 변경)으로 Activity 가 다시 만들어지면 새 Activity 에서 결과를 이어받는다. 프로세스가 종료되면 이 호출은
     * 코루틴과 함께 사라지고 결과는 버려진다.
     *
     * 여러 곳에서 동시에 불러도 시스템 요청은 하나씩 차례로 뜬다(요청기 전체가 같은 줄을 쓴다). 취소하면 등록을 풀고
     * 줄에서 빠진다. 메인 스레드로 옮겨 실행하므로 어느 디스패처에서 불러도 된다.
     *
     * @throws IllegalArgumentException [specs]·[requirements] 가 모두 비었거나 id 가 겹칠 때
     * @throws IllegalStateException 요청할 권한이 매니페스트에 없고 [ManifestCheck] 가 확인하라고 할 때
     * @throws PermissionException 설정·켜기 화면을 열 수 없을 때
     */
    public suspend fun request(
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement> = emptyList(),
        settingsPolicy: SettingsPolicy = SettingsPolicy.NEVER,
    ): RequestResult

    /**
     * [activity] 에 요청을 띄우고 결과를 기다린다. 나머지는 위 [request] 와 같다.
     *
     * @throws IllegalArgumentException [request] 와 같다
     */
    public suspend fun request(
        activity: ComponentActivity,
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement> = emptyList(),
        settingsPolicy: SettingsPolicy = SettingsPolicy.NEVER,
    ): RequestResult

    public companion object {
        /**
         * 기본 구현을 만든다.
         *
         * @param context 애플리케이션 컨텍스트를 꺼내 쓴다 — Activity 를 넘겨도 붙잡지 않는다. 애플리케이션 컨텍스트가
         *   `Application` 이면 [request] 를 위해 Activity 수명주기를 따라가기 시작한다
         */
        public fun create(context: Context, config: PermissionConfig = PermissionConfig()): PermissionManager =
            DefaultPermissionManager.create(context, config)
    }
}

/**
 * 등록된 요청기. 런타임 권한은 한 번의 시스템 요청으로 묶어 묻고, 특수 권한은 설정 화면을, 시스템 기능은 켜기 화면을 하나씩 연다.
 * 끝나면 등록할 때 넘긴 콜백으로 최종 상태를 준다.
 *
 * 같은 관리자의 요청기들은 한 줄을 쓴다 — 다른 요청기의 흐름이 진행 중이면 [launch] 는 그 흐름이 끝난 뒤 시작한다.
 */
public interface PermissionRequester {

    /** 다루는 명세 */
    public val specs: List<PermissionSpec>

    /** 다루는 시스템 기능 */
    public val requirements: List<SystemRequirement>

    /** 요청 흐름이 진행 중인지(줄에서 기다리는 중 포함) */
    public val isRequesting: Boolean

    /**
     * 요청 흐름을 시작한다. 결과가 설정으로만 바꿀 수 있는 상태여도 설정 화면으로 보내지 않는다([SettingsPolicy.NEVER]).
     *
     * @see launch
     */
    public fun launch(): Boolean

    /**
     * 요청 흐름을 시작한다. 이미 다 허용·켜짐이면 시스템 화면 없이 곧바로 콜백한다.
     *
     * API 30 이상에서 백그라운드 위치(그리고 신체 센서 백그라운드)는 다른 권한과 한 요청에 묶지 않고 따로 요청한다.
     * 영구 거부 상태여도 시스템 요청을 보낸다 — 사용자가 설정에서 권한을 초기화했다면 대화상자가 다시 뜨고,
     * 허용하거나 2초 이상 띄웠다 닫은 결과로 판정을 바로잡는다(2초 안에 닫으면 판정이 남는다). 꺼진 시스템 기능은 권한 요청 뒤 켜기 화면을 연다 — 사용자가 켜지 않으면 결과에
     * [RequirementStatus.Disabled] 로 남고, 다시 [launch] 하면 켜기 화면만 다시 연다.
     *
     * @param settingsPolicy [SettingsPolicy.DIRECT] 면 결과가 [needsSettings] 일 때 앱 설정 화면을 열고 돌아온 뒤 콜백한다
     * @return 시작했거나 줄에 섰으면 true, 이미 진행 중이라 무시했으면 false
     * @throws IllegalStateException 요청할 권한이 앱 매니페스트에 없고 [ManifestCheck] 가 확인하라고 할 때,
     *   또는 등록이 풀린 뒤에 부를 때
     * @throws PermissionException 특수 권한의 설정 화면이나 시스템 기능의 켜기 화면을 열 수 없을 때
     */
    public fun launch(settingsPolicy: SettingsPolicy): Boolean

    /**
     * 앱 상세 설정 화면을 열고, 돌아오면 다시 판정한 상태를 콜백한다. [SettingsPolicy.PROMPT] 에서 안내 뒤 부른다.
     *
     * @return 열었으면 true, 흐름이 진행 중이라 무시했으면 false
     * @throws IllegalStateException 등록이 풀린 뒤에 부를 때
     * @throws PermissionException 설정 화면을 열 수 없을 때
     */
    public fun openAppSettings(): Boolean

    /** 등록을 푼다. 그 뒤 [launch] 는 `IllegalStateException` 이다. 여러 번 불러도 된다. */
    public fun unregister()
}

/**
 * 이 Activity 에 요청기를 등록한다. `onCreate` 에서 부른다.
 *
 * @see PermissionManager.createRequester
 */
public fun ComponentActivity.registerPermissionRequester(
    manager: PermissionManager,
    specs: List<PermissionSpec>,
    requirements: List<SystemRequirement> = emptyList(),
    onResult: (MultiplePermissionsStatus) -> Unit,
): PermissionRequester = manager.createRequester(this, { this }, specs, requirements, onResult)
