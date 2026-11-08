package net.plumt.permission

/**
 * 권한 하나의 현재 상태.
 *
 * [PermanentlyDenied] 는 요청 전·후 근거 안내 값과 대화상자가 실제로 떴는지로 내린 **추정**이다. 시스템은 영구 거부를 직접
 * 알려 주지 않는다. 대화상자를 바깥 탭·뒤로가기로 닫은 것은 거부 횟수로 세지 않는다(시스템도 세지 않는다).
 * 그래서 [PermissionRequester.launch] 는 이 상태에서도 시스템 요청을 보내고, 허용하거나 2초 이상 띄웠다 닫은 결과로 판정을 고친다.
 *
 * | 상태 | 뜻 | 기능 사용 | 다시 요청 |
 * |---|---|---|---|
 * | [Granted] | 전부 허용 | 가능 | 필요 없음 |
 * | [NotApplicable] | 이 API 레벨에는 요청할 권한이 없다 | 가능 | 필요 없음 |
 * | [Partial] | 일부만 허용(대략적 위치·사진 일부 선택·포그라운드 위치만) | 제한적으로 가능 | 가능 |
 * | [Denied] | 거부됐지만 대화상자를 다시 띄울 수 있다 | 불가 | 가능 |
 * | [PermanentlyDenied] | "다시 묻지 않음" — 대화상자가 뜨지 않는다 | 불가 | 설정 화면에서만 |
 */
public sealed interface PermissionStatus {

    /** 전부 허용됐다. */
    public data object Granted : PermissionStatus

    /**
     * 이 기기의 API 레벨에서는 요청할 런타임 권한이 없다(API 33 미만의 알림, API 29 미만의 활동 인식 등).
     * 기능을 쓸 수 있는 상태로 다룬다.
     *
     * @property sdkInt 판정한 기기의 API 레벨
     */
    public class NotApplicable(public val sdkInt: Int) : PermissionStatus {
        override fun equals(other: Any?): Boolean = other is NotApplicable && other.sdkInt == sdkInt

        override fun hashCode(): Int = sdkInt

        override fun toString(): String = "NotApplicable(sdkInt=$sdkInt)"
    }

    /**
     * 일부만 허용됐다.
     *
     * @property granted 허용된 권한
     * @property missing 아직 허용되지 않은 권한
     * @property shouldShowRationale 나머지를 요청하기 전에 근거 안내를 보이라고 시스템이 권하는지
     * @property isMissingPermanentlyDenied 나머지가 "다시 묻지 않음" 으로 막혔는지 — 참이면 설정 화면에서만 바꿀 수 있다.
     *   요청 대화상자에서 선택지(사진 일부·대략적 위치)를 처음 고른 것은 거부가 아니다. 대략적 위치를 이미 가진 채 띄운
     *   정밀 승격 대화상자를 거절("대략적인 위치 계속 사용")하면 참이 된다(시스템이 더 묻지 않는다).
     *   사진·동영상 일부 선택이 허용된 동안은 늘 거짓이다 — 시스템이 요청마다 선택 대화상자를 다시 띄운다
     * @property access 부분 접근의 종류
     */
    public class Partial(
        public val granted: Set<String>,
        public val missing: Set<String>,
        public val shouldShowRationale: Boolean,
        public val isMissingPermanentlyDenied: Boolean,
        public val access: PartialAccess = PartialAccess.SOME_PERMISSIONS,
    ) : PermissionStatus {
        override fun equals(other: Any?): Boolean = other is Partial &&
            other.granted == granted &&
            other.missing == missing &&
            other.shouldShowRationale == shouldShowRationale &&
            other.isMissingPermanentlyDenied == isMissingPermanentlyDenied &&
            other.access == access

        override fun hashCode(): Int {
            var result = granted.hashCode()
            result = 31 * result + missing.hashCode()
            result = 31 * result + shouldShowRationale.hashCode()
            result = 31 * result + isMissingPermanentlyDenied.hashCode()
            return 31 * result + access.hashCode()
        }

        override fun toString(): String = "Partial(granted=$granted, missing=$missing, shouldShowRationale=$shouldShowRationale, " +
            "isMissingPermanentlyDenied=$isMissingPermanentlyDenied, access=$access)"
    }

    /**
     * 거부됐지만 다시 요청하면 시스템 대화상자가 뜬다.
     *
     * @property shouldShowRationale 요청 전에 근거 안내를 보이라고 시스템이 권하는지
     *   (한 번 거부한 뒤에 참이 된다. 처음 요청하기 전에는 거짓이다)
     */
    public class Denied(public val shouldShowRationale: Boolean) : PermissionStatus {
        override fun equals(other: Any?): Boolean = other is Denied && other.shouldShowRationale == shouldShowRationale

        override fun hashCode(): Int = shouldShowRationale.hashCode()

        override fun toString(): String = "Denied(shouldShowRationale=$shouldShowRationale)"
    }

    /** "다시 묻지 않음" 상태다. 시스템 대화상자가 뜨지 않으므로 앱 설정 화면으로 안내한다. */
    public data object PermanentlyDenied : PermissionStatus
}

/** 기능을 (적어도 일부라도) 쓸 수 있는 상태인지 — [PermissionStatus.Granted]·[PermissionStatus.NotApplicable]·[PermissionStatus.Partial] */
public val PermissionStatus.isUsable: Boolean
    get() = this is PermissionStatus.Granted || this is PermissionStatus.NotApplicable || this is PermissionStatus.Partial

/** 더 요청할 것이 없는 상태인지 — [PermissionStatus.Granted]·[PermissionStatus.NotApplicable] */
public val PermissionStatus.isFullyGranted: Boolean
    get() = this is PermissionStatus.Granted || this is PermissionStatus.NotApplicable

/**
 * 시스템 대화상자로 다시 물을 수 있는지. [PermissionStatus.Denied] 는 언제나 참,
 * [PermissionStatus.Partial] 은 나머지가 영구 거부가 아니면 참, 나머지는 거짓이다.
 */
public val PermissionStatus.canAskAgain: Boolean
    get() = when (this) {
        is PermissionStatus.Denied -> true
        is PermissionStatus.Partial -> !isMissingPermanentlyDenied
        PermissionStatus.Granted, is PermissionStatus.NotApplicable, PermissionStatus.PermanentlyDenied -> false
    }

/** 요청 전에 근거 안내를 보이라고 시스템이 권하는지 */
public val PermissionStatus.shouldShowRationale: Boolean
    get() = when (this) {
        is PermissionStatus.Denied -> shouldShowRationale
        is PermissionStatus.Partial -> shouldShowRationale
        PermissionStatus.Granted, is PermissionStatus.NotApplicable, PermissionStatus.PermanentlyDenied -> false
    }

/** 설정 화면으로만 바꿀 수 있는 상태인지 — 영구 거부, 또는 나머지가 영구 거부된 일부 허용 */
public val PermissionStatus.needsSettings: Boolean
    get() = this is PermissionStatus.PermanentlyDenied ||
        (this is PermissionStatus.Partial && isMissingPermanentlyDenied)
