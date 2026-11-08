package net.plumt.permission.internal

import android.app.Activity
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * 런타임 권한 요청 한 번 동안 시스템이 대화상자를 실제로 띄웠는지 관측한다.
 *
 * 요청 전·후 근거 안내가 둘 다 거짓이고 결과가 거부면, 시스템이 대화상자 없이 곧바로 거부했는지("다시 묻지 않음")
 * 대화상자를 띄웠는데 사용자가 뒤로가기 등으로 닫았는지(거부 횟수에 세지 않는다) 결과만으로는 가를 수 없다.
 * 결과 코드와 extras 도 두 경우가 같고, 시스템이 권한의 고정 여부를 앱에 알려 주지 않는다. 남은 근거는 시간뿐이다.
 */
internal fun interface DialogWatcher {
    /** 요청을 보내기 직전에 부른다. [activity] 가 없으면 요청부터 결과까지의 시간으로만 판정한다 */
    fun watch(activity: Activity?): DialogWatch
}

internal fun interface DialogWatch {
    /** 결과를 받았을 때 한 번 부른다 — 관측을 끝내고 대화상자가 떴다는 증거의 세기를 돌려준다 */
    fun finish(): DialogEvidence
}

/** 대화상자가 떴다는 증거의 세기. 틀리는 쪽의 비용이 달라 셋으로 나눈다 */
internal enum class DialogEvidence {
    /** 대화상자 없이 끝나는 요청의 시간 안이다 — 영구 거부로 판정한다 */
    NONE,

    /** 대화상자가 떴다고 볼 만하다 — 그러나 이미 확인한 영구 거부 이력을 지울 만큼은 아니다 */
    LIKELY,

    /** 사람이 대화상자를 본 시간이다 — 영구 거부 이력도 지운다 */
    CERTAIN,
}

/**
 * 기본 관측: 요청한 화면이 멈춘 뒤(`ON_PAUSE`) 결과가 오기까지의 시간.
 *
 * 창 포커스는 근거가 못 된다. 대화상자 없이 끝나는 요청도 투명한 권한 화면이 잠깐 포커스를 뺏기 때문이다(실기기 API 29·36).
 * 멈춘 시점을 기준으로 삼으면 요청 직후 앱 스레드가 밀린 시간이 빠지고, 알림 창처럼 화면을 멈추지 않는 창도 세지 않는다.
 * 화면이 멈추는 것을 볼 수 없으면(수명주기가 없는 화면·화면 없음) 요청 시점부터 잰다 — 그만큼 길어 기준을 따로 둔다.
 *
 * 기준값은 실측 분포에서 정했다. 근거는 [LIKELY_AFTER_MILLIS]·[CERTAIN_AFTER_MILLIS]·[LIKELY_AFTER_UNPAUSED_MILLIS].
 */
internal class PauseDialogWatcher(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val likelyAfterMillis: Long = LIKELY_AFTER_MILLIS,
    private val certainAfterMillis: Long = CERTAIN_AFTER_MILLIS,
    private val likelyAfterUnpausedMillis: Long = LIKELY_AFTER_UNPAUSED_MILLIS,
) : DialogWatcher {

    override fun watch(activity: Activity?): DialogWatch {
        val start = clock()
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        var pausedAt: Long? = null
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && pausedAt == null) pausedAt = clock()
        }
        lifecycle?.addObserver(observer)
        return DialogWatch {
            lifecycle?.removeObserver(observer)
            val paused = pausedAt
            val elapsed = clock() - (paused ?: start)
            val likelyAfter = if (paused != null) likelyAfterMillis else likelyAfterUnpausedMillis
            when {
                elapsed >= certainAfterMillis -> DialogEvidence.CERTAIN
                elapsed >= likelyAfter -> DialogEvidence.LIKELY
                else -> DialogEvidence.NONE
            }
        }
    }

    companion object {
        /**
         * 화면이 멈춘 뒤 이만큼 지나 결과가 오면 떴다고 볼 만하다. 실측 경계: 대화상자 없는 요청의 최댓값보다 크고,
         * 요청 300ms 뒤 뒤로가기로 닫은 요청의 최솟값보다 작다. 대화상자 없는 쪽 여유를 더 둔다 —
         * 그 요청을 닫힘으로 잘못 보면 사용자가 눌러도 아무 일이 안 생기지만, 반대로 틀리면 설정으로 안내할 뿐이다.
         */
        const val LIKELY_AFTER_MILLIS: Long = 280

        /**
         * 이미 확인한 영구 거부 이력은 이 시간을 넘긴 대화상자를 닫았을 때만 지운다. 대화상자 없는 요청은 실측 최댓값의 열 배 가까이 되고,
         * 권한 화면의 프로세스가 새로 뜨는 지연까지 여유를 둔 값이다. 틀려도 대화상자는 다음 요청에 그대로 뜬다.
         */
        const val CERTAIN_AFTER_MILLIS: Long = 2_000

        /** 화면이 멈추는 것을 못 봤을 때 [LIKELY_AFTER_MILLIS] 대신 쓴다 — 요청부터 멈춤까지의 시간이 더 들어 있다 */
        const val LIKELY_AFTER_UNPAUSED_MILLIS: Long = 400
    }
}
