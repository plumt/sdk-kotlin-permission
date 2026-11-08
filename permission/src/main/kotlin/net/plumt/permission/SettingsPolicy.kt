package net.plumt.permission

/**
 * 요청 결과가 설정 화면으로만 바꿀 수 있는 상태([needsSettings])일 때 앱 설정 화면으로 보낼지.
 * [PermissionManager.request]·[PermissionRequester.launch]·Compose 의 `launchFlow` 에 넘긴다. 셋으로 닫힌 집합이다.
 */
public sealed interface SettingsPolicy {
    /** 보내지 않는다. 결과만 돌려준다 */
    public data object Never : SettingsPolicy

    /**
     * 안내 후 보낸다. Compose `launchFlow` 는 설정 안내 대화상자를 띄우고 확인하면 연다.
     * `permission` 의 `request`·요청기는 안내 UI 가 없어 [Never] 처럼 결과를 돌려준다 — 호출부가 안내한 뒤
     * [PermissionManager.openAppSettings] 나 [PermissionRequester.openAppSettings] 를 부른다.
     */
    public data object Prompt : SettingsPolicy

    /** 안내 없이 곧바로 앱 설정 화면을 열고, 돌아오면 다시 판정한 결과를 준다 */
    public data object Direct : SettingsPolicy
}
