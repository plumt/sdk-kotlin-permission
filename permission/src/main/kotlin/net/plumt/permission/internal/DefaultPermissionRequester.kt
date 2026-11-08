package net.plumt.permission.internal

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.savedstate.SavedStateRegistry
import net.plumt.permission.EnableRequest
import net.plumt.permission.MultiplePermissionsStatus
import net.plumt.permission.PermissionException
import net.plumt.permission.PermissionRequester
import net.plumt.permission.PermissionSpec
import net.plumt.permission.RequirementStatus
import net.plumt.permission.RuntimePermissionSpec
import net.plumt.permission.SettingsPolicy
import net.plumt.permission.SpecialPermissionSpec
import net.plumt.permission.SystemRequirement
import net.plumt.permission.isFullyGranted

/**
 * 요청 흐름: 줄([RequestGate])에서 차례를 받고 → 런타임 권한을 묻고(대개 한 번, 따로 묻는 백그라운드 권한은 따로) → 결과로 영구 거부를
 * 판정해 이력에 남기고 → 특수 권한 설정 화면을 하나씩 열고 → 꺼진 시스템 기능의 켜기 화면을 하나씩 열고 → 끝나면 다시 판정해
 * ([SettingsPolicy.Direct] 면 설정 화면을 한 번 더 거쳐) 콜백한다. 메인 스레드에서만 쓰여 동기화하지 않는다.
 *
 * 진행 중인 흐름은 [SavedStateRegistry] 가 있으면 거기 남겨, 재생성된 화면의 새 요청기가 이어 간다.
 */
