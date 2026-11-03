package net.plumt.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultRegistry
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
     * 여러 명세의 상태를 판정한다.
     *
     * @throws IllegalArgumentException [specs] 가 비었거나 id 가 겹칠 때
     * @throws PermissionException 소비자가 구현한 명세가 예외를 던졌을 때
     */
    public fun checkAll(specs: List<PermissionSpec>, activity: Activity? = null): MultiplePermissionsStatus

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
     * 요청기를 등록한다. Activity·Fragment 의 `onCreate` 처럼 STARTED 전에 불러야 한다
     * (`registerForActivityResult` 규칙). 등록은 [caller] 의 수명이 끝나면 함께 풀린다.
     *
     * @param activity 근거 안내 판정과 설정 화면 열기에 쓸 현재 Activity
     * @param onResult 요청 흐름이 끝날 때마다 받는 최종 상태
     * @throws IllegalArgumentException [specs] 가 비었거나 id 가 겹칠 때
     */
    public fun createRequester(
        caller: ActivityResultCaller,
        activity: () -> Activity?,
        specs: List<PermissionSpec>,
        onResult: (MultiplePermissionsStatus) -> Unit,
    ): PermissionRequester

    /**
     * 수명주기 없이 [registry] 에 직접 등록한다. 다 쓰면 [PermissionRequester.unregister] 를 불러야 한다.
     * 화면이 다시 만들어진 뒤 같은 [key] 로 다시 등록하면 진행 중이던 결과를 이어받는다.
     *
     * @throws IllegalArgumentException [specs] 가 비었거나 id 가 겹치거나 [key] 가 비었을 때
     */
    public fun createRequester(
        registry: ActivityResultRegistry,
        key: String,
        activity: () -> Activity?,
        specs: List<PermissionSpec>,
        onResult: (MultiplePermissionsStatus) -> Unit,
    ): PermissionRequester

    public companion object {
        /**
         * 기본 구현을 만든다.
         *
         * @param context 애플리케이션 컨텍스트를 꺼내 쓴다 — Activity 를 넘겨도 붙잡지 않는다
         */
        public fun create(context: Context, config: PermissionConfig = PermissionConfig()): PermissionManager =
            DefaultPermissionManager.create(context, config)
    }
}

/**
 * 등록된 요청기. 런타임 권한은 한 번의 시스템 요청으로 묶어 묻고, 특수 권한은 설정 화면을 하나씩 연다.
 * 끝나면 등록할 때 넘긴 콜백으로 최종 상태를 준다.
 */
public interface PermissionRequester {

    /** 다루는 명세 */
    public val specs: List<PermissionSpec>

    /** 요청 흐름이 진행 중인지 */
    public val isRequesting: Boolean

    /**
     * 요청 흐름을 시작한다. 이미 다 허용됐으면 시스템 화면 없이 곧바로 콜백한다.
     *
     * 영구 거부 상태여도 시스템 요청을 보낸다 — 사용자가 설정에서 권한을 초기화했다면 대화상자가 다시 뜨고,
     * 그 결과로 판정을 바로잡는다. 영구 거부일 때 설정 화면으로 보내는 것은 호출부가 정한다.
     *
     * @return 시작했으면 true, 이미 진행 중이라 무시했으면 false
     * @throws IllegalStateException 요청할 권한이 앱 매니페스트에 없고 [ManifestCheck] 가 확인하라고 할 때,
     *   또는 등록이 풀린 뒤에 부를 때
     * @throws PermissionException 특수 권한의 설정 화면을 열 수 없을 때
     */
    public fun launch(): Boolean

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
    onResult: (MultiplePermissionsStatus) -> Unit,
): PermissionRequester = manager.createRequester(this, { this }, specs, onResult)
