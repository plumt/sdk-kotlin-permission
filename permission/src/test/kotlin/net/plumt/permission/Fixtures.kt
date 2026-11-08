package net.plumt.permission

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import net.plumt.permission.internal.DefaultPermissionManager

/** 허용·근거 안내 상태를 손으로 정하는 가짜 기기 */
internal class FakeDevice {
    val granted = mutableSetOf<String>()
    val rationale = mutableSetOf<String>()
    var rationaleCalls = 0

    /** 다음 요청의 대화상자 관측 결과 */
    var dialogEvidence = net.plumt.permission.internal.DialogEvidence.CERTAIN

    /** 다음 요청에서 시스템이 대화상자를 띄우는지 — 거짓이면 화면 없이 곧바로 결과가 온다("다시 묻지 않음") */
    var dialogShown: Boolean
        get() = dialogEvidence != net.plumt.permission.internal.DialogEvidence.NONE
        set(value) {
            dialogEvidence = if (value) net.plumt.permission.internal.DialogEvidence.CERTAIN else net.plumt.permission.internal.DialogEvidence.NONE
        }
    var dialogWatches = 0

    fun grant(vararg permissions: String) {
        granted += permissions
    }

    fun revoke(vararg permissions: String) {
        granted -= permissions.toSet()
    }
}

internal fun fakeManager(
    context: Context,
    device: FakeDevice,
    sdkInt: Int = 34,
    declared: Set<String> = ALL_DECLARED,
    history: RequestHistoryStore = RequestHistoryStore.inMemory(),
    manifestCheck: ManifestCheck = ManifestCheck.ThrowIfDebuggable,
    debuggable: Boolean = true,
    tracker: net.plumt.permission.internal.ActivityTracker? = null,
    dialogWatcher: net.plumt.permission.internal.DialogWatcher = net.plumt.permission.internal.DialogWatcher {
        device.dialogWatches++
        net.plumt.permission.internal.DialogWatch { device.dialogEvidence }
    },
): DefaultPermissionManager = DefaultPermissionManager(
    context = context,
    history = history,
    manifestCheck = manifestCheck,
    environment = PermissionEnvironment.of(sdkInt, declared),
    isDebuggable = debuggable,
    isHeld = { it in device.granted },
    rationale = { _: Activity, p: String ->
        device.rationaleCalls++
        p in device.rationale
    },
    tracker = tracker,
    dialogWatcher = dialogWatcher,
)

/** 손으로 상태와 켜기 요청을 정하는 시스템 기능 */
internal class FakeRequirement(
    override val id: String = "fake.requirement",
    var status: RequirementStatus = RequirementStatus.Disabled,
    var runtime: List<String> = emptyList(),
    var declared: List<String>? = null,
    var request: (() -> EnableRequest?)? = { EnableRequest.OfIntent(Intent("fake.ENABLE")) },
    var async: Boolean = false,
) : SystemRequirement {
    var pending: ((EnableRequest?) -> Unit)? = null
    var enableCalls = 0

    override fun permissions(environment: PermissionEnvironment): List<String> = runtime

    override fun declaredPermissions(environment: PermissionEnvironment): List<String> = declared ?: runtime

    override fun status(context: Context, environment: PermissionEnvironment): RequirementStatus = status

    override fun enableRequest(context: Context, onReady: (EnableRequest?) -> Unit) {
        enableCalls++
        val make = request ?: throw IllegalStateException("enable boom")
        if (async) pending = { onReady(it) } else onReady(make())
    }
}

internal val ALL_DECLARED: Set<String> = setOf(
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.ACTIVITY_RECOGNITION",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.READ_CALENDAR",
    "android.permission.WRITE_CALENDAR",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_ADVERTISE",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.BLUETOOTH",
    "android.permission.ACCESS_WIFI_STATE",
)

/** 시스템 대신 결과를 손으로 돌려주는 결과 레지스트리 */
internal class FakeRegistry : ActivityResultRegistry() {
    class Launch(val requestCode: Int, val input: Any?)

    val launches = mutableListOf<Launch>()

    /** 남은 횟수만큼 인텐트 실행을 ActivityNotFoundException 으로 실패시킨다 */
    var failIntentLaunches = 0

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        if (input is Intent && failIntentLaunches > 0) {
            failIntentLaunches--
            throw ActivityNotFoundException("테스트: ${input.action}")
        }
        launches += Launch(requestCode, input)
    }

    @Suppress("UNCHECKED_CAST")
    val lastPermissions: List<String>
        get() = (launches.last { it.input is Array<*> }.input as Array<String>).toList()

    val lastIntent: Intent get() = launches.last { it.input is Intent }.input as Intent

    fun respondPermissions(results: Map<String, Boolean>) {
        val launch = launches.last { it.input is Array<*> }
        check(dispatchResult(launch.requestCode, results)) { "받을 콜백이 없습니다" }
    }

    fun respondSettings(resultCode: Int = Activity.RESULT_CANCELED, data: Intent? = null) {
        val launch = launches.last { it.input is Intent }
        check(dispatchResult(launch.requestCode, androidx.activity.result.ActivityResult(resultCode, data)))
    }

    val intents: List<Intent> get() = launches.mapNotNull { it.input as? Intent }

    fun respondSender() {
        val launch = launches.last { it.input is androidx.activity.result.IntentSenderRequest }
        check(dispatchResult(launch.requestCode, androidx.activity.result.ActivityResult(Activity.RESULT_OK, null)))
    }
}

/** 소비자 구현을 흉내 내는 명세 — 지정한 단계에서 예외를 던진다 */
internal class ThrowingSpec(private val where: String, private val error: RuntimeException = IllegalStateException("boom")) :
    RuntimePermissionSpec {
    override val id: String = "throwing.$where"

    override fun permissions(environment: PermissionEnvironment): List<String> {
        if (where == "permissions") throw error
        return listOf("x.permission.A")
    }

    override fun evaluate(environment: PermissionEnvironment, isGranted: (String) -> Boolean): GrantLevel {
        if (where == "evaluate") throw error
        return GrantLevel.None
    }

    override fun nextRequest(environment: PermissionEnvironment, isGranted: (String) -> Boolean): List<String> {
        if (where == "nextRequest") throw error
        return listOf("x.permission.A")
    }
}

/** 손으로 상태를 정하는 특수 권한 명세 */
internal class FakeSpecialSpec(
    override val id: String = "fake.special",
    var applicable: Boolean = true,
    var granted: Boolean = false,
    override val manifestPermission: String? = null,
    var failIntent: RuntimeException? = null,
) : SpecialPermissionSpec {
    override fun isApplicable(environment: PermissionEnvironment): Boolean = applicable

    override fun isGranted(context: Context): Boolean = granted

    override fun settingsIntent(context: Context): Intent {
        failIntent?.let { throw it }
        return Intent("fake.SETTINGS")
    }
}

/** 저장 상태를 손으로 저장·복원하는 화면 흉내 */
internal class SavedStateOwner(state: android.os.Bundle? = null) : androidx.savedstate.SavedStateRegistryOwner {
    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this as androidx.lifecycle.LifecycleOwner)
    private val controller = androidx.savedstate.SavedStateRegistryController.create(this)
    override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry get() = controller.savedStateRegistry
    val registry: androidx.savedstate.SavedStateRegistry get() = savedStateRegistry

    init {
        controller.performAttach()
        controller.performRestore(state)
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
    }

    fun save(): android.os.Bundle = android.os.Bundle().also { controller.performSave(it) }
}
