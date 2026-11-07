package net.plumt.permission

/**
 * 요청 결과가 설정 화면으로만 바꿀 수 있는 상태([needsSettings])일 때 앱 설정 화면으로 보낼지.
 * [PermissionRequester.launch]·[PermissionManager.request]·Compose 의 `launchFlow` 에 넘긴다.
 */
public enum class SettingsPolicy {
    /** 보내지 않는다. 결과만 돌려준다 */
    NEVER,

    /**
     * 안내 후 보낸다. Compose `launchFlow` 는 설정 안내 대화상자를 띄우고 확인하면 연다.
     * `core` 의 요청기·`request` 는 안내 UI 가 없어 [NEVER] 처럼 결과를 돌려준다 — 호출부가 안내한 뒤
     * [PermissionRequester.openAppSettings] 를 부르면 돌아왔을 때 결과를 다시 받는다.
     */
    PROMPT,

    /** 안내 없이 곧바로 앱 설정 화면을 열고, 돌아오면 다시 판정한 결과를 준다 */
    DIRECT,
}
