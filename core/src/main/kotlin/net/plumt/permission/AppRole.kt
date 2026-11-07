package net.plumt.permission

/**
 * 기본 앱 역할. [PermissionSpecs.role] 에 넘긴다. 역할은 API 29 의 `RoleManager` 에서 생겼다.
 *
 * 역할마다 앱이 갖춰야 할 컴포넌트(SMS 앱의 `SMS_DELIVER` 수신기, 전화 앱의 `InCallService` 등)가 있다 — 갖추지 않으면
 * 시스템이 요청 대화상자를 띄우지 않고 거부한다. 요건은 Android 개발자 문서의 역할별 안내를 따른다.
 *
 * @property roleName `RoleManager.ROLE_*` 값
 * @property requestable 앱이 `createRequestRoleIntent` 로 요청할 수 있는지 — 아니면 기본 앱 설정 화면을 연다
 * @property hasLegacyApi API 23~28 에 옛 기본 앱 변경 화면이 있는지
 */
public enum class AppRole(
    public val roleName: String,
    public val requestable: Boolean,
    public val hasLegacyApi: Boolean,
) {
    /** 기본 SMS 앱. API 23~28 은 `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT` */
    SMS("android.app.role.SMS", requestable = true, hasLegacyApi = true),

    /** 기본 전화 앱. API 23~28 은 `TelecomManager.ACTION_CHANGE_DEFAULT_DIALER` */
    DIALER("android.app.role.DIALER", requestable = true, hasLegacyApi = true),

    /** 기본 브라우저 */
    BROWSER("android.app.role.BROWSER", requestable = true, hasLegacyApi = false),

    /** 기본 홈 앱 */
    HOME("android.app.role.HOME", requestable = true, hasLegacyApi = false),

    /** 발신자 확인·스팸 차단(`CallScreeningService`) */
    CALL_SCREENING("android.app.role.CALL_SCREENING", requestable = true, hasLegacyApi = false),

    /** 발신 전화 재연결(`CallRedirectionService`) */
    CALL_REDIRECTION("android.app.role.CALL_REDIRECTION", requestable = true, hasLegacyApi = false),

    /** 디지털 어시스턴트. 앱이 요청할 수 없는 역할이라 기본 앱 설정 화면을 연다 */
    ASSISTANT("android.app.role.ASSISTANT", requestable = false, hasLegacyApi = false),
}
