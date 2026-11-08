package net.plumt.permission.internal

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import net.plumt.permission.GrantLevel
import net.plumt.permission.ManifestCheck
import net.plumt.permission.MultiplePermissionsStatus
import net.plumt.permission.PermissionConfig
import net.plumt.permission.PermissionEnvironment
import net.plumt.permission.PermissionException
import net.plumt.permission.PermissionManager
import net.plumt.permission.PermissionRequester
import net.plumt.permission.PermissionSpec
import net.plumt.permission.PermissionStatus
import net.plumt.permission.RequestHistoryStore
import net.plumt.permission.RequestResult
import net.plumt.permission.RequirementStatus
import net.plumt.permission.SettingsPolicy
import net.plumt.permission.SystemRequirement
import net.plumt.permission.isFullyGranted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.plumt.permission.RequestHandle
import net.plumt.permission.RuntimePermissionSpec
import net.plumt.permission.SpecialPermissionSpec

/** 매니페스트 선언은 실행 중에 바뀌지 않으므로 처음 물을 때 한 번 읽는다 */
internal class PackageEnvironment(
    private val context: Context,
    override val sdkInt: Int,
    override val targetSdkInt: Int,
) : PermissionEnvironment {
    private val declared: Set<String> by lazy {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.toSet().orEmpty()
    }

    override fun isDeclared(permission: String): Boolean = permission in declared

    override fun toString(): String = "PermissionEnvironment(sdkInt=$sdkInt, targetSdkInt=$targetSdkInt)"
}

