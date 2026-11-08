package net.plumt.permission

/**
 * [PermissionManager] 설정. 만든 뒤 바뀌지 않는다.
 *
 * @property historyStore 요청 이력 저장소. null 이면 [RequestHistoryStore.sharedPreferences] 기본값을 쓴다
 * @property manifestCheck 요청 직전에 앱 매니페스트 선언을 확인하는 방식
 */
public class PermissionConfig(
    public val historyStore: RequestHistoryStore? = null,
    public val manifestCheck: ManifestCheck = ManifestCheck.ThrowIfDebuggable,
) {
    override fun toString(): String = "PermissionConfig(historyStore=$historyStore, manifestCheck=$manifestCheck)"
}

/**
 * 요청 직전 매니페스트 선언 확인. 셋으로 닫힌 집합이다.
 *
 * 선언하지 않은 권한을 요청하면 시스템은 대화상자 없이 곧바로 거부하고, 근거 안내 판정도 거짓이라
 * 영구 거부와 구별되지 않는다. 그 실수를 개발 중에 바로 드러내려는 확인이다.
 */
public sealed interface ManifestCheck {
    /** 디버그 가능한 빌드(`android:debuggable`)에서만 확인하고, 빠졌으면 `IllegalStateException` 을 던진다. 기본값. */
    public data object ThrowIfDebuggable : ManifestCheck

    /** 언제나 확인하고 빠졌으면 `IllegalStateException` 을 던진다. */
    public data object AlwaysThrow : ManifestCheck

    /** 확인하지 않는다. 빠진 권한은 시스템이 거부한다. */
    public data object Off : ManifestCheck
}
