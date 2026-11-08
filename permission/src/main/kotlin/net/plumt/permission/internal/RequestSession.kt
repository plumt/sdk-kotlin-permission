package net.plumt.permission.internal

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.suspendCancellableCoroutine
import net.plumt.permission.PermissionSpec
import net.plumt.permission.RequestResult
import net.plumt.permission.SettingsPolicy
import net.plumt.permission.SystemRequirement
import java.util.UUID
import kotlin.coroutines.resume

/**
 * 화면 없이 부르는 요청 하나. Activity 의 결과 레지스트리에 고유 키로 직접 등록하고, 구성 변경으로 Activity 가 다시 만들어지면
 * 같은 키로 새 Activity 에 다시 등록해 결과를 이어받는다(레지스트리가 키와 대기 중인 결과를 저장 상태로 넘긴다).
 * 메인 스레드에서만 쓰인다.
 */
internal class RequestSession(
    private val manager: DefaultPermissionManager,
    private val specs: List<PermissionSpec>,
    private val requirements: List<SystemRequirement>,
    private val policy: SettingsPolicy,
) : ActivityTracker.Listener {

    private val key = "net.plumt.permission.request#" + UUID.randomUUID()
    private var requester: DefaultPermissionRequester? = null
    private var host: ComponentActivity? = null
    private var hostClass: Class<*>? = null
    private var observer: LifecycleEventObserver? = null
    private var awaitingRecreate = false
    private var done = false
    private var complete: (RequestResult) -> Unit = {}

    suspend fun await(activity: ComponentActivity): RequestResult = suspendCancellableCoroutine { continuation ->
        complete = { if (continuation.isActive) continuation.resume(it) }
        continuation.invokeOnCancellation { onMain { cancel() } }
        try {
            manager.activityTracker?.addListener(this)
            attach(activity)
            requireNotNull(requester).launch(policy)
        } catch (e: RuntimeException) {
            cancel()
            throw e
        }
    }

    private fun attach(activity: ComponentActivity) {
        host = activity
        hostClass = activity.javaClass
        requester = manager.createRequester(
            registry = activity.activityResultRegistry,
            key = key,
            activity = { host },
            specs = specs,
            savedStateRegistry = activity.savedStateRegistry,
            requirements = requirements,
        ) { status -> finish(RequestResult.Completed(status)) } as DefaultPermissionRequester
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) onHostDestroyed(activity)
        }
        observer = lifecycleObserver
        activity.lifecycle.addObserver(lifecycleObserver)
    }

    private fun onHostDestroyed(activity: ComponentActivity) {
        if (done || activity !== host) return
        observer?.let(activity.lifecycle::removeObserver)
        observer = null
        host = null
        if (activity.isChangingConfigurations && manager.activityTracker != null) {
            // 새 Activity 가 같은 키로 이어받는다 — 등록은 풀지 않는다
            requester?.detach()
            requester = null
            awaitingRecreate = true
        } else {
            requester?.unregister()
            requester = null
            finish(RequestResult.NoActivity(manager.checkAll(specs, requirements, null)))
        }
    }

    override fun onActivityCreated(activity: Activity) {
        if (done || !awaitingRecreate || activity !is ComponentActivity || activity.javaClass != hostClass) return
        awaitingRecreate = false
        attach(activity)
    }

    private fun finish(result: RequestResult) {
        if (done) return
        done = true
        cleanup()
        complete(result)
    }

    private fun cancel() {
        if (done) return
        done = true
        cleanup()
    }

    private fun cleanup() {
        manager.activityTracker?.removeListener(this)
        host?.let { h -> observer?.let(h.lifecycle::removeObserver) }
        observer = null
        host = null
        requester?.unregister()
        requester = null
    }

    private companion object {
        private val main by lazy { Handler(Looper.getMainLooper()) }

        fun onMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
        }
    }
}
