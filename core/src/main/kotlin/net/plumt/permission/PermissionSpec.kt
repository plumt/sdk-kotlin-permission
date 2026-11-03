package net.plumt.permission

import android.content.Context
import android.content.Intent

/**
 * 권한 하나를 설명하는 명세. 새 권한을 더하려면 [RuntimePermissionSpec] 이나 [SpecialPermissionSpec] 을 구현한다.
 *
 * 내장 명세는 [PermissionSpecs] 가 만든다.
 */
public interface PermissionSpec {
    /**
     * 명세를 가르는 안정적인 식별자. 여러 명세를 한꺼번에 다룰 때 중복 판정에 쓴다.
     * 같은 권한 집합을 뜻하는 명세는 같은 값을 돌려준다.
     */
    public val id: String
}

/**
 * 시스템 권한 대화상자(`requestPermissions`)로 요청하는 런타임 권한의 명세.
 *
 * 기기의 API 레벨과 매니페스트 선언은 [PermissionEnvironment] 로 받는다. 구현은 상태를 갖지 않고
 * 같은 입력에 같은 값을 돌려줘야 한다 — 확인할 때마다, 요청할 때마다 다시 부른다.
 */
public interface RuntimePermissionSpec : PermissionSpec {

    /**
     * 이 기기에서 이 기능에 관련된 런타임 권한 전부. 빈 목록이면 이 기기에서는 요청할 것이 없다는 뜻이고
     * 상태는 [PermissionStatus.NotApplicable] 이 된다.
     */
    public fun permissions(environment: PermissionEnvironment): List<String>

    /**
     * 현재 허용 상태로 이 기능을 얼마나 쓸 수 있는지 판정한다. 기본은 전부 허용이면 [GrantLevel.FULL],
     * 일부만 허용이면 [GrantLevel.PARTIAL], 하나도 없으면 [GrantLevel.NONE] 이다.
     */
    public fun evaluate(environment: PermissionEnvironment, isGranted: (String) -> Boolean): GrantLevel {
        val all = permissions(environment)
        val granted = all.count(isGranted)
        return when {
            granted == all.size -> GrantLevel.FULL
            granted > 0 -> GrantLevel.PARTIAL
            else -> GrantLevel.NONE
        }
    }

    /**
     * 다음 요청에서 대화상자에 넘길 권한. 기본은 아직 허용되지 않은 권한 전부다.
     *
     * 단계를 나눠 요청해야 하는 권한(API 30 이상의 백그라운드 위치)은 지금 단계의 권한만 돌려준다.
     * 이 값에 든 권한으로 근거 안내 필요 여부와 영구 거부를 판정한다.
     */
    public fun nextRequest(environment: PermissionEnvironment, isGranted: (String) -> Boolean): List<String> =
        permissions(environment).filterNot(isGranted)
}

/**
 * 대화상자가 아니라 시스템 설정 화면에서 사용자가 켜는 특수 권한의 명세(배터리 최적화 예외 등).
 *
 * 특수 권한에는 "다시 묻지 않음" 이 없어 [PermissionStatus.PermanentlyDenied] 가 나오지 않는다.
 * 요청하면 [settingsIntent] 화면을 열고, 돌아오면 [isGranted] 로 다시 판정한다.
 */
public interface SpecialPermissionSpec : PermissionSpec {

    /** 이 기기에서 이 권한이 존재하는지. false 면 상태는 [PermissionStatus.NotApplicable] 이다. */
    public fun isApplicable(environment: PermissionEnvironment): Boolean

    /** 설정 화면을 열려면 앱이 선언해야 하는 매니페스트 권한. 없으면 null. */
    public val manifestPermission: String?

    /** 지금 허용돼 있는지. 메인 스레드에서 불릴 수 있으므로 가볍게 구현한다. */
    public fun isGranted(context: Context): Boolean

    /** 사용자가 이 권한을 켜는 설정 화면의 인텐트. 열 수 없으면 앱 상세 설정 화면으로 대신 연다. */
    public fun settingsIntent(context: Context): Intent
}

/** 허용 정도. [RuntimePermissionSpec.evaluate] 가 돌려준다. */
public enum class GrantLevel {
    /** 기능을 온전히 쓸 수 있다 */
    FULL,

    /** 일부만 허용됐다 — 대략적 위치만, 사진 일부 선택만, 백그라운드 없이 포그라운드 위치만 등 */
    PARTIAL,

    /** 허용된 것이 없다 */
    NONE,
}
