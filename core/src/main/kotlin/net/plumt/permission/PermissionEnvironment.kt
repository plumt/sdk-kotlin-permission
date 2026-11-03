package net.plumt.permission

import net.plumt.permission.internal.StaticEnvironment

/**
 * 명세가 판단에 쓰는 기기 정보. 실제 값은 [PermissionManager] 가 채우고, 테스트에서는 [of] 로 만든다.
 */
public interface PermissionEnvironment {
    /** 기기의 API 레벨(`Build.VERSION.SDK_INT`) */
    public val sdkInt: Int

    /** 앱 매니페스트가 이 권한을 선언했는지(이 기기에서 유효한 선언만 — `maxSdkVersion` 이 지난 선언은 빠진다) */
    public fun isDeclared(permission: String): Boolean

    public companion object {
        /**
         * 고정 값 환경을 만든다. 명세를 단위 테스트할 때 쓴다.
         *
         * @throws IllegalArgumentException [sdkInt] 가 1 미만일 때
         */
        public fun of(sdkInt: Int, declaredPermissions: Set<String> = emptySet()): PermissionEnvironment {
            require(sdkInt >= 1) { "sdkInt 는 1 이상이어야 합니다: $sdkInt" }
            return StaticEnvironment(sdkInt, declaredPermissions.toSet())
        }
    }
}
