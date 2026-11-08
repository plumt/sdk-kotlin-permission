package net.plumt.permission

import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import net.plumt.permission.internal.ActivityTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 콜백 요청 — suspend 요청을 감싸 메인 스레드로 결과를 주고, 취소 핸들을 돌려주고, 실수는 호출 즉시 던진다 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallbackRequestTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val device = FakeDevice()
    private val tracker = ActivityTracker().also { app.registerActivityLifecycleCallbacks(it) }
    private val camera = "android.permission.CAMERA"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        scope.cancel()
        app.unregisterActivityLifecycleCallbacks(tracker)
    }

    private fun manager(declared: Set<String> = ALL_DECLARED) = fakeManager(app, device, declared = declared, tracker = tracker)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun activity(): ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun answer(activity: ComponentActivity, granted: Boolean) {
        val request = shadowOf(activity).lastRequestedPermission
        if (granted) device.grant(camera)
        android.app.Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest").apply { isAccessible = true }.setBoolean(activity, false)
        activity.onRequestPermissionsResult(
            request.requestCode,
            arrayOf(camera),
            intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED),
        )
    }

    @Test
    fun `이미 허용됐으면 시스템 요청 없이 곧바로 콜백한다`() {
        val activity = activity()
        device.grant(camera)
        val results = mutableListOf<RequestResult>()
        val handle = manager().request(activity, PermissionSpecs.camera) { results += it }
        idle()
        assertTrue(results.single() is RequestResult.Completed)
        assertTrue(results.single().status.allGranted)
        assertFalse(handle.isActive)
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    fun `요청을 띄우고 결과를 메인 스레드로 한 번 준다`() {
        val activity = activity()
        val m = manager()
        val results = mutableListOf<RequestResult>()
        val threads = mutableListOf<Looper?>()
        val handle = m.request(activity, listOf(PermissionSpecs.camera)) {
            results += it
            threads += Looper.myLooper()
        }
        idle()
        assertTrue(handle.isActive)
        assertTrue(results.isEmpty())
        answer(activity, granted = true)
        idle()
        assertEquals(PermissionStatus.Granted, results.single().status[PermissionSpecs.camera])
        assertSame(Looper.getMainLooper(), threads.single())
        assertFalse(handle.isActive)
        assertNull(m.gate.current)
        assertEquals("RequestHandle(active=false)", handle.toString())
    }

    @Test
    fun `onCreate 밖(STARTED 뒤)에 불러도 된다`() {
        val activity = activity()
        assertTrue(activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        val results = mutableListOf<RequestResult>()
        manager().request(activity, PermissionSpecs.camera, SettingsPolicy.Never) { results += it }
        idle()
        answer(activity, granted = false)
        idle()
        assertEquals(PermissionStatus.Denied(false), results.single().status[PermissionSpecs.camera])
    }

    @Test
    fun `취소하면 콜백이 불리지 않고 줄에서 빠진다`() {
        val activity = activity()
        val m = manager()
        val results = mutableListOf<RequestResult>()
        val handle = m.request(activity, listOf(PermissionSpecs.camera)) { results += it }
        idle()
        handle.cancel()
        handle.cancel()
        idle()
        assertFalse(handle.isActive)
        assertNull(m.gate.current)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `인자·매니페스트 실수는 호출 즉시 던진다`() {
        val activity = activity()
        assertThrows(IllegalArgumentException::class.java) { manager().request(activity, emptyList()) { } }
        val undeclared = assertThrows(IllegalStateException::class.java) {
            manager(declared = emptySet()).request(activity, PermissionSpecs.camera) { }
        }
        assertTrue(undeclared.message!!.contains(camera))
    }

    @Test
    fun `권한 하나의 suspend 요청은 목록 요청과 같다`() {
        val m = manager()
        device.grant(camera)
        val single = scope.async { m.request(PermissionSpecs.camera) }
        idle()
        // 띄울 화면이 없어도 이미 허용이면 지금 상태를 준다
        assertTrue(single.isCompleted)
        assertTrue(single.getCompleted().status.allGranted)
        val activity = activity()
        val onActivity = scope.async { m.request(activity, PermissionSpecs.camera, SettingsPolicy.Direct) }
        idle()
        assertTrue(onActivity.getCompleted() is RequestResult.Completed)
    }
}
