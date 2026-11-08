package net.plumt.permission

/**
 * 라이브러리 밖(시스템·명세 구현)에서 생긴 실패. 원래 예외는 [cause] 에 있다.
 *
 * 지금은 설정 화면을 열 수 없을 때(명세의 화면과 앱 상세 설정 화면이 모두 없을 때),
 * 그리고 소비자가 구현한 명세가 예외를 던졌을 때 난다.
 */
public class PermissionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
