package net.plumt.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultRegistry
import androidx.savedstate.SavedStateRegistry
import net.plumt.permission.internal.validateSpecs

/**
 * 테스트용 [PermissionManager]. 기기·Robolectric 없이 앱 단위 테스트에서 권한 상태와 요청 결과를 정해 둔다.
 *
 * - 상태는 [setStatus] 로 정한다. 정하지 않은 명세는 [defaultStatus], 요구 조건은 [defaultRequirementStatus] 다
 * - 요청하면 [setResultAfterRequest] 로 정한 상태로 바뀐 뒤(정하지 않았으면 그대로) 결과가 온다. 요청한 명세 id 는 [requests] 에 쌓인다
 * - [hasActivity] 가 거짓이면 화면 없는 요청이 [RequestResult.NoActivity] 다
 * - 콜백·요청기 결과는 **부른 스레드에서 곧바로** 온다(메인 스레드로 옮기지 않는다) — 테스트 디스패처 없이도 결정적이다
 *
 * Android 클래스는 인자로만 받고 만들지 않는다. [appSettingsIntent]·[notificationSettingsIntent] 만 `Intent` 를 만들므로
 * 기기 없는 JVM 테스트에서는 부르지 않는다. 스레드 안전하지 않다.
 *
 * @param environment [environment] 가 돌려줄 값
 */
