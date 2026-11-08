package net.plumt.permission

import net.plumt.permission.internal.StaticEnvironment

/**
 * 명세가 판단에 쓰는 기기 정보. 실제 값은 [PermissionManager] 가 채우고, 테스트에서는 [of] 로 만든다.
 */
public interface PermissionEnvironment {
    /** 기기의 API 레벨(`Build.VERSION.SDK_INT`) */
    public val sdkInt: Int

    /**
     * 앱의 targetSdkVersion. 알림·사진·블루투스처럼 targetSdk 가 낮으면 시스템이 옛 권한 모델을 쓰는 명세는
     * `min(sdkInt, targetSdkInt)` 로 분기한다. 기본값은 [sdkInt] 다(targetSdk 가 기기보다 낮지 않다고 본다).
     */
    public val targetSdkInt: Int get() = sdkInt

    /** 앱 매니페스트가 이 권한을 선언했는지(이 기기에서 유효한 선언만 — `maxSdkVersion` 이 지난 선언은 빠진다) */
    public fun isDeclared(permission: String): Boolean

    public companion object {
        /**
         * 고정 값 환경을 만든다. 명세를 단위 테스트할 때 쓴다.
         *
         * @throws IllegalArgumentException [sdkInt] 나 [targetSdkInt] 가 1 미만일 때
         */
        public fun of(
            sdkInt: Int,
            declaredPermissions: Set<String> = emptySet(),
            targetSdkInt: Int = sdkInt,
        ): PermissionEnvironment {
            require(sdkInt >= 1) { "sdkInt 는 1 이상이어야 합니다: $sdkInt" }
            require(targetSdkInt >= 1) { "targetSdkInt 는 1 이상이어야 합니다: $targetSdkInt" }
            return StaticEnvironment(sdkInt, declaredPermissions.toSet(), targetSdkInt)
        }
    }
}
