package net.plumt.permission

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import net.plumt.permission.BuiltInSpecsTest.Companion.BG
import net.plumt.permission.BuiltInSpecsTest.Companion.CAMERA
import net.plumt.permission.BuiltInSpecsTest.Companion.COARSE
import net.plumt.permission.BuiltInSpecsTest.Companion.FINE
import net.plumt.permission.BuiltInSpecsTest.Companion.IMAGES
import net.plumt.permission.BuiltInSpecsTest.Companion.MIC
import net.plumt.permission.BuiltInSpecsTest.Companion.NOTIFY
import net.plumt.permission.BuiltInSpecsTest.Companion.USER_SELECTED
import org.junit.Assert.assertEquals
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

/** 확인(요청 없음) 경로의 상태 판정 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionManagerCheckTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = FakeDevice()
    private val activity: Activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    @Test
    fun `전부 허용이면 Granted`() {
        device.grant(CAMERA)
        assertEquals(PermissionStatus.Granted, fakeManager(context, device).check(PermissionSpecs.camera, activity))
    }

    @Test
    fun `요청할 권한이 없으면 그 API 레벨을 담은 NotApplicable`() {
        val m = fakeManager(context, device, sdkInt = 32)
        assertEquals(PermissionStatus.NotApplicable(32), m.check(PermissionSpecs.notifications, activity))
        assertEquals(PermissionStatus.Denied(false), fakeManager(context, device, sdkInt = 33).check(PermissionSpecs.notifications))
    }

    @Test
    fun `처음에는 근거 안내 없는 Denied`() {
        assertEquals(PermissionStatus.Denied(false), fakeManager(context, device).check(PermissionSpecs.camera, activity))
    }

    @Test
    fun `근거 안내가 권해지면 Denied(true)`() {
        device.rationale += CAMERA
        assertEquals(PermissionStatus.Denied(true), fakeManager(context, device).check(PermissionSpecs.camera, activity))
    }

    @Test
    fun `Activity 가 없으면 근거 안내를 묻지 않고 거짓으로 본다`() {
        device.rationale += CAMERA
        assertEquals(PermissionStatus.Denied(false), fakeManager(context, device).check(PermissionSpecs.camera, null))
        assertEquals(0, device.rationaleCalls)
    }

    @Test
    fun `이력에 영구 거부가 남아 있으면 PermanentlyDenied`() {
        val history = RequestHistoryStore.inMemory().apply { record(CAMERA, permanentlyDenied = true) }
        assertEquals(PermissionStatus.PermanentlyDenied, fakeManager(context, device, history = history).check(PermissionSpecs.camera, activity))
    }

    @Test
    fun `이력이 영구 거부여도 시스템이 근거 안내를 권하면 Denied(true) 가 이긴다`() {
        val history = RequestHistoryStore.inMemory().apply { record(CAMERA, permanentlyDenied = true) }
        device.rationale += CAMERA
        assertEquals(PermissionStatus.Denied(true), fakeManager(context, device, history = history).check(PermissionSpecs.camera, activity))
    }

    @Test
    fun `요청만 했고 영구 거부가 아니면 Denied(false)`() {
        val history = RequestHistoryStore.inMemory().apply { record(CAMERA, permanentlyDenied = false) }
        assertEquals(PermissionStatus.Denied(false), fakeManager(context, device, history = history).check(PermissionSpecs.camera, activity))
    }

    @Test
    fun `대략 위치만 허용이면 Partial 에 허용과 누락을 담는다`() {
        device.grant(COARSE)
        device.rationale += FINE
        val status = fakeManager(context, device).check(PermissionSpecs.location(), activity)
        assertEquals(PermissionStatus.Partial(setOf(COARSE), setOf(FINE), shouldShowRationale = true, isMissingPermanentlyDenied = false, access = PartialAccess.APPROXIMATE_LOCATION), status)
    }

    @Test
    fun `일부 허용의 나머지가 영구 거부면 Partial 이 그것을 밝힌다`() {
        device.grant(FINE, COARSE)
        val history = RequestHistoryStore.inMemory().apply { record(BG, permanentlyDenied = true) }
        val status = fakeManager(context, device, sdkInt = 30, history = history).check(PermissionSpecs.backgroundLocation(), activity)
        assertEquals(PermissionStatus.Partial(setOf(FINE, COARSE), setOf(BG), shouldShowRationale = false, isMissingPermanentlyDenied = true, access = PartialAccess.FOREGROUND_LOCATION_ONLY), status)
        assertTrue(status.needsSettings)
    }

    @Test
    fun `사진 일부 선택은 Partial`() {
        device.grant(USER_SELECTED)
        val status = fakeManager(context, device).check(PermissionSpecs.media(), activity)
        assertEquals(PermissionStatus.Partial(setOf(USER_SELECTED), setOf(IMAGES), false, false, PartialAccess.SELECTED_MEDIA), status)
    }

    @Test
    fun `백그라운드 위치 API 30 이상 포그라운드 미허용은 포그라운드 기준으로 판정`() {
        device.rationale += FINE
        assertEquals(PermissionStatus.Denied(true), fakeManager(context, device, sdkInt = 31).check(PermissionSpecs.backgroundLocation(), activity))
    }

    @Test
    fun `특수 권한은 비해당 허용 거부 셋이다`() {
        val spec = FakeSpecialSpec()
        val m = fakeManager(context, device)
        assertEquals(PermissionStatus.Denied(false), m.check(spec, activity))
        spec.granted = true
        assertEquals(PermissionStatus.Granted, m.check(spec))
        spec.applicable = false
        assertEquals(PermissionStatus.NotApplicable(34), m.check(spec))
    }

    @Test
    fun `배터리 최적화 예외는 PowerManager 값을 따른다`() {
        val m = fakeManager(context, device)
        val spec = PermissionSpecs.ignoreBatteryOptimizations
        assertEquals(PermissionStatus.Denied(false), m.check(spec))
        val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, true)
        assertEquals(PermissionStatus.Granted, m.check(spec))
        val intent = spec.settingsIntent(context)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test
    fun `checkAll 은 넘긴 순서대로 묶고 빈 목록과 id 중복을 거부한다`() {
        device.grant(CAMERA)
        val m = fakeManager(context, device)
        val all = m.checkAll(listOf(PermissionSpecs.microphone, PermissionSpecs.camera), activity)
        assertEquals(listOf(PermissionSpecs.microphone, PermissionSpecs.camera), all.specs)
        assertEquals(PermissionStatus.Granted, all[PermissionSpecs.camera])
        assertEquals(PermissionStatus.Denied(false), all[PermissionSpecs.microphone])
        val empty = assertThrows(IllegalArgumentException::class.java) { m.checkAll(emptyList()) }
        assertEquals("명세가 하나 이상 있어야 합니다", empty.message)
        val dup = assertThrows(IllegalArgumentException::class.java) { m.checkAll(listOf(PermissionSpecs.camera, PermissionSpecs.camera)) }
        assertEquals("같은 id 의 명세가 겹칩니다: [camera]", dup.message)
    }

    @Test
    fun `소비자 명세의 예외는 PermissionException 으로 감싸고 cause 를 보존한다`() {
        val m = fakeManager(context, device)
        for (where in listOf("permissions", "evaluate", "nextRequest")) {
            val boom = IllegalStateException("boom-$where")
            val e = assertThrows(PermissionException::class.java) { m.check(ThrowingSpec(where, boom), activity) }
            assertSame(boom, e.cause)
            assertEquals("명세가 실패했습니다: throwing.$where", e.message)
        }
        val special = object : SpecialPermissionSpec {
            override val id = "s"
            override val manifestPermission: String? = null
            override fun isApplicable(environment: PermissionEnvironment) = true
            override fun isGranted(context: Context): Boolean = throw UnsupportedOperationException("x")
            override fun settingsIntent(context: Context) = Intent()
        }
        val e = assertThrows(PermissionException::class.java) { m.check(special) }
        assertTrue(e.cause is UnsupportedOperationException)
    }

    @Test
    fun `소비자 명세가 던진 PermissionException 은 그대로 올린다`() {
        val own = PermissionException("own")
        val e = assertThrows(PermissionException::class.java) { fakeManager(context, device).check(ThrowingSpec("permissions", own)) }
        assertSame(own, e)
    }

    @Test
    fun `id 까지 던지는 명세도 클래스 이름으로 보고한다`() {
        val spec = object : RuntimePermissionSpec {
            override val id: String get() = throw IllegalStateException("no id")
            override fun permissions(environment: PermissionEnvironment): List<String> = throw IllegalStateException("x")
        }
        val e = assertThrows(PermissionException::class.java) { fakeManager(context, device).check(spec) }
        assertTrue(e.message!!.startsWith("명세가 실패했습니다: "))
    }

    @Test
    fun `앱 설정 인텐트는 이 패키지의 상세 화면`() {
        val intent = fakeManager(context, device).appSettingsIntent()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test
    fun `openAppSettings 는 Activity 가 아니면 새 태스크로 연다`() {
        val m = fakeManager(context, device)
        m.openAppSettings(context)
        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        m.openAppSettings(activity)
        val fromActivity = shadowOf(activity).nextStartedActivity
        assertEquals(0, fromActivity.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun `openAppSettings 가 화면을 못 찾으면 PermissionException 에 cause 를 담는다`() {
        val broken = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent?) = throw android.content.ActivityNotFoundException("none")
        }
        val e = assertThrows(PermissionException::class.java) { fakeManager(context, device).openAppSettings(broken) }
        assertTrue(e.cause is android.content.ActivityNotFoundException)
        assertEquals("앱 상세 설정 화면을 열 수 없습니다: ${Settings.ACTION_APPLICATION_DETAILS_SETTINGS}", e.message)
    }

    @Test
    fun `toString 은 환경과 확인 방식을 드러낸다`() {
        val text = fakeManager(context, device).toString()
        assertTrue(text, text.contains("sdkInt=34") && text.contains("ThrowIfDebuggable"))
    }
}

/** 실제 create() 경로 — Build.VERSION·PackageManager·checkSelfPermission 을 쓴다 */
@RunWith(RobolectricTestRunner::class)
class PermissionManagerCreateTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun declare(vararg permissions: String) {
        @Suppress("DEPRECATION")
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).requestedPermissions = arrayOf(*permissions)
    }

    @Test
    @Config(sdk = [28])
    fun `API 28 에서 알림은 NotApplicable 이고 환경은 실제 API 레벨`() {
        val m = PermissionManager.create(app, PermissionConfig(historyStore = RequestHistoryStore.inMemory()))
        assertEquals(28, m.environment.sdkInt)
        assertEquals(Build.VERSION.SDK_INT, m.environment.sdkInt)
        assertEquals(PermissionStatus.NotApplicable(28), m.check(PermissionSpecs.notifications))
    }

    @Test
    @Config(sdk = [33])
    fun `API 33 에서 알림은 실제 권한 부여를 따른다`() {
        val m = PermissionManager.create(app, PermissionConfig(historyStore = RequestHistoryStore.inMemory()))
        assertEquals(PermissionStatus.Denied(false), m.check(PermissionSpecs.notifications))
        shadowOf(app).grantPermissions(NOTIFY)
        assertEquals(PermissionStatus.Granted, m.check(PermissionSpecs.notifications))
    }

    @Test
    @Config(sdk = [30, 31, 35])
    fun `여러 API 레벨에서 정밀 위치의 대략만 허용은 Partial`() {
        val m = PermissionManager.create(app, PermissionConfig(historyStore = RequestHistoryStore.inMemory()))
        shadowOf(app).grantPermissions(COARSE)
        shadowOf(app).denyPermissions(FINE)
        val status = m.check(PermissionSpecs.location())
        assertTrue(status is PermissionStatus.Partial)
    }

    @Test
    @Config(sdk = [34])
    fun `매니페스트 선언은 패키지 정보에서 읽는다`() {
        declare(CAMERA)
        val m = PermissionManager.create(app)
        assertTrue(m.environment.isDeclared(CAMERA))
        assertEquals(false, m.environment.isDeclared(MIC))
        assertTrue(m.environment.toString().contains("sdkInt=34"))
    }

    @Test
    @Config(sdk = [34])
    fun `선언이 하나도 없는 패키지도 확인할 수 있다`() {
        @Suppress("DEPRECATION")
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).requestedPermissions = null
        assertEquals(false, PermissionManager.create(app).environment.isDeclared(CAMERA))
    }

    @Test
    @Config(sdk = [34])
    fun `Activity 를 넘겨도 애플리케이션 컨텍스트를 쓴다`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val m = PermissionManager.create(activity)
        assertEquals("package:${app.packageName}", m.appSettingsIntent().dataString)
        // 기본 이력 저장소도 애플리케이션 컨텍스트의 SharedPreferences 에 만든다
        m.check(PermissionSpecs.camera)
        assertNull(activity.getSharedPreferences("unused", 0).getString("x", null))
    }
}