internal class DefaultPermissionRequester(
    private val manager: DefaultPermissionManager,
    override val specs: List<PermissionSpec>,
    override val requirements: List<SystemRequirement>,
    private val activity: () -> Activity?,
    private val onResult: (MultiplePermissionsStatus) -> Unit,
    gateToken: Any? = null,
) : PermissionRequester {

    /** 줄에서 이 요청기를 가르는 값 — 레지스트리 키가 있으면 키라서 재생성된 요청기가 같은 자리를 잇는다 */
    private val token: Any = gateToken ?: this

    private var runtimeLauncher: ActivityResultLauncher<Array<String>>? = null
    private var settingsLauncher: ActivityResultLauncher<Intent>? = null
    private var senderLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var unregistered = false
    private var rationaleBefore: Map<String, Boolean> = emptyMap()
    private var heldBefore: Set<String> = emptySet()

    /** 보낸 런타임 요청의 대화상자 관측 — 화면 재생성·프로세스 재시작으로 이어받은 요청에는 없다 */
    private var dialogWatch: DialogWatch? = null
    private val runtimeQueue = ArrayDeque<List<String>>()
    private val specialQueue = ArrayDeque<SpecialPermissionSpec>()
    private val requirementQueue = ArrayDeque<SystemRequirement>()
    private var policy: SettingsPolicy = SettingsPolicy.Never
    private var settingsOpened = false
    private var queued = false
    private var awaitingEnable = false
    private var enableGeneration = 0

    /** 지금 열린 화면이 그 특수 권한의 화면이면 그 id — 돌아온 결과를 명세에 넘긴다([SpecialPermissionSpec.onSettingsResult]) */
    private var launchedSpecial: String? = null

    /** 런처가 붙기 전에 결과가 오면(재생성 직후 등록하면서 받은 결과) 붙은 뒤에 이어 간다 */
    private var ready = false
    private var pendingAdvance = false
    private var restartQueued = false

    private var savedState: SavedStateRegistry? = null
    private var stateKey: String? = null
    private var restorePending = false

    override var isRequesting: Boolean = false
        private set

    /** 저장 상태를 먼저 붙인다 — 런처 등록 중에 이전 결과가 곧바로 올 수 있어, 그 전에 흐름을 복원해야 한다 */
    fun attachState(savedStateRegistry: SavedStateRegistry?, key: String?) {
        if (savedStateRegistry != null && key != null) {
            savedState = savedStateRegistry
            stateKey = key
            savedStateRegistry.registerSavedStateProvider(key) { save() }
            restorePending = true
            restoreIfReady()
        }
    }

    fun attachLaunchers(
        runtime: ActivityResultLauncher<Array<String>>,
        settings: ActivityResultLauncher<Intent>,
        sender: ActivityResultLauncher<IntentSenderRequest>,
    ) {
        runtimeLauncher = runtime
        settingsLauncher = settings
        senderLauncher = sender
        ready = true
        restoreIfReady()
        when {
            restartQueued -> {
                restartQueued = false
                enqueue()
            }
            pendingAdvance -> {
                pendingAdvance = false
                advance()
            }
        }
    }

    override fun launch(): Boolean = launch(SettingsPolicy.Never)

    override fun launch(settingsPolicy: SettingsPolicy): Boolean {
        check(!unregistered) { "등록이 풀린 요청기입니다. 새로 등록하세요" }
        restoreIfReady()
        if (isRequesting) return false
        val plan = plan()
        manager.verifyDeclared(plan.declared, plan.problems)
        isRequesting = true
        policy = settingsPolicy
        settingsOpened = false
        enqueue()
        return true
    }

    override fun openAppSettings(): Boolean {
        check(!unregistered) { "등록이 풀린 요청기입니다. 새로 등록하세요" }
        restoreIfReady()
        if (isRequesting) return false
        isRequesting = true
        policy = SettingsPolicy.Never
        settingsOpened = true
        guardedStep { launchAppSettings() }
        return true
    }

    private fun enqueue() {
        queued = true
        manager.gate.acquire(token) {
            queued = false
            guardedStep { start() }
        }
    }

    /** 차례가 온 시점의 상태로 다시 계획한다 — 줄에서 기다리는 동안 다른 흐름이 같은 권한을 받았을 수 있다 */
    private fun start() {
        val plan = plan()
        runtimeQueue.clear()
        runtimeQueue.addAll(batches(plan.runtime))
        specialQueue.clear()
        specialQueue.addAll(plan.specials)
        requirementQueue.clear()
        requirementQueue.addAll(plan.requirements)
        next()
    }

    private fun plan(): RequestPlan = manager.plan(specs, requirements, activity())

    /**
     * API 30 이상은 백그라운드 위치를 다른 권한과 함께 요청하면 시스템이 요청을 무시한다. 신체 센서 백그라운드 권한도 같다.
     * 그래서 따로 뗀다. 이런 권한이 요청에 들어 있다는 것은 포그라운드 권한이 이미 허용됐다는 뜻이다
     * (명세의 [RuntimePermissionSpec.nextRequest]).
     */
    private fun batches(permissions: Set<String>): List<List<String>> {
        if (permissions.isEmpty()) return emptyList()
        val separate = permissions.filter { isSeparate(it) }
        if (separate.isEmpty() || separate.size == permissions.size && separate.size == 1) return listOf(permissions.toList())
        val rest = permissions.filterNot { isSeparate(it) }
        return listOfNotNull(rest.takeIf { it.isNotEmpty() }) + separate.map { listOf(it) }
    }

    private fun isSeparate(permission: String): Boolean =
        (permission == BackgroundLocationSpec.BACKGROUND && manager.environment.sdkInt >= Api.R) ||
            permission in BodySensorsSpec.SEPARATE_BACKGROUND

    /**
     * 결과 맵이 비었으면 요청이 취소된 것(동시 요청·화면 재생성)이라 이력을 건드리지 않는다.
     *
     * 거부된 권한의 영구 거부 판정(거부 횟수는 세지 않는다 — 바깥 탭·뒤로가기로 닫은 대화상자는 시스템도 세지 않는다):
     * - 요청 뒤 근거 안내가 참 → 다시 물을 수 있다
     * - 요청 전 참 → 요청 뒤 거짓: 두 번째 거부·"다시 묻지 않음" → 영구 거부
     * - 전·후 모두 거짓이면 대화상자가 떴는지로 가른다. 이 대화상자에서 선택지(사진 일부·대략적 위치)를 골랐으면 떴던 것이고
     *   거부가 아니다. 이미 가진 권한을 끼워 띄운 승격 대화상자(대략적 위치 → 정밀)의 거절은 영구 거부다(시스템이 더 묻지 않는다).
     *   그 밖에는 [DialogWatcher] 의 증거로 가른다. 증거가 없으면 영구 거부, 확실하면 다시 물을 수 있다. 그 사이이거나 관측이 없으면
     *   (재생성·프로세스 재시작으로 이어받은 요청) 지금까지의 판정을 유지한다 — 이미 확인한 영구 거부를 불확실한 근거로 풀면
     *   사용자가 눌러도 아무 일이 안 생기는 상태가 되지만, 반대로 틀리면 설정으로 안내할 뿐이고 요청은 그대로 보낼 수 있다
     */
    fun onRuntimeResult(results: Map<String, Boolean>) {
        restoreIfReady()
        val current = activity()
        val history = manager.historyStore
        val evidence = dialogWatch?.finish()
        dialogWatch = null
        val chosen = choicePermissions(results)
        val upgrades = upgradePermissions(results)
        for ((permission, granted) in results) {
            when {
                granted || manager.isGranted(permission) -> history.remove(permission)
                manager.isReaskableWhileSelected(permission) -> history.record(permission, permanentlyDenied = false)
                // 근거 안내를 물을 화면이 없으면 판정을 바꾸지 않고 요청한 사실만 남긴다
                current == null -> history.record(permission, history.isPermanentlyDenied(permission))
                else -> {
                    val after = manager.shouldShowRationale(current, permission)
                    val before = rationaleBefore[permission] == true
                    val permanent = !after && when {
                        before -> true
                        permission in chosen -> false
                        permission in upgrades -> true
                        evidence == DialogEvidence.NONE -> true
                        evidence == DialogEvidence.CERTAIN -> false
                        else -> history.isPermanentlyDenied(permission)
                    }
                    history.record(permission, permanent)
                }
            }
        }
        rationaleBefore = emptyMap()
        heldBefore = emptySet()
        advanceWhenReady()
    }

    /**
     * 한 명세의 권한 여럿을 함께 물었는데 그 가운데 일부를 **요청 전부터** 가지고 있었다면 승격 대화상자다
     * (대략적 위치를 가진 채 정밀 위치를 묻는 것). 거기서 거부된 나머지가 이 집합이다.
     */
    private fun upgradePermissions(results: Map<String, Boolean>): Set<String> {
        if (results.size < 2 || heldBefore.isEmpty()) return emptySet()
        val upgrades = HashSet<String>()
        for (spec in specs) {
            if (spec !is RuntimePermissionSpec) continue
            val asked = guarded(spec) { spec.permissions(manager.environment) }.filter { it in results }
            if (asked.size > 1 && asked.any { it in heldBefore }) upgrades += asked.filterNot { it in heldBefore }
        }
        return upgrades
    }

    /**
     * 한 명세의 권한 여럿을 함께 물었는데 그 가운데 하나가 **이 대화상자에서 새로** 허용됐다면, 나머지는 사용자가 고르지 않은
     * 선택지다(사진 일부 선택의 READ_MEDIA_IMAGES, 대략적 위치의 ACCESS_FINE_LOCATION, "앱 사용 중에만" 의 백그라운드 위치).
     *
     * 요청 전부터 허용돼 있던 권한은 선택의 증거가 아니다. 대략적 위치를 가진 채 띄운 정밀 승격 대화상자에서
     * "대략적인 위치 계속 사용" 은 거부이고, 실기기(Android 16)에서 그다음 요청은 화면 없이 끝났다.
     */
    private fun choicePermissions(results: Map<String, Boolean>): Set<String> {
        if (results.size < 2) return emptySet()
        val chosen = HashSet<String>()
        for (spec in specs) {
            if (spec !is RuntimePermissionSpec) continue
            val asked = guarded(spec) { spec.permissions(manager.environment) }.filter { it in results }
            val newlyGranted = asked.any { it !in heldBefore && (results[it] == true || manager.isGranted(it)) }
            if (asked.size > 1 && newlyGranted) chosen += asked
        }
        return chosen
    }

    fun onSettingsResult(result: ActivityResult? = null) {
        restoreIfReady()
        val id = launchedSpecial
        launchedSpecial = null
        if (id != null && result != null) {
            val spec = specs.firstOrNull { it.id == id } as? SpecialPermissionSpec
            if (spec != null) guardedStep { guarded(spec) { spec.onSettingsResult(result.resultCode, result.data) } }
        }
        advanceWhenReady()
    }

    /** 흐름 상태가 없어도(저장 상태 없이 재등록) 결과는 판정해 콜백한다 */
    private fun advanceWhenReady() {
        if (!ready) {
            pendingAdvance = true
            return
        }
        advance()
    }

    private fun advance() = guardedStep { next() }

    private inline fun guardedStep(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            reset()
            throw e
        }
    }

    private fun next() {
        val batch = runtimeQueue.removeFirstOrNull() ?: return nextSpecial()
        val current = activity()
        rationaleBefore = batch.associateWith { p -> current?.let { manager.shouldShowRationale(it, p) } ?: false }
        heldBefore = batch.filter(manager::isGranted).toSet()
        val launcher = requireNotNull(runtimeLauncher)
        dialogWatch?.finish()
        dialogWatch = manager.dialogWatcher.watch(current)
        launcher.launch(batch.toTypedArray())
    }

    private fun nextSpecial() {
        val spec = specialQueue.removeFirstOrNull() ?: return nextRequirement()
        val launcher = requireNotNull(settingsLauncher)
        val context = activity() ?: manager.appContext
        val intent = guarded(spec) { spec.settingsIntent(context) }
        try {
            launcher.launch(intent)
            launchedSpecial = spec.id
        } catch (first: ActivityNotFoundException) {
            try {
                launcher.launch(manager.appSettingsIntent())
            } catch (second: ActivityNotFoundException) {
                second.addSuppressed(first)
                throw PermissionException("설정 화면을 열 수 없습니다: ${spec.id} (${intent.action})", second)
            }
        }
    }

    /** 꺼진 기능만 켜기 화면을 연다. 그사이 켜졌거나 필요한 권한을 받지 못했으면 건너뛴다 */
    private fun nextRequirement() {
        val requirement = requirementQueue.removeFirstOrNull() ?: return finish()
        if (manager.check(requirement) != RequirementStatus.Disabled) return nextRequirement()
        val generation = ++enableGeneration
        awaitingEnable = true
        val context = activity() ?: manager.appContext
        guardedRequirement(requirement) {
            requirement.enableRequest(context) { request -> onEnableReady(generation, requirement, request) }
        }
    }

    private fun onEnableReady(generation: Int, requirement: SystemRequirement, request: EnableRequest?) {
        if (generation != enableGeneration || !awaitingEnable || !isRequesting || unregistered) return
        awaitingEnable = false
        guardedStep {
            when (request) {
                null -> nextRequirement()
                is EnableRequest.OfIntent -> try {
                    requireNotNull(settingsLauncher).launch(request.intent)
                } catch (e: ActivityNotFoundException) {
                    throw PermissionException("켜기 화면을 열 수 없습니다: ${requirement.id} (${request.intent.action})", e)
                }
                is EnableRequest.OfIntentSender -> requireNotNull(senderLauncher).launch(request.request)
            }
        }
    }

    private fun finish() {
        val status = manager.checkAll(specs, requirements, activity())
        if (policy == SettingsPolicy.Direct && !settingsOpened && status.anyNeedsSettings) {
            settingsOpened = true
            launchAppSettings()
            return
        }
        reset()
        if (!unregistered) onResult(status)
    }

    private fun launchAppSettings() {
        val intent = manager.appSettingsIntent()
        try {
            requireNotNull(settingsLauncher).launch(intent)
        } catch (e: ActivityNotFoundException) {
            throw PermissionException("앱 상세 설정 화면을 열 수 없습니다: ${intent.action}", e)
        }
    }

    private fun reset() {
        isRequesting = false
        rationaleBefore = emptyMap()
        heldBefore = emptySet()
        dialogWatch?.finish()
        dialogWatch = null
        runtimeQueue.clear()
        specialQueue.clear()
        requirementQueue.clear()
        queued = false
        awaitingEnable = false
        enableGeneration++
        launchedSpecial = null
        pendingAdvance = false
        restartQueued = false
        manager.gate.release(token)
    }

    // ---- 저장 상태

    private fun save(): Bundle = Bundle().apply {
        putBoolean(KEY_REQUESTING, isRequesting)
        if (!isRequesting) return@apply
        putStringArray(KEY_RATIONALE_PERMISSIONS, rationaleBefore.keys.toTypedArray())
        putBooleanArray(KEY_RATIONALE_VALUES, rationaleBefore.values.toBooleanArray())
        putStringArray(KEY_HELD_BEFORE, heldBefore.toTypedArray())
        putInt(KEY_RUNTIME_COUNT, runtimeQueue.size)
        runtimeQueue.forEachIndexed { index, batch -> putStringArray("$KEY_RUNTIME_PREFIX$index", batch.toTypedArray()) }
        putStringArray(KEY_SPECIALS, specialQueue.map { it.id }.toTypedArray())
        putStringArray(KEY_REQUIREMENTS, requirementQueue.map { it.id }.toTypedArray())
        putString(KEY_POLICY, policyName(policy))
        putBoolean(KEY_SETTINGS_OPENED, settingsOpened)
        putBoolean(KEY_QUEUED, queued)
        putBoolean(KEY_AWAITING_ENABLE, awaitingEnable)
        putString(KEY_LAUNCHED_SPECIAL, launchedSpecial)
    }

    /** 저장 상태는 화면의 onCreate 가 복원한 뒤에야 읽을 수 있다 — 요청기는 그 전에 등록될 수 있어 늦게 읽는다 */
    private fun restoreIfReady() {
        val registry = savedState ?: return
        val key = stateKey ?: return
        if (!restorePending || !registry.isRestored) return
        restorePending = false
        val bundle = registry.consumeRestoredStateForKey(key) ?: return
        if (!bundle.getBoolean(KEY_REQUESTING)) return
        val permissions = bundle.getStringArray(KEY_RATIONALE_PERMISSIONS).orEmpty()
        val values = bundle.getBooleanArray(KEY_RATIONALE_VALUES) ?: BooleanArray(0)
        rationaleBefore = permissions.zip(values.toList()).toMap()
        heldBefore = bundle.getStringArray(KEY_HELD_BEFORE).orEmpty().toSet()
        runtimeQueue.clear()
        repeat(bundle.getInt(KEY_RUNTIME_COUNT)) { index ->
            bundle.getStringArray("$KEY_RUNTIME_PREFIX$index")?.toList()?.let(runtimeQueue::addLast)
        }
        specialQueue.clear()
        bundle.getStringArray(KEY_SPECIALS).orEmpty().forEach { id ->
            (specs.firstOrNull { it.id == id } as? SpecialPermissionSpec)?.let(specialQueue::addLast)
        }
        requirementQueue.clear()
        bundle.getStringArray(KEY_REQUIREMENTS).orEmpty().forEach { id ->
            requirements.firstOrNull { it.id == id }?.let(requirementQueue::addLast)
        }
        policy = policyOf(bundle.getString(KEY_POLICY))
        settingsOpened = bundle.getBoolean(KEY_SETTINGS_OPENED)
        launchedSpecial = bundle.getString(KEY_LAUNCHED_SPECIAL)
        isRequesting = true
        when {
            // 줄에서 기다리다 화면이 바뀌었다 — 아직 아무것도 띄우지 않았으니 다시 줄에 선다
            bundle.getBoolean(KEY_QUEUED) -> if (ready) enqueue() else restartQueued = true
            // 켜기 화면을 만들던 중이었다 — 그 비동기 결과는 사라졌으니 다음으로 넘어간다
            bundle.getBoolean(KEY_AWAITING_ENABLE) -> if (ready) advance() else pendingAdvance = true
            else -> manager.gate.claim(token)
        }
    }

    override fun unregister() {
        if (unregistered) return
        unregistered = true
        reset()
        runtimeLauncher?.unregister()
        settingsLauncher?.unregister()
        senderLauncher?.unregister()
        stateKey?.let { savedState?.unregisterSavedStateProvider(it) }
    }

    /**
     * 구성 변경으로 화면이 사라질 때. 등록은 풀지 않는다 — 레지스트리가 키와 대기 중인 결과를 저장 상태로 넘겨,
     * 새 화면에서 같은 키로 등록한 요청기가 이어받는다. 줄의 자리도 같은 키(토큰)로 이어진다.
     */
    fun detach() {
        unregistered = true
    }

    /** 수명주기 소유자(Activity·Fragment)가 끝났다. 런처는 androidx 가 풀었으니 줄에서만 빠진다 */
    fun onOwnerDestroyed() {
        if (unregistered) return
        unregistered = true
        reset()
    }

    override fun toString(): String {
        val required = if (requirements.isEmpty()) "" else ", requirements=${requirements.map { it.id }}"
        return "PermissionRequester(specs=${specs.map { it.id }}$required, requesting=$isRequesting)"
    }

    private companion object {
        const val KEY_REQUESTING = "requesting"
        const val KEY_RATIONALE_PERMISSIONS = "rationale.permissions"
        const val KEY_RATIONALE_VALUES = "rationale.values"
        const val KEY_HELD_BEFORE = "held.before"
        const val KEY_RUNTIME_COUNT = "runtime.count"
        const val KEY_RUNTIME_PREFIX = "runtime."
        const val KEY_SPECIALS = "specials"
        const val KEY_REQUIREMENTS = "requirements"
        // 정책 이름으로 남긴다(0.0.x 는 순서 번호였다 — 읽지 못하면 Never)
        const val KEY_POLICY = "policy.name"
        const val KEY_SETTINGS_OPENED = "settings.opened"
        const val KEY_QUEUED = "queued"
        const val KEY_AWAITING_ENABLE = "awaiting.enable"
        const val KEY_LAUNCHED_SPECIAL = "launched.special"
    }
}

/** 저장 상태에 남기는 정책 이름 */
internal fun policyName(policy: SettingsPolicy): String = when (policy) {
    SettingsPolicy.Never -> "never"
    SettingsPolicy.Prompt -> "prompt"
    SettingsPolicy.Direct -> "direct"
}

/** [policyName] 의 역. 모르는 값은 [SettingsPolicy.Never] */
internal fun policyOf(name: String?): SettingsPolicy = when (name) {
    "prompt" -> SettingsPolicy.Prompt
    "direct" -> SettingsPolicy.Direct
    else -> SettingsPolicy.Never
}
