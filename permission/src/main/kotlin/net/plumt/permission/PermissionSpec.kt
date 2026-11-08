package net.plumt.permission

import android.content.Context
import android.content.Intent

/**
 * 권한 하나를 설명하는 명세. 갈래는 둘뿐이다 — 시스템 대화상자로 묻는 [RuntimePermissionSpec] 과
 * 설정 화면에서 켜는 [SpecialPermissionSpec]. 새 권한은 둘 가운데 하나를 구현해 더한다.
 *
 * 내장 명세는 [PermissionSpecs] 가 만든다.
 */
public sealed interface PermissionSpec {
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
     * 이 기기에서 앱 매니페스트에 선언돼 있어야 하는 권한 — [permissions] 에 설치 시 부여되는 짝 권한(API 30 이하 블루투스의
     * `BLUETOOTH`·`BLUETOOTH_ADMIN` 등)을 더한 것. [PermissionManager.undeclaredPermissions] 와 빌드 lint 표의 일치 검사가 쓴다.
     * 기본은 [permissions].
     */
    public fun declaredPermissions(environment: PermissionEnvironment): List<String> = permissions(environment)

    /**
     * 현재 허용 상태로 이 기능을 얼마나 쓸 수 있는지 판정한다. 기본은 전부 허용이면 [GrantLevel.Full],
     * 일부만 허용이면 [GrantLevel.Partial], 하나도 없으면 [GrantLevel.None] 이다.
     */
    public fun evaluate(environment: PermissionEnvironment, isGranted: (String) -> Boolean): GrantLevel {
        val all = permissions(environment)
        val granted = all.count(isGranted)
        return when {
            granted == all.size -> GrantLevel.Full
            granted > 0 -> GrantLevel.Partial
            else -> GrantLevel.None
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

    /**
     * [evaluate] 가 [GrantLevel.Partial] 일 때 어떤 종류의 부분 접근인지. 기본은 [PartialAccess.SOME_PERMISSIONS] 다.
     * 내장 명세는 사진 일부 선택·대략적 위치·포그라운드 위치만을 가른다.
     */
    public fun partialAccess(environment: PermissionEnvironment, isGranted: (String) -> Boolean): PartialAccess =
        PartialAccess.SOME_PERMISSIONS

    /**
     * 권한 밖에 앱 매니페스트에 있어야 하는데 빠진 선언(Health Connect 의 권한 근거 화면 등)을 사람이 읽을 문장으로 돌려준다.
     * 요청 직전 선언 확인([ManifestCheck])이 이 값을 함께 본다. 기본은 없음.
     */
    public fun missingDeclarations(context: Context): List<String> = emptyList()
}

/**
 * 일부 허용([PermissionStatus.Partial])의 종류. [RuntimePermissionSpec.partialAccess] 가 돌려준다.
 *
 * 값이 늘 수 있는 열린 집합이다 — `when` 으로 가를 때는 `else` 를 둔다. 직접 만든 명세는 [name] 이 다른 값을 만들어 쓸 수 있다.
 * 같은 [name] 이면 같다.
 *
 * @property name 종류 이름(`SELECTED_MEDIA` 등)
 * @throws IllegalArgumentException [name] 이 비었을 때(생성 시)
 */
public class PartialAccess(public val name: String) {
    init {
        require(name.isNotBlank()) { "name 이 비었습니다" }
    }

    override fun equals(other: Any?): Boolean = other is PartialAccess && other.name == name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    public companion object {
        /** API 34 이상에서 사용자가 사진·동영상을 일부만 골랐다. 다시 요청하면 선택 화면이 다시 뜬다 */
        @JvmField
        public val SELECTED_MEDIA: PartialAccess = PartialAccess("SELECTED_MEDIA")

        /** 정밀 위치를 원했는데 사용자가 대략적 위치만 허용했다 */
        @JvmField
        public val APPROXIMATE_LOCATION: PartialAccess = PartialAccess("APPROXIMATE_LOCATION")

        /** 포그라운드 위치만 있고 백그라운드 위치("항상 허용")가 없다 */
        @JvmField
        public val FOREGROUND_LOCATION_ONLY: PartialAccess = PartialAccess("FOREGROUND_LOCATION_ONLY")

        /** 명세의 권한 가운데 일부만 허용됐다 — 위 어느 것에도 해당하지 않는 경우 */
        @JvmField
        public val SOME_PERMISSIONS: PartialAccess = PartialAccess("SOME_PERMISSIONS")
    }
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

    /**
     * [manifestPermission] 밖에 앱 매니페스트에 있어야 하는데 빠진 선언(알림 접근·접근성의 `<service>` 등)을
     * 사람이 읽을 문장으로 돌려준다. 요청 직전 선언 확인([ManifestCheck])이 이 값을 함께 본다. 기본은 없음.
     */
    public fun missingDeclarations(context: Context): List<String> = emptyList()

    /**
     * [settingsIntent] 로 연 화면이 돌아왔을 때 그 결과. 결과 데이터가 필요한 명세(화면 캡처 동의의 토큰)만 구현한다.
     * 설정 화면을 열 수 없어 앱 상세 설정 화면을 대신 열었을 때는 부르지 않는다. 메인 스레드에서 부른다. 기본은 무시.
     */
    public fun onSettingsResult(resultCode: Int, data: Intent?) {}
}

/**
 * 허용 정도. [RuntimePermissionSpec.evaluate] 가 돌려준다. 셋으로 닫힌 집합이다.
 */
public sealed interface GrantLevel {
    /** 기능을 온전히 쓸 수 있다 */
    public data object Full : GrantLevel

    /** 일부만 허용됐다 — 대략적 위치만, 사진 일부 선택만, 백그라운드 없이 포그라운드 위치만 등 */
    public data object Partial : GrantLevel

    /** 허용된 것이 없다 */
    public data object None : GrantLevel
}
