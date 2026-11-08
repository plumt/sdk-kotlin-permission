package net.plumt.permission

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import net.plumt.permission.BuiltInSpecsTest.Companion.BG
import net.plumt.permission.BuiltInSpecsTest.Companion.CAMERA
import net.plumt.permission.BuiltInSpecsTest.Companion.COARSE
import net.plumt.permission.BuiltInSpecsTest.Companion.FINE
import net.plumt.permission.BuiltInSpecsTest.Companion.IMAGES
import net.plumt.permission.BuiltInSpecsTest.Companion.MIC
import net.plumt.permission.BuiltInSpecsTest.Companion.USER_SELECTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 판정이 현실과 갈라질 때 스스로 바로잡히는지 — 리뷰 H2(이력 잔존)·H3(부분 선택 오판)·M4(백그라운드 위치 단독 요청).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JudgementCorrectionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = FakeDevice()
    private val history = RequestHistoryStore.inMemory()
    private val registry = FakeRegistry()
    private val activity: Activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val results = mutableListOf<MultiplePermissionsStatus>()

    private fun manager(sdkInt: Int = 34) = fakeManager(context, device, sdkInt, history = history)

    private fun requester(vararg specs: PermissionSpec, sdkInt: Int = 34): PermissionRequester =
        manager(sdkInt).createRequester(registry, "k", { activity }, specs.toList()) { results += it }

    // ---- H2: 허용돼 있으면 이력을 지운다

    @Test
    fun `H2 영구 거부 이력이 있어도 지금 허용이면 확인할 때 이력을 지운다`() {
        history.record(CAMERA, permanentlyDenied = true)
        device.grant(CAMERA)
        assertEquals(PermissionStatus.Granted, manager().check(PermissionSpecs.camera, activity))
        assertFalse(history.isPermanentlyDenied(CAMERA))
        assertFalse(history.hasRequested(CAMERA))
    }

    @Test
    fun `H2 설정에서 허용한 뒤 권한이 초기화되면 영구 거부가 아니라 처음 상태다`() {
        history.record(CAMERA, permanentlyDenied = true)
        device.grant(CAMERA)
        manager().check(PermissionSpecs.camera, activity)
        // 자동 초기화(hibernation)·설정의 초기화로 권한이 "묻기" 로 돌아갔다
        device.revoke(CAMERA)
        assertEquals(PermissionStatus.Denied(false), manager().check(PermissionSpecs.camera, activity))
    }

    @Test
    fun `H2 근거 안내가 서 있으면 영구 거부 이력을 지운다`() {
        history.record(CAMERA, permanentlyDenied = true)
        device.rationale += CAMERA
        assertEquals(PermissionStatus.Denied(true), manager().check(PermissionSpecs.camera, activity))
        assertFalse(history.isPermanentlyDenied(CAMERA))
        assertTrue(history.hasRequested(CAMERA))
    }

    @Test
    fun `H2 일부 허용 명세에서도 허용된 권한의 이력만 지운다`() {
        history.record(COARSE, permanentlyDenied = true)
        history.record(FINE, permanentlyDenied = true)
        device.grant(COARSE)
        val status = manager().check(PermissionSpecs.location(), activity)
        assertTrue(status is PermissionStatus.Partial)
        assertFalse(history.hasRequested(COARSE))
        assertTrue(history.isPermanentlyDenied(FINE))
    }

    @Test
    fun `H2 허용 결과는 이력을 지운다`() {
        history.record(CAMERA, permanentlyDenied = true)
        val r = requester(PermissionSpecs.camera)
        r.launch()
        device.grant(CAMERA)
        registry.respondPermissions(mapOf(CAMERA to true))
        assertFalse(history.hasRequested(CAMERA))
        assertFalse(history.isPermanentlyDenied(CAMERA))
    }

    // ---- H3: 부분 선택은 거부가 아니다

    @Test
    fun `H3 사진 일부 선택을 두 번 골라도 영구 거부로 보지 않는다`() {
        val spec = PermissionSpecs.media()
        val r = requester(spec)
        repeat(2) {
            r.launch()
            assertEquals(listOf(IMAGES, USER_SELECTED), registry.lastPermissions)
            device.grant(USER_SELECTED)
            registry.respondPermissions(mapOf(IMAGES to false, USER_SELECTED to true))
        }
        val status = results.last()[spec] as PermissionStatus.Partial
        assertFalse(status.isMissingPermanentlyDenied)
        assertTrue(status.canAskAgain)
        assertFalse(status.needsSettings)
        assertFalse(history.isPermanentlyDenied(IMAGES))
    }

    @Test
    fun `H3 첫 대화상자에서 대략적 위치를 고른 것은 정밀 위치의 영구 거부가 아니다`() {
        val spec = PermissionSpecs.location()
        val r = requester(spec)
        r.launch()
        assertEquals(listOf(FINE, COARSE), registry.lastPermissions)
        device.grant(COARSE)
        registry.respondPermissions(mapOf(FINE to false, COARSE to true))
        val status = results.last()[spec] as PermissionStatus.Partial
        assertFalse(status.isMissingPermanentlyDenied)
        assertFalse(history.isPermanentlyDenied(FINE))
    }

    // 실기기 관측으로 뒤집었다: 대략적 위치를 가진 채 띄운 승격 대화상자의 "대략적인 위치 계속 사용" 은 거부이고 다음 요청은 화면 없이 끝난다
    @Test
    fun `H3 대략적 위치를 가진 채 승격을 거절하면 정밀 위치는 영구 거부다`() {
        val spec = PermissionSpecs.location()
        val r = requester(spec)
        repeat(2) {
            r.launch()
            device.grant(COARSE)
            registry.respondPermissions(mapOf(FINE to false, COARSE to true))
        }
        val status = results.last()[spec] as PermissionStatus.Partial
        assertTrue(status.isMissingPermanentlyDenied)
        assertTrue(history.isPermanentlyDenied(FINE))
    }

    @Test
    fun `H3 부분 선택이어도 근거 안내가 서 있다가 사라지면 영구 거부다`() {
        val spec = PermissionSpecs.location()
        device.grant(COARSE)
        device.rationale += FINE
        val r = requester(spec)
        r.launch()
        device.rationale -= FINE
        registry.respondPermissions(mapOf(FINE to false, COARSE to true))
        val status = results.last()[spec] as PermissionStatus.Partial
        assertTrue(status.isMissingPermanentlyDenied)
    }

    @Test
    fun `H3 다른 명세의 허용은 이 명세의 거부를 부분 선택으로 만들지 않는다`() {
        val r = requester(PermissionSpecs.camera, PermissionSpecs.microphone)
        // 1차는 대화상자, 2차는 근거 안내 없이 화면 없이 끝남 — 다른 명세(마이크)의 허용은 판정에 끼지 않는다
        repeat(2) { round ->
            device.dialogShown = round == 0
            r.launch()
            device.grant(MIC)
            registry.respondPermissions(mapOf(CAMERA to false, MIC to true))
            device.revoke(MIC)
        }
        assertTrue(history.isPermanentlyDenied(CAMERA))
    }

    // ---- M4: API 30+ 백그라운드 위치는 혼자 요청한다

    @Test
    fun `M4 API 30 이상에서 백그라운드 위치는 다른 권한과 묶지 않고 따로 요청한다`() {
        device.grant(FINE, COARSE)
        val bg = PermissionSpecs.backgroundLocation()
        val r = requester(PermissionSpecs.camera, bg, sdkInt = 30)
        r.launch()
        assertEquals(listOf(CAMERA), registry.lastPermissions)
        device.grant(CAMERA)
        registry.respondPermissions(mapOf(CAMERA to true))
        assertTrue(results.isEmpty())
        assertEquals(listOf(BG), registry.lastPermissions)
        assertTrue(r.isRequesting)
        device.grant(BG)
        registry.respondPermissions(mapOf(BG to true))
        assertTrue(results.single().allGranted)
        assertFalse(r.isRequesting)
    }

    @Test
    fun `M4 API 29 는 백그라운드 위치를 한 번에 묻는다`() {
        val r = requester(PermissionSpecs.camera, PermissionSpecs.backgroundLocation(), sdkInt = 29)
        r.launch()
        assertEquals(listOf(CAMERA, FINE, COARSE, BG), registry.lastPermissions)
    }

    @Test
    fun `M4 백그라운드 위치만 남으면 한 번만 요청한다`() {
        device.grant(FINE, COARSE)
        val r = requester(PermissionSpecs.backgroundLocation(), sdkInt = 31)
        r.launch()
        assertEquals(1, registry.launches.size)
        assertEquals(listOf(BG), registry.lastPermissions)
    }
}
