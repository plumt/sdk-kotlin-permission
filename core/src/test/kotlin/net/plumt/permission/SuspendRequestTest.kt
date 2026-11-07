package net.plumt.permission

import android.app.Application
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import net.plumt.permission.internal.ActivityTracker
import net.plumt.permission.internal.DefaultPermissionManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** 화면 없이 부르는 suspend 요청 — Activity 추적, 결과 대기, 회전 이어받기, 종료·취소, 직렬화 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SuspendRequestTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val device = FakeDevice()
    private val tracker = ActivityTracker().also { app.registerActivityLifecycleCallbacks(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val camera = "android.permission.CAMERA"
    private val mic = "android.permission.RECORD_AUDIO"

    @After
    fun tearDown() {
        scope.cancel()
        app.unregisterActivityLifecycleCallbacks(tracker)
    }

    private fun manager(withTracker: Boolean = true): DefaultPermissionManager =
        fakeManager(app, device, tracker = tracker.takeIf { withTracker })

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun resumed(): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).setup()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T> Deferred<T>.done(): T {
        idle()
        assertTrue("아직 끝나지 않았다", isCompleted)
        return getCompleted()
    }

    /** 실제 Activity 에 나간 마지막 권한 요청에 시스템처럼 답한다 */
    private fun answer(activity: ComponentActivity, vararg results: Pair<String, Boolean>) {
        val request = shadowOf(activity).lastRequestedPermission
        for ((p, granted) in results) if (granted) device.grant(p)
        // 시스템 경로(dispatchRequestPermissionsResult)처럼 "진행 중인 요청" 표시를 먼저 내린다 — 내리지 않으면 다음 요청이 곧바로 빈 결과로 끝난다
        android.app.Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest").apply { isAccessible = true }.setBoolean(activity, false)
        activity.onRequestPermissionsResult(
            request.requestCode,
            results.map { it.first }.toTypedArray(),
            results.map { if (it.second) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED }.toIntArray(),
        )
    }

    @Test
    fun `띄울 화면이 없으면 NoActivity 와 지금 상태`() {
        val result = scope.async { manager().request(listOf(PermissionSpecs.camera)) }.done()
        assertTrue(result is RequestResult.NoActivity)
        assertEquals(PermissionStatus.Denied(false), result.status[PermissionSpecs.camera])
        // 추적기가 없는 관리자도 같다
        val noTracker = scope.async { manager(withTracker = false).request(listOf(PermissionSpecs.camera)) }.done()
        assertTrue(noTracker is RequestResult.NoActivity)
    }

    @Test
    fun `이미 허용됐으면 시스템 요청 없이 곧바로 Completed`() {
        val activity = resumed().get()
        device.grant(camera)
        val result = scope.async { manager().request(listOf(PermissionSpecs.camera)) }.done()
        assertEquals(RequestResult.Completed(result.status), result)
        assertTrue(result.status.allGranted)
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    fun `추적한 Activity 에 요청을 띄우고 결과를 기다린다`() {
        val activity = resumed().get()
        val m = manager()
        val deferred = scope.async { m.request(listOf(PermissionSpecs.camera)) }
        idle()
        assertFalse(deferred.isCompleted)
        assertEquals(listOf(camera), shadowOf(activity).lastRequestedPermission.requestedPermissions.toList())
        answer(activity, camera to true)
        val result = deferred.done()
        assertTrue(result is RequestResult.Completed)
        assertEquals(PermissionStatus.Granted, result.status[PermissionSpecs.camera])
        assertNull(m.gate.current)
    }

    @Test
    fun `넘긴 Activity 에 띄우는 판도 같다`() {
        val activity = resumed().get()
        val m = manager(withTracker = false)
        val deferred = scope.async { m.request(activity, listOf(PermissionSpecs.camera)) }
        idle()
        answer(activity, camera to false)
        assertEquals(PermissionStatus.Denied(false), deferred.done().status[PermissionSpecs.camera])
    }

    @Test
    fun `화면 회전으로 Activity 가 다시 만들어져도 새 Activity 에서 결과를 이어받는다`() {
        val controller = resumed()
        val first = controller.get()
        val m = manager()
        val deferred = scope.async { m.request(listOf(PermissionSpecs.camera, PermissionSpecs.microphone)) }
        idle()
        val requestCode = shadowOf(first).lastRequestedPermission.requestCode
        controller.configurationChange(Configuration(first.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE })
        val second = controller.get()
        assertTrue(second !== first)
        assertFalse(deferred.isCompleted)
        device.grant(camera)
        second.onRequestPermissionsResult(
            requestCode,
            arrayOf(camera, mic),
            intArrayOf(PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_DENIED),
        )
        val result = deferred.done()
        assertTrue(result is RequestResult.Completed)
        assertEquals(PermissionStatus.Granted, result.status[PermissionSpecs.camera])
        assertEquals(PermissionStatus.Denied(false), result.status[PermissionSpecs.microphone])
        assertNull(m.gate.current)
    }

    @Test
    fun `흐름 도중 화면이 닫히면 NoActivity`() {
        val controller = resumed()
        val m = manager()
        val deferred = scope.async { m.request(listOf(PermissionSpecs.camera)) }
        idle()
        controller.pause().stop().destroy()
        val result = deferred.done()
        assertTrue(result is RequestResult.NoActivity)
        assertNull(m.gate.current)
    }

    @Test
    fun `취소하면 등록을 풀고 줄에서 빠진다`() {
        resumed()
        val m = manager()
        val job = scope.async { m.request(listOf(PermissionSpecs.camera)) }
        idle()
        assertTrue(m.gate.current != null)
        job.cancel()
        idle()
        assertNull(m.gate.current)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `동시에 부른 두 요청은 하나씩 차례로 뜬다`() {
        val activity = resumed().get()
        val m = manager()
        val a = scope.async { m.request(listOf(PermissionSpecs.camera)) }
        val b = scope.async { m.request(listOf(PermissionSpecs.microphone)) }
        idle()
        assertEquals(listOf(camera), shadowOf(activity).lastRequestedPermission.requestedPermissions.toList())
        answer(activity, camera to true)
        idle()
        assertTrue(a.isCompleted)
        assertFalse(b.isCompleted)
        assertEquals(listOf(mic), shadowOf(activity).lastRequestedPermission.requestedPermissions.toList())
        answer(activity, mic to true)
        assertEquals(PermissionStatus.Granted, b.done().status[PermissionSpecs.microphone])
    }

    @Test
    fun `매니페스트 미선언이면 IllegalStateException 이 호출부로 온다`() {
        resumed()
        val m = fakeManager(app, device, declared = emptySet(), tracker = tracker)
        val deferred = scope.async { m.request(listOf(PermissionSpecs.camera)) }
        idle()
        assertTrue(@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) deferred.getCompletionExceptionOrNull() is IllegalStateException)
        assertNull(m.gate.current)
    }

    @Test
    fun `인자가 틀리면 IllegalArgumentException`() {
        val deferred = scope.async { manager().request(emptyList()) }
        idle()
        assertTrue(@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) deferred.getCompletionExceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `추적기는 재개된 것을 먼저, 없으면 마지막으로 시작된 것을 고른다`() {
        val t = ActivityTracker()
        app.registerActivityLifecycleCallbacks(t)
        try {
            assertNull(t.current())
            val a = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            assertSame(a.get(), t.current())
            val b = Robolectric.buildActivity(ComponentActivity::class.java).create().start()
            assertSame(a.get(), t.current())
            a.pause()
            assertSame(b.get(), t.current())
            b.stop()
            assertSame(a.get(), t.current())
            a.stop().destroy()
            assertNull(t.current())
            b.destroy()
        } finally {
            app.unregisterActivityLifecycleCallbacks(t)
        }
    }

    @Test
    fun `create 는 Application 이면 Activity 를 추적한다`() {
        val m = PermissionManager.create(app, PermissionConfig(RequestHistoryStore.inMemory(), ManifestCheck.OFF)) as DefaultPermissionManager
        val activity = resumed().get()
        assertSame(activity, m.activityTracker!!.current())
    }
}