public class FakePermissionManager(
    override val environment: PermissionEnvironment = PermissionEnvironment.of(sdkInt = 36),
) : PermissionManager {

    private val statuses = LinkedHashMap<String, PermissionStatus>()
    private val afterRequest = LinkedHashMap<String, PermissionStatus>()
    private val requirementStatuses = LinkedHashMap<String, RequirementStatus>()
    private val afterRequestRequirements = LinkedHashMap<String, RequirementStatus>()
    private val requested = ArrayList<List<String>>()

    /** [setStatus] 로 정하지 않은 명세의 상태. 기본은 처음 요청 전 상태(`Denied(false)`) */
    public var defaultStatus: PermissionStatus = PermissionStatus.Denied(shouldShowRationale = false)

    /** [setStatus] 로 정하지 않은 요구 조건의 상태. 기본은 [RequirementStatus.Satisfied] */
    public var defaultRequirementStatus: RequirementStatus = RequirementStatus.Satisfied

    /** 화면 없는 `request` 가 띄울 화면이 있다고 볼지. 거짓이면 [RequestResult.NoActivity] 다. 기본은 참 */
    public var hasActivity: Boolean = true

    /** [undeclaredPermissions] 가 돌려줄 값. 기본은 빈 집합 */
    public var undeclared: Set<String> = emptySet()

    /** 요청한 명세·요구 조건 id 목록(요청마다 하나, 순서대로). 이미 다 허용이어도 요청이면 쌓인다 */
    public val requests: List<List<String>> get() = requested.toList()

    /** 앱 설정 화면을 연 횟수([openAppSettings]·[openNotificationSettings]·요청기의 `openAppSettings`) */
    public var settingsOpenCount: Int = 0
        private set

    /** [spec] 의 지금 상태를 정한다 */
    public fun setStatus(spec: PermissionSpec, status: PermissionStatus) {
        statuses[spec.id] = status
    }

    /** [requirement] 의 지금 상태를 정한다 */
    public fun setStatus(requirement: SystemRequirement, status: RequirementStatus) {
        requirementStatuses[requirement.id] = status
    }

    /** [spec] 을 요청하면 바뀔 상태를 정한다. 요청할 때마다 적용된다 */
    public fun setResultAfterRequest(spec: PermissionSpec, status: PermissionStatus) {
        afterRequest[spec.id] = status
    }

    /** [requirement] 를 요청하면 바뀔 상태를 정한다 */
    public fun setResultAfterRequest(requirement: SystemRequirement, status: RequirementStatus) {
        afterRequestRequirements[requirement.id] = status
    }

    /** 정한 상태·요청 기록을 모두 지운다 */
    public fun reset() {
        statuses.clear()
        afterRequest.clear()
        requirementStatuses.clear()
        afterRequestRequirements.clear()
        requested.clear()
        settingsOpenCount = 0
    }

    override fun check(spec: PermissionSpec, activity: Activity?): PermissionStatus = statuses[spec.id] ?: defaultStatus

    override fun check(requirement: SystemRequirement): RequirementStatus =
        requirementStatuses[requirement.id] ?: defaultRequirementStatus

    override fun checkAll(specs: List<PermissionSpec>, activity: Activity?): MultiplePermissionsStatus {
        validateSpecs(specs)
        return MultiplePermissionsStatus(specs.associateWith { check(it, activity) })
    }

    override fun checkAll(
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement>,
        activity: Activity?,
    ): MultiplePermissionsStatus {
        validateSpecs(specs, requirements)
        return MultiplePermissionsStatus(specs.associateWith { check(it, activity) }, requirements.associateWith { check(it) })
    }

    override fun undeclaredPermissions(specs: List<PermissionSpec>, requirements: List<SystemRequirement>): Set<String> = undeclared

    override fun appSettingsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)

    override fun openAppSettings(context: Context) {
        settingsOpenCount++
    }

    // Settings.ACTION_APP_NOTIFICATION_SETTINGS(API 26) 의 값 — 가짜는 API 레벨을 가리지 않는다
    override fun notificationSettingsIntent(channelId: String?): Intent = Intent("android.settings.APP_NOTIFICATION_SETTINGS")

    override fun openNotificationSettings(context: Context, channelId: String?) {
        require(channelId == null || channelId.isNotEmpty()) { "channelId 가 빈 문자열입니다" }
        settingsOpenCount++
    }

    override suspend fun request(
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement>,
        settingsPolicy: SettingsPolicy,
    ): RequestResult {
        validateSpecs(specs, requirements)
        if (!hasActivity) return RequestResult.NoActivity(checkAll(specs, requirements, null))
        return RequestResult.Completed(perform(specs, requirements, settingsPolicy))
    }

    override suspend fun request(
        activity: ComponentActivity,
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement>,
        settingsPolicy: SettingsPolicy,
    ): RequestResult {
        validateSpecs(specs, requirements)
        return RequestResult.Completed(perform(specs, requirements, settingsPolicy))
    }

    override fun request(
        activity: ComponentActivity,
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement>,
        settingsPolicy: SettingsPolicy,
        onResult: (RequestResult) -> Unit,
    ): RequestHandle {
        validateSpecs(specs, requirements)
        onResult(RequestResult.Completed(perform(specs, requirements, settingsPolicy)))
        return DoneHandle
    }

    override fun createRequester(
        caller: ActivityResultCaller,
        activity: () -> Activity?,
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement>,
        onResult: (MultiplePermissionsStatus) -> Unit,
    ): PermissionRequester {
        validateSpecs(specs, requirements)
        return FakeRequester(specs.toList(), requirements.toList(), onResult)
    }

    override fun createRequester(
        registry: ActivityResultRegistry,
        key: String,
        activity: () -> Activity?,
        specs: List<PermissionSpec>,
        savedStateRegistry: SavedStateRegistry?,
        requirements: List<SystemRequirement>,
        onResult: (MultiplePermissionsStatus) -> Unit,
    ): PermissionRequester {
        require(key.isNotBlank()) { "key 가 비었습니다" }
        validateSpecs(specs, requirements)
        return FakeRequester(specs.toList(), requirements.toList(), onResult)
    }

    /** 요청 한 번 — 기록하고, 정한 결과를 적용하고, 설정 이동 정책을 흉내 낸 뒤 상태를 돌려준다 */
    private fun perform(
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement>,
        settingsPolicy: SettingsPolicy,
    ): MultiplePermissionsStatus {
        requested += specs.map { it.id } + requirements.map { it.id }
        for (spec in specs) afterRequest[spec.id]?.let { statuses[spec.id] = it }
        for (requirement in requirements) afterRequestRequirements[requirement.id]?.let { requirementStatuses[requirement.id] = it }
        val status = checkAll(specs, requirements, null)
        if (settingsPolicy == SettingsPolicy.Direct && status.anyNeedsSettings) settingsOpenCount++
        return status
    }

    override fun toString(): String = "FakePermissionManager(environment=$environment, statuses=$statuses)"

    private object DoneHandle : RequestHandle {
        override val isActive: Boolean get() = false

        override fun cancel() = Unit

        override fun toString(): String = "RequestHandle(active=false)"
    }

    private inner class FakeRequester(
        override val specs: List<PermissionSpec>,
        override val requirements: List<SystemRequirement>,
        private val onResult: (MultiplePermissionsStatus) -> Unit,
    ) : PermissionRequester {
        private var unregistered = false

        override val isRequesting: Boolean get() = false

        override fun launch(): Boolean = launch(SettingsPolicy.Never)

        override fun launch(settingsPolicy: SettingsPolicy): Boolean {
            check(!unregistered) { "등록이 풀린 요청기입니다. 새로 등록하세요" }
            onResult(perform(specs, requirements, settingsPolicy))
            return true
        }

        override fun openAppSettings(): Boolean {
            check(!unregistered) { "등록이 풀린 요청기입니다. 새로 등록하세요" }
            settingsOpenCount++
            onResult(checkAll(specs, requirements, null))
            return true
        }

        override fun unregister() {
            unregistered = true
        }

        override fun toString(): String = "FakePermissionRequester(specs=${specs.map { it.id }})"
    }
}
