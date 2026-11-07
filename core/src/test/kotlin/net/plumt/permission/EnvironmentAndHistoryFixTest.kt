package net.plumt.permission

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import net.plumt.permission.BuiltInSpecsTest.Companion.ACTIVITY
import net.plumt.permission.BuiltInSpecsTest.Companion.BG
import net.plumt.permission.BuiltInSpecsTest.Companion.BT_CONNECT
import net.plumt.permission.BuiltInSpecsTest.Companion.BT_SCAN
import net.plumt.permission.BuiltInSpecsTest.Companion.CAMERA
import net.plumt.permission.BuiltInSpecsTest.Companion.COARSE
import net.plumt.permission.BuiltInSpecsTest.Companion.FINE
import net.plumt.permission.BuiltInSpecsTest.Companion.IMAGES
import net.plumt.permission.BuiltInSpecsTest.Companion.LEGACY
import net.plumt.permission.BuiltInSpecsTest.Companion.NOTIFY
import net.plumt.permission.BuiltInSpecsTest.Companion.USER_SELECTED
import net.plumt.permission.BuiltInSpecsTest.Companion.VIDEO
import net.plumt.permission.internal.INSTALL_STAMP_KEY
import net.plumt.permission.internal.SharedPreferencesHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 리뷰 H2(백업 복원)·M5(오버레이)·M6(targetSdk)·부분 접근 종류 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnvironmentAndHistoryFixTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ---- H2-2: 다른 설치에서 온 이력은 버린다

    @Test
    fun `H2 설치 시각이 다른 이력은 열 때 버린다(백업 복원·재설치)`() {
        val old = SharedPreferencesHistoryStore(context, "stamp-test", installStamp = 1L)
        old.record(CAMERA, permanentlyDenied = true)
        val sameInstall = SharedPreferencesHistoryStore(context, "stamp-test", installStamp = 1L)
        assertTrue(sameInstall.isPermanentlyDenied(CAMERA))
        val restoredOnNewDevice = SharedPreferencesHistoryStore(context, "stamp-test", installStamp = 2L)
        assertFalse(restoredOnNewDevice.hasRequested(CAMERA))
        assertFalse(restoredOnNewDevice.isPermanentlyDenied(CAMERA))
    }

    @Test
    fun `H2 설치 시각 없이 남은 이력(스탬프 도입 전·외부에서 쓴 파일)도 버린다`() {
        context.getSharedPreferences("stamp-missing", Context.MODE_PRIVATE).edit().putInt(CAMERA, 3).commit()
        val store = SharedPreferencesHistoryStore(context, "stamp-missing", installStamp = 0L)
        assertFalse(store.hasRequested(CAMERA))
    }

    @Test
    fun `clear 는 이력만 지우고 설치 시각은 남긴다`() {
        val store = SharedPreferencesHistoryStore(context, "stamp-clear", installStamp = 7L)
        store.record(CAMERA, permanentlyDenied = true)
        store.clear()
        assertFalse(store.hasRequested(CAMERA))
        val prefs = context.getSharedPreferences("stamp-clear", Context.MODE_PRIVATE)
        assertEquals(7L, prefs.getLong(INSTALL_STAMP_KEY, -1L))
        store.record(CAMERA, permanentlyDenied = false)
        // 같은 설치로 다시 열면 이력이 남아 있다
        assertTrue(SharedPreferencesHistoryStore(context, "stamp-clear", installStamp = 7L).hasRequested(CAMERA))
    }

    @Test
    fun `기본 저장소는 이 설치의 최초 설치 시각을 쓴다`() {
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        RequestHistoryStore.sharedPreferences(context, "stamp-default").record(CAMERA, permanentlyDenied = true)
        val prefs = context.getSharedPreferences("stamp-default", Context.MODE_PRIVATE)
        assertEquals(stamp, prefs.getLong(INSTALL_STAMP_KEY, -1L))
        assertTrue(RequestHistoryStore.sharedPreferences(context, "stamp-default").isPermanentlyDenied(CAMERA))
    }

    @Test
    fun `remove 는 그 권한만 처음 상태로 돌린다`() {
        for (store in listOf(RequestHistoryStore.inMemory(), RequestHistoryStore.sharedPreferences(context, "remove-test"))) {
            store.record(CAMERA, permanentlyDenied = true)
            store.record(FINE, permanentlyDenied = true)
            store.remove(CAMERA)
            store.remove("x.never.recorded")
            assertFalse(store.hasRequested(CAMERA))
            assertFalse(store.isPermanentlyDenied(CAMERA))
            assertTrue(store.isPermanentlyDenied(FINE))
        }
    }

    // ---- M6: targetSdk

    @Test
    fun `targetSdkInt 기본값은 sdkInt 이고 1 미만이면 IllegalArgumentException`() {
        assertEquals(34, PermissionEnvironment.of(34).targetSdkInt)
        assertEquals(30, PermissionEnvironment.of(34, targetSdkInt = 30).targetSdkInt)
        val e = assertThrows(IllegalArgumentException::class.java) { PermissionEnvironment.of(34, targetSdkInt = 0) }
        assertEquals("targetSdkInt 는 1 이상이어야 합니다: 0", e.message)
        assertTrue(PermissionEnvironment.of(34, targetSdkInt = 30).toString().contains("targetSdkInt=30"))
    }

    @Test
    fun `인터페이스를 직접 구현하면 targetSdkInt 는 sdkInt 를 따른다`() {
        val env = object : PermissionEnvironment {
            override val sdkInt: Int = 31
            override fun isDeclared(permission: String) = false
        }
        assertEquals(31, env.targetSdkInt)
    }

    @Test
    fun `M6 targetSdk 32 앱은 API 33 기기에서 READ_EXTERNAL_STORAGE 를 요청한다`() {
        val env = PermissionEnvironment.of(33, ALL_DECLARED, targetSdkInt = 32)
        assertEquals(listOf(LEGACY), PermissionSpecs.media().permissions(env))
        assertEquals(GrantLevel.FULL, PermissionSpecs.media().evaluate(env) { it == LEGACY })
    }

    @Test
    fun `M6 targetSdk 33 앱은 API 34 기기에서 일부 선택 권한을 요청하지 않는다`() {
        val env = PermissionEnvironment.of(34, ALL_DECLARED, targetSdkInt = 33)
        assertEquals(listOf(IMAGES), PermissionSpecs.media().permissions(env))
        assertEquals(GrantLevel.NONE, PermissionSpecs.media().evaluate(env) { it == USER_SELECTED })
    }

    @Test
    fun `M6 targetSdk 32 앱의 알림은 API 33 기기에서도 NotApplicable 이다`() {
        val env = PermissionEnvironment.of(33, ALL_DECLARED, targetSdkInt = 32)
        assertEquals(emptyList<String>(), PermissionSpecs.notifications.permissions(env))
        assertEquals(listOf(NOTIFY), PermissionSpecs.notifications.permissions(PermissionEnvironment.of(33, targetSdkInt = 33)))
    }

    @Test
    fun `M6 targetSdk 28 앱의 활동 인식은 API 29 기기에서 NotApplicable 이다`() {
        assertEquals(emptyList<String>(), PermissionSpecs.activityRecognition.permissions(PermissionEnvironment.of(29, targetSdkInt = 28)))
        assertEquals(listOf(ACTIVITY), PermissionSpecs.activityRecognition.permissions(PermissionEnvironment.of(29)))
    }

    @Test
    fun `M6 targetSdk 30 앱의 블루투스는 API 31 기기에서도 옛 모델이다`() {
        val env = PermissionEnvironment.of(31, targetSdkInt = 30)
        assertEquals(emptyList<String>(), PermissionSpecs.bluetooth(connect = true).permissions(env))
        assertEquals(listOf(FINE), PermissionSpecs.bluetooth(scan = true).permissions(env))
        assertEquals(listOf(COARSE), PermissionSpecs.bluetooth(scan = true).permissions(PermissionEnvironment.of(31, targetSdkInt = 28)))
        assertEquals(listOf(BT_SCAN, BT_CONNECT), PermissionSpecs.bluetooth(scan = true).permissions(PermissionEnvironment.of(31)))
    }

    @Test
    fun `M6 실제 관리자는 앱의 targetSdkVersion 을 환경에 담는다`() {
        val m = PermissionManager.create(context)
        assertEquals(context.applicationInfo.targetSdkVersion, m.environment.targetSdkInt)
    }

    // ---- 부분 접근의 종류

    @Test
    fun `부분 접근 — 대략적 위치`() {
        val env = PermissionEnvironment.of(34)
        assertEquals(PartialAccess.APPROXIMATE_LOCATION, PermissionSpecs.location().partialAccess(env) { it == COARSE })
    }

    @Test
    fun `부분 접근 — 사진 일부 선택과 매체 일부 허용을 가른다`() {
        val env = PermissionEnvironment.of(34, ALL_DECLARED)
        val both = PermissionSpecs.media(images = true, video = true)
        assertEquals(PartialAccess.SELECTED_MEDIA, both.partialAccess(env) { it == USER_SELECTED })
        assertEquals(PartialAccess.SOME_PERMISSIONS, both.partialAccess(env) { it == IMAGES || it == USER_SELECTED })
        assertEquals(PartialAccess.SOME_PERMISSIONS, both.partialAccess(env) { it == VIDEO })
        assertEquals(PartialAccess.SOME_PERMISSIONS, both.partialAccess(PermissionEnvironment.of(33)) { it == VIDEO })
    }

    @Test
    fun `부분 접근 — 백그라운드 위치`() {
        val bg = PermissionSpecs.backgroundLocation()
        val env = PermissionEnvironment.of(30)
        assertEquals(PartialAccess.FOREGROUND_LOCATION_ONLY, bg.partialAccess(env) { it == FINE || it == COARSE })
        assertEquals(PartialAccess.FOREGROUND_LOCATION_ONLY, bg.partialAccess(env) { it == COARSE })
        assertEquals(PartialAccess.APPROXIMATE_LOCATION, bg.partialAccess(env) { it == COARSE || it == BG })
        // API 29 미만에는 백그라운드 권한이 따로 없어 남는 것은 정밀도뿐이다
        assertEquals(PartialAccess.APPROXIMATE_LOCATION, bg.partialAccess(PermissionEnvironment.of(28)) { it == COARSE })
    }

    @Test
    fun `부분 접근 — 사용자 정의 명세의 기본값`() {
        val spec = object : RuntimePermissionSpec {
            override val id = "custom"
            override fun permissions(environment: PermissionEnvironment) = listOf("a", "b")
        }
        assertEquals(PartialAccess.SOME_PERMISSIONS, spec.partialAccess(PermissionEnvironment.of(34)) { it == "a" })
    }

    @Test
    fun `check 의 Partial 은 부분 접근 종류를 담는다`() {
        val device = FakeDevice().apply { grant(USER_SELECTED) }
        val status = fakeManager(context, device).check(PermissionSpecs.media()) as PermissionStatus.Partial
        assertEquals(PartialAccess.SELECTED_MEDIA, status.access)
        assertEquals(setOf(USER_SELECTED), status.granted)
    }

    @Test
    fun `부분 접근 종류 값`() {
        assertEquals(
            listOf("SELECTED_MEDIA", "APPROXIMATE_LOCATION", "FOREGROUND_LOCATION_ONLY", "SOME_PERMISSIONS"),
            PartialAccess.entries.map { it.name },
        )
    }

    // ---- M5: API 26~27 오버레이 판정 지연

    @Test
    @Config(sdk = [26])
    fun `M5 API 26 은 canDrawOverlays 가 거짓이어도 AppOps 가 허용이면 허용이다`() {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        // 기기에서 켜지 않은 앱의 모드는 MODE_DEFAULT 다(Robolectric 기본값은 허용이라 맞춘다)
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), context.packageName, AppOpsManager.MODE_DEFAULT)
        assertFalse(PermissionSpecs.overlay.isGranted(context))
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
        assertTrue(PermissionSpecs.overlay.isGranted(context))
    }

    @Test
    @Config(sdk = [28])
    fun `M5 API 28 은 AppOps 로 보조 판정하지 않는다`() {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
        // Robolectric 의 canDrawOverlays 는 따로 정한다
        assertEquals(Settings.canDrawOverlays(context), PermissionSpecs.overlay.isGranted(context))
    }
}
