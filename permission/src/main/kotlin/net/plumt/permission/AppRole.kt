package net.plumt.permission

/**
 * 기본 앱 역할. [PermissionSpecs.role] 에 넘긴다. 역할은 API 29 의 `RoleManager` 에서 생겼다.
 *
 * 역할마다 앱이 갖춰야 할 컴포넌트(SMS 앱의 `SMS_DELIVER` 수신기, 전화 앱의 `InCallService` 등)가 있다 — 갖추지 않으면
 * 시스템이 요청 대화상자를 띄우지 않고 거부한다. 요건은 Android 개발자 문서의 역할별 안내를 따른다.
 *
 * 값이 늘 수 있는 열린 집합이다 — 새 플랫폼 역할은 생성자로 직접 만들어 쓸 수 있다. 같은 [roleName] 이면 같다.
 *
 * @property roleName `RoleManager.ROLE_*` 값
 * @property requestable 앱이 `createRequestRoleIntent` 로 요청할 수 있는지 — 아니면 기본 앱 설정 화면을 연다
 * @property minSdk 역할이 생긴 API 레벨. 그 아래는 [PermissionStatus.NotApplicable] 이다(옛 기본 앱 화면이 있는 SMS·전화 제외)
 * @throws IllegalArgumentException [roleName] 이 비었거나 [minSdk] 가 29 미만일 때(생성 시)
 */
public class AppRole private constructor(
    public val roleName: String,
    public val requestable: Boolean,
    public val minSdk: Int,
    internal val legacy: LegacyRole?,
) {
    /** 플랫폼 역할 [roleName] 을 다룬다. API 23~28 의 옛 기본 앱 화면은 내장 [SMS]·[DIALER] 만 쓴다 */
    public constructor(roleName: String, requestable: Boolean, minSdk: Int = 29) : this(roleName, requestable, minSdk, null)

    init {
        require(roleName.isNotBlank()) { "roleName 이 비었습니다" }
        require(minSdk >= 29) { "역할은 API 29 의 RoleManager 에서 생겼습니다 — minSdk 는 29 이상이어야 합니다: $minSdk" }
    }

    /** API 23~28 에 옛 기본 앱 변경 화면이 있는지(SMS·전화) */
    public val hasLegacyApi: Boolean get() = legacy != null

    override fun equals(other: Any?): Boolean = other is AppRole && other.roleName == roleName

    override fun hashCode(): Int = roleName.hashCode()

    override fun toString(): String = "AppRole($roleName)"

    /** API 23~28 의 옛 기본 앱 API 가 있는 역할 */
    internal enum class LegacyRole { SMS, DIALER }

    public companion object {
        /** 기본 SMS 앱. API 23~28 은 `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT` */
        @JvmField
        public val SMS: AppRole = AppRole("android.app.role.SMS", requestable = true, minSdk = 29, legacy = LegacyRole.SMS)

        /** 기본 전화 앱. API 23~28 은 `TelecomManager.ACTION_CHANGE_DEFAULT_DIALER` */
        @JvmField
        public val DIALER: AppRole = AppRole("android.app.role.DIALER", requestable = true, minSdk = 29, legacy = LegacyRole.DIALER)

        /** 기본 브라우저 */
        @JvmField
        public val BROWSER: AppRole = AppRole("android.app.role.BROWSER", requestable = true)

        /** 기본 홈 앱 */
        @JvmField
        public val HOME: AppRole = AppRole("android.app.role.HOME", requestable = true)

        /** 발신자 확인·스팸 차단(`CallScreeningService`) */
        @JvmField
        public val CALL_SCREENING: AppRole = AppRole("android.app.role.CALL_SCREENING", requestable = true)

        /** 발신 전화 재연결(`CallRedirectionService`) */
        @JvmField
        public val CALL_REDIRECTION: AppRole = AppRole("android.app.role.CALL_REDIRECTION", requestable = true)

        /** 디지털 어시스턴트. 앱이 요청할 수 없는 역할이라 기본 앱 설정 화면을 연다 */
        @JvmField
        public val ASSISTANT: AppRole = AppRole("android.app.role.ASSISTANT", requestable = false)

        /**
         * 긴급 정보 앱(API 29+). 대개 기기에 미리 설치된 앱이 갖는 역할이라 요청 대화상자를 쓰지 않는다 — 판정(보유 여부)에 쓰고,
         * 요청하면 기본 앱 설정 화면을 연다
         */
        @JvmField
        public val EMERGENCY: AppRole = AppRole("android.app.role.EMERGENCY", requestable = false)

        /** 메모 앱(API 34+, 잠금 화면·스타일러스 메모). 요청 대화상자 대신 기본 앱 설정 화면을 연다 */
        @JvmField
        public val NOTES: AppRole = AppRole("android.app.role.NOTES", requestable = false, minSdk = 34)

        /** 지갑 앱(API 35+, NFC 결제 기본 앱). 요청 대화상자 대신 기본 앱 설정 화면을 연다 */
        @JvmField
        public val WALLET: AppRole = AppRole("android.app.role.WALLET", requestable = false, minSdk = 35)

        /** 내장 역할 전부 — 문서·일치 테스트용 */
        internal val BUILT_IN: List<AppRole> =
            listOf(SMS, DIALER, BROWSER, HOME, CALL_SCREENING, CALL_REDIRECTION, ASSISTANT, EMERGENCY, NOTES, WALLET)
    }
}
