package net.plumt.permission

/** [PermissionManager.request] 의 결과 */
public sealed interface RequestResult {
    /** 요청 뒤(또는 화면 없이) 판정한 상태 */
    public val status: MultiplePermissionsStatus

    /** 요청 흐름을 마쳤다. 이미 다 허용돼 있었으면 시스템 화면 없이 곧바로 온다 */
    public data class Completed(override val status: MultiplePermissionsStatus) : RequestResult

    /**
     * 요청을 띄울 화면(시작된 `ComponentActivity`)이 없었거나, 흐름 도중 화면이 닫혀(회전이 아닌 종료) 결과를 받지 못했다.
     * [status] 는 그 시점에 다시 판정한 상태다. 화면이 보일 때 다시 요청한다.
     */
    public data class NoActivity(override val status: MultiplePermissionsStatus) : RequestResult
}
