package net.plumt.permission.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import java.lang.ref.WeakReference

/**
 * 요청 흐름을 한 번에 하나씩만 돌리는 줄. 시스템은 권한 대화상자를 한 번에 하나만 받아, 겹친 뒤 요청은 빈 결과로 끝난다.
 * 메인 스레드에서만 쓰여 동기화하지 않는다.
 *
 * 토큰은 요청기를 가른다 — 레지스트리 키로 등록한 요청기는 키가 토큰이라 화면이 다시 만들어져도 같은 자리를 이어 쓴다.
 */
internal class RequestGate {
    private var holder: Any? = null
    private val waiting = ArrayDeque<Pair<Any, () -> Unit>>()

    val current: Any? get() = holder

    val waitingCount: Int get() = waiting.size

    /** 비어 있으면 곧바로 [action] 을 돌리고, 아니면 줄 끝에 세운다 */
    fun acquire(token: Any, action: () -> Unit) {
        if (holder == null || holder == token) {
            holder = token
            action()
        } else {
            waiting.removeAll { it.first == token }
            waiting.addLast(token to action)
        }
    }

    /** 이미 진행 중이던 흐름(재생성으로 이어받은 흐름)이 자리를 잡는다. 다른 흐름이 잡고 있으면 그대로 둔다 */
    fun claim(token: Any) {
        if (holder == null) holder = token
    }

    /** 자리를 내놓거나 줄에서 빠진다. 다음 흐름이 있으면 곧바로 시작한다 */
    fun release(token: Any) {
        waiting.removeAll { it.first == token }
        if (holder != token) return
        holder = null
        val next = waiting.removeFirstOrNull() ?: return
        holder = next.first
        next.second()
    }
}

/**
 * 시작된 Activity 를 따라간다. 화면 없이 부르는 요청([net.plumt.permission.PermissionManager.request])이 띄울 곳을 찾고,
 * 구성 변경으로 다시 만들어진 Activity 를 알아챈다.
 */
internal class ActivityTracker : Application.ActivityLifecycleCallbacks {

    interface Listener {
        fun onActivityCreated(activity: Activity)
    }

    private val started = ArrayList<WeakReference<Activity>>()
    private var resumed: WeakReference<Activity>? = null
    private val listeners = ArrayList<Listener>()

    /** 지금 요청을 띄울 수 있는 Activity — 재개된 것, 없으면 마지막으로 시작된 것 */
    fun current(): ComponentActivity? {
        val candidates = listOfNotNull(resumed?.get()) + started.asReversed().mapNotNull { it.get() }
        return candidates.firstOrNull { it is ComponentActivity && !it.isFinishing && !it.isDestroyed } as ComponentActivity?
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        for (listener in listeners.toList()) listener.onActivityCreated(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        started.removeAll { it.get() == null || it.get() === activity }
        started += WeakReference(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityStopped(activity: Activity) {
        started.removeAll { it.get() == null || it.get() === activity }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        started.removeAll { it.get() == null || it.get() === activity }
        if (resumed?.get() === activity) resumed = null
    }
}
