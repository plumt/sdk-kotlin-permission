package net.plumt.permission

import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest

/**
 * 권한과 별개로 켜져 있어야 하는 시스템 기능(블루투스 어댑터·위치 서비스·NFC·Wi-Fi·앱 알림 등)의 명세.
 *
 * 권한 상태([PermissionStatus])와 섞지 않고 [RequirementStatus] 로 따로 판정한다. 내장 명세는 [SystemRequirements] 가 만든다.
 * 구현은 상태를 갖지 않고 메인 스레드에서 가볍게 돌아야 한다.
 */
public interface SystemRequirement {
    /** 요구 조건을 가르는 안정적인 식별자. 한 묶음 안에서 겹치면 안 된다 */
    public val id: String

    /**
     * 켜기 요청을 보내기 전에 허용돼 있어야 하는 런타임 권한(API 31+ 블루투스 켜기의 `BLUETOOTH_CONNECT` 등).
     * 요청기는 이 권한을 런타임 권한 요청에 함께 묶는다. 기본은 없음.
     */
    public fun permissions(environment: PermissionEnvironment): List<String> = emptyList()

    /**
     * 앱 매니페스트에 선언돼 있어야 하는 권한 — [permissions] 에 설치 시 부여되는 권한(API 30 이하 `BLUETOOTH`,
     * `ACCESS_WIFI_STATE` 등)을 더한 것. 요청 직전 선언 확인([ManifestCheck])에 쓴다. 기본은 [permissions].
     */
    public fun declaredPermissions(environment: PermissionEnvironment): List<String> = permissions(environment)

    /**
     * 지금 상태. 기능이 꺼져 있으면 [RequirementStatus.Disabled] 를 돌려준다 —
     * [permissions] 가 빠졌는지는 관리자가 따로 보고 [RequirementStatus.PermissionRequired] 로 바꾼다.
     */
    public fun status(context: Context, environment: PermissionEnvironment): RequirementStatus

    /**
     * 기능을 켜는 화면 요청을 만든다. 만들 수 없으면 `null` 을 넘긴다. [onReady] 는 메인 스레드에서 한 번 부른다 —
     * 곧바로 불러도 되고, Play 서비스 `SettingsClient` 처럼 비동기로 확인한 뒤 불러도 된다.
     */
    public fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit)
}

/** 기능을 켜는 화면 요청. 요청기가 띄우고, 돌아오면 다시 판정한다. */
public sealed interface EnableRequest {
    /** `startActivityForResult` 로 여는 인텐트(시스템 설정 화면·`BluetoothAdapter.ACTION_REQUEST_ENABLE` 등) */
    public class OfIntent(public val intent: Intent) : EnableRequest {
        override fun toString(): String = "EnableRequest.OfIntent(${intent.action})"
    }

    /** `IntentSender` 로 여는 해결 화면(Play 서비스 위치 설정 대화상자 등) */
    public class OfIntentSender(public val request: IntentSenderRequest) : EnableRequest {
        override fun toString(): String = "EnableRequest.OfIntentSender"
    }
}

/** 시스템 기능의 상태. [PermissionManager.check] 가 돌려준다. 다섯으로 닫힌 집합이다. */
public sealed interface RequirementStatus {
    /** 켜져 있다(또는 쓸 수 있다) */
    public data object Satisfied : RequirementStatus

    /** 꺼져 있다. 켜기 요청을 보낼 수 있다 */
    public data object Disabled : RequirementStatus

    /**
     * 꺼져 있고, 켜기 요청 전에 [permissions] 가 허용돼야 한다. 요청기가 이 권한을 먼저 요청한다.
     * 설치 시 부여 권한이 빠졌다면 앱 매니페스트에 선언해야 한다는 뜻이다.
     */
    public class PermissionRequired(public val permissions: Set<String>) : RequirementStatus {
        override fun equals(other: Any?): Boolean = other is PermissionRequired && other.permissions == permissions

        override fun hashCode(): Int = permissions.hashCode()

        override fun toString(): String = "PermissionRequired(permissions=$permissions)"
    }

    /**
     * 조건을 만족하지 않지만 앱이 열 해결 화면이 없다 — 상태만 알린다(대기 버킷 제한·만들지 않은 알림 채널 등).
     * 요청기는 이 요구 조건을 건너뛴다.
     */
    public data object Unmet : RequirementStatus

    /** 이 기기에서는 쓸 수 없다 — 하드웨어가 없거나 API 레벨이 낮다 */
    public class Unsupported(public val reason: UnsupportedReason) : RequirementStatus {
        override fun equals(other: Any?): Boolean = other is Unsupported && other.reason == reason

        override fun hashCode(): Int = reason.hashCode()

        override fun toString(): String = "Unsupported(reason=$reason)"
    }
}

/**
 * [RequirementStatus.Unsupported] 의 이유. 값이 늘 수 있는 열린 집합이다 — `when` 으로 가를 때는 `else` 를 둔다.
 * 직접 만든 요구 조건은 [name] 이 다른 값을 만들어 쓸 수 있다. 같은 [name] 이면 같다.
 *
 * @property name 이유 이름(`NO_HARDWARE` 등)
 * @throws IllegalArgumentException [name] 이 비었을 때(생성 시)
 */
public class UnsupportedReason(public val name: String) {
    init {
        require(name.isNotBlank()) { "name 이 비었습니다" }
    }

    override fun equals(other: Any?): Boolean = other is UnsupportedReason && other.name == name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    public companion object {
        /** 하드웨어가 없다 */
        @JvmField
        public val NO_HARDWARE: UnsupportedReason = UnsupportedReason("NO_HARDWARE")

        /** 이 API 레벨에는 기능이 없다 */
        @JvmField
        public val API_LEVEL: UnsupportedReason = UnsupportedReason("API_LEVEL")
    }
}

/** 켜져 있는지 */
public val RequirementStatus.isSatisfied: Boolean get() = this is RequirementStatus.Satisfied

/** 켜기 요청(권한 요청 포함)으로 바꿀 수 있는 상태인지 — [RequirementStatus.Disabled]·[RequirementStatus.PermissionRequired] */
public val RequirementStatus.canRequestEnable: Boolean
    get() = this is RequirementStatus.Disabled || this is RequirementStatus.PermissionRequired
