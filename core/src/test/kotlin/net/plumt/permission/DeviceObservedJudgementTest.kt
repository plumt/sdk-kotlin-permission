package net.plumt.permission

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import net.plumt.permission.BuiltInSpecsTest.Companion.COARSE
import net.plumt.permission.BuiltInSpecsTest.Companion.FINE
import net.plumt.permission.BuiltInSpecsTest.Companion.IMAGES
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
 * 실기기(Android 16)에서 관측한 시스템 동작을 그대로 옮긴 판정 고정.
 *
 * - 사진: "제한된 액세스 허용" 뒤 다시 요청하면 선택 대화상자가 뜬다. 거기서 "추가 선택 안함" 을 골라
 *   근거 안내가 사라져도, 다음 요청에서 시스템은 선택기를 **다시** 띄운다 → 영구 거부가 아니다
 * - 위치: 첫 대화상자에서 대략적 위치를 고른 뒤, 정밀 승격 대화상자에서 "대략적인 위치 계속 사용" 을 고르면
 *   다음 요청은 화면 없이 바로 끝난다 → 정밀 위치는 영구 거부다
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35])
class DeviceObservedJudgementTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = FakeDevice()
    private val history = RequestHistoryStore.inMemory()
    private val registry = FakeRegistry()
    private val activity: Activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val results = mutableListOf<MultiplePermissionsStatus>()

    private fun manager() = fakeManager(context, device, 34, history = history)

    private fun requester(spec: PermissionSpec): PermissionRequester =
        manager().createRequester(registry, "k", { activity }, listOf(spec)) { results += it }

    @Test
    fun `사진 추가 선택 안함 뒤에도 영구 거부가 아니다 — 시스템이 선택기를 다시 띄운다`() {
        val spec = PermissionSpecs.media()
        val r = requester(spec)

        // 1차: 제한된 액세스 허용(0개 선택) → 근거 안내 참
        r.launch()
        device.grant(USER_SELECTED)
        device.rationale += IMAGES
        registry.respondPermissions(mapOf(IMAGES to false, USER_SELECTED to true))
        val first = results.last()[spec] as PermissionStatus.Partial
        assertEquals(PartialAccess.SELECTED_MEDIA, first.access)
        assertTrue(first.shouldShowRationale)
        assertFalse(first.isMissingPermanentlyDenied)

        // 2차: 추가 선택 안함 → 근거 안내가 사라진다
        r.launch()
        assertEquals(listOf(IMAGES, USER_SELECTED), registry.lastPermissions)
        device.rationale -= IMAGES
        registry.respondPermissions(mapOf(IMAGES to false, USER_SELECTED to true))
        val second = results.last()[spec] as PermissionStatus.Partial
        assertFalse("시스템은 3차 요청에 선택기를 다시 띄운다", second.isMissingPermanentlyDenied)
        assertTrue(second.canAskAgain)
        assertFalse(second.needsSettings)
        assertFalse(history.isPermanentlyDenied(IMAGES))

        // 확인만 해도(요청 없이) 같은 판정
        val checked = manager().check(spec, activity) as PermissionStatus.Partial
        assertFalse(checked.isMissingPermanentlyDenied)
    }

    @Test
    fun `일부 선택이 남아 있으면 옛 영구 거부 이력이 있어도 다시 물을 수 있다`() {
        history.record(IMAGES, permanentlyDenied = true)
        device.grant(USER_SELECTED)
        val status = manager().check(PermissionSpecs.media(), activity) as PermissionStatus.Partial
        assertFalse(status.isMissingPermanentlyDenied)
        assertTrue(status.canAskAgain)
    }

    @Test
    fun `일부 선택마저 없으면 사진도 기존 규칙대로 영구 거부를 판정한다`() {
        val spec = PermissionSpecs.media()
        val r = requester(spec)
        r.launch()
        device.rationale += IMAGES
        registry.respondPermissions(mapOf(IMAGES to false, USER_SELECTED to false))
        r.launch()
        device.rationale -= IMAGES
        registry.respondPermissions(mapOf(IMAGES to false, USER_SELECTED to false))
        assertEquals(PermissionStatus.PermanentlyDenied, results.last()[spec])
    }

    @Test
    fun `위치 대략적인 위치 계속 사용 뒤 정밀 위치는 영구 거부다 — 다음 요청이 화면 없이 끝난다`() {
        val spec = PermissionSpecs.location()
        val r = requester(spec)

        // 1차: 첫 대화상자에서 대략적 위치 + 앱 사용 중에만 — 이 대화상자에서 고른 선택지라 거부가 아니다
        r.launch()
        device.grant(COARSE)
        registry.respondPermissions(mapOf(FINE to false, COARSE to true))
        val first = results.last()[spec] as PermissionStatus.Partial
        assertEquals(PartialAccess.APPROXIMATE_LOCATION, first.access)
        assertFalse(first.isMissingPermanentlyDenied)

        // 2차: 정밀 승격 대화상자에서 "대략적인 위치 계속 사용"(근거 안내는 1·2차 내내 거짓)
        r.launch()
        assertEquals(listOf(FINE, COARSE), registry.lastPermissions)
        registry.respondPermissions(mapOf(FINE to false, COARSE to true))
        val second = results.last()[spec] as PermissionStatus.Partial
        assertTrue("시스템은 정밀 위치를 더 묻지 않는다", second.isMissingPermanentlyDenied)
        assertFalse(second.canAskAgain)
        assertTrue(second.needsSettings)

        // 3차: 화면 없이 즉시 같은 결과 — 판정이 유지된다
        r.launch()
        registry.respondPermissions(mapOf(FINE to false, COARSE to true))
        assertTrue((results.last()[spec] as PermissionStatus.Partial).isMissingPermanentlyDenied)
    }
}
