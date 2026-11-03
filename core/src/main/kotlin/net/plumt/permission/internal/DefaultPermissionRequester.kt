package net.plumt.permission.internal

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import net.plumt.permission.MultiplePermissionsStatus
import net.plumt.permission.PermissionException
import net.plumt.permission.PermissionRequester
import net.plumt.permission.PermissionSpec
import net.plumt.permission.RuntimePermissionSpec
import net.plumt.permission.SpecialPermissionSpec
import net.plumt.permission.isFullyGranted

/**
 * 요청 흐름: 런타임 권한을 한 번에 묻고 → 결과로 영구 거부를 판정해 이력에 남기고 → 특수 권한 설정 화면을
 * 하나씩 열고 → 끝나면 다시 판정해 콜백한다. 메인 스레드에서만 쓰여 동기화하지 않는다.
 */
internal class DefaultPermissionRequester(
    private val manager: DefaultPermissionManager,
    override val specs: List<PermissionSpec>,
    private val activity: () -> Activity?,
    private val onResult: (MultiplePermissionsStatus) -> Unit,
) : PermissionRequester {

    private var runtimeLauncher: ActivityResultLauncher<Array<String>>? = null
    private var settingsLauncher: ActivityResultLauncher<Intent>? = null
    private var unregistered = false
    private var rationaleBefore: Map<String, Boolean> = emptyMap()
    private val specialQueue = ArrayDeque<SpecialPermissionSpec>()

    override var isRequesting: Boolean = false
        private set

    fun attach(runtime: ActivityResultLauncher<Array<String>>, settings: ActivityResultLauncher<Intent>) {
        runtimeLauncher = runtime
        settingsLauncher = settings
    }

    override fun launch(): Boolean {
        check(!unregistered) { "등록이 풀린 요청기입니다. 새로 등록하세요" }
        if (isRequesting) return false
        val current = activity()
        val runtimePermissions = LinkedHashSet<String>()
        val specials = ArrayList<SpecialPermissionSpec>()
        for (spec in specs) {
            if (manager.check(spec, current).isFullyGranted) continue
            when (spec) {
                is RuntimePermissionSpec -> runtimePermissions += manager.pendingOf(spec)
                is SpecialPermissionSpec -> specials += spec
            }
        }
        manager.verifyDeclared(runtimePermissions + specials.mapNotNull { it.manifestPermission })

        isRequesting = true
        specialQueue.clear()
        specialQueue.addAll(specials)
        try {
            if (runtimePermissions.isNotEmpty()) {
                rationaleBefore = runtimePermissions.associateWith { p ->
                    current?.let { manager.shouldShowRationale(it, p) } ?: false
                }
                requireNotNull(runtimeLauncher).launch(runtimePermissions.toTypedArray())
            } else {
                nextSpecial()
            }
        } catch (e: RuntimeException) {
            reset()
            throw e
        }
        return true
    }

    /** 결과 맵이 비었으면 요청이 취소된 것(동시 요청·화면 재생성)이라 이력을 건드리지 않는다 */
    fun onRuntimeResult(results: Map<String, Boolean>) {
        val current = activity()
        val history = manager.historyStore
        for ((permission, granted) in results) {
            when {
                granted || manager.isGranted(permission) -> history.record(permission, permanentlyDenied = false)
                // 근거 안내를 물을 화면이 없으면 판정을 바꾸지 않고 요청한 사실만 남긴다
                current == null -> history.record(permission, history.isPermanentlyDenied(permission))
                else -> {
                    val after = manager.shouldShowRationale(current, permission)
                    val before = rationaleBefore[permission] == true
                    val permanent = !after && (before || history.hasRequested(permission))
                    history.record(permission, permanent)
                }
            }
        }
        rationaleBefore = emptyMap()
        advance()
    }

    fun onSettingsResult() {
        advance()
    }

    private fun advance() {
        try {
            nextSpecial()
        } catch (e: RuntimeException) {
            reset()
            throw e
        }
    }

    private fun nextSpecial() {
        val spec = specialQueue.removeFirstOrNull() ?: return finish()
        val launcher = requireNotNull(settingsLauncher)
        val context = activity() ?: manager.appContext
        val intent = guarded(spec) { spec.settingsIntent(context) }
        try {
            launcher.launch(intent)
        } catch (first: ActivityNotFoundException) {
            try {
                launcher.launch(manager.appSettingsIntent())
            } catch (second: ActivityNotFoundException) {
                second.addSuppressed(first)
                throw PermissionException("설정 화면을 열 수 없습니다: ${spec.id} (${intent.action})", second)
            }
        }
    }

    private fun finish() {
        reset()
        if (!unregistered) onResult(manager.checkAll(specs, activity()))
    }

    private fun reset() {
        isRequesting = false
        rationaleBefore = emptyMap()
        specialQueue.clear()
    }

    override fun unregister() {
        if (unregistered) return
        unregistered = true
        reset()
        runtimeLauncher?.unregister()
        settingsLauncher?.unregister()
    }

    override fun toString(): String = "PermissionRequester(specs=${specs.map { it.id }}, requesting=$isRequesting)"
}
