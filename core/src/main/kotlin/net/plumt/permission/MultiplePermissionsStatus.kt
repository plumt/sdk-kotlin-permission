package net.plumt.permission

/**
 * 여러 명세의 상태 묶음. 순서는 넘긴 명세 순서를 따른다.
 *
 * @throws IllegalArgumentException 명세가 비었거나 [PermissionSpec.id] 가 겹칠 때 (생성 시)
 */
public class MultiplePermissionsStatus(public val statuses: Map<PermissionSpec, PermissionStatus>) {

    init {
        require(statuses.isNotEmpty()) { "명세가 하나 이상 있어야 합니다" }
        val duplicated = statuses.keys.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "같은 id 의 명세가 겹칩니다: $duplicated" }
    }

    /** 명세 목록(넘긴 순서) */
    public val specs: List<PermissionSpec> get() = statuses.keys.toList()

    /** 모두 더 요청할 것이 없는지([isFullyGranted]) */
    public val allGranted: Boolean get() = statuses.values.all { it.isFullyGranted }

    /** 모두 적어도 일부라도 쓸 수 있는지([isUsable]) */
    public val allUsable: Boolean get() = statuses.values.all { it.isUsable }

    /** 하나라도 근거 안내가 권해지는지 */
    public val shouldShowRationale: Boolean get() = statuses.values.any { it.shouldShowRationale }

    /** 하나라도 영구 거부인지 */
    public val anyPermanentlyDenied: Boolean get() = statuses.values.any { it is PermissionStatus.PermanentlyDenied }

    /** 아직 다 허용되지 않은 명세 가운데 설정 화면으로만 바꿀 수 있는 것만 남았는지 */
    public val onlySettingsCanHelp: Boolean
        get() {
            val pending = statuses.values.filterNot { it.isFullyGranted }
            return pending.isNotEmpty() && pending.all { it.needsSettings }
        }

    /** 다 허용되지 않은 명세 */
    public val notGranted: List<PermissionSpec> get() = statuses.filterValues { !it.isFullyGranted }.keys.toList()

    /**
     * 그 명세의 상태.
     *
     * @throws IllegalArgumentException 이 묶음에 없는 명세일 때
     */
    public operator fun get(spec: PermissionSpec): PermissionStatus =
        statuses[spec] ?: statuses.entries.firstOrNull { it.key.id == spec.id }?.value
            ?: throw IllegalArgumentException("이 묶음에 없는 명세입니다: ${spec.id} (있는 것: ${statuses.keys.map { it.id }})")

    override fun equals(other: Any?): Boolean = other is MultiplePermissionsStatus && other.statuses == statuses

    override fun hashCode(): Int = statuses.hashCode()

    override fun toString(): String = "MultiplePermissionsStatus(${statuses.entries.joinToString { "${it.key.id}=${it.value}" }})"
}
