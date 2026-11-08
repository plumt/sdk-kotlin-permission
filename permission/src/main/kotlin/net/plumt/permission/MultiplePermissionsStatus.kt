package net.plumt.permission

/**
 * 여러 명세의 상태 묶음. 순서는 넘긴 명세 순서를 따른다.
 * 권한 상태([statuses])와 시스템 기능 상태([requirements])를 따로 담는다.
 *
 * @throws IllegalArgumentException 명세와 요구 조건이 모두 비었거나 id 가 겹칠 때 (생성 시)
 */
public class MultiplePermissionsStatus(
    public val statuses: Map<PermissionSpec, PermissionStatus>,
    public val requirements: Map<SystemRequirement, RequirementStatus>,
) {

    /** 권한 상태만 담는다 */
    public constructor(statuses: Map<PermissionSpec, PermissionStatus>) : this(statuses, emptyMap())

    init {
        require(statuses.isNotEmpty() || requirements.isNotEmpty()) { "명세가 하나 이상 있어야 합니다" }
        val duplicated = statuses.keys.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "같은 id 의 명세가 겹칩니다: $duplicated" }
        val duplicatedRequirements = requirements.keys.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicatedRequirements.isEmpty()) { "같은 id 의 요구 조건이 겹칩니다: $duplicatedRequirements" }
    }

    /** 명세 목록(넘긴 순서) */
    public val specs: List<PermissionSpec> get() = statuses.keys.toList()

    /** 모두 더 요청할 것이 없는지([isFullyGranted]). 요구 조건은 보지 않는다 — [isReady] 참고 */
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

    /** 하나라도 설정 화면으로만 바꿀 수 있는지([needsSettings]) */
    public val anyNeedsSettings: Boolean get() = statuses.values.any { it.needsSettings }

    /** 다 허용되지 않은 명세 */
    public val notGranted: List<PermissionSpec> get() = statuses.filterValues { !it.isFullyGranted }.keys.toList()

    /** 요구 조건이 모두 켜져 있는지. 요구 조건이 없으면 참 */
    public val allRequirementsSatisfied: Boolean get() = requirements.values.all { it.isSatisfied }

    /** 켜기 요청으로 바꿀 수 있는 요구 조건([canRequestEnable]) */
    public val pendingRequirements: List<SystemRequirement>
        get() = requirements.filterValues { it.canRequestEnable }.keys.toList()

    /** 권한이 다 허용되고 요구 조건도 다 켜져 기능을 바로 쓸 수 있는지 */
    public val isReady: Boolean get() = allGranted && allRequirementsSatisfied

    /**
     * 그 명세의 상태.
     *
     * @throws IllegalArgumentException 이 묶음에 없는 명세일 때
     */
    public operator fun get(spec: PermissionSpec): PermissionStatus =
        statuses[spec] ?: statuses.entries.firstOrNull { it.key.id == spec.id }?.value
            ?: throw IllegalArgumentException("이 묶음에 없는 명세입니다: ${spec.id} (있는 것: ${statuses.keys.map { it.id }})")

    /**
     * 그 요구 조건의 상태.
     *
     * @throws IllegalArgumentException 이 묶음에 없는 요구 조건일 때
     */
    public operator fun get(requirement: SystemRequirement): RequirementStatus =
        requirements[requirement] ?: requirements.entries.firstOrNull { it.key.id == requirement.id }?.value
            ?: throw IllegalArgumentException(
                "이 묶음에 없는 요구 조건입니다: ${requirement.id} (있는 것: ${requirements.keys.map { it.id }})",
            )

    override fun equals(other: Any?): Boolean =
        other is MultiplePermissionsStatus && other.statuses == statuses && other.requirements == requirements

    override fun hashCode(): Int = statuses.hashCode() * 31 + requirements.hashCode()

    override fun toString(): String {
        val specs = statuses.entries.joinToString { "${it.key.id}=${it.value}" }
        if (requirements.isEmpty()) return "MultiplePermissionsStatus($specs)"
        return "MultiplePermissionsStatus($specs; requirements: ${requirements.entries.joinToString { "${it.key.id}=${it.value}" }})"
    }
}