internal class DefaultPermissionManager(
    private val context: Context,
    private val history: RequestHistoryStore,
    private val manifestCheck: ManifestCheck,
    override val environment: PermissionEnvironment,
    private val isDebuggable: Boolean,
    private val isHeld: (String) -> Boolean,
    private val rationale: (Activity, String) -> Boolean,
    private val tracker: ActivityTracker? = null,
    internal val dialogWatcher: DialogWatcher = PauseDialogWatcher(),
) : PermissionManager {

    /** 모든 요청기가 함께 서는 줄 — 시스템 권한 대화상자는 한 번에 하나만 뜬다 */
    internal val gate: RequestGate = RequestGate()

    internal val activityTracker: ActivityTracker? get() = tracker

    /** 콜백 요청이 suspend 요청을 돌리는 곳. 관리자마다 하나이고 메인 스레드에서 돈다 */
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun check(spec: PermissionSpec, activity: Activity?): PermissionStatus = when (spec) {
        is RuntimePermissionSpec -> runtimeStatus(spec, activity)
        is SpecialPermissionSpec -> specialStatus(spec)
    }

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

    override fun check(requirement: SystemRequirement): RequirementStatus {
        val status = guardedRequirement(requirement) { requirement.status(context, environment) }
        if (status != RequirementStatus.Disabled) return status
        val missing = guardedRequirement(requirement) { requirement.permissions(environment) }.filterNot(isHeld)
        return if (missing.isEmpty()) status else RequirementStatus.PermissionRequired(missing.toSet())
    }

    override fun undeclaredPermissions(specs: List<PermissionSpec>, requirements: List<SystemRequirement>): Set<String> {
        val used = LinkedHashSet<String>()
        for (spec in specs) {
            when (spec) {
                is RuntimePermissionSpec -> used += guarded(spec) { spec.declaredPermissions(environment) }
                is SpecialPermissionSpec -> if (guarded(spec) { spec.isApplicable(environment) }) used += listOfNotNull(spec.manifestPermission)
            }
        }
        for (requirement in requirements) used += guardedRequirement(requirement) { requirement.declaredPermissions(environment) }
        return used.filterNot(environment::isDeclared).toCollection(LinkedHashSet())
    }

    private fun runtimeStatus(spec: RuntimePermissionSpec, activity: Activity?): PermissionStatus {
        val all = guarded(spec) { spec.permissions(environment) }
        if (all.isEmpty()) return PermissionStatus.NotApplicable(environment.sdkInt)
        forgetGranted(all)
        val level = guarded(spec) { spec.evaluate(environment, isHeld) }
        if (level == GrantLevel.Full) return PermissionStatus.Granted
        val pending = pendingOf(spec)
        val rationaleOf = if (activity == null) emptyMap() else pending.associateWith { rationale(activity, it) }
        // 근거 안내가 서 있으면 대화상자가 뜬다는 뜻이다 — 영구 거부 이력은 틀렸다
        for ((permission, shows) in rationaleOf) {
            if (shows && history.isPermanentlyDenied(permission)) history.record(permission, permanentlyDenied = false)
        }
        val showRationale = rationaleOf.values.any { it }
        val permanent = !showRationale && pending.any { history.isPermanentlyDenied(it) && !isReaskableWhileSelected(it) }
        return when (level) {
            GrantLevel.Full -> PermissionStatus.Granted
            GrantLevel.Partial -> PermissionStatus.Partial(
                granted = all.filter(isHeld).toSet(),
                missing = all.filterNot(isHeld).toSet(),
                shouldShowRationale = showRationale,
                isMissingPermanentlyDenied = permanent,
                access = guarded(spec) { spec.partialAccess(environment, isHeld) },
            )
            GrantLevel.None -> when {
                showRationale -> PermissionStatus.Denied(shouldShowRationale = true)
                permanent -> PermissionStatus.PermanentlyDenied
                else -> PermissionStatus.Denied(shouldShowRationale = false)
            }
        }
    }

    /** 허용된 권한의 이력은 지운다 — 나중에 권한이 초기화되면 처음 상태로 판정해야 한다 */
    internal fun forgetGranted(permissions: Collection<String>) {
        for (permission in permissions) {
            if (isHeld(permission) && history.hasRequested(permission)) history.remove(permission)
        }
    }

    private fun specialStatus(spec: SpecialPermissionSpec): PermissionStatus = when {
        !guarded(spec) { spec.isApplicable(environment) } -> PermissionStatus.NotApplicable(environment.sdkInt)
        guarded(spec) { spec.isGranted(context) } -> PermissionStatus.Granted
        else -> PermissionStatus.Denied(shouldShowRationale = false)
    }

    internal fun pendingOf(spec: RuntimePermissionSpec): List<String> =
        guarded(spec) { spec.nextRequest(environment, isHeld) }.distinct()

    internal fun shouldShowRationale(activity: Activity, permission: String): Boolean = rationale(activity, permission)

    internal fun isGranted(permission: String): Boolean = isHeld(permission)

    /**
     * 사진·동영상 일부 선택(READ_MEDIA_VISUAL_USER_SELECTED)이 허용된 동안 나머지 매체 권한은 영구 거부가 될 수 없다.
     * 실기기(Android 16): "추가 선택 안함" 으로 근거 안내가 사라져도 다음 요청에서 시스템이 선택 대화상자를 다시 띄운다.
     */
    internal fun isReaskableWhileSelected(permission: String): Boolean =
        (permission == MediaSpec.IMAGES || permission == MediaSpec.VIDEO) &&
            environment.sdkInt >= Api.UPSIDE_DOWN_CAKE && isHeld(MediaSpec.USER_SELECTED)

    internal val historyStore: RequestHistoryStore get() = history

    internal val appContext: Context get() = context

    /** 요청 직전 매니페스트 선언 확인. 빠졌으면 [IllegalStateException] */
    internal fun verifyDeclared(permissions: Collection<String>, problems: Collection<String> = emptyList()) {
        val enforce = when (manifestCheck) {
            ManifestCheck.Off -> false
            ManifestCheck.AlwaysThrow -> true
            ManifestCheck.ThrowIfDebuggable -> isDebuggable
        }
        if (!enforce) return
        val missing = permissions.filterNot(environment::isDeclared).distinct()
        check(missing.isEmpty() && problems.isEmpty()) {
            buildString {
                if (missing.isNotEmpty()) {
                    append("앱 매니페스트에 선언되지 않은 권한을 요청하려 합니다: $missing. ")
                    append("선언하지 않은 권한은 대화상자 없이 거부되어 영구 거부처럼 보입니다. ")
                    append("앱의 AndroidManifest.xml 에 ")
                    append(missing.joinToString(" ") { "<uses-permission android:name=\"$it\" />" })
                    append(" 를 추가하세요(이 라이브러리는 권한을 선언하지 않습니다. maxSdkVersion 은 README 의 표를 따릅니다). ")
                }
                if (problems.isNotEmpty()) {
                    append("앱 매니페스트에 필요한 선언이 없습니다: ${problems.joinToString("; ")}. ")
                }
                append("확인을 끄려면 PermissionConfig(manifestCheck = ManifestCheck.Off) 를 쓰세요.")
            }
        }
    }

    override fun appSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    override fun openAppSettings(context: Context) {
        val intent = appSettingsIntent()
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw PermissionException("앱 상세 설정 화면을 열 수 없습니다: ${intent.action}", e)
        }
    }

    override fun notificationSettingsIntent(channelId: String?): Intent = notificationSettingsIntent(context, channelId)

    override fun openNotificationSettings(context: Context, channelId: String?) {
        require(channelId == null || channelId.isNotEmpty()) { "channelId 가 빈 문자열입니다" }
        val intent = notificationSettingsIntent(channelId)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (first: ActivityNotFoundException) {
            // 알림 설정 화면을 바꾼 기기 — 앱 상세 설정 화면에서 알림으로 들어간다
            try {
                openAppSettings(context)
            } catch (second: PermissionException) {
                second.addSuppressed(first)
                throw second
            }
        }
    }

    override fun createRequester(
        caller: ActivityResultCaller,
        activity: () -> Activity?,
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement>,
        onResult: (MultiplePermissionsStatus) -> Unit,
    ): PermissionRequester {
        validateSpecs(specs, requirements)
        val requester = DefaultPermissionRequester(this, specs.toList(), requirements.toList(), activity, onResult)
        val saved = (caller as? SavedStateRegistryOwner)?.savedStateRegistry
        requester.attachState(saved, saved?.let(::nextStateKey))
        val runtime = caller.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            requester.onRuntimeResult(it)
        }
        val settings = caller.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            requester.onSettingsResult(it)
        }
        val sender = caller.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            requester.onSettingsResult()
        }
        requester.attachLaunchers(runtime, settings, sender)
        // 화면이 끝나면 androidx 가 런처를 푼다 — 줄의 자리도 함께 내놓는다
        (caller as? LifecycleOwner)?.lifecycle?.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) requester.onOwnerDestroyed() },
        )
        return requester
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
        val stateKey = "$key#state"
        require(savedStateRegistry?.getSavedStateProvider(stateKey) == null) {
            "같은 key 의 요청기가 이미 등록돼 있습니다: $key"
        }
        validateSpecs(specs, requirements)
        val requester = DefaultPermissionRequester(this, specs.toList(), requirements.toList(), activity, onResult, gateToken = key)
        requester.attachState(savedStateRegistry, stateKey.takeIf { savedStateRegistry != null })
        val runtime = registry.register("$key#runtime", ActivityResultContracts.RequestMultiplePermissions()) {
            requester.onRuntimeResult(it)
        }
        val settings = registry.register("$key#settings", ActivityResultContracts.StartActivityForResult()) {
            requester.onSettingsResult(it)
        }
        val sender = registry.register("$key#sender", ActivityResultContracts.StartIntentSenderForResult()) {
            requester.onSettingsResult()
        }
        requester.attachLaunchers(runtime, settings, sender)
        return requester
    }

    override suspend fun request(
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement>,
        settingsPolicy: SettingsPolicy,
    ): RequestResult = withContext(Dispatchers.Main.immediate) {
        validateSpecs(specs, requirements)
        val activity = tracker?.current() ?: return@withContext RequestResult.NoActivity(checkAll(specs, requirements, null))
        RequestSession(this@DefaultPermissionManager, specs, requirements, settingsPolicy).await(activity)
    }

    override suspend fun request(
        activity: ComponentActivity,
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement>,
        settingsPolicy: SettingsPolicy,
    ): RequestResult = withContext(Dispatchers.Main.immediate) {
        validateSpecs(specs, requirements)
        RequestSession(this@DefaultPermissionManager, specs, requirements, settingsPolicy).await(activity)
    }

    override fun request(
        activity: ComponentActivity,
        specs: List<PermissionSpec>,
        requirements: List<SystemRequirement>,
        settingsPolicy: SettingsPolicy,
        onResult: (RequestResult) -> Unit,
    ): RequestHandle {
        // 인자·매니페스트 실수는 코루틴 안이 아니라 지금 호출부에 던진다
        validateSpecs(specs, requirements)
        val plan = plan(specs, requirements, activity)
        verifyDeclared(plan.declared, plan.problems)
        val job = callbackScope.launch {
            val result = request(activity, specs, requirements, settingsPolicy)
            onResult(result)
        }
        return JobHandle(job)
    }

    /**
     * 요청 한 번의 계획 — 아직 다 허용되지 않은 명세의 다음 런타임 권한, 특수 권한, 꺼진 요구 조건과 확인할 선언.
     * 요청기와 콜백 요청이 같은 계획을 쓴다.
     */
    internal fun plan(specs: List<PermissionSpec>, requirements: List<SystemRequirement>, activity: Activity?): RequestPlan {
        val runtime = LinkedHashSet<String>()
        val specials = ArrayList<SpecialPermissionSpec>()
        val pendingRuntime = ArrayList<RuntimePermissionSpec>()
        for (spec in specs) {
            if (check(spec, activity).isFullyGranted) continue
            when (spec) {
                is RuntimePermissionSpec -> {
                    runtime += pendingOf(spec)
                    pendingRuntime += spec
                }
                is SpecialPermissionSpec -> specials += spec
            }
        }
        val needed = ArrayList<SystemRequirement>()
        for (requirement in requirements) {
            when (val status = check(requirement)) {
                is RequirementStatus.Disabled -> needed += requirement
                is RequirementStatus.PermissionRequired -> {
                    needed += requirement
                    val askable = guardedRequirement(requirement) { requirement.permissions(environment) }
                    runtime += status.permissions.filter { it in askable }
                }
                // 켜져 있거나 앱이 열 화면이 없거나 이 기기에 없다 — 요청할 것이 없다
                RequirementStatus.Satisfied, RequirementStatus.Unmet, is RequirementStatus.Unsupported -> Unit
            }
        }
        val declared = runtime.toList() + specials.mapNotNull { it.manifestPermission } +
            needed.flatMap { r -> guardedRequirement(r) { r.declaredPermissions(environment) } }
        val problems = specials.flatMap { s -> guarded(s) { s.missingDeclarations(context) } } +
            pendingRuntime.filter { spec -> pendingOf(spec).isNotEmpty() }.flatMap { s -> guarded(s) { s.missingDeclarations(context) } }
        return RequestPlan(runtime, specials, needed, declared, problems)
    }

    override fun toString(): String = "PermissionManager(environment=$environment, manifestCheck=$manifestCheck)"

    companion object {
        fun create(context: Context, config: PermissionConfig): DefaultPermissionManager {
            val app = context.applicationContext ?: context
            val tracker = (app as? Application)?.let { application ->
                ActivityTracker().also(application::registerActivityLifecycleCallbacks)
            }
            return DefaultPermissionManager(
                context = app,
                history = config.historyStore ?: RequestHistoryStore.sharedPreferences(app),
                manifestCheck = config.manifestCheck,
                environment = PackageEnvironment(app, Build.VERSION.SDK_INT, app.applicationInfo.targetSdkVersion),
                isDebuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                isHeld = { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED },
                rationale = { activity, permission -> ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) },
                tracker = tracker,
            )
        }
    }
}

