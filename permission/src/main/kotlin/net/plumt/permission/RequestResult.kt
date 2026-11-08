package net.plumt.permission

/** [PermissionManager.request] 의 결과. 둘로 닫힌 집합이다. */
public sealed interface RequestResult {
    /** 요청 뒤(또는 화면 없이) 판정한 상태 */
    public val status: MultiplePermissionsStatus

    /** 요청 흐름을 마쳤다. 이미 다 허용돼 있었으면 시스템 화면 없이 곧바로 온다 */
    public class Completed(override val status: MultiplePermissionsStatus) : RequestResult {
        override fun equals(other: Any?): Boolean = other is Completed && other.status == status

        override fun hashCode(): Int = status.hashCode()

        override fun toString(): String = "Completed($status)"
    }

    /**
     * 요청을 띄울 화면(시작된 `ComponentActivity`)이 없었거나, 흐름 도중 화면이 닫혀(회전이 아닌 종료) 결과를 받지 못했다.
     * [status] 는 그 시점에 다시 판정한 상태다. 화면이 보일 때 다시 요청한다.
     */
    public class NoActivity(override val status: MultiplePermissionsStatus) : RequestResult {
        override fun equals(other: Any?): Boolean = other is NoActivity && other.status == status

        override fun hashCode(): Int = 31 * status.hashCode() + 1

        override fun toString(): String = "NoActivity($status)"
    }
}

/**
 * 콜백 요청([PermissionManager.request] 의 콜백 형태)의 취소 핸들.
 */
public interface RequestHandle {
    /** 아직 결과를 기다리는 중인지. 결과를 받았거나 취소했으면 거짓 */
    public val isActive: Boolean

    /** 요청을 취소한다. 등록을 풀고 줄에서 빠지며 콜백은 불리지 않는다. 여러 번 불러도 된다 */
    public fun cancel()
}
