package net.plumt.permission

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** 시드를 고정한 무작위 입력 — 어떤 조합에서도 크래시 없이 일관된 상태를 내는지 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionFuzzTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val universe = ALL_DECLARED.toList()

    private fun randomSpec(r: Random): RuntimePermissionSpec = when (r.nextInt(11)) {
        0 -> PermissionSpecs.camera
        1 -> PermissionSpecs.microphone
        2 -> PermissionSpecs.notifications
        3 -> PermissionSpecs.activityRecognition
        4 -> PermissionSpecs.location(r.nextBoolean())
        5 -> PermissionSpecs.backgroundLocation(r.nextBoolean())
        6 -> PermissionSpecs.media(images = true, video = r.nextBoolean())
        7 -> PermissionSpecs.contacts(write = r.nextBoolean())
        8 -> PermissionSpecs.calendar(write = r.nextBoolean())
        9 -> PermissionSpecs.bluetooth(scan = r.nextBoolean(), connect = true, advertise = r.nextBoolean())
        else -> PermissionSpecs.media(images = false, video = true)
    }

    private fun subset(r: Random): Set<String> = universe.filter { r.nextInt(3) == 0 }.toSet()

    @Test
    fun `무작위 기기 상태에서 확인은 크래시 없이 불변식을 지킨다`() {
        val r = Random(20261102)
        repeat(5_000) { iteration ->
            val sdk = r.nextInt(1, 41)
            val device = FakeDevice().apply {
                granted += subset(r)
                rationale += subset(r)
            }
            val history = RequestHistoryStore.inMemory()
            subset(r).forEach { history.record(it, r.nextBoolean()) }
            val declared = subset(r) + if (r.nextBoolean()) ALL_DECLARED else emptySet()
            val m = fakeManager(context, device, sdkInt = sdk, declared = declared, history = history)
            val spec = randomSpec(r)
            val env = m.environment
            val perms = spec.permissions(env)
            val status = m.check(spec, if (r.nextBoolean()) activity else null)
            val msg = "iteration=$iteration sdk=$sdk spec=${spec.id} status=$status"

            assertEquals(msg, perms.isEmpty(), status is PermissionStatus.NotApplicable)
            val level = if (perms.isEmpty()) null else spec.evaluate(env) { it in device.granted }
            assertEquals(msg, level == GrantLevel.FULL, status == PermissionStatus.Granted)
            if (status is PermissionStatus.Partial) {
                assertEquals(msg, perms.toSet(), status.granted + status.missing)
                assertTrue(msg, status.granted.intersect(status.missing).isEmpty())
            }
            assertTrue(msg, spec.permissions(env).containsAll(spec.nextRequest(env) { it in device.granted }))
            if (level == GrantLevel.FULL) assertTrue(msg, spec.nextRequest(env) { it in device.granted }.isEmpty())
        }
    }

    @Test
    fun `무작위 요청 결과에도 흐름은 끝나고 콜백은 정확히 한 번이다`() {
        val r = Random(7)
        repeat(1_000) { iteration ->
            val device = FakeDevice().apply { granted += subset(r) }
            val registry = FakeRegistry()
            val m = fakeManager(context, device, sdkInt = r.nextInt(23, 37))
            val specs = List(r.nextInt(1, 4)) { randomSpec(r) }.distinctBy { it.id }
            var callbacks = 0
            val requester = m.createRequester(registry, "k$iteration", { activity }, specs) { callbacks++ }
            requester.launch()
            // API 30+ 백그라운드 위치는 따로 요청하므로 요청이 두 번까지 이어질 수 있다
            var answered = 0
            while (requester.isRequesting) {
                check(answered++ < 2) { "iteration=$iteration 요청이 끝나지 않는다" }
                device.rationale += subset(r)
                val answer = registry.lastPermissions.associateWith { r.nextBoolean() }
                answer.filterValues { it }.keys.forEach { device.granted += it }
                registry.respondPermissions(if (r.nextInt(10) == 0) emptyMap() else answer)
            }
            assertEquals("iteration=$iteration", 1, callbacks)
            assertEquals(false, requester.isRequesting)
        }
    }
}