/** [DefaultPermissionManager.plan] 의 결과 */
internal class RequestPlan(
    val runtime: Set<String>,
    val specials: List<SpecialPermissionSpec>,
    val requirements: List<SystemRequirement>,
    val declared: List<String>,
    val problems: List<String>,
)

private class JobHandle(private val job: Job) : RequestHandle {
    override val isActive: Boolean get() = job.isActive

    override fun cancel() = job.cancel()

    override fun toString(): String = "RequestHandle(active=$isActive)"
}

/** 등록 순서대로 비어 있는 첫 키 — 재생성된 화면도 같은 순서로 등록하므로 같은 키를 얻는다 */
private fun nextStateKey(registry: SavedStateRegistry): String {
    var index = 0
    while (registry.getSavedStateProvider("$STATE_KEY_PREFIX$index") != null) index++
    return "$STATE_KEY_PREFIX$index"
}

private const val STATE_KEY_PREFIX = "net.plumt.permission.requester#"

internal fun validateSpecs(specs: List<PermissionSpec>) {
    require(specs.isNotEmpty()) { "명세가 하나 이상 있어야 합니다" }
    val duplicated = specs.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicated.isEmpty()) { "같은 id 의 명세가 겹칩니다: $duplicated" }
}

internal fun validateSpecs(specs: List<PermissionSpec>, requirements: List<SystemRequirement>) {
    if (requirements.isEmpty()) return validateSpecs(specs)
    val duplicatedSpecs = specs.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicatedSpecs.isEmpty()) { "같은 id 의 명세가 겹칩니다: $duplicatedSpecs" }
    val duplicated = requirements.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicated.isEmpty()) { "같은 id 의 요구 조건이 겹칩니다: $duplicated" }
}

/** 소비자가 구현한 요구 조건의 예외를 [PermissionException] 으로 감싼다 */
internal inline fun <T> guardedRequirement(requirement: SystemRequirement, block: () -> T): T = try {
    block()
} catch (e: PermissionException) {
    throw e
} catch (e: RuntimeException) {
    throw PermissionException(
        "요구 조건이 실패했습니다: ${runCatching { requirement.id }.getOrDefault(requirement::class.java.name)}",
        e,
    )
}

/** 소비자가 구현한 명세의 예외를 [PermissionException] 으로 감싼다 */
internal inline fun <T> guarded(spec: PermissionSpec, block: () -> T): T = try {
    block()
} catch (e: PermissionException) {
    throw e
} catch (e: RuntimeException) {
    throw PermissionException("명세가 실패했습니다: ${runCatching { spec.id }.getOrDefault(spec::class.java.name)}", e)
}
